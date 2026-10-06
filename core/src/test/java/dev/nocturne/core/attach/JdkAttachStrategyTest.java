package dev.nocturne.core.attach;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Constructor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link JdkAttachStrategy#isJdk8ResponseMismatch(Throwable)} 的回归测试。
 *
 * <p>这一条判定直接决定「注入算不算成功」：JDK 21 客户端读非 21 目标的响应时会抛异常，
 * 但目标侧其实已经加载成功、返回码为 0。曾经只认「裸返回码 0」，把真实形态
 * {@code Failed to load agent library: 0} 判成失败——用户实机就是这样被挡住的。
 *
 * <p>异常用反射构造：{@code com.sun.tools.attach.*} 在 {@code --release 8} 的编译期不可见
 * （它在 JDK 8 的 tools.jar 里），而运行期必然存在。
 */
class JdkAttachStrategyTest {

    /** 反射构造 {@code com.sun.tools.attach.AgentLoadException}（message 可为 null）。 */
    private static Throwable agentLoadException(String message) {
        try {
            Class<?> type = Class.forName("com.sun.tools.attach.AgentLoadException");
            Constructor<?> constructor = type.getConstructor(String.class);
            return (Throwable) constructor.newInstance(message);
        } catch (Throwable failure) {
            throw new IllegalStateException("无法构造 AgentLoadException", failure);
        }
    }

    @Test
    @DisplayName("带 loadAgentLibrary 前缀的 0 是成功（实机遇到的形态）")
    void acceptsPrefixedZero() {
        assertTrue(JdkAttachStrategy.isJdk8ResponseMismatch(
                agentLoadException("Failed to load agent library: 0")));
    }

    @Test
    @DisplayName("裸返回码 0 是成功")
    void acceptsBareZero() {
        assertTrue(JdkAttachStrategy.isJdk8ResponseMismatch(
                agentLoadException("0")));
    }

    @Test
    @DisplayName("响应解析失败但末尾返回码为 0 也是成功")
    void acceptsUnexpectedReplyZero() {
        assertTrue(JdkAttachStrategy.isJdk8ResponseMismatch(
                new IOException("Unexpected reply from target JVM: 0")));
    }

    @Test
    @DisplayName("非 0 返回码一律是失败")
    void rejectsNonZero() {
        assertFalse(JdkAttachStrategy.isJdk8ResponseMismatch(
                agentLoadException("Failed to load agent library: 1")));
        assertFalse(JdkAttachStrategy.isJdk8ResponseMismatch(
                agentLoadException("2")));
        assertFalse(JdkAttachStrategy.isJdk8ResponseMismatch(
                new IOException("Unexpected reply from target JVM: 103")));
    }

    @Test
    @DisplayName("异常类型不对时不认：避免把无关失败当成「其实成功了」")
    void rejectsUnrelatedExceptionTypes() {
        assertFalse(JdkAttachStrategy.isJdk8ResponseMismatch(
                new RuntimeException("Failed to load agent library: 0")));
        assertFalse(JdkAttachStrategy.isJdk8ResponseMismatch(
                new IllegalStateException("0")));
    }

    @Test
    @DisplayName("消息为空或完全无关时是失败")
    void rejectsEmptyOrUnrelatedMessage() {
        assertFalse(JdkAttachStrategy.isJdk8ResponseMismatch(agentLoadException(null)));
        assertFalse(JdkAttachStrategy.isJdk8ResponseMismatch(
                agentLoadException("agent failed to start")));
        assertFalse(JdkAttachStrategy.isJdk8ResponseMismatch(null));
    }
}
