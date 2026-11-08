package dev.nocturne.client.module.modules.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Scaffold 纯逻辑回归：基准 yaw 不被写回视角污染、输入补偿方向、黑名单词元匹配、换槽恢复。
 */
class ScaffoldPlanTest {

    private static final double EPSILON = 1.0E-6;

    /** 目标点只由基准 yaw 决定：两次调用（模拟 aim 已写回 180° 之后）必须完全一致。 */
    @Test
    void targetPointDoesNotDependOnWrittenBackYaw() {
        double[] first = ScaffoldModule.targetPoint(0.0f, 10.5, 70.0, 10.5, 1.0, false, false);
        double[] second = ScaffoldModule.targetPoint(0.0f, 10.5, 70.0, 10.5, 1.0, false, false);
        assertArrayEquals(first, second, EPSILON, "same base yaw must yield the same bridge target");
        assertEquals(10.5, first[0], EPSILON);
        assertEquals(11.5, first[2], EPSILON, "yaw 0 looks toward +Z");
        assertEquals(69.0, first[1], EPSILON);
    }

    /** 基准 yaw 180° 时目标落在反方向，而不是相对上一次写回的 yaw 再 +180 绕圈。 */
    @Test
    void targetPointFollowsBaseYawOnly() {
        double[] forward = ScaffoldModule.targetPoint(0.0f, 0.0, 64.0, 0.0, 1.2, false, false);
        double[] backward = ScaffoldModule.targetPoint(180.0f, 0.0, 64.0, 0.0, 1.2, false, false);
        assertEquals(0.0, forward[0], EPSILON);
        assertTrue(forward[2] > 0.0);
        assertEquals(0.0, backward[0], EPSILON);
        assertTrue(backward[2] < 0.0);
        assertNotEquals(forward[2], backward[2]);
    }

    /** KeepY 模式支撑面按整格向下取，普通模式用实体 y。 */
    @Test
    void keepYFloorsTheSupportHeight() {
        assertEquals(63.0, ScaffoldModule.targetPoint(0.0f, 0.0, 64.7, 0.0, 1.0, false, true)[1], EPSILON);
        assertEquals(63.7, ScaffoldModule.targetPoint(0.0f, 0.0, 64.7, 0.0, 1.0, false, false)[1], EPSILON);
    }

    /** 视角被转 180° 后，向前的输入必须反向，否则按 W 会往身后走。 */
    @Test
    void inputIsRotatedBackWhenViewIsFlipped() {
        float[] flipped = ScaffoldModule.rotateInput(1.0f, 0.0f, 180.0f);
        assertEquals(-1.0f, flipped[0], EPSILON);
        assertEquals(0.0f, flipped[1], EPSILON);

        float[] quarter = ScaffoldModule.rotateInput(1.0f, 0.0f, 90.0f);
        assertEquals(0.0f, quarter[0], EPSILON);
        assertEquals(1.0f, quarter[1], EPSILON);

        float[] none = ScaffoldModule.rotateInput(1.0f, 0.0f, 0.0f);
        assertEquals(1.0f, none[0], EPSILON);
        assertEquals(0.0f, none[1], EPSILON);
    }

    /** 黑名单按词元匹配：door 命中 oak_door，bed 不得误伤 bedrock。 */
    @Test
    void blacklistMatchesTokensNotSubstrings() {
        String config = "chest,bed,door,pressure_plate,shulker";
        assertTrue(ScaffoldModule.blacklisted(config, "block.minecraft.red_bed"));
        assertTrue(ScaffoldModule.blacklisted(config, "block.minecraft.oak_door"));
        assertTrue(ScaffoldModule.blacklisted(config, "block.minecraft.oak_pressure_plate"));
        assertTrue(ScaffoldModule.blacklisted(config, "block.minecraft.shulker_box"));
        assertTrue(ScaffoldModule.blacklisted(config, "block.minecraft.chest"));
        assertFalse(ScaffoldModule.blacklisted(config, "block.minecraft.bedrock"));
        assertFalse(ScaffoldModule.blacklisted(config, "block.minecraft.stone"));
        assertTrue(ScaffoldModule.blacklisted(config, ""), "unreadable id must not be placed");
    }

    /** 换槽状态机：读不到原槽放弃换槽；槽 0 是合法值而不是"没有原槽"。 */
    @Test
    void slotSwapNeverStrandsThePlayer() {
        ScaffoldModule.SlotSwap unknown = new ScaffoldModule.SlotSwap();
        assertFalse(unknown.begin(-1, 3), "unknown original slot must abort the swap");
        assertFalse(unknown.swapped());
        assertEquals(-1, unknown.takeRestore());

        ScaffoldModule.SlotSwap same = new ScaffoldModule.SlotSwap();
        assertTrue(same.begin(2, 2));
        assertFalse(same.swapped(), "already holding the target slot");
        assertEquals(-1, same.takeRestore(), "nothing to restore");

        ScaffoldModule.SlotSwap zero = new ScaffoldModule.SlotSwap();
        assertTrue(zero.begin(0, 3));
        assertTrue(zero.swapped());
        assertEquals(0, zero.takeRestore(), "slot 0 is a real slot");
        assertEquals(-1, zero.takeRestore(), "restore happens once");

        ScaffoldModule.SlotSwap swapped = new ScaffoldModule.SlotSwap();
        assertTrue(swapped.begin(4, 3));
        assertTrue(swapped.swapped());
        assertEquals(4, swapped.takeRestore());
        assertFalse(swapped.swapped());
    }
}
