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
 * 包入口钩子：把事件返回的包写回原参数槽，返回 {@code null} 时取消原调用。
 *
 * <p>相比只返回 boolean 的门控，这条路径同时保留 {@code PacketEvent#setPacket} 的实际效果。
 */
public final class PacketHookTransformer implements ClassFileTransformer {

    private static final int ASM_API = Opcodes.ASM9;
    private static final String HOOK_DESCRIPTOR = "(Ljava/lang/Object;)Ljava/lang/Object;";

    private final String targetClassInternalName;
    private final String targetMethod;
    private final String targetDescriptor;
    private final String hookOwner;
    private final String hookMethod;
    private final int packetArgument;
    private final AtomicBoolean patched = new AtomicBoolean(false);
    private final AtomicBoolean errorLogged = new AtomicBoolean(false);

    public PacketHookTransformer(String targetClass, String method, String descriptor,
                                 String hookOwner, String hookMethod, int packetArgument) {
        if (Type.getReturnType(descriptor).getSort() != Type.VOID) {
            throw new IllegalArgumentException("packet target must return void: " + descriptor);
        }
        Type[] arguments = Type.getArgumentTypes(descriptor);
        if (packetArgument < 0 || packetArgument >= arguments.length
                || (arguments[packetArgument].getSort() != Type.OBJECT
                && arguments[packetArgument].getSort() != Type.ARRAY)) {
            throw new IllegalArgumentException("packet argument must be a reference: " + packetArgument);
        }
        this.targetClassInternalName = targetClass.replace('.', '/');
        this.targetMethod = method;
        this.targetDescriptor = descriptor;
        this.hookOwner = hookOwner;
        this.hookMethod = hookMethod;
        this.packetArgument = packetArgument;
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
                            boolean isStatic = (access & Opcodes.ACC_STATIC) != 0;
                            int packetSlot = packetSlot(isStatic);
                            super.visitVarInsn(Opcodes.ALOAD, packetSlot);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, hookOwner, hookMethod,
                                    HOOK_DESCRIPTOR, false);
                            super.visitInsn(Opcodes.DUP);
                            Label continueLabel = new Label();
                            super.visitJumpInsn(Opcodes.IFNONNULL, continueLabel);
                            super.visitInsn(Opcodes.RETURN);
                            super.visitLabel(continueLabel);
                            Object[] locals = localsOf(isStatic);
                            super.visitFrame(Opcodes.F_NEW, locals.length, locals,
                                    1, new Object[]{"java/lang/Object"});
                            super.visitVarInsn(Opcodes.ASTORE, packetSlot);
                            patched.set(true);
                        }

                        private int packetSlot(boolean isStatic) {
                            int slot = isStatic ? 0 : 1;
                            Type[] arguments = Type.getArgumentTypes(targetDescriptor);
                            for (int i = 0; i < packetArgument; i++) {
                                slot += arguments[i].getSize();
                            }
                            return slot;
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
                System.out.println("[nocturne] packet hook transformer failed on "
                        + targetClassInternalName + "." + targetMethod + ": " + t);
            }
            return null;
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
