package dev.nocturne.ui.text;

import dev.nocturne.ui.RecordingRenderer;
import dev.nocturne.ui.fatality.FaFont;
import dev.nocturne.ui.render.Renderer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FontRasterizer} 的行为测试：度量为正、图集只上传一次并复用、失效时真的删除纹理、
 * 后端不支持纹理时退化为游戏字体。
 *
 * <p>全程无头运行（{@code java.awt.headless=true} 也可）：字形栅格只用到 {@code BufferedImage}
 * 与 {@code Font.createFont}，都不需要显示设备。
 */
class FontRasterizerTest {

    /** 记录绘制调用的替身，同时充当纹理解析与上传的入口。 */
    private RecordingRenderer renderer;

    @BeforeEach
    void setUp() {
        FontRasterizer.invalidate();
        renderer = new RecordingRenderer();
    }

    @AfterEach
    void tearDown() {
        FontRasterizer.invalidate();
    }

    /** 走图集路径时度量必须为正且与绘制同源（否则居中/省略号会算错）。 */
    @Test
    void glyphAtlasProvidesPositiveMetrics() {
        FaFont.Provider fonts = FontRasterizer.bind(renderer, 1.0f, 1.0f);

        assertTrue(FontRasterizer.atlasActive(), "a texture-capable renderer must use the glyph atlas");
        FaFont label = fonts.label();
        float width = label.width("Hello");
        assertTrue(width > 0f, "measured width must be positive, got " + width);
        assertTrue(label.height() > 0f, "line height must be positive");
        assertTrue(label.pxSize() > 0, "atlas fonts report their raster size");
        assertEquals(FontRegistry.MISANS, label.fontId(), "label text uses the UI font");

        renderer.clear();
        label.draw(renderer, "Hello", 10f, 20f, 0xFFFFFFFF);
        assertEquals(5, renderer.quadCalls, "one textured quad per glyph");
    }

    /** 同一份文字画两遍只上传一次图集：第二遍不该再上传（否则每帧 4MB 拷贝）。 */
    @Test
    void atlasIsUploadedOnceAndReused() {
        FaFont.Provider fonts = FontRasterizer.bind(renderer, 1.0f, 1.0f);
        FaFont label = fonts.label();

        label.draw(renderer, "Hello", 0f, 0f, 0xFFFFFFFF);
        int uploadsAfterFirst = renderer.uploadCalls;
        assertTrue(uploadsAfterFirst >= 1, "the first draw must upload the atlas");

        label.draw(renderer, "Hello", 0f, 0f, 0xFFFFFFFF);
        assertEquals(uploadsAfterFirst, renderer.uploadCalls,
                "re-drawing already-rasterized glyphs must not re-upload");
        assertTrue(label.width("Hello") > 0f);
    }

    /** {@code invalidate()} 必须把图集纹理还给后端，否则每次缩放变化都会泄漏一块显存。 */
    @Test
    void invalidateReleasesAtlasTextures() {
        FaFont.Provider fonts = FontRasterizer.bind(renderer, 1.0f, 1.0f);
        fonts.label().draw(renderer, "abc", 0f, 0f, 0xFFFFFFFF);
        assertFalse(renderer.liveTextures.isEmpty(), "the atlas must be registered with the renderer");

        FontRasterizer.invalidate();

        assertTrue(renderer.deleteCalls > 0, "invalidate must call deleteTexture for every atlas");
        assertTrue(renderer.liveTextures.isEmpty(), "no atlas texture may stay alive after invalidate");
    }

    /** 绑定到不同缩放时图集必须重建（栅格密度变了，旧图集会糊）。 */
    @Test
    void rebindingWithAnotherScaleRebuildsTheAtlas() {
        FaFont.Provider first = FontRasterizer.bind(renderer, 1.0f, 1.0f);
        int firstPx = first.label().pxSize();

        FaFont.Provider second = FontRasterizer.bind(renderer, 1.0f, 2.0f);

        assertEquals(0, renderer.liveTextures.size(), "the old atlas must be released on rebind");
        assertTrue(second.label().pxSize() > firstPx,
                "denser backend scale must rasterize at a larger pixel size");
    }

    /** 后端不支持纹理（或字体资源缺失）时必须退化到游戏字体，而不是画不出任何文字。 */
    @Test
    void fallsBackToTheGameFontWithoutTextureSupport() {
        renderer.textures = false;
        FaFont.Provider fonts = FontRasterizer.bind(renderer, 1.0f, 1.0f);

        assertFalse(FontRasterizer.atlasActive(), "no atlas without texture support");
        FaFont label = fonts.label();
        assertEquals(0, renderer.uploadCalls, "the fallback must not upload anything");

        renderer.clear();
        label.draw(renderer, "Hello", 4f, 6f, 0xFFFFFFFF);
        assertEquals(1, renderer.count("text:Hello"), "the fallback draws through Renderer.text");
        assertTrue(label.width("Hello") > 0f, "fallback metrics must still be usable for layout");
        assertEquals(-1, label.fontId(), "the fallback reports no atlas font id");
    }

    /** 退化模式下没有后端时也不能抛异常（界面在首帧之前就会问字体度量）。 */
    @Test
    void detachedProviderIsUsable() {
        FontRasterizer.invalidate();
        Renderer none = null;
        FaFont.Provider fonts = FontRasterizer.bind(none, 1.0f, 1.0f);

        FaFont label = fonts.label();
        assertNotNull(label);
        assertTrue(label.width("Hello") > 0f);
        label.draw(none, "Hello", 0f, 0f, 0xFFFFFFFF);
    }
}
