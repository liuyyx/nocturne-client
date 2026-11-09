package gg.vape.lifecycle;

import gg.vape.utils.SleepUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** 回归：SleepUtil 不得吞掉 InterruptedException，否则 stop()/shutdown 无法唤醒循环。 */
class SleepUtilTest {

    @Test
    void interruptedSleepRestoresTheInterruptFlag() {
        Thread.currentThread().interrupt();
        long started = System.nanoTime();
        SleepUtil.sleep(5_000L);
        long elapsedMillis = (System.nanoTime() - started) / 1_000_000L;

        assertTrue(Thread.interrupted(), "InterruptedException 必须恢复中断位");
        assertTrue(elapsedMillis < 2_000L, "中断后 sleep 必须立即返回，而不是睡满 5 秒");
    }
}
