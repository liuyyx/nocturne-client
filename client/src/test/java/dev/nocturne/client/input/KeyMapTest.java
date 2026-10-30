package dev.nocturne.client.input;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 钉住键码翻译表里"值看着一样、含义按代际完全不同"的那几项。
 *
 * <p>这类错位的代价很隐蔽：{@code -} 在 1.8.9（LWJGL2）与 1.13+（GLFW）都对，在 26.3（SDL）
 * 上却被翻译成别的键——表现只是"按这个键没反应"，不报错、不崩溃。所以期望值全部写死，
 * 不看实现推导。
 */
class KeyMapTest {

    /**
     * 调界面大小的 {@code =} / {@code -}（主键盘）：三代后端都必须认得。
     *
     * <p>SDL 侧记住：这里填的是 <b>scancode</b>（{@code SDL_GetKeyboardState} 的索引），
     * 不是 {@code SDL_Keycode}——{@code -} 的 scancode 恰好也是 45 只是巧合，
     * {@code =} 的 scancode 是 46 而不是 61。
     */
    @Test
    void scaleKeysResolveInEveryBackend() {
        assertEquals(13, KeyMap.lwjgl2(61), "1.8.9: Keyboard.KEY_EQUALS");
        assertEquals(61, KeyMap.glfw(61), "1.13+: GLFW_KEY_EQUAL");
        assertEquals(46, KeyMap.sdl(61), "26.3: SDL_SCANCODE_EQUALS");

        assertEquals(12, KeyMap.lwjgl2(45), "1.8.9: Keyboard.KEY_MINUS");
        assertEquals(45, KeyMap.glfw(45), "1.13+: GLFW_KEY_MINUS");
        assertEquals(45, KeyMap.sdl(45), "26.3: SDL_SCANCODE_MINUS");
    }

    /**
     * 标点必须按 AWT VK 解释，不能沿用 LWJGL2 的编号。
     *
     * <p>SDL 分支早期是照 LWJGL2 的键码填的，于是 26.3 上 {@code -} 被当成 Insert、
     * {@code .} 被当成 Delete、{@code /} 被当成反引号；Insert 与 Delete 则完全没有映射
     * （AWT 里它们是 155 / 127，不是 LWJGL2 的 210 / 211）。
     */
    @Test
    void punctuationFollowsAwtCodesInSdl() {
        assertEquals(55, KeyMap.sdl(46), "AWT 46 = '.' → SDL_SCANCODE_PERIOD");
        assertEquals(56, KeyMap.sdl(47), "AWT 47 = '/' → SDL_SCANCODE_SLASH");
        assertEquals(73, KeyMap.sdl(155), "AWT 155 = Insert → SDL_SCANCODE_INSERT");
        assertEquals(76, KeyMap.sdl(127), "AWT 127 = Delete → SDL_SCANCODE_DELETE");
    }

    /**
     * 默认开关键：右 Shift（规范键码 54）必须解析成右 Shift，绝不能被当成数字键 6。
     *
     * <p>{@code 54} 与字符 {@code '6'} 的 ASCII 相同，而 SDL 分支用「字母/数字连续段套公式」的写法，
     * 会先把它算成数字键 6（scancode 35）——症状是"产品默认的开关键按下去没反应"，而左 Shift（16）
     * 一切正常，极易误判成键盘/输入法问题。真机实测：`sdl(54)=35`，`SDL_GetKeyboardState().get(229)`
     * 恒为 0，但游戏事件流里 `scancode=229 down=true` 明明到了。
     *
     * <p>取舍：SDL 世代里数字键 6 会被读成右 Shift。界面不读数字键，换来默认开关键可用，划算。
     */
    @Test
    void rightShiftIsNotMistakenForDigitSix() {
        assertEquals(229, KeyMap.sdl(KeyMap.VK_RIGHT_SHIFT), "26.3: SDL_SCANCODE_RSHIFT，不是 35");
        assertEquals(225, KeyMap.sdl(KeyMap.VK_LEFT_SHIFT), "26.3: SDL_SCANCODE_LSHIFT");
        assertEquals(54, KeyMap.lwjgl2(KeyMap.VK_RIGHT_SHIFT), "1.8.9: Keyboard.KEY_RSHIFT");
        assertEquals(344, KeyMap.glfw(KeyMap.VK_RIGHT_SHIFT), "1.13+: GLFW_KEY_RIGHT_SHIFT");
    }

    /**
     * 标点与功能键必须按 AWT VK 解释，且不能与邻近数值互相冒充。
     *
     * <p>这批错的共同点都是"照 LWJGL2 或想当然填表"：44 在 AWT 是逗号却被当成 PrintScreen；
     * 91/92/93 是 {@code [ \ ]} 却被当成 Win/Meta/Menu（按一下括号等于按下 Win 键，会弹出开始菜单）；
     * 112 是 F1 却被当成 F11，F1..F12 整段因此错位；59（分号）被 F 键公式抢走变成 F1。
     * 共同症状：某个键静默失效或触发无关功能，不报错。
     */
    @Test
    void punctuationAndFunctionKeysFollowAwtCodesInSdl() {
        assertEquals(54, KeyMap.sdl(44), "AWT 44 = ',' → SDL_SCANCODE_COMMA，不是 PrintScreen");
        assertEquals(51, KeyMap.sdl(59), "AWT 59 = ';' → SDL_SCANCODE_SEMICOLON");
        assertEquals(47, KeyMap.sdl(91), "AWT 91 = '[' → SDL_SCANCODE_LEFTBRACKET，不是 Win");
        assertEquals(49, KeyMap.sdl(92), "AWT 92 = '\\' → SDL_SCANCODE_BACKSLASH，不是 Meta");
        assertEquals(48, KeyMap.sdl(93), "AWT 93 = ']' → SDL_SCANCODE_RIGHTBRACKET，不是 Menu");
        assertEquals(70, KeyMap.sdl(154), "AWT 154 = PrintScreen → SDL_SCANCODE_PRINTSCREEN");
        assertEquals(101, KeyMap.sdl(525), "AWT 525 = ContextMenu → SDL_SCANCODE_APPLICATION");
        assertEquals(227, KeyMap.sdl(524), "AWT 524 = Win → SDL_SCANCODE_LGUI");
        assertEquals(58, KeyMap.sdl(112), "AWT 112 = F1 → SDL_SCANCODE_F1，不是 F11");
        assertEquals(69, KeyMap.sdl(123), "AWT 123 = F12 → SDL_SCANCODE_F12");
    }

    /** 事件入口把后端键码还原到同一 AWT VK 空间。 */
    @Test
    void backendCodesResolveBackToAwt() {
        assertEquals(65, KeyMap.fromGlfw(65));
        assertEquals(65, KeyMap.fromSdl(4));
        assertEquals(KeyMap.VK_RIGHT_SHIFT, KeyMap.fromSdl(229));
        assertEquals(KeyMap.NONE, KeyMap.fromGlfw(-999));
        assertEquals(KeyMap.NONE, KeyMap.fromSdl(-999));
    }

    /**
     * 表外键返回 {@link KeyMap#NONE}，调用方据此视为"永不按下"。
     *
     * <p>这条是防"猜一个错的键码"：猜错会让某个无关键被当成按下，比不识别更糟。
     */
    @Test
    void unmappedKeysAreReportedAsNone() {
        assertEquals(KeyMap.NONE, KeyMap.sdl(0xBEEF), "表外的规范键码");
        assertEquals(KeyMap.NONE, KeyMap.lwjgl2(0xBEEF));
        assertEquals(KeyMap.NONE, KeyMap.glfw(0xBEEF));
    }
}
