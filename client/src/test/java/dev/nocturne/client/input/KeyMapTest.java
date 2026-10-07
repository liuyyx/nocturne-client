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
