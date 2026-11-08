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

/** 用前两个引用形参决定是否让目标方法继续；false 时 boolean 返回 false、void 直接返回。 */
public final class FirstArgumentGateTransformer implements ClassFileTransformer {
    private final String targetClass;
    private final String method;
    private final String descriptor;
    private final String hookOwner;
    private final String hookMethod;

    /** 传给钩子的两个引用形参下标（默认前两个）。 */
    private final int first;
    private final int second;

    public FirstArgumentGateTransformer(String targetClass, String method, String descriptor,
                                        String hookOwner, String hookMethod) {
        this(targetClass, method, descriptor, hookOwner, hookMethod, 0, 1);
    }

    /**
     * 指定要传给钩子的两个**引用形参下标**。
     *
     * <p>为什么需要它：同一个规范方法在不同版本的形参位置不同——{@code renderBatched} 的
     * {@code BlockState} 是第 0 参，而 26.x 的 {@code ModelBlockRenderer#tesselateBlock} 把它排在第 6 参。
     * 由调用方按运行期描述符里的类型定位下标，织入逻辑一份，不做版本判断。
     *
     * @param first  第一个引用形参下标（BlockState）
     * @param second 第二个引用形参下标（BlockPos）
     */
    public FirstArgumentGateTransformer(String targetClass, String method, String descriptor,
                                        String hookOwner, String hookMethod, int first, int second) {
        int returnSort = Type.getReturnType(descriptor).getSort();
        if (returnSort != Type.BOOLEAN && returnSort != Type.VOID) {
            throw new IllegalArgumentException("target must return boolean or void");
        }
        Type[] arguments = Type.getArgumentTypes(descriptor);
        if (arguments.length <= Math.max(first, second) || first < 0 || second < 0
                || arguments[first].getSort() != Type.OBJECT
                || arguments[second].getSort() != Type.OBJECT) {
            throw new IllegalArgumentException("gate arguments must be objects");
        }
        this.first = first;
        this.second = second;
        this.targetClass = targetClass.replace('.', '/');
        this.method = method;
        this.descriptor = descriptor;
        this.hookOwner = hookOwner.replace('.', '/');
        this.hookMethod = hookMethod;
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (!targetClass.equals(className) || TransformGuard.containsHook(classfileBuffer, hookOwner, hookMethod)) {
            return null;
        }
        try {
            ClassReader reader = new ClassReader(classfileBuffer);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String desc,
                                                 String signature, String[] exceptions) {
                    MethodVisitor delegate = super.visitMethod(access, name, desc, signature, exceptions);
                    if (!method.equals(name) || !descriptor.equals(desc)) {
                        return delegate;
                    }
                    return new MethodVisitor(Opcodes.ASM9, delegate) {
                        @Override
                        public void visitCode() {
                            super.visitCode();
                            int slot = (access & Opcodes.ACC_STATIC) == 0 ? 1 : 0;
                            Type[] types = Type.getArgumentTypes(descriptor);
                            int firstSlot = slot;
                            for (int i = 0; i < first; i++) {
                                firstSlot += types[i].getSize();
                            }
                            int secondSlot = firstSlot;
                            for (int i = first; i < second; i++) {
                                secondSlot += types[i].getSize();
                            }
                            super.visitVarInsn(Opcodes.ALOAD, firstSlot);
                            super.visitVarInsn(Opcodes.ALOAD, secondSlot);
                            super.visitMethodInsn(Opcodes.INVOKESTATIC, hookOwner, hookMethod,
                                    "(Ljava/lang/Object;Ljava/lang/Object;)Z", false);
                            Label proceed = new Label();
                            super.visitJumpInsn(Opcodes.IFNE, proceed);
                            if (Type.getReturnType(descriptor).getSort() == Type.VOID) {
                                super.visitInsn(Opcodes.RETURN);
                            } else {
                                super.visitInsn(Opcodes.ICONST_0);
                                super.visitInsn(Opcodes.IRETURN);
                            }
                            super.visitLabel(proceed);
                            Object[] locals = locals(access);
                            super.visitFrame(Opcodes.F_NEW, locals.length, locals, 0, null);
                        }
                    };
                }

                private Object[] locals(int access) {
                    Type[] arguments = Type.getArgumentTypes(descriptor);
                    Object[] out = new Object[arguments.length + ((access & Opcodes.ACC_STATIC) == 0 ? 1 : 0)];
                    int at = 0;
                    if ((access & Opcodes.ACC_STATIC) == 0) {
                        out[at++] = targetClass;
                    }
                    for (Type argument : arguments) {
                        out[at++] = frame(argument);
                    }
                    return out;
                }

                private Object frame(Type type) {
                    switch (type.getSort()) {
                        case Type.BOOLEAN:
                        case Type.BYTE:
                        case Type.CHAR:
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
            }, ClassReader.EXPAND_FRAMES);
            return writer.toByteArray();
        } catch (Throwable t) {
            System.out.println("[nocturne] first-argument gate failed on "
                    + targetClass + "." + method + ": " + t);
            return null;
        }
    }
}
