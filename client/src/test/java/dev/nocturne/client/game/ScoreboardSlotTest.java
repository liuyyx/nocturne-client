package dev.nocturne.client.game;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * {@code Scoreboard#getDisplayObjective(DisplaySlot)} 的形参是枚举，不是 int。
 *
 * <p>回归：调用方传 {@code Integer} 时重载推断必然失败（"no overload for getDisplayObjective"），
 * Better Scoreboard 整块空白。这里验证从形参类型取 SIDEBAR 的两条路：按名字、以及名字被
 * 混淆时按稳定序号。
 */
class ScoreboardSlotTest {

    /** 未混淆客户端的枚举顺序：LIST、SIDEBAR、BELOW_NAME。 */
    enum DisplaySlot {
        LIST, SIDEBAR, BELOW_NAME
    }

    /** 混淆客户端的枚举常量名不再是语义名。 */
    enum ObfuscatedSlot {
        a, b, c
    }

    /** 单常量枚举没有 SIDEBAR，也不能退回序号，必须返回 null。 */
    enum SingleSlot {
        only
    }

    @Test
    void resolvesSidebarByName() {
        assertSame(DisplaySlot.SIDEBAR, GameBridge.sidebarSlot(DisplaySlot.class));
    }

    @Test
    void fallsBackToOrdinalWhenNamesAreObfuscated() {
        assertEquals(ObfuscatedSlot.b, GameBridge.sidebarSlot(ObfuscatedSlot.class));
    }

    @Test
    void rejectsNonEnumAndDegenerateEnums() {
        assertNull(GameBridge.sidebarSlot(String.class));
        assertNull(GameBridge.sidebarSlot(int.class));
        assertNull(GameBridge.sidebarSlot(null));
        assertNull(GameBridge.sidebarSlot(SingleSlot.class));
    }
}
