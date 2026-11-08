package gg.vape.sync;

import gg.vape.Vape;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/**
 * 保存请求线程：轮询 {@link #requestSave()} 置位的标志，真正落盘交给 {@link SyncThread}。
 *
 * <p>原实现的条件是 {@code while (!Vape.INSTANCE.isEnabled())}（该字段全源码无赋值，恒为 false）
 * 且线程非 daemon；现改为显式 running 标志，打断时立刻退出，不再吞掉 InterruptedException。
 */
public class SyncStoreRequestWorker
implements Runnable {
    private final AtomicBoolean saveRequested = new AtomicBoolean();
    private final BooleanSupplier running;

    public SyncStoreRequestWorker(BooleanSupplier running) {
        this.running = running;
    }

    public void requestSave() {
        this.saveRequested.set(true);
    }

    @Override
    public void run() {
        while (this.running.getAsBoolean()) {
            try {
                Thread.sleep(100L);
                if (!this.saveRequested.get()) continue;
                Thread.sleep(100L);
                if (!this.running.getAsBoolean()) {
                    return;
                }
                Vape.INSTANCE.getSyncThread().saveSettings();
                this.saveRequested.set(false);
            }
            catch (InterruptedException interruptedException) {
                Thread.currentThread().interrupt();
                return;
            }
            catch (Exception exception) {
                // 一次保存失败不得让工作线程永久退出：清掉请求，下一轮重试。
                Vape.logThrowable(exception);
                this.saveRequested.set(false);
            }
        }
    }
}
