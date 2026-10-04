package dev.noturne.injector;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 验证快捷键名与 AWT VK 码的互查：名字能换算成 VK，VK 也能还原成同一个名字。
 *
 * <p>按契约 K1，注入器只发 AWT VK 码（运行时由 KeyMap 翻译成后端码），所以这里不再有版本维度。
 * 这些数字直接决定注入后快捷键是否生效，且无法从代码结构推导，因此逐个写死断言。
 */
class KeyCodesTest {

    /** 字母/数字/AWT 与 ASCII 一致。 */
    @Test
    void resolvesLettersAndDigits() {
        assertEquals(82, KeyCodes.codeFor("R"));
        assertEquals(65, KeyCodes.codeFor("A"));
        assertEquals(48, KeyCodes.codeFor("0"));
        assertEquals(57, KeyCodes.codeFor("9"));
    }

    /** F 键用 AWT 的连续编号（F1=112）。 */
    @Test
    void resolvesFunctionKeys() {
        assertEquals(112, KeyCodes.codeFor("F1"));
        assertEquals(116, KeyCodes.codeFor("F5"));
        assertEquals(123, KeyCodes.codeFor("F12"));
    }

    /** 修饰键：左半边用 AWT 原生值，右半边用与 RSHIFT=54 相同的后端右变体哨兵值。 */
    @Test
    void resolvesModifiers() {
        assertEquals(16, KeyCodes.codeFor("LSHIFT"));
        assertEquals(54, KeyCodes.codeFor("RSHIFT"));
        assertEquals(17, KeyCodes.codeFor("LCTRL"));
        assertEquals(157, KeyCodes.codeFor("RCTRL"));
        assertEquals(18, KeyCodes.codeFor("LALT"));
        assertEquals(184, KeyCodes.codeFor("RALT"));
    }

    /** 符号键：H-32 的回归——录制名与这里的键名必须一致，否则会静默退回右 Shift。 */
    @Test
    void resolvesSymbolKeys() {
        assertEquals(45, KeyCodes.codeFor("MINUS"));
        assertEquals(61, KeyCodes.codeFor("EQUALS"));
        assertEquals(91, KeyCodes.codeFor("OPENBRACKET"));
        assertEquals(93, KeyCodes.codeFor("CLOSEBRACKET"));
        assertEquals(92, KeyCodes.codeFor("BACKSLASH"));
        assertEquals(59, KeyCodes.codeFor("SEMICOLON"));
        assertEquals(222, KeyCodes.codeFor("QUOTE"));
        assertEquals(192, KeyCodes.codeFor("BACKQUOTE"));
        assertEquals(44, KeyCodes.codeFor("COMMA"));
        assertEquals(46, KeyCodes.codeFor("PERIOD"));
        assertEquals(47, KeyCodes.codeFor("SLASH"));
    }

    /** 小键盘：AWT 的 NUMPAD0..9 是 96..105，与主键区数字完全不同。 */
    @Test
    void resolvesNumpadKeys() {
        assertEquals(96, KeyCodes.codeFor("NUMPAD0"));
        assertEquals(105, KeyCodes.codeFor("NUMPAD9"));
        assertEquals(106, KeyCodes.codeFor("NUMPADMULTIPLY"));
        assertEquals(107, KeyCodes.codeFor("NUMPADADD"));
        assertEquals(109, KeyCodes.codeFor("NUMPADSUBTRACT"));
        assertEquals(110, KeyCodes.codeFor("NUMPADDECIMAL"));
        assertEquals(111, KeyCodes.codeFor("NUMPADDIVIDE"));
        // 小键盘回车与主回车共用 VK_ENTER。
        assertEquals(10, KeyCodes.codeFor("NUMPADENTER"));
    }

    /** 反向查表：VK → 规范名，且正向能再解析回同一个 VK（往返一致）。 */
    @Test
    void vkNamesRoundTrip() {
        String[] names = {
                "MINUS", "EQUALS", "OPENBRACKET", "CLOSEBRACKET", "BACKSLASH", "SEMICOLON",
                "QUOTE", "BACKQUOTE", "COMMA", "PERIOD", "SLASH",
                "NUMPAD0", "NUMPAD5", "NUMPAD9", "NUMPADMULTIPLY", "NUMPADDIVIDE",
                "F1", "F12", "R", "0", "RSHIFT", "PAGEUP"};
        for (String name : names) {
            int vk = KeyCodes.codeFor(name);
            assertEquals(name, KeyCodes.nameForVk(vk), "往返失败：" + name);
        }
    }

    /** 表外 VK 用 {@code KEY<code>} 表达，且能被 {@link KeyCodes#codeFor(String)} 原样解析回来。 */
    @Test
    void unknownVkUsesKeyPrefix() {
        assertEquals("KEY9999", KeyCodes.nameForVk(9999));
        assertEquals(9999, KeyCodes.codeFor("KEY9999"));
    }

    /** 组合键只绑定主键：agent 每帧只轮询一个键，无法表达「按住修饰键的同时按某键」。 */
    @Test
    void usesPrimaryKeyOfCombination() {
        assertEquals("F5", KeyCodes.primaryKey("CTRL+F5"));
        assertEquals("A", KeyCodes.primaryKey("SHIFT+A"));
        assertEquals("RSHIFT", KeyCodes.primaryKey("RSHIFT"));
        assertEquals(116, KeyCodes.codeFor("CTRL+F5"));
    }

    /** 无法识别的键名退回右 Shift(54)；绝不能返回 0，0 在后端里代表「没有这个键」。 */
    @Test
    void unknownNamesFallBackToRightShift() {
        assertEquals(54, KeyCodes.codeFor("SOMETHINGWEIRD"));
        assertEquals(54, KeyCodes.codeFor(null));
        assertEquals(54, KeyCodes.codeFor(""));
    }

    /**
     * 传给 agent 的选项串格式：{@code guiKey=<十进制 VK>}，版本可判定时追加 {@code mcVersion=<版本族>}。
     *
     * <p>agent 按 {@code split(",")} 后匹配前缀取值，因此这里是两个模块间的硬契约。
     */
    @Test
    void buildsOptionsStringInAgreedFormat() {
        assertEquals("guiKey=54", KeyCodes.attachOptions("RSHIFT", null));
        assertEquals("guiKey=82", KeyCodes.attachOptions("R", null));
        assertEquals("guiKey=116", KeyCodes.attachOptions("CTRL+F5", null));
        // 版本标签来自启动器（实例名），必须被压成版本号再传
        assertEquals("guiKey=54,mcVersion=1.8.9", KeyCodes.attachOptions("RSHIFT", "1.8.9优化"));
        assertEquals("guiKey=54,mcVersion=26.3", KeyCodes.attachOptions("RSHIFT", "26.3-Fabric 0.19.5"));
        // 判定不出时不传这一项，agent 侧按未知处理（退化为恒等映射并打日志）
        assertEquals("guiKey=54", KeyCodes.attachOptions("RSHIFT", "\u2014"));
    }
}
