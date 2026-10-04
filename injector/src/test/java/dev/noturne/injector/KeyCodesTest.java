package dev.noturne.injector;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证快捷键名到键码的换算：两代输入栈各用一套毫无关联的编号，组合键取主键，未知键名退回右 Shift。
 *
 * <p>这些数字直接决定注入后快捷键是否生效，而它们既无法从代码结构推导、也无法靠肉眼看出来，
 * 因此逐个写死断言。
 */
class KeyCodesTest {

    /** 同一个键名必须按目标版本换算出对应输入栈的编号，不能混用两套表。 */
    @Test
    void resolvesRightShiftPerInputStack() {
        assertEquals(54, KeyCodes.codeFor("RSHIFT", "1.8.9"));
        assertEquals(344, KeyCodes.codeFor("RSHIFT", "1.21.4"));
    }

    /** 字母键同样分两套：LWJGL2 的 R 是 19，GLFW 的 R 是 ASCII 的 82。 */
    @Test
    void resolvesLetterPerInputStack() {
        assertEquals(19, KeyCodes.codeFor("R", "1.12.2"));
        assertEquals(82, KeyCodes.codeFor("R", "26.1.2"));
    }

    /** 版本判定：1.13 起换输入栈；26.x 这类新式版本号按现代处理。 */
    @Test
    void detectsInputStackFromVersion() {
        assertTrue(KeyCodes.usesLwjgl2("1.8.9"));
        assertTrue(KeyCodes.usesLwjgl2("1.12.2"));
        assertFalse(KeyCodes.usesLwjgl2("1.13"));
        assertFalse(KeyCodes.usesLwjgl2("1.21.8"));
        assertFalse(KeyCodes.usesLwjgl2("26.1.2"));
    }

    /** 版本读不到时（命令行拿不到或是启动器实例名）按 GLFW 处理，而不是让快捷键失效。 */
    @Test
    void fallsBackToModernWhenVersionUnknown() {
        assertFalse(KeyCodes.usesLwjgl2("\u2014"));
        assertFalse(KeyCodes.usesLwjgl2(null));
        assertEquals(344, KeyCodes.codeFor("RSHIFT", null));
    }

    /** 组合键只绑定主键：agent 每帧只轮询一个键，无法表达「按住修饰键的同时按某键」。 */
    @Test
    void usesPrimaryKeyOfCombination() {
        assertEquals("F5", KeyCodes.primaryKey("CTRL+F5"));
        assertEquals("A", KeyCodes.primaryKey("SHIFT+A"));
        assertEquals("RSHIFT", KeyCodes.primaryKey("RSHIFT"));
        assertEquals(294, KeyCodes.codeFor("CTRL+F5", "1.21.4"));
        assertEquals(63, KeyCodes.codeFor("CTRL+F5", "1.8.9"));
    }

    /** 无法识别的键名退回该后端的右 Shift；绝不能返回 0，0 在后端里代表「没有这个键」。 */
    @Test
    void unknownNamesFallBackToRightShift() {
        assertEquals(344, KeyCodes.codeFor("SOMETHINGWEIRD", "1.21.4"));
        assertEquals(54, KeyCodes.codeFor("SOMETHINGWEIRD", "1.8.9"));
        assertEquals(344, KeyCodes.codeFor(null, "1.21.4"));
    }

    /** 数字键在两代里的编号不成规律（LWJGL2 的 0 排在 9 之后），必须逐个核对。 */
    @Test
    void resolvesDigitsPerInputStack() {
        assertEquals(11, KeyCodes.codeFor("0", "1.8.9"));
        assertEquals(2, KeyCodes.codeFor("1", "1.8.9"));
        assertEquals(10, KeyCodes.codeFor("9", "1.8.9"));
        assertEquals(48, KeyCodes.codeFor("0", "1.21.4"));
        assertEquals(57, KeyCodes.codeFor("9", "1.21.4"));
    }

    /** F 键在 LWJGL2 里的 F10 与 F11 之间断开，这个边界最容易写错。 */
    @Test
    void resolvesFunctionKeysAcrossTheGap() {
        assertEquals(68, KeyCodes.codeFor("F10", "1.8.9"));
        assertEquals(87, KeyCodes.codeFor("F11", "1.8.9"));
        assertEquals(88, KeyCodes.codeFor("F12", "1.8.9"));
        assertEquals(299, KeyCodes.codeFor("F10", "1.21.4"));
        assertEquals(300, KeyCodes.codeFor("F11", "1.21.4"));
        assertEquals(301, KeyCodes.codeFor("F12", "1.21.4"));
    }

    /**
     * 传给 agent 的选项串格式：前缀与十进制键码。
     *
     * <p>agent 按 {@code split(",")} 后匹配前缀取值，因此这里是两个模块间的硬契约；
     * 前缀或进制一旦改动而没同步，快捷键会静默失效。
     */
    @Test
    void buildsOptionsStringInAgreedFormat() {
        assertEquals("guiKey=344", KeyCodes.attachOptions("RSHIFT", "1.21.4"));
        assertEquals("guiKey=54", KeyCodes.attachOptions("RSHIFT", "1.8.9"));
        assertEquals("guiKey=82", KeyCodes.attachOptions("R", "26.1.2"));
    }
}
