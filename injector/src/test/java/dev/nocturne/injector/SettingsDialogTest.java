package dev.nocturne.injector;

import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 验证录制时按键名如何渲染：普通键取自身名字、修饰键区分左右、符号键与小键盘键必须能被
 * {@link KeyCodes} 原样解析。
 *
 * <p>这些字符串会被写进 {@code config.guiBind} 再交给 {@link KeyCodes} 换算，所以命名一旦漂移，
 * 快捷键就会静默退回右 Shift——那正是「按什么键都绑到右 Shift」这类问题的表现。
 */
class SettingsDialogTest {

    /**
     * 构造一个按下事件。
     *
     * @param keyCode   虚拟键码
     * @param modifiers 修饰键掩码
     * @param location  按键位置，用于区分左右修饰键与小键盘
     */
    private static KeyEvent press(int keyCode, int modifiers, int location) {
        return new KeyEvent(new JPanel(), KeyEvent.KEY_PRESSED, System.currentTimeMillis(),
                modifiers, keyCode, KeyEvent.CHAR_UNDEFINED, location);
    }

    /** 普通键用自身名字，不带任何前缀。 */
    @Test
    void namesPlainKeys() {
        assertEquals("R", SettingsDialog.describe(press(KeyEvent.VK_R, 0, KeyEvent.KEY_LOCATION_STANDARD)));
        assertEquals("F5", SettingsDialog.describe(press(KeyEvent.VK_F5, 0, KeyEvent.KEY_LOCATION_STANDARD)));
    }

    /** 左右修饰键必须能分开：游戏把它们当作不同的绑定。 */
    @Test
    void distinguishesLeftAndRightModifiers() {
        assertEquals("RSHIFT",
                SettingsDialog.describe(press(KeyEvent.VK_SHIFT, 0, KeyEvent.KEY_LOCATION_RIGHT)));
        assertEquals("LSHIFT",
                SettingsDialog.describe(press(KeyEvent.VK_SHIFT, 0, KeyEvent.KEY_LOCATION_LEFT)));
        assertEquals("RCTRL",
                SettingsDialog.describe(press(KeyEvent.VK_CONTROL, 0, KeyEvent.KEY_LOCATION_RIGHT)));
        assertEquals("LALT",
                SettingsDialog.describe(press(KeyEvent.VK_ALT, 0, KeyEvent.KEY_LOCATION_LEFT)));
    }

    /** 组合键带修饰前缀，但按下的就是修饰键本身时不加前缀（否则会得到 SHIFT+RSHIFT）。 */
    @Test
    void prefixesModifiersButNotTheKeyItself() {
        assertEquals("SHIFT+R", SettingsDialog.describe(
                press(KeyEvent.VK_R, InputEvent.SHIFT_DOWN_MASK, KeyEvent.KEY_LOCATION_STANDARD)));
    }

    /** 名字里的空格要压掉，换算表按无空格的名字建键。 */
    @Test
    void stripsSpacesFromKeyNames() {
        assertEquals("PAGEUP",
                SettingsDialog.describe(press(KeyEvent.VK_PAGE_UP, 0, KeyEvent.KEY_LOCATION_STANDARD)));
        assertEquals("CAPSLOCK",
                SettingsDialog.describe(press(KeyEvent.VK_CAPS_LOCK, 0, KeyEvent.KEY_LOCATION_STANDARD)));
    }

    /** H-32 回归：符号键的录制名必须与 KeyCodes 表一致，且能解析回正确的 VK。 */
    @Test
    void recordsSymbolKeysResolvable() {
        assertEquals("MINUS", SettingsDialog.describe(
                press(KeyEvent.VK_MINUS, 0, KeyEvent.KEY_LOCATION_STANDARD)));
        assertEquals(45, KeyCodes.codeFor(SettingsDialog.describe(
                press(KeyEvent.VK_MINUS, 0, KeyEvent.KEY_LOCATION_STANDARD))));
        assertEquals(91, KeyCodes.codeFor(SettingsDialog.describe(
                press(KeyEvent.VK_OPEN_BRACKET, 0, KeyEvent.KEY_LOCATION_STANDARD))));
        assertEquals(222, KeyCodes.codeFor(SettingsDialog.describe(
                press(KeyEvent.VK_QUOTE, 0, KeyEvent.KEY_LOCATION_STANDARD))));
    }

    /** H-32 回归：小键盘键的录制名必须能解析回小键盘 VK，而不是退回右 Shift。 */
    @Test
    void recordsNumpadKeysResolvable() {
        assertEquals("NUMPAD0", SettingsDialog.describe(
                press(KeyEvent.VK_NUMPAD0, 0, KeyEvent.KEY_LOCATION_NUMPAD)));
        assertEquals(96, KeyCodes.codeFor(SettingsDialog.describe(
                press(KeyEvent.VK_NUMPAD0, 0, KeyEvent.KEY_LOCATION_NUMPAD))));
        assertEquals(105, KeyCodes.codeFor(SettingsDialog.describe(
                press(KeyEvent.VK_NUMPAD9, 0, KeyEvent.KEY_LOCATION_NUMPAD))));
        assertEquals(111, KeyCodes.codeFor(SettingsDialog.describe(
                press(KeyEvent.VK_DIVIDE, 0, KeyEvent.KEY_LOCATION_NUMPAD))));
    }

    /** 小键盘回车与主回车靠 location 区分名字。 */
    @Test
    void distinguishesNumpadEnter() {
        assertEquals("ENTER",
                SettingsDialog.describe(press(KeyEvent.VK_ENTER, 0, KeyEvent.KEY_LOCATION_STANDARD)));
        assertEquals("NUMPADENTER",
                SettingsDialog.describe(press(KeyEvent.VK_ENTER, 0, KeyEvent.KEY_LOCATION_NUMPAD)));
    }

    /** 录制结果必须能被换算表认出来，两端命名约定不能脱节。 */
    @Test
    void recordedNamesAreResolvable() {
        String recorded = SettingsDialog.describe(press(KeyEvent.VK_PAGE_UP, 0, KeyEvent.KEY_LOCATION_STANDARD));
        assertEquals(33, KeyCodes.codeFor(recorded));
    }
}
