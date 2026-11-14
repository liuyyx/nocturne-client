package dev.nocturne.client.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 覆盖层宽高比覆写：只改横向投影，不改纵向、深度与可见性。 */
class AspectOverrideTest {

    private static WorldProjection projection() {
        WorldProjection p = new WorldProjection();
        p.update(0, 0, 0, 0, 0, 70, 427, 240);
        return p;
    }

    @Test
    void overrideChangesOnlyHorizontalProjection() {
        WorldProjection p = projection();
        WorldProjection.ScreenPoint base = p.project(1, 0, 5);

        p.setAspectOverride(3.2f);
        WorldProjection.ScreenPoint changed = p.project(1, 0, 5);

        assertTrue(changed.rawX != base.rawX, "横向投影必须随覆写变化");
        assertEquals(base.rawY, changed.rawY, 0.0001f, "纵向投影不受影响");
        assertEquals(base.depth, changed.depth, 0.0001, "深度不受影响");
        assertEquals(base.visible, changed.visible, "可见性不受影响");

        p.setAspectOverride(0f);
        WorldProjection.ScreenPoint restored = p.project(1, 0, 5);
        assertEquals(base.rawX, restored.rawX, 0.0001f, "覆写清空后恢复视口实际比例");
        assertEquals(base.rawY, restored.rawY, 0.0001f);
    }
}
