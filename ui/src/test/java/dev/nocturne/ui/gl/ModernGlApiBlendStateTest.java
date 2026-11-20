package dev.nocturne.ui.gl;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.IntBuffer;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** 只有数组形态可读时也必须恢复 RGB/ALPHA 四因子，包括合法的 GL_ZERO。 */
class ModernGlApiBlendStateTest {
    public static final class BlendState {
        static int[] factors;

        public static void getInteger(int name, int[] out) {
            out[0] = factors[index(name)];
        }

        public static void getInteger(int name, IntBuffer out) {
            out.put(0, factors[index(name)]);
        }

        private static int index(int name) {
            switch (name) {
                case 0x80C9: return 0;
                case 0x80C8: return 1;
                case 0x80CB: return 2;
                case 0x80CA: return 3;
                default: throw new IllegalArgumentException("unexpected GL query " + name);
            }
        }

        public static void blendFunc(int src, int dst) {
            blendFuncSeparate(src, dst, src, dst);
        }

        public static void blendFuncSeparate(int src, int dst, int srcAlpha, int dstAlpha) {
            factors = new int[]{src, dst, srcAlpha, dstAlpha};
        }
    }

    @Test
    void restoresFourFactorsWithEitherQuerySignature() throws Exception {
        for (boolean buffer : new boolean[]{false, true}) {
            int[] original = {0, 1, 0x0304, 0x0305};
            BlendState.factors = original.clone();
            ModernGlApi api = ModernGlApi.bind(getClass().getClassLoader());
            assertNotNull(api);
            set(api, "getIntegervBuffer", buffer
                    ? BlendState.class.getMethod("getInteger", int.class, IntBuffer.class) : null);
            set(api, "getIntegervArray", buffer ? null
                    : BlendState.class.getMethod("getInteger", int.class, int[].class));
            set(api, "blendFunc", BlendState.class.getMethod("blendFunc", int.class, int.class));
            set(api, "blendFuncSeparate", BlendState.class.getMethod("blendFuncSeparate",
                    int.class, int.class, int.class, int.class));

            int[] previous = api.enableBlendAndReadPrevious();
            assertArrayEquals(new int[]{0x0302, 0x0303, 0x0302, 0x0303}, BlendState.factors);
            api.restoreBlendFunc(previous);
            assertArrayEquals(original, BlendState.factors,
                    "查询形态不能改变帧末的游戏混合因子");
        }
    }

    private static void set(ModernGlApi api, String name, Object value) throws Exception {
        Field field = ModernGlApi.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(api, value);
    }
}
