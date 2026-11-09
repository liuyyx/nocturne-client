package gg.vape.module.combat.aimassist;

import java.util.concurrent.atomic.AtomicBoolean;

public class AimAssistRotationWorkerThread
extends Thread {
    private final AimAssistRotationSubModule rotationModule;
    private final AtomicBoolean running = new AtomicBoolean(true);

    public AimAssistRotationWorkerThread(AimAssistRotationSubModule rotationModule) {
        super("Vape aim assist rotation worker");
        this.rotationModule = rotationModule;
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
                if (!this.rotationModule.isEnabled()
                        || !this.rotationModule.isSelectedSubModule()) {
                    continue;
                }
                AimAssistRotationSubModule.runWorkerTick(this.rotationModule);
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
