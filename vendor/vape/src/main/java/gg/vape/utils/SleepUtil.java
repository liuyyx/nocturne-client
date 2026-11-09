package gg.vape.utils;

public class SleepUtil {
    public static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        }
        catch (InterruptedException interruptedException) {
            // 恢复中断位：吞掉中断会让上层循环永远注意不到 stop()/JVM shutdown。
            Thread.currentThread().interrupt();
        }
    }
}

