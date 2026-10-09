package dev.nocturne.ui.gl;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GameInput} 纯逻辑部分的契约：按钮索引换算与窗口→逻辑坐标换算。
 *
 * <p>为什么单独钉这两条：它们错了都不会报错，只会"点错东西"或"点不中"，而真机验证一轮代价很高。
 * <ul>
 *   <li>按钮：界面侧索引是 0=左 1=右 2=中，游戏侧 {@code MouseButtonInfo.button()} 是
 *       1=左 2=中 3=右（26.3 {@code onButton} 的分支顺序）。搞反了就是"左键点出右键菜单"。</li>
 *   <li>坐标：{@code MouseHandler.xpos} 是窗口坐标，界面用游戏 GUI 缩放后的逻辑坐标。26.3 实测
 *       窗口 854x480、逻辑 427x240，换算比例 0.5——不换算时点击落点整体偏一倍。</li>
 * </ul>
 */
class GameInputTest {

    @Test
    void mapsGuiButtonIndexToGameButtonValue() {
        assertEquals(1, GameInput.pressedIndex(0), "interface index 0 = left = game value 1");
        assertEquals(3, GameInput.pressedIndex(1), "interface index 1 = right = game value 3");
        assertEquals(2, GameInput.pressedIndex(2), "interface index 2 = middle = game value 2");
        // 越界必须给出一个与任何合法取值都不等的值，否则会被当成"按下了某个键"。
        assertNotEquals(1, GameInput.pressedIndex(3));
        assertNotEquals(2, GameInput.pressedIndex(3));
        assertNotEquals(3, GameInput.pressedIndex(3));
    }

    @Test
    void convertsWindowCoordinatesToLogicalCoordinates() {
        // 26.3 真机口径：窗口 854x480、界面逻辑 427x240。
        assertEquals(242d, GameInput.scaled(484d, 427, 854), 0.001);
        assertEquals(140d, GameInput.scaled(280d, 240, 480), 0.001);
    }

    @Test
    void leavesCoordinatesAloneWhenASizeIsUnknown() {
        // GUI 缩放为 1（逻辑 == 窗口）时不得改动。
        assertEquals(100d, GameInput.scaled(100d, 854, 854), 0.001);
        // 逻辑尺寸还没测出来、或窗口尺寸读不到：原样返回（调用方另有告警），不得变成 0 或 NaN。
        assertEquals(100d, GameInput.scaled(100d, 0, 854), 0.001);
        assertEquals(100d, GameInput.scaled(100d, 427, 0), 0.001);
        assertTrue(Double.isFinite(GameInput.scaled(100d, 427, 0)));
    }
}
