package gg.vape.sync;

import gg.vape.Vape;
import gg.vape.module.none.ClientSettings;
import java.util.function.BooleanSupplier;

/**
 * 配置保存防抖线程：只有客户端仍在运行、且配置确实变更过时才请求保存。
 *
 * <p>原实现的条件是 {@code while (!Vape.INSTANCE.isEnabled())} —— 而 {@code enabled} 在全源码里
 * 从无赋值处，永远为 false，所以循环永不退出；线程又不是 daemon，JVM 关闭时被它拖住。这里改为
 * 由 {@link SyncThread} 持有的显式 running 标志，并在打断时立即退出。
 */
public class SyncDebounceWorker
implements Runnable {
    private static final long DEBOUNCE_MILLIS = 3000L;
    private final BooleanSupplier running;
    private long lastChangeTime;

    public SyncDebounceWorker(BooleanSupplier running) {
        this.running = running;
    }

    @Override
    public void run() {
        while (this.running.getAsBoolean()) {
            try {
                this.processPendingSave();
            }
            catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    public void markChanged() {
        this.lastChangeTime = System.currentTimeMillis();
    }

    private void processPendingSave() throws InterruptedException {
        try {
            Thread.sleep(1000L);
            if (!this.running.getAsBoolean()) {
                return;
            }
            if (!Vape.INSTANCE.getPublicProfileSettings().autoSave.getEffectiveValue()) {
                return;
            }
            if (!ClientSettings.INSTANCE.isMainGuiStack() && !ClientSettings.INSTANCE.inputEnabled) {
                return;
            }
            long observedChangeTime = this.lastChangeTime;
            if (Vape.INSTANCE.getSyncThread().hasPendingSave()) {
                Thread.sleep(DEBOUNCE_MILLIS);
                if (this.running.getAsBoolean() && this.lastChangeTime == observedChangeTime) {
                    Vape.INSTANCE.getSyncThread().requestSave();
                }
            }
        }
        catch (InterruptedException interruptedException) {
            throw interruptedException;
        }
        catch (Exception exception) {
            Vape.logThrowable(exception);
        }
    }
}
