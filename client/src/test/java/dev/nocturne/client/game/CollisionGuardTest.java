package dev.nocturne.client.game;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 碰撞兜底路径的回归测试。
 *
 * <p>两类历史 bug：1.8.9–1.12.2 只读得到一参 {@code checkNoEntityCollision(AABB)} 时，
 * 站在盒子里的玩家自己被算成碰撞，边缘检测恒为 false；{@code blockBoxFree} 把未加载区块
 * （状态读不到）当成空位，且没有对异常 AABB 的有限性/跨度守卫。
 */
class CollisionGuardTest {

    /** 实体检查读不到时只看方块：读不到不等于"有碰撞"。 */
    @Test
    void legacyNoCollisionIgnoresMissingEntityCheck() {
        assertTrue(GameBridge.legacyBoxFree(false, null),
                "offline entity check must not be treated as a hit");
        assertTrue(GameBridge.legacyBoxFree(false, Boolean.TRUE));
        assertFalse(GameBridge.legacyBoxFree(false, Boolean.FALSE));
        assertFalse(GameBridge.legacyBoxFree(true, Boolean.TRUE), "block collision always wins");
        assertFalse(GameBridge.legacyBoxFree(true, null));
    }

    /** 正常玩家盒子：有限、跨度约 0.6×1.8×0.6。 */
    @Test
    void normalBoxSpanIsAccepted() {
        assertTrue(GameBridge.boxSpanValid(0.2, 64.0, -0.3, 0.8, 65.8, 0.3));
        assertTrue(GameBridge.boxSpanValid(0.0, 0.0, 0.0, 16.0, 16.0, 16.0));
    }

    /** 非有限值/反向/超跨度都会让逐格采样爆炸，必须直接拒绝。 */
    @Test
    void invalidBoxSpanIsRejected() {
        assertFalse(GameBridge.boxSpanValid(Double.NaN, 0, 0, 1, 1, 1));
        assertFalse(GameBridge.boxSpanValid(0, Double.POSITIVE_INFINITY, 0, 1, 1, 1));
        assertFalse(GameBridge.boxSpanValid(1, 0, 0, 0, 1, 1), "inverted axis");
        assertFalse(GameBridge.boxSpanValid(0, 0, 0, 16.5, 1, 1), "span above 16");
        assertFalse(GameBridge.boxSpanValid(0, 0, 0, 1, 100, 1), "span above 16");
    }
}
