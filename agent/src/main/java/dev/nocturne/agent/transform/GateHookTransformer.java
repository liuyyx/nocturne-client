package dev.nocturne.agent.transform;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Label;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 在目标方法上织入事件钩子。
 *
 * <p>头部钩子返回 {@code boolean} 时，false 会直接取消原方法；返回 {@code void} 时只做通知。
 * 尾部通知插在每个 RETURN 前，因此提前返回路径也不会漏掉。钩子形参由目标描述符派生：
 * 基本类型原样转发，游戏引用类型统一擦成 {@code Object}，避免 bootstrap 钩子依赖游戏类。
 */
public final class GateHookTransformer implements ClassFileTransformer {

    private static final int ASM_API = Opcodes.ASM9;

    private final String targetClassInternalName;
    private final String targetMethod;
    private final String targetDescriptor;
    private final String hookOwner;
    private final String hookMethod;
    private final String hookReturn;
    private final boolean atMethodEnd;
    private final boolean passThis;
    private final AtomicBoolean patched = new AtomicBoolean(false);
    private final AtomicBoolean errorLogged = new AtomicBoolean(false);

    /**
     * @param targetClass 目标类名（点号或斜杠）
     * @param method      目标方法名
     * @param descriptor  目标方法描述符；尾部通知时返回类型必须为 V
     * @param hookOwner   bootstrap 钩子类内部名
     * @param hookMethod  钩子方法名
     * @param hookReturn  钩子返回类型：V=通知，Z=false 时取消原方法
     * @param atMethodEnd true=每个 RETURN 前通知；false=方法头织入
     * @param passThis    true=只传 this；false=转发全部目标形参
     */
    public GateHookTransformer(String targetClass, String method, String descriptor,
                               String hookOwner, String hookMethod, String hookReturn,
                               boolean atMethodEnd, boolean passThis) {
        if (atMethodEnd && Type.getReturnType(descriptor).getSort() != Type.VOID) {
            throw new IllegalArgumentException("method-end target must return void: " + descriptor);
        }
        if (!"V".equals(hookReturn) && !"Z".equals(hookReturn)) {
            throw new IllegalArgumentException("hook must return V or Z: " + hookReturn);
        }
        if (atMethodEnd && !"V".equals(hookReturn)) {
            throw new IllegalArgumentException("method-end hook must return V");
        }
        this.targetClassInternalName = targetClass.replace('.', '/');
        this.targetMethod = method;
        this.targetDescriptor = descriptor;
        this.hookOwner = hookOwner;
        this.hookMethod = hookMethod;
        this.hookReturn = hookReturn;
        this.atMethodEnd = atMethodEnd;
        this.passThis = passThis;
    }

    public boolean patched() {
        return patched.get();
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (className == null || !className.equals(targetClassInternalName)) {
            return null;
        }
        try {
            ClassReader reader = new ClassReader(classfileBuffer);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            ClassVisitor visitor = new ClassVisitor(ASM_API, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                    if (!targetMethod.equals(name) || !targetDescriptor.equals(descriptor)) {
                        return delegate;
                    }
                    return new MethodVisitor(ASM_API, delegate) {
                        @Override
                        public void visitCode() {
                            super.visitCode();
                            if (!atMethodEnd) {
                                inject(access);
                                patched.set(true);
                            }
                        }

                        @Override
                        public void visitInsn(int opcode) {
                            if (atMethodEnd && opcode == Opcodes.RETURN) {
                                inject(access);
                                patched.set(true);
                            }
                            super.visitInsn(opcode);
                        }

                        private void inject(int access) {
                            boolean isStatic = (access & Opcodes.ACC_STATIC) != 0;
                            loadHookArguments(isStatic);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, hookOwner, hookMethod,
                                    hookDescriptor(), false);
                            if ("Z".equals(hookReturn)) {
                                Label continueLabel = new Label();
                                super.visitJumpInsn(Opcodes.IFNE, continueLabel);
                                pushDefaultReturn();
                                super.visitLabel(continueLabel);
                                Object[] locals = localsOf(isStatic);
                                super.visitFrame(Opcodes.F_NEW, locals.length, locals, 0, null);
                            }
                        }

                        /** 取消非 void 入口时返回零值；引用类型返回 null。 */
                        private void pushDefaultReturn() {
                            switch (Type.getReturnType(targetDescriptor).getSort()) {
                                case Type.VOID:
                                    super.visitInsn(Opcodes.RETURN);
                                    return;
                                case Type.BOOLEAN:
                                case Type.BYTE:
                                case Type.CHAR:
                                case Type.SHORT:
                                case Type.INT:
                                    super.visitInsn(Opcodes.ICONST_0);
                                    super.visitInsn(Opcodes.IRETURN);
                                    return;
                                case Type.LONG:
                                    super.visitInsn(Opcodes.LCONST_0);
                                    super.visitInsn(Opcodes.LRETURN);
                                    return;
                                case Type.FLOAT:
                                    super.visitInsn(Opcodes.FCONST_0);
                                    super.visitInsn(Opcodes.FRETURN);
                                    return;
                                case Type.DOUBLE:
                                    super.visitInsn(Opcodes.DCONST_0);
                                    super.visitInsn(Opcodes.DRETURN);
                                    return;
                                default:
                                    super.visitInsn(Opcodes.ACONST_NULL);
                                    super.visitInsn(Opcodes.ARETURN);
                            }
                        }

                        private void loadHookArguments(boolean isStatic) {
                            if (passThis) {
                                if (isStatic) {
                                    throw new IllegalArgumentException("cannot pass this from a static method");
                                }
                                super.visitVarInsn(Opcodes.ALOAD, 0);
                                return;
                            }
                            int slot = isStatic ? 0 : 1;
                            for (Type argument : Type.getArgumentTypes(targetDescriptor)) {
                                super.visitVarInsn(argument.getOpcode(Opcodes.ILOAD), slot);
                                slot += argument.getSize();
                            }
                        }

                        private Object[] localsOf(boolean isStatic) {
                            Type[] arguments = Type.getArgumentTypes(targetDescriptor);
                            Object[] locals = new Object[arguments.length + (isStatic ? 0 : 1)];
                            int at = 0;
                            if (!isStatic) {
                                locals[at++] = targetClassInternalName;
                            }
                            for (Type argument : arguments) {
                                locals[at++] = frameEntry(argument);
                            }
                            return locals;
                        }
                    };
                }
            };
            reader.accept(visitor, ClassReader.EXPAND_FRAMES);
            return patched.get() ? writer.toByteArray() : null;
        } catch (Throwable t) {
            if (errorLogged.compareAndSet(false, true)) {
                System.out.println("[nocturne] gate hook transformer failed on "
                        + targetClassInternalName + "." + targetMethod + ": " + t);
            }
            return null;
        }
    }

    /** 游戏引用类型擦成 Object；基本类型与槽位宽度保持不变。 */
    private String hookDescriptor() {
        StringBuilder descriptor = new StringBuilder(targetDescriptor.length() + 8).append('(');
        if (passThis) {
            descriptor.append("Ljava/lang/Object;");
        } else {
            for (Type argument : Type.getArgumentTypes(targetDescriptor)) {
                descriptor.append(referenceAsObject(argument).getDescriptor());
            }
        }
        return descriptor.append(')').append(hookReturn).toString();
    }

    private static Type referenceAsObject(Type type) {
        switch (type.getSort()) {
            case Type.OBJECT:
                return Type.getType(Object.class);
            case Type.ARRAY:
                return Type.getType(Object.class);
            default:
                return type;
        }
    }

    private static Object frameEntry(Type type) {
        switch (type.getSort()) {
            case Type.BOOLEAN:
            case Type.CHAR:
            case Type.BYTE:
            case Type.SHORT:
            case Type.INT:
                return Opcodes.INTEGER;
            case Type.FLOAT:
                return Opcodes.FLOAT;
            case Type.LONG:
                return Opcodes.LONG;
            case Type.DOUBLE:
                return Opcodes.DOUBLE;
            case Type.ARRAY:
                return type.getDescriptor();
            default:
                return type.getInternalName();
        }
    }
}
