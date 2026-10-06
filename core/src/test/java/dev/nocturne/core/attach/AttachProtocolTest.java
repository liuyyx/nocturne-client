package dev.nocturne.core.attach;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link AttachProtocol} 编解码的单元测试：线格式是跨进程契约，
 * 编错一个字节目标 JVM 就读不懂，必须用测试锁死。
 */
class AttachProtocolTest {

    @Test
    @DisplayName("load 编码为 5 个 NUL 结尾字符串")
    void encodeLoadShape() {
        byte[] wire = AttachProtocol.encodeLoad("C:\\agent.jar", "a=1", false);
        String text = new String(wire, StandardCharsets.UTF_8);
        String[] parts = text.split("\0", -1);
        // 5 个字段 + 末尾空串（最后一个 NUL 之后）
        assertEquals(6, parts.length);
        assertEquals("1", parts[0]);
        assertEquals("load", parts[1]);
        assertEquals("instrument", parts[2]);
        assertEquals("false", parts[3]);
        assertEquals("C:\\agent.jar=a=1", parts[4]);
    }

    @Test
    @DisplayName("options 为 null 时按空串处理")
    void encodeLoadNullOptions() {
        byte[] wire = AttachProtocol.encodeLoad("x.jar", null, true);
        String text = new String(wire, StandardCharsets.UTF_8);
        assertTrue(text.endsWith("x.jar=\0"));
    }

    @Test
    @DisplayName("动词超长或含 NUL 时拒绝")
    void rejectsBadCommand() {
        assertThrows(IllegalArgumentException.class, () ->
                AttachProtocol.encode("0123456789abcdefg", new String[]{"", "", ""}));
        assertThrows(IllegalArgumentException.class, () ->
                AttachProtocol.encode("lo\0ad", new String[]{"", "", ""}));
    }

    @Test
    @DisplayName("参数个数不是 3 时拒绝")
    void rejectsBadArguments() {
        assertThrows(IllegalArgumentException.class, () ->
                AttachProtocol.encode("load", new String[]{"", ""}));
    }

    @Test
    @DisplayName("外层返回码解析")
    void parseReplyCode() {
        assertEquals(0, AttachProtocol.parseReplyCode("0"));
        assertEquals(101, AttachProtocol.parseReplyCode("101\n"));
        assertEquals(AttachProtocol.TARGET_INTERNAL_ERROR, AttachProtocol.parseReplyCode("bogus"));
        assertEquals(AttachProtocol.TARGET_INTERNAL_ERROR, AttachProtocol.parseReplyCode(null));
    }

    @Test
    @DisplayName("load 内层兼容 JDK 8 裸整数与 JDK 21 前缀")
    void parseLoadResult() {
        assertEquals(0, AttachProtocol.parseLoadResult("0"));
        assertEquals(0, AttachProtocol.parseLoadResult("return-code-0"));
        assertEquals(100, AttachProtocol.parseLoadResult("100"));
        assertEquals(100, AttachProtocol.parseLoadResult("return-code-100"));
        assertEquals(-1, AttachProtocol.parseLoadResult("garbage"));
        assertEquals(-1, AttachProtocol.parseLoadResult(null));
    }
}
