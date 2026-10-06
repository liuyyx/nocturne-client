package gg.vape.notification;

import gg.vape.Vape;
import java.io.ByteArrayInputStream;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.Clip;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.LineEvent;

public class SoundClip {
    private static final String[] SUPPORTED_EXTENSIONS = new String[]{".wav", ".au", ".aif", ".aiff"};
    private final byte[] audioData;

    public ByteArrayInputStream openStream() {
        return new ByteArrayInputStream(this.audioData);
    }

    private static byte[] loadAudioData(String resourceName) {
        if (resourceName.contains(".")) {
            String resourcePath = "sounds/" + resourceName;
            byte[] data = Vape.readResource(resourcePath);
            if (data != null) {
                return data;
            }
            throw new IllegalArgumentException("Missing sound resource: " + resourceName);
        }
        for (String extension : SUPPORTED_EXTENSIONS) {
            String resourcePath = "sounds/" + resourceName + extension;
            byte[] data = Vape.readResource(resourcePath);
            if (data == null) continue;
            return data;
        }
        throw new IllegalArgumentException(
                "Missing sound resource with supported extensions: " + resourceName);
    }

    public SoundClip(String resourceName) {
        this.audioData = SoundClip.loadAudioData(resourceName);
    }

    public void play(float volumePercent) {
        try {
            AudioInputStream audioStream = AudioSystem.getAudioInputStream(this.openStream());
            Clip clip = AudioSystem.getClip();
            clip.open(audioStream);
            FloatControl gainControl = (FloatControl)clip.getControl(FloatControl.Type.MASTER_GAIN);
            gainControl.setValue(20.0f * (float)Math.log10((double)volumePercent / 100.0));
            // 每次播放创建独立 Clip：同一音效快速连发也互不打断，各自完整播放。
            // 播放结束（STOP）后自动关闭，避免新建 Clip 造成资源泄漏。
            clip.addLineListener(event -> {
                if (event.getType() == LineEvent.Type.STOP) {
                    clip.close();
                }
            });
            clip.start();
        }
        catch (Exception error) {
            Vape.logThrowable(error);
        }
    }

    private static Exception propagateException(Exception error) {
        return error;
    }
}
