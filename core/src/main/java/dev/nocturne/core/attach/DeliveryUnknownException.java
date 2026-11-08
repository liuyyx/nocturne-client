package dev.nocturne.core.attach;

import java.io.IOException;

/**
 * attach 请求「可能已经送达」但没有拿到可信回复。
 *
 * <p>与普通失败的区别：普通失败（进程打不开、管道建不出来、目标明确拒绝）说明 agent
 * <em>没有</em>被加载，换一条策略重放是安全的；本异常表示请求已经投递进目标 JVM，
 * 只是回复超时/为空/读不懂——目标侧可能已经把 agent 装上了。
 *
 * <p>因此 {@link Attacher} 收到它必须<strong>终止整条策略链</strong>：继续用
 * windows-native / unix 域套接字重放，同一次点击就会注入两次（两套转换器、两套监听器）。
 * 这类重复注入在 1.8.9 上表现为按键两次抵消，在现代版本上是逐帧重复绘制。
 *
 * <p>继承 {@link IOException}：现有策略的传输层签名都是 {@code throws IOException}，
 * 这样超时/EOF 路径可以就地抛出它，而不必把整条调用链的异常声明改宽。
 */
public final class DeliveryUnknownException extends IOException {

    /** 序列化版本标识。 */
    private static final long serialVersionUID = 1L;

    /**
     * 构造「投递结果未知」异常。
     *
     * @param message 诊断文本，应说明请求已发出、结果不可信
     */
    public DeliveryUnknownException(String message) {
        super(message);
    }

    /**
     * 构造「投递结果未知」异常并保留底层传输错误。
     *
     * @param message 诊断文本
     * @param cause   底层 IO 错误，可为 {@code null}
     */
    public DeliveryUnknownException(String message, Throwable cause) {
        super(message, cause);
    }
}
