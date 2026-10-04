package dev.noturne.injector;

import dev.noturne.core.attach.AttachException;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationTargetException;
import java.util.Collections;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证注入失败原因的可读化映射。
 *
 * <p>重点在于「既不丢信息、又要说人话」：曾经所有含 {@code agent}/{@code attach} 字样的异常
 * 都被压成同一句「游戏拒绝 attach」，真正的原因（例如 agent jar 路径对目标 JVM 不可见）
 * 被吞掉，排查只能靠猜。
 *
 * <p>所有用例都按<b>生产路径</b>构造异常：{@code SwingWorker.get()} 抛的是
 * {@link ExecutionException}，真实原因在其 cause 里。旧用例直接用裸异常调用，
 * 掩盖了这层包装（M-102）。
 */
class DescribeFailureTest {

    /** 构造一个「所有策略都失败」的异常，根因为 {@code cause}。 */
    private static AttachException attachFailure(Throwable cause) {
        return new AttachException(1234, Collections.singletonList("jdk-api"), cause);
    }

    /** 模拟 {@code SwingWorker.get()} 的包装层 */
    private static ExecutionException fromWorker(Throwable cause) {
        return new ExecutionException(cause);
    }

    /** attach API 报「找不到 agent jar」时，应指出是路径不可见，并保留原始路径 */
    @Test
    void agentJarNotFoundIsSpelledOut() {
        String reason = MainWindow.describeFailure(fromWorker(
                attachFailure(new IllegalArgumentException("agent jar not found: /C:/x/y.jar"))));
        assertTrue(reason.contains("agent jar"), reason);
        assertTrue(reason.contains("/C:/x/y.jar"), reason);
    }

    /** 其它 attach 类失败也要带上原始消息，否则只剩「拒绝」二字无法定位 */
    @Test
    void otherAttachFailuresKeepTheOriginalMessage() {
        String reason = MainWindow.describeFailure(fromWorker(
                attachFailure(new RuntimeException("The attach mechanism is not available"))));
        assertTrue(reason.contains("attach mechanism"), reason);
    }

    /** 反射包装同样要被剥掉，仍能识别出 attach API 缺失这一根因 */
    @Test
    void unwrapsInvocationTargetException() {
        Throwable wrapped = fromWorker(new InvocationTargetException(
                attachFailure(new ClassNotFoundException("sun.tools.attach.VirtualMachine"))));
        assertTrue(MainWindow.describeFailure(wrapped).contains("attach API"));
    }

    /** 进程已退出这类可自解释的消息仍走友好映射 */
    @Test
    void missingProcessKeepsFriendlyText() {
        String reason = MainWindow.describeFailure(fromWorker(
                attachFailure(new RuntimeException("No such process"))));
        assertTrue(reason.contains("没找到 JVM"), reason);
    }

    /** M-102：SwingWorker.get() 抛的是 ExecutionException，必须先剥掉才发现真实原因。 */
    @Test
    void unwrapsExecutionException() {
        Throwable wrapped = fromWorker(attachFailure(new RuntimeException("No such process")));
        assertTrue(MainWindow.describeFailure(wrapped).contains("没找到 JVM"));
    }

    /** L-96：「already」分支必须排在笼统的 agent/attach 匹配之前，否则永远不可达。 */
    @Test
    void alreadyLoadedBeatsGenericAgentText() {
        String reason = MainWindow.describeFailure(fromWorker(
                attachFailure(new RuntimeException("agent already loaded"))));
        assertTrue(reason.contains("已经注入过"), reason);
    }
}
