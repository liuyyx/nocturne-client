package dev.nocturne.ui.gl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** 纹理图像构造签名按版本分两种；选错会让整条图集字体支路被判为不可用。 */
class ExtractorRendererTextureBindingTest {

    public static final class ModernTexture {
        public ModernTexture(String name, int width, int height, boolean calloc) {
        }
    }

    public static final class LegacyTexture {
        public LegacyTexture(int width, int height, boolean calloc) {
        }
    }
    /** 同时存在两个浮点 UV 重载的 1.21.1 形态。 */
    public static final class ScaledGraphics {
        public void blit(Object id, int x, int y, float u, float v,
                         int width, int height, int textureWidth, int textureHeight) {
        }

        public void blit(Object id, int x, int y, int width, int height, float u, float v,
                         int regionWidth, int regionHeight, int textureWidth, int textureHeight) {
        }
    }

    @Test
    void prefersBlitWithIndependentUvRegionSize() {
        java.lang.reflect.Method selected = ExtractorRenderer.blitWithFloatUv(
                ScaledGraphics.class, Object.class);
        assertNotNull(selected);
        assertEquals(11, selected.getParameterCount(),
                "高密度图集缩小绘制时必须独立传 UV 区域，否则只采到字形左上角");
    }

    @Test
    void selectsConstructorWithAndWithoutName() {
        assertEquals(4, ExtractorRenderer.dynamicTextureConstructor(ModernTexture.class)
                .getParameterCount());
        assertNotNull(ExtractorRenderer.dynamicTextureConstructor(LegacyTexture.class));
        assertEquals(3, ExtractorRenderer.dynamicTextureConstructor(LegacyTexture.class)
                .getParameterCount());
    }
}
