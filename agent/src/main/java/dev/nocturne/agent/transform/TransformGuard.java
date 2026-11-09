package dev.nocturne.agent.transform;

/** 转换器的轻量幂等检查，避免 retransform 时重复织入同一个 bootstrap 钩子。 */
public final class TransformGuard {
    private TransformGuard() {
    }

    public static boolean containsHook(byte[] bytes, String hookOwner, String hookMethod) {
        if (bytes == null || hookOwner == null || hookMethod == null) {
            return false;
        }
        return containsUtf8(bytes, hookOwner.replace('.', '/'))
                && containsUtf8(bytes, hookMethod);
    }

    /** 只匹配完整的 CONSTANT_Utf8 项，避免 attack 命中 attackCapture 这类前缀。 */
    private static boolean containsUtf8(byte[] bytes, String value) {
        byte[] needle = value.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        outer:
        for (int i = 0; i + needle.length + 3 <= bytes.length; i++) {
            if ((bytes[i] & 0xff) != 1
                    || (bytes[i + 1] & 0xff) != (needle.length >>> 8)
                    || (bytes[i + 2] & 0xff) != (needle.length & 0xff)) {
                continue;
            }
            for (int j = 0; j < needle.length; j++) {
                if (bytes[i + 3 + j] != needle[j]) {
                    continue outer;
                }
            }
            return true;
        }
        return false;
    }

    /**
     * 方法级幂等检查：只认目标方法体内真的调用了该钩子。
     *
     * <p>类级 {@link #containsHook} 会把同一个类里其它方法的钩子也算进来；
     * 同一方法上挂多个钩子（如 World3D 的 matrixCapture + draw）时必须按方法判断。
     * 解析失败时退回类级检查（宁可少织一次，也不要重复织入）。
     */
    public static boolean containsHookInMethod(byte[] bytes, String targetClass,
                                               String method, String descriptor,
                                               String hookOwner, String hookMethod) {
        if (bytes == null || method == null || descriptor == null
                || hookOwner == null || hookMethod == null) {
            return false;
        }
        final String ownerInternal = hookOwner.replace('.', '/');
        final boolean[] found = {false};
        try {
            new org.objectweb.asm.ClassReader(bytes).accept(new org.objectweb.asm.ClassVisitor(
                    org.objectweb.asm.Opcodes.ASM9) {
                @Override
                public org.objectweb.asm.MethodVisitor visitMethod(
                        int access, String name, String desc, String signature, String[] exceptions) {
                    if (!method.equals(name) || !descriptor.equals(desc)) {
                        return null;
                    }
                    return new org.objectweb.asm.MethodVisitor(org.objectweb.asm.Opcodes.ASM9) {
                        @Override
                        public void visitMethodInsn(int opcode, String owner, String name,
                                                    String desc, boolean itf) {
                            if (ownerInternal.equals(owner) && hookMethod.equals(name)) {
                                found[0] = true;
                            }
                        }
                    };
                }
            }, org.objectweb.asm.ClassReader.SKIP_DEBUG | org.objectweb.asm.ClassReader.SKIP_FRAMES);
        } catch (Throwable ignored) {
            return containsHook(bytes, hookOwner, hookMethod);
        }
        return found[0];
    }
}
