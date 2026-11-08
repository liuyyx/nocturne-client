package dev.nocturne.core.attach;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 当所有 {@link AttachStrategy} 都失败时抛出，携带尝试过的策略名与失败原因。 */
public final class AttachException extends Exception {

    /** 序列化版本标识。 */
    private static final long serialVersionUID = 1L;

    /** 已尝试过的策略名，顺序即尝试顺序；构造时复制并包装为不可修改。 */
    private final List<String> attempted;

    /** 失败时是否处于「请求可能已送达」状态（见 {@link DeliveryUnknownException}）。 */
    private final boolean deliveryUnknown;

    /**
     * 构造 attach 失败异常。
     *
     * <p>内部保存 {@code attempted} 的<em>副本</em>，避免调用方在构造后修改原列表影响本对象；
     * 失败原因同时写入消息，保证即使不做 {@code getCause()} 也能看到「为什么失败」。
     *
     * @param pid       目标进程 id，写入异常消息
     * @param attempted 已尝试的策略名列表
     * @param cause     最后一个策略抛出的异常，可为 {@code null}
     */
    public AttachException(int pid, List<String> attempted, Throwable cause) {
        this(pid, attempted, cause, false);
    }

    /**
     * 构造 attach 失败异常，并显式标注投递结果是否未知。
     *
     * @param pid             目标进程 id，写入异常消息
     * @param attempted       已尝试的策略名列表
     * @param cause           最后一个策略抛出的异常，可为 {@code null}
     * @param deliveryUnknown 请求可能已送达目标（agent 可能已注入）时为 {@code true}
     */
    public AttachException(int pid, List<String> attempted, Throwable cause,
                           boolean deliveryUnknown) {
        super(buildMessage(pid, attempted, cause, deliveryUnknown), cause);
        this.attempted = Collections.unmodifiableList(new ArrayList<String>(
                attempted == null ? Collections.<String>emptyList() : attempted));
        this.deliveryUnknown = deliveryUnknown;
    }

    /** @return 已尝试的策略名列表（不可修改） */
    public List<String> attempted() {
        return attempted;
    }

    /**
     * 失败时请求是否可能已经送达目标 JVM。
     *
     * <p>为 {@code true} 时调用方<strong>不得</strong>立刻再点一次注入或自行换策略重试：
     * 那会重复加载 agent。正确做法是提示用户检查目标进程，必要时重启游戏。
     *
     * @return 投递结果未知时为 {@code true}
     */
    public boolean deliveryUnknown() {
        return deliveryUnknown;
    }

    /** 组装含目标 pid、已尝试策略与最后失败原因的诊断消息。 */
    private static String buildMessage(int pid, List<String> attempted, Throwable cause,
                                       boolean deliveryUnknown) {
        String reason = cause == null ? "(no strategy attempted)" : cause.toString();
        if (deliveryUnknown) {
            // 这一行必须让用户看懂「不要再点一次」：重复注入不是无害的重试。
            return "attach to pid " + pid + " has an UNKNOWN outcome: the request was already"
                    + " delivered, so the agent may already be injected; retrying would inject"
                    + " it twice. strategies tried: " + attempted
                    + "; last failure: " + reason;
        }
        return "failed to attach to pid " + pid
                + "; strategies tried: " + attempted
                + "; last failure: " + reason;
    }
}
