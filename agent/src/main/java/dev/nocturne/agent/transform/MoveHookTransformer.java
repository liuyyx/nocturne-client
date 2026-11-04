package dev.nocturne.agent.transform;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassVisitor;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.MethodVisitor;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.Type;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 在 Entity.move 方法头改写实际位移实参。
 *
 * <p>现代版本替换 Vec3 形参；旧版 (DDDV) 先捕获三个分量、投递一次事件，再写回三个槽。
 * 插入不含分支或新增局部变量，保留原 StackMapTable，不使用 COMPUTE_FRAMES。
 */
public final class MoveHookTransformer implements ClassFileTransformer {

    private final String targetClass;
    private final String targetMethod;
    private final String targetDescriptor;
    private final String hookOwner;
    private final boolean legacy;
    private final AtomicBoolean patched = new AtomicBoolean();
    private final AtomicBoolean failed = new AtomicBoolean();

    public MoveHookTransformer(String targetClass, String targetMethod, String targetDescriptor,
                               String hookOwner, boolean legacy) {
        this.targetClass = targetClass.replace('.', '/');
        this.targetMethod = targetMethod;
        this.targetDescriptor = targetDescriptor;
        this.hookOwner = hookOwner.replace('.', '/');
        this.legacy = legacy;
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        if (!targetClass.equals(className)) {
            return null;
        }
        try {
            ClassReader reader = new ClassReader(classfileBuffer);
            ClassWriter writer = new ClassWriter(reader, ClassWriter.COMPUTE_MAXS);
            reader.accept(new ClassVisitor(Opcodes.ASM9, writer) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                 String signature, String[] exceptions) {
                    MethodVisitor delegate = super.visitMethod(access, name, descriptor, signature, exceptions);
                    if (!targetMethod.equals(name) || !targetDescriptor.equals(descriptor)) {
                        return delegate;
                    }
                    return new MethodVisitor(Opcodes.ASM9, delegate) {
                        @Override
                        public void visitCode() {
                            super.visitCode();
                            if (legacy) {
                                injectLegacy(this);
                            } else {
                                injectModern(this);
                            }
                            patched.set(true);
                        }
                    };
                }
            }, 0);
            return patched.get() ? writer.toByteArray() : null;
        } catch (Throwable t) {
            if (failed.compareAndSet(false, true)) {
                System.out.println("[nocturne] move hook transformer failed on "
                        + targetClass + "." + targetMethod + ": " + t);
            }
            return null;
        }
    }

    private void injectModern(MethodVisitor visitor) {
        visitor.visitVarInsn(Opcodes.ALOAD, 0);
        visitor.visitVarInsn(Opcodes.ALOAD, 2);
        visitor.visitMethodInsn(Opcodes.INVOKESTATIC, hookOwner, "moveVec",
                "(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;", false);
        visitor.visitTypeInsn(Opcodes.CHECKCAST,
                Type.getArgumentTypes(targetDescriptor)[1].getInternalName());
        visitor.visitVarInsn(Opcodes.ASTORE, 2);
    }

    private void injectLegacy(MethodVisitor visitor) {
        int first = targetDescriptor.startsWith("(L") ? 2 : 1;
        visitor.visitVarInsn(Opcodes.ALOAD, 0);
        visitor.visitVarInsn(Opcodes.DLOAD, first);
        visitor.visitVarInsn(Opcodes.DLOAD, first + 2);
        visitor.visitVarInsn(Opcodes.DLOAD, first + 4);
        visitor.visitMethodInsn(Opcodes.INVOKESTATIC, hookOwner, "moveLegacyCapture",
                "(Ljava/lang/Object;DDD)V", false);
        visitor.visitVarInsn(Opcodes.ALOAD, 0);
        visitor.visitMethodInsn(Opcodes.INVOKESTATIC, hookOwner, "moveLegacyDispatch",
                "(Ljava/lang/Object;)V", false);
        rewriteLegacy(visitor, first, "moveLegacyX");
        rewriteLegacy(visitor, first + 2, "moveLegacyY");
        rewriteLegacy(visitor, first + 4, "moveLegacyZ");
    }

    private void rewriteLegacy(MethodVisitor visitor, int slot, String method) {
        visitor.visitVarInsn(Opcodes.ALOAD, 0);
        visitor.visitVarInsn(Opcodes.DLOAD, slot);
        visitor.visitMethodInsn(Opcodes.INVOKESTATIC, hookOwner, method,
                "(Ljava/lang/Object;D)D", false);
        visitor.visitVarInsn(Opcodes.DSTORE, slot);
    }
}
