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
        new Thread(this::runSoundLoop, "Vape notification sound player").start();
    }

    private void runSoundLoop() {
        // 原实现使用 while (!Vape.INSTANCE.enabled) 作为循环条件，逻辑完全反了：
        // 一旦客户端 enabled 被置 true，线程立刻退出，队列中的声音永远不会被播放。
        // 改为无条件轮询，直到线程中断（JVM 关闭）。
        while (!Thread.currentThread().isInterrupted()) {
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
