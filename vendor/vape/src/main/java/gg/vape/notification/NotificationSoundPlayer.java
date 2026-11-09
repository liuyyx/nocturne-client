package gg.vape.notification;

import gg.vape.Vape;
import gg.vape.notification.SoundClip;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

public class NotificationSoundPlayer {
    private static int[] controlFlowMarker;
    // 多槽队列：同一轮次内入队的多个音效都会被播放（可重叠），
    // 例如切换配置档时所有开关模块的音效同时出声。
    private final Queue<SoundClip> pendingSounds = new ConcurrentLinkedQueue<SoundClip>();
    /** 显式生命周期：Vape.enabled 全源码无赋值，不能拿来当循环条件。 */
    private final java.util.concurrent.atomic.AtomicBoolean running =
            new java.util.concurrent.atomic.AtomicBoolean(true);
    /** 防止构造/重复 start 产生第二个播放线程。 */
    private final java.util.concurrent.atomic.AtomicBoolean threadStarted =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private volatile Thread soundThread;

    public NotificationSoundPlayer() {
        this.startSoundThread();
    }

    static {
        if (NotificationSoundPlayer.getControlFlowMarker() == null) {
            NotificationSoundPlayer.setControlFlowMarker(new int[3]);
        }
    }

    public void playPendingSound() {
        if (this.pendingSounds.isEmpty()) {
            return;
        }
        if (this.isMuted()) {
            this.pendingSounds.clear();
            return;
        }
        float volumePercent = this.getVolumePercent();
        SoundClip sound;
        while ((sound = this.pendingSounds.poll()) != null) {
            sound.play(volumePercent);
        }
    }

    public boolean isMuted() {
        return Vape.INSTANCE.getPublicProfileSettings().muted.getEffectiveValue();
    }

    public static int[] getControlFlowMarker() {
        return controlFlowMarker;
    }

    public static void setControlFlowMarker(int[] marker) {
        controlFlowMarker = marker;
    }


    public float getVolumePercent() {
        return ((Double)Vape.INSTANCE.getPublicProfileSettings().volume.getValue()).floatValue();
    }

    public void queue(SoundClip sound) {
        this.pendingSounds.add(sound);
    }

    public void startSoundThread() {
        if (!this.threadStarted.compareAndSet(false, true)) {
            return;
        }
        Thread thread = new Thread(this::runSoundLoop, "Vape notification sound player");
        thread.setDaemon(true);
        this.soundThread = thread;
        thread.start();
    }

    /** 停止播放线程：panic/teardown 时调用；幂等。 */
    public void stop() {
        this.running.set(false);
        Thread thread = this.soundThread;
        this.soundThread = null;
        if (thread != null) {
            thread.interrupt();
        }
    }

    private void runSoundLoop() {
        while (this.running.get() && !Thread.currentThread().isInterrupted()) {
            try {
                Thread.sleep(100L);
                this.playPendingSound();
            }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
            catch (Exception exception) {
                Vape.logThrowable(exception);
            }
        }
    }
}
