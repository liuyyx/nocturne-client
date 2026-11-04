package org.lwjgl;

/** 测试用直接缓冲工厂；与 LWJGL BufferUtils 的实际形状一致。 */
public final class BufferUtils {

    private BufferUtils() {
    }

    public static java.nio.FloatBuffer createFloatBuffer(int elements) {
        return java.nio.ByteBuffer.allocateDirect(elements * 4)
                .order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer();
    }
}
