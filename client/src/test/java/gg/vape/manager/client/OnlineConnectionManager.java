package gg.vape.manager.client;

import java.util.concurrent.atomic.AtomicInteger;

/** 测试替身：验证 panic 会取消在线连接/重连。 */
public final class OnlineConnectionManager {
    public static final OnlineConnectionManager INSTANCE = new OnlineConnectionManager();
    public static final AtomicInteger CANCELS = new AtomicInteger();

    private OnlineConnectionManager() {
    }

    public void cancelConnectionAttempt() {
        CANCELS.incrementAndGet();
    }
}
