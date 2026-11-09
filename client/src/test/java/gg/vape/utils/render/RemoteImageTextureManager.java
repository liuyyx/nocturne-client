package gg.vape.utils.render;

import java.util.concurrent.atomic.AtomicInteger;

/** 测试替身：验证 panic 会停止头像下载线程。 */
public final class RemoteImageTextureManager {
    private static final RemoteImageTextureManager INSTANCE = new RemoteImageTextureManager();
    public static final AtomicInteger STOPS = new AtomicInteger();

    private RemoteImageTextureManager() {
    }

    public static RemoteImageTextureManager getInstance() {
        return INSTANCE;
    }

    public void stop() {
        STOPS.incrementAndGet();
    }
}
