package dev.nocturne.ui.text;

import dev.nocturne.ui.RecordingRenderer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 栅格纹理生命周期：失效后同一后端也不能继续使用旧上下文句柄。 */
class RasterTexturesTest {

    @AfterEach
    void tearDown() {
        RasterTextures.invalidate();
    }

    @Test
    void invalidateRebindsTheSameRenderer() {
        RecordingRenderer renderer = new RecordingRenderer();
        int first = RasterTextures.gradient(renderer, 2, 2,
                0xFFFF0000, 0xFF00FF00, 0xFF0000FF, 0xFFFFFFFF);
        assertTrue(first > 0);

        RasterTextures.invalidate();
        assertEquals(1, renderer.deleteCalls, "失效必须删除旧纹理");
        assertTrue(renderer.liveTextures.isEmpty());

        int second = RasterTextures.gradient(renderer, 2, 2,
                0xFFFF0000, 0xFF00FF00, 0xFF0000FF, 0xFFFFFFFF);
        assertNotEquals(first, second, "同一 Renderer 重新使用时必须重新上传纹理");
        assertEquals(2, renderer.uploadCalls, "失效后的相同形状不能命中旧缓存");
    }
}
