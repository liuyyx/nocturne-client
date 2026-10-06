package dev.nocturne.core.attach;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link WindowsAttachStrategy} 的单元测试：策略名、参数校验与回复映射。
 *
 * <p>原生层未落地前不碰真实进程：管道服务端用内存替身注入，传输只验到“原生入口被调用”为止。
 */
class WindowsAttachStrategyTest {

    /** 内存管道替身：awaitConnection 直接放行，input 吐出预置回复。 */
    private static final class MemoryPipe implements WindowsAttachStrategy.PipeServer {
        private final byte[] reply;

        MemoryPipe(String reply) {
            this.reply = reply.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public void awaitConnection(long timeoutMs) {
            // 内存替身无需等待。
        }

        @Override
        public InputStream input() {
            return new java.io.ByteArrayInputStream(reply);
        }

        @Override
        public void close() {
            // 无资源可释放。
        }
    }

    @Test
    @DisplayName("策略名固定为 windows-native")
    void strategyName() {
        assertEquals("windows-native", new WindowsAttachStrategy().name());
    }

    @Test
    @DisplayName("agent jar 不存在时直接拒绝，不碰原生层")
    void rejectsMissingJar() {
        WindowsAttachStrategy strategy = new WindowsAttachStrategy();
        assertThrows(IllegalArgumentException.class, () ->
                strategy.attach(1234, new File("no-such-agent.jar"), ""));
    }

    @Test
    @DisplayName("非法 pid 直接拒绝，不碰原生层")
    void rejectsBadPid() {
        WindowsAttachStrategy strategy = new WindowsAttachStrategy();
        assertThrows(IllegalArgumentException.class, () ->
                strategy.attach(0, new File("."), ""));
    }

    @Test
    @DisplayName("外层非 0 映射为目标拒绝")
    void mapsOuterFailure() {
        WindowsAttachStrategy.AttachOperationException failure;
        try {
            WindowsAttachStrategy.checkReply("102\n", 4321);
            throw new IllegalStateException("must have thrown");
        } catch (WindowsAttachStrategy.AttachOperationException expected) {
            failure = expected;
        }
        assertTrue(failure.asException(4321) instanceof IllegalArgumentException);
    }

    @Test
    @DisplayName("内层非 0 映射为 agent 启动失败")
    void mapsInnerFailure() {
        WindowsAttachStrategy.AttachOperationException failure;
        try {
            WindowsAttachStrategy.checkReply("0\n102", 4321);
            throw new IllegalStateException("must have thrown");
        } catch (WindowsAttachStrategy.AttachOperationException expected) {
            failure = expected;
        }
        assertTrue(failure.asException(4321) instanceof IOException);
    }

    @Test
    @DisplayName("空回复按传输失败处理")
    void rejectsEmptyReply() {
        try {
            WindowsAttachStrategy.checkReply("", 4321);
            throw new IllegalStateException("must have thrown");
        } catch (WindowsAttachStrategy.AttachOperationException expected) {
            assertTrue(expected.asException(4321) instanceof IOException);
        }
    }

    @Test
    @DisplayName("原生层就绪时打到真 openProcess（不存在的 pid 按无权限/不存在报告）")
    void nativeLayerOpensRealProcess() throws Exception {
        WindowsAttachStrategy strategy = new WindowsAttachStrategy();
        File jar = File.createTempFile("agent", ".jar");
        jar.deleteOnExit();
        try {
            strategy.attach(424242, jar, "");
            throw new IllegalStateException("must have thrown");
        } catch (IOException expected) {
            // pid 不存在：openProcess 返回 0，策略按“进程不存在或无权限”报告。
        } catch (Exception unexpected) {
            throw new IllegalStateException("expected IOException, got " + unexpected);
        }
    }
}
