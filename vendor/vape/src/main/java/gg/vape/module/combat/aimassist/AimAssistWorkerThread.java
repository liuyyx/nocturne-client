package gg.vape.module.combat.aimassist;

import java.util.concurrent.atomic.AtomicBoolean;

public class AimAssistWorkerThread
extends Thread {
    private final AimAssistTargetingSubModule targetingSubModule;
    private final AtomicBoolean running = new AtomicBoolean(true);

    public AimAssistWorkerThread(AimAssistTargetingSubModule targetingSubModule) {
        super("Vape aim assist worker");
        this.targetingSubModule = targetingSubModule;
        this.setDaemon(true);
    }

    /** 停止工作线程：panic/teardown 时调用；幂等。 */
    public void stopWorker() {
        this.running.set(false);
        this.interrupt();
    }

    @Override
    public void run() {
        // 原条件 while (!Vape.INSTANCE.isEnabled()) 反了，且 enabled 全源码无赋值。
        while (this.running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(1L);
                if (!this.targetingSubModule.isEnabled()
                        || !this.targetingSubModule.isSelectedSubModule()) {
                    continue;
                }
                AimAssistTargetingSubModule.runWorkerTick(this.targetingSubModule);
            }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
            catch (Exception exception) {
                if (!this.running.get()) {
                    return;
                }
                // 单次 tick 失败不能让工作线程静默退出；下一次循环继续。
            }
        }
    }
}
