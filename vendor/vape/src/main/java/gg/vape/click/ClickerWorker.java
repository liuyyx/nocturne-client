package gg.vape.click;

import gg.vape.Vape;
import gg.vape.module.combat.ClickerMod;
import gg.vape.input.BadlionKeyBindingEventQueue;
import gg.vape.utils.SleepUtil;
import java.util.concurrent.atomic.AtomicBoolean;

public class ClickerWorker
implements Runnable {
    private final AtomicBoolean active = new AtomicBoolean(false);
    private final Object activityMonitor = new Object();
    /** 显式运行标志；stop() 后循环必须退出。 */
    private final AtomicBoolean running = new AtomicBoolean(true);
    private volatile Thread workerThread;
    private final ClickerMod clickerModule;

    public ClickerWorker(ClickerMod clickerModule) {
        this.clickerModule = clickerModule;
    }

    /** 启动或恢复；synchronized 保证并发调用只会创建一个线程。 */
    public synchronized void startOrResume() {
        if (this.workerThread == null || !this.workerThread.isAlive()) {
            this.running.set(true);
            Thread thread = new Thread(this, "Vape clicker worker");
            thread.setDaemon(true);
            this.workerThread = thread;
            thread.start();
        }
        this.resume();
    }

    /** 停止工作线程：清运行标志 + 打断 sleep/wait；panic/teardown 时调用。 */
    public synchronized void stop() {
        this.running.set(false);
        Thread thread = this.workerThread;
        this.workerThread = null;
        if (thread != null) {
            thread.interrupt();
        }
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    private void awaitActivityAndRunClickCycle() {
        ClickEngine clickEngine = this.clickerModule.getClickEngine();
        try {
            synchronized (this.activityMonitor) {
                if (!this.active.get()) {
                    try {
                        this.activityMonitor.wait();
                    }
                    catch (InterruptedException interruptedException) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
            if (clickEngine.getButton() == ClickButton.LEFT && this.clickerModule.isClickCycleBlocked()) {
                return;
            }
            this.clickerModule.runClickCycle();
        }
        catch (Exception exception) {
            Vape.logThrowable(exception);
        }
    }

    @Override
    public void run() {
        BadlionKeyBindingEventQueue.enterClickerWorker();
        try {
            while (this.running.get() && !Thread.currentThread().isInterrupted()) {
                SleepUtil.sleep(5L);
                if (!this.running.get() || Thread.currentThread().isInterrupted()) {
                    break;
                }
                this.awaitActivityAndRunClickCycle();
            }
        }
        finally {
            BadlionKeyBindingEventQueue.leaveClickerWorker();
        }
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    public void resume() {
        synchronized (this.activityMonitor) {
            this.active.set(true);
            this.activityMonitor.notify();
        }
    }

    /*
     * WARNING - Removed try catching itself - possible behaviour change.
     */
    public void pause() {
        synchronized (this.activityMonitor) {
            this.active.set(false);
        }
    }
}
