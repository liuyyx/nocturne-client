package gg.vape.lifecycle;

import gg.vape.notification.NotificationSoundPlayer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 回归：声音线程必须是 daemon，且 stop() 后能退出（不能拖住 JVM）。 */
class NotificationSoundPlayerLifecycleTest {

    @Test
    void soundThreadIsDaemonAndStopsOnDemand() throws Exception {
        NotificationSoundPlayer player = new NotificationSoundPlayer();
        Thread thread = findThread("Vape notification sound player");
        assertTrue(thread != null, "声音线程必须已启动");
        assertTrue(thread.isDaemon(), "声音线程必须是 daemon");

        player.stop();
        thread.join(2_000L);
        assertFalse(thread.isAlive(), "stop() 后声音线程必须退出");
    }

    private static Thread findThread(String name) {
        for (Thread thread : Thread.getAllStackTraces().keySet()) {
            if (name.equals(thread.getName())) {
                return thread;
            }
        }
        return null;
    }
}
