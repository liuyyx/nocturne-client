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
        super(buildMessage(pid, attempted, cause), cause);
        this.attempted = Collections.unmodifiableList(new ArrayList<String>(
                attempted == null ? Collections.<String>emptyList() : attempted));
    }

    /** @return 已尝试的策略名列表（不可修改） */
    public List<String> attempted() {
        return attempted;
    }

    /** 组装含目标 pid、已尝试策略与最后失败原因的诊断消息。 */
    private static String buildMessage(int pid, List<String> attempted, Throwable cause) {
        String reason = cause == null ? "(no strategy attempted)" : cause.toString();
        return "failed to attach to pid " + pid
                + "; strategies tried: " + attempted
                + "; last failure: " + reason;
    }
}
