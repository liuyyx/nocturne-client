package dev.nocturne.client.render3d;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 世界 3D 自检层默认必须关闭：生产启动（没有调试属性）不能出现绿色线框与白色圆环。
 *
 * <p>回归背景：真机截图出现过 1×1×1 绿框 + 白色椭圆环，被误认成 ESP 模块视觉。它们来自
 * {@link World3DDebugOverlay}，只在 {@code -Dnocturne.debug.world3d} 下创建；这里把「缺省关闭」
 * 和「显式 false 也不开」钉死，防止启动脚本/启动器把该属性带进生产路径。
 */
class World3DRendererDebugTest {

    @AfterEach
    void clearProperty() {
        System.clearProperty("nocturne.debug.world3d");
    }

    @Test
    void defaultIsOff() {
        System.clearProperty("nocturne.debug.world3d");
        assertFalse(World3DRenderer.debugOverlayEnabled());
        assertFalse(new World3DRenderer(null).hasDebugOverlay(),
                "没有 -Dnocturne.debug.world3d 时不得创建自检覆盖层");
    }

    @Test
    void explicitFalseValuesAreOff() {
        for (String value : new String[]{"false", "FALSE", "0", "no", "off", " false "}) {
            System.setProperty("nocturne.debug.world3d", value);
            assertFalse(World3DRenderer.debugOverlayEnabled(), "value=" + value);
            assertFalse(new World3DRenderer(null).hasDebugOverlay(), "value=" + value);
        }
    }

    @Test
    void explicitOnStillWorksForDebugHarness() {
        for (String value : new String[]{"", "true", "1", "yes"}) {
            System.setProperty("nocturne.debug.world3d", value);
            assertTrue(World3DRenderer.debugOverlayEnabled(), "value=" + value);
            assertTrue(new World3DRenderer(null).hasDebugOverlay(), "value=" + value);
        }
    }
}
