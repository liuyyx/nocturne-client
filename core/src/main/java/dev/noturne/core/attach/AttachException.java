package dev.noturne.core.attach;

import java.util.Collections;
import java.util.List;

/** 当所有 {@link AttachStrategy} 都失败时抛出，携带尝试过的策略名与失败原因。 */
public final class AttachException extends Exception {

    /** 序列化版本标识。 */
    private static final long serialVersionUID = 1L;

    /** 已尝试过的策略名，顺序即尝试顺序；构造后不可修改。 */
    private final List<String> attempted;

    /**
     * 构造 attach 失败异常。
     *
     * @param pid       目标进程 id，写入异常消息
     * @param attempted 已尝试的策略名列表，内部包装为不可修改视图
     * @param cause     最后一个策略抛出的异常，可为 {@code null}
     */
    public AttachException(int pid, List<String> attempted, Throwable cause) {
        super("failed to attach to pid " + pid + "; strategies tried: " + attempted, cause);
        this.attempted = Collections.unmodifiableList(attempted);
    }

    /** @return 已尝试的策略名列表（不可修改） */
    public List<String> attempted() {
        return attempted;
    }
}
