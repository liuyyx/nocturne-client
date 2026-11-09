package gg.vape.service;

import java.util.concurrent.atomic.AtomicInteger;

/** 测试替身：验证 panic/teardown 会关闭内嵌服务（8080/8091）。 */
public final class VapeServiceLauncher {
    public static final AtomicInteger STOPS = new AtomicInteger();

    private VapeServiceLauncher() {
    }

    public static void stop() {
        STOPS.incrementAndGet();
    }
}
