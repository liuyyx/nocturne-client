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
}
