package dev.nocturne.ui.gl;

import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 全量与子区域上传只能临时使用紧密 RGBA 布局，成功、失败均须恢复游戏的 UNPACK 状态。 */
class GlApiUnpackStateTest {
    public static class UploadGl {
        static int[] unpack;
        static int[] duringUpload;
        static int uploads;
        static boolean failUpload;
        static boolean failQuery;

        public static void glColor4f(float r, float g, float b, float a) { }
        public static void glBegin(int mode) { }
        public static void glEnd() { }
        public static void glVertex2f(float x, float y) { }
        public static void glGenTextures(int[] out) { out[0] = 1; }
        public static void glTexParameteri(int target, int name, int value) { }

        public static void glPixelStorei(int name, int value) {
            unpack[index(name)] = value;
        }

        static int index(int name) {
            switch (name) {
                case GlApi.GL_UNPACK_ALIGNMENT: return 0;
                case GlApi.GL_UNPACK_ROW_LENGTH: return 1;
                case GlApi.GL_UNPACK_SKIP_ROWS: return 2;
                case GlApi.GL_UNPACK_SKIP_PIXELS: return 3;
                default: throw new IllegalArgumentException("unexpected UNPACK query " + name);
            }
        }

        static int read(int name) {
            if (failQuery) {
                throw new IllegalStateException("query failed");
            }
            return unpack[index(name)];
        }

        static void upload() {
            uploads++;
            duringUpload = unpack.clone();
            if (failUpload) {
                throw new IllegalStateException("upload failed");
            }
        }

        public static void glTexImage2D(int target, int level, int format, int width, int height,
                                        int border, int pixelsFormat, int type, ByteBuffer pixels) {
            upload();
        }

        public static void glTexSubImage2D(int target, int level, int x, int y, int width, int height,
                                           int format, int type, ByteBuffer pixels) {
            upload();
        }
    }

    public static final class ArrayGl {
        public static void glGetIntegerv(int name, int[] out) {
            out[0] = UploadGl.read(name);
        }
    }

    public static final class BufferGl {
        public static void glGetInteger(int name, IntBuffer out) {
            if (out.remaining() < 16) {
                throw new IllegalArgumentException("LWJGL2 requires 16 remaining ints");
            }
            out.put(0, UploadGl.read(name));
        }
    }

    @Test
    void bothUploadShapesRestoreAllUnpackValuesOnSuccessAndFailure() throws Exception {
        for (Class<?> type : new Class<?>[]{ArrayGl.class, BufferGl.class}) {
            reset();
            GlApi api = bind(type);
            assertTrue(api.hasTextures());
            int[] expected = UploadGl.unpack.clone();
            for (boolean fail : new boolean[]{false, true}) {
                UploadGl.failUpload = fail;
                assertEquals(!fail, api.uploadTexture(1, 1, new int[]{0xFF123456}));
                assertArrayEquals(new int[]{4, 0, 0, 0}, UploadGl.duringUpload);
                assertArrayEquals(expected, UploadGl.unpack);
                assertEquals(!fail, api.uploadTextureRegion(0, 0, 1, 1, 1,
                        new int[]{0xFF123456}));
                assertArrayEquals(new int[]{4, 0, 0, 0}, UploadGl.duringUpload);
                assertArrayEquals(expected, UploadGl.unpack);
            }
        }
    }

    @Test
    void unreadableUnpackStatePreventsUnsafeUpload() throws Exception {
        for (Class<?> type : new Class<?>[]{ArrayGl.class, BufferGl.class, UploadGl.class}) {
            reset();
            UploadGl.failQuery = true;
            int[] expected = UploadGl.unpack.clone();
            GlApi api = bind(type);
            assertFalse(api.uploadTexture(1, 1, new int[]{0}));
            assertFalse(api.uploadTextureRegion(0, 0, 1, 1, 1, new int[]{0}));
            assertEquals(0, UploadGl.uploads);
            assertArrayEquals(expected, UploadGl.unpack);
        }
    }

    private static GlApi bind(Class<?> queryType) throws Exception {
        GlApi api = GlApi.bind(UploadGl.class);
        Field array = GlApi.class.getDeclaredField("getIntegerv");
        Field buffer = GlApi.class.getDeclaredField("getIntegervBuffer");
        array.setAccessible(true);
        buffer.setAccessible(true);
        array.set(api, queryType == ArrayGl.class
                ? ArrayGl.class.getMethod("glGetIntegerv", int.class, int[].class) : null);
        buffer.set(api, queryType == BufferGl.class
                ? BufferGl.class.getMethod("glGetInteger", int.class, IntBuffer.class) : null);
        return api;
    }

    private static void reset() {
        UploadGl.unpack = new int[]{8, 32, 3, 4};
        UploadGl.duringUpload = null;
        UploadGl.uploads = 0;
        UploadGl.failUpload = false;
        UploadGl.failQuery = false;
    }
}
