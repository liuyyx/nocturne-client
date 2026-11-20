package dev.nocturne.ui.gl;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** OpenGL 1.3 活动纹理入口的绑定与客户态数组清理。 */
class GlApiTextureTest {

    @BeforeEach
    void reset() {
        FakeGl.reset();
        GL11.reset();
    }

    @Test
    void bindsGl13ServerAndClientTextureUnits() {
        GlApi api = GlApi.bind(FakeGl.class);
        assertNotNull(api);
        assertTrue(api.hasTextures(), "纹理能力集必须包含 glActiveTexture");

        api.activeTexture(GlApi.GL_TEXTURE0 + 1);
        assertEquals(1, FakeGl.activeTextureCalls);
        assertEquals(GlApi.GL_TEXTURE0 + 1, FakeGl.lastActiveTexture);

        api.disableClientArrays();
        assertEquals(3, FakeGl.clientActiveTextureCalls,
                "两个纹理单元都应关闭纹理坐标数组，最后复位到单元 0");
    }
}
