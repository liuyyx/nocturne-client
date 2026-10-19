package dev.nocturne.ui.gl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GlRenderer} 的纹理回归：上传参数、字节序、采样参数与句柄生命周期。
 *
 * <p>看不到像素的单元测试只能钉住"下发了什么"：{@code glTexImage2D} 的格式/类型/尺寸与像素
 * 字节序必须与非预乘 {@code SRC_ALPHA/ONE_MINUS_SRC_ALPHA} 混合一致——传错字节序在计数器上
 * 完全看不出来，真机上却表现为"字形颜色不对 / 整块透明"。
 */
class TextureUploadTest {

    /** 单个测试纹理的边长；取非 4 的倍数以便顺带验证行对齐不会错位。 */
    private static final int SIZE = 3;

    @BeforeEach
    void setUp() {
        FakeGl.reset();
    }

    /** 上传一次新建纹理：返回非零句柄，且下发的是 RGBA/UNSIGNED_BYTE 与正确的宽高。 */
    @Test
    void uploadCreatesTextureWithRgbaPixels() {
        GlRenderer renderer = new GlRenderer(GlApi.bind(FakeGl.class), null);
        assertTrue(renderer.supportsTextures(), "the fixture exposes the texture entrypoints");

        int handle = renderer.uploadTexture(0, SIZE, SIZE, pixels());

        assertEquals(FakeGl.lastGenTexture, handle, "the handle must come from glGenTextures");
        assertTrue(handle > 0);
        assertEquals(SIZE, FakeGl.lastTexImage[0]);
        assertEquals(SIZE, FakeGl.lastTexImage[1]);
        assertEquals(GlApi.GL_RGBA, FakeGl.lastTexImage[2], "internal format");
        assertEquals(GlApi.GL_RGBA, FakeGl.lastTexImage[3], "pixel format");
        assertEquals(GlApi.GL_UNSIGNED_BYTE, FakeGl.lastTexImage[4], "pixel type");
        assertEquals(SIZE * SIZE * 4, FakeGl.lastTexImage[5], "exactly one RGBA byte per pixel");
    }

    /** 像素必须按 RGBA 顺序、行优先摊开（非预乘）。 */
    @Test
    void uploadKeepsRowMajorRgbaOrder() {
        GlRenderer renderer = new GlRenderer(GlApi.bind(FakeGl.class), null);
        // ARGB 布局下 0x11223344 是 A=11 R=22 G=33 B=44；末像素 0xFFEEDDCC。
        int[] argb = new int[]{0x11223344, 0, 0, 0, 0, 0, 0, 0, 0xFFEEDDCC};

        renderer.uploadTexture(0, SIZE, SIZE, argb);

        byte[] bytes = FakeGl.lastTexPixels;
        assertEquals((byte) 0x22, bytes[0], "R first");
        assertEquals((byte) 0x33, bytes[1], "G second");
        assertEquals((byte) 0x44, bytes[2], "B third");
        assertEquals((byte) 0x11, bytes[3], "A fourth");
        int last = (SIZE * SIZE - 1) * 4;
        assertEquals((byte) 0xEE, bytes[last], "the last pixel must land at the end of the buffer");
        assertEquals((byte) 0xDD, bytes[last + 1]);
        assertEquals((byte) 0xCC, bytes[last + 2]);
        assertEquals((byte) 0xFF, bytes[last + 3]);
    }

    /** 采样参数必须是最近邻 + 边缘夹取（线性过滤会把字形糊掉，重复采样会串格）。 */
    @Test
    void uploadUsesNearestFilteringAndClamping() {
        GlRenderer renderer = new GlRenderer(GlApi.bind(FakeGl.class), null);

        renderer.uploadTexture(0, SIZE, SIZE, pixels());

        assertEquals(Integer.valueOf(GlApi.GL_NEAREST),
                FakeGl.texParameters.get(GlApi.GL_TEXTURE_MIN_FILTER), "min filter");
        assertEquals(Integer.valueOf(GlApi.GL_NEAREST),
                FakeGl.texParameters.get(GlApi.GL_TEXTURE_MAG_FILTER), "mag filter");
        assertEquals(Integer.valueOf(GlApi.GL_CLAMP_TO_EDGE),
                FakeGl.texParameters.get(GlApi.GL_TEXTURE_WRAP_S), "wrap S");
        assertEquals(Integer.valueOf(GlApi.GL_CLAMP_TO_EDGE),
                FakeGl.texParameters.get(GlApi.GL_TEXTURE_WRAP_T), "wrap T");
    }

    /** 已有句柄的更新不得再生成新句柄（图集每加一个字形就重传一次，泄漏会很快耗尽句柄）。 */
    @Test
    void updatingAnExistingHandleReusesIt() {
        GlRenderer renderer = new GlRenderer(GlApi.bind(FakeGl.class), null);
        int handle = renderer.uploadTexture(0, SIZE, SIZE, pixels());
        int generated = FakeGl.genTextureCalls;

        int again = renderer.uploadTexture(handle, SIZE, SIZE, pixels());

        assertEquals(handle, again, "updating must keep the same handle");
        assertEquals(generated, FakeGl.genTextureCalls, "no new texture may be generated");
    }

    /** 删除会把句柄交给 GL；0 必须被忽略（调用方用 0 表示"没有纹理"）。 */
    @Test
    void deleteForwardsPositiveHandlesOnly() {
        GlRenderer renderer = new GlRenderer(GlApi.bind(FakeGl.class), null);

        renderer.deleteTexture(0);
        assertEquals(-1, FakeGl.deletedTexture, "zero is not a handle");

        renderer.deleteTexture(77);
        assertEquals(77, FakeGl.deletedTexture);
    }

    /** 纹理四边形要开纹理、提交四个顶点与纹理坐标，并在画完后把纹理关掉。 */
    @Test
    void texturedQuadEnablesAndDisablesTexture() {
        GlRenderer renderer = new GlRenderer(GlApi.bind(FakeGl.class), null);

        renderer.texturedQuad(12, 1f, 2f, 3f, 4f, 0f, 0f, 1f, 1f, 0xFFFFFFFF);

        assertTrue(FakeGl.isCapEnabled(GlApi.GL_TEXTURE_2D),
                "the texture stays enabled while the batch is open (batching)");
        assertEquals(GlApi.GL_QUADS, FakeGl.lastBeginMode);
        assertEquals(1, FakeGl.beginCalls);
        assertEquals(4, FakeGl.vertexCalls, "a quad has four vertices");
        assertTrue(FakeGl.enableCount(GlApi.GL_TEXTURE_2D) >= 1, "texturing must be enabled");

        // 离开纹理批次（下一个无纹理图元）时必须把纹理关掉，否则后续无纹理图元会去采样图集。
        renderer.rect(0f, 0f, 1f, 1f, dev.nocturne.ui.render.Color.WHITE);
        assertFalse(FakeGl.isCapEnabled(GlApi.GL_TEXTURE_2D),
                "leaving the textured batch must release the texture unit");
    }

    /** 透明、零尺寸或无句柄的纹理四边形必须被跳过，不得产生绘制调用。 */
    @Test
    void texturedQuadSkipsInvisibleOrInvalidInput() {
        GlRenderer renderer = new GlRenderer(GlApi.bind(FakeGl.class), null);

        renderer.texturedQuad(0, 1f, 2f, 3f, 4f, 0f, 0f, 1f, 1f, 0xFFFFFFFF);
        renderer.texturedQuad(5, 1f, 2f, 0f, 4f, 0f, 0f, 1f, 1f, 0xFFFFFFFF);
        renderer.texturedQuad(5, 1f, 2f, 3f, 4f, 0f, 0f, 1f, 1f, 0x00FFFFFF);

        assertEquals(0, FakeGl.beginCalls, "nothing must be submitted");
    }

    /** 后端没有纹理入口时不得报支持，也不得抛异常（句柄原样返回）。 */
    @Test
    void withoutTextureEntrypointsSupportIsReportedFalse() {
        GlRenderer renderer = new GlRenderer(GlApi.bind(FakeGlBufferOnly.class), null);

        assertFalse(renderer.supportsTextures());
        assertEquals(7, renderer.uploadTexture(7, SIZE, SIZE, pixels()), "the handle must pass through");
    }

    /** @return 3×3 的测试像素 */
    private static int[] pixels() {
        int[] pixels = new int[SIZE * SIZE];
        for (int i = 0; i < pixels.length; i++) {
            pixels[i] = 0xFF000000 | i * 0x010101;
        }
        return pixels;
    }
}
