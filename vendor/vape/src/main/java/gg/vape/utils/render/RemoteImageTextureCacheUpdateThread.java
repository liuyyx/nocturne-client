package gg.vape.utils.render;

import gg.vape.Vape;
import java.util.concurrent.atomic.AtomicBoolean;

class RemoteImageTextureCacheUpdateThread
extends Thread {
    final RemoteImageTextureManager manager;
    private final AtomicBoolean running = new AtomicBoolean(true);

    @Override
    public void run() {
        while (this.running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(50L);
                for (Integer imageSize : RemoteImageTextureManager.getCaches(this.manager).keySet()) {
                    RemoteImageTextureCache cache =
                            RemoteImageTextureManager.getCaches(this.manager).get(imageSize);
                    if (cache != null) {
                        cache.processPendingDownloads();
                    }
                }
            }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            catch (Exception exception) {
                if (!this.running.get()) {
                    return;
                }
                Vape.logThrowable(exception);
            }
        }
    }

    RemoteImageTextureCacheUpdateThread(RemoteImageTextureManager manager) {
        super("Vape remote image cache");
        this.manager = manager;
        this.setDaemon(true);
    }

    /** 停止下载线程：panic/teardown 时调用；幂等。 */
    void stopLoop() {
        this.running.set(false);
        this.interrupt();
    }
}
