package dev.nocturne.core.attach;

import org.junit.jupiter.api.Test;

import java.io.File;
import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link Attacher} 策略链的失败语义。
 *
 * <p>回归（High-26）：循环原来只捕 {@code Exception}，于是"这条策略在本机走不通"的 Error
 * （裁剪 JRE 上原生库缺失的 {@code UnsatisfiedLinkError}、JDK attach API 不在 classpath 时的
 * {@code NoClassDefFoundError}）会直接逃出去——一次正常的多策略回退变成整个工具崩掉，连
 * "试试 tools.jar"这类可操作提示都打不出来（Nocturne 只捕 {@code AttachException}）。
 *
 * <p>反过来，VM 级 Error（OOM 等）与 ThreadDeath 必须原样传播：那是工具自身的问题或调用方的
 * 终止请求，不能伪装成"目标注入失败"。
 *
 * <p>被测入口是包可见的 {@link Attacher#attachWith}，因此不必去改静态策略链。
 */
class AttacherTest {

    /** 抛指定 Throwable 的策略。 */
    private static AttachStrategy throwing(Throwable failure) {
        return new AttachStrategy() {
            @Override
            public String name() {
                return "throwing";
            }

            @Override
            public void attach(int pid, File agentJar, String options) {
                if (failure instanceof RuntimeException) {
                    throw (RuntimeException) failure;
                }
                if (failure instanceof Error) {
                    throw (Error) failure;
                }
                throw new IllegalStateException(failure);
            }
        };
    }

    /** 什么都不做即算成功的策略。 */
    private static AttachStrategy succeeding(String name) {
        return new AttachStrategy() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public void attach(int pid, File agentJar, String options) {
            }
        };
    }

    /** 一条策略抛"本机不可用"的 Error 时，必须继续尝试链上后面的策略。 */
    @Test
    void continuesWithTheNextStrategyAfterAnUnavailableError() throws Exception {
        for (Error error : new Error[]{
                new UnsatisfiedLinkError("no native library for this platform"),
                new NoClassDefFoundError("com/sun/tools/attach/VirtualMachine")}) {
            String winner = Attacher.attachWith(
                    Arrays.asList(throwing(error), succeeding("next")),
                    1234, new File("agent.jar"), null);

            assertEquals("next", winner, error.getClass().getSimpleName()
                    + " means this strategy is unavailable, not that attaching is over");
        }
    }

    /** 所有策略都失败时仍抛 {@link AttachException}，并把最后一个错误挂在 cause 上。 */
    @Test
    void reportsAttachExceptionWhenEveryStrategyFails() {
        Error error = new UnsatisfiedLinkError("no native library");

        AttachException failure = assertThrows(AttachException.class,
                () -> Attacher.attachWith(Collections.singletonList(throwing(error)),
                        1234, new File("agent.jar"), null));

        assertSame(error, failure.getCause(), "the last failure must stay reachable");
    }

    /** VM 级 Error 必须照旧向上传播，不能被吞成"普通失败"。 */
    @Test
    void propagatesVirtualMachineErrors() {
        assertThrows(OutOfMemoryError.class,
                () -> Attacher.attachWith(
                        Arrays.asList(throwing(new OutOfMemoryError("heap")),
                                succeeding("must-not-be-reached")),
                        1234, new File("agent.jar"), null));
    }

    /** 正常路径：第一条成功的策略胜出，后面的不再尝试。 */
    @Test
    void returnsTheFirstSucceedingStrategy() throws Exception {
        String winner = Attacher.attachWith(
                Arrays.asList(throwing(new UnsatisfiedLinkError("skip me")),
                        succeeding("winner"), succeeding("loser")),
                1234, new File("agent.jar"), null);

        assertEquals("winner", winner);
    }
}
