package dev.nocturne.client.runtime;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 帧动作抛链接错误时：只报一次、指出缺失类、且不打断其它监听器。 */
class FrameDispatcherLinkageTest {

    @Test
    void linkageErrorIsReportedOnceAndOtherListenersKeepRunning() {
        AtomicInteger healthy = new AtomicInteger();
        // 复刻真机现象：1.12.2 上 ClickGUI 帧动作加载不到的私有嵌套类。
        Runnable broken = new Runnable() {
            @Override
            public void run() {
                throw new NoClassDefFoundError("dev/nocturne/ui/fatality/FaPreviewCard$Effects");
            }
        };
        Runnable ok = new Runnable() {
            @Override
            public void run() {
                healthy.incrementAndGet();
            }
        };

        PrintStream original = System.out;
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        System.setOut(new PrintStream(buffer, true));
        try {
            FrameDispatcher.add(broken);
            FrameDispatcher.add(ok);
            FrameDispatcher.dispatch();
            FrameDispatcher.dispatch();
        } finally {
            FrameDispatcher.remove(broken);
            FrameDispatcher.remove(ok);
            System.setOut(original);
        }

        String out = buffer.toString();
        assertEquals(2, healthy.get(), "链接错误不得中断其它每帧监听器");
        assertEquals(1, occurrences(out, "frame action linkage failure"),
                "同一链接错误只应打印一次，避免每帧刷屏");
        assertTrue(out.contains("FaPreviewCard$Effects"), "日志必须指出缺失的类，避免 GUI 静默失效");
        assertTrue(out.contains("NoClassDefFoundError"), "日志必须保留错误类型");
    }

    private static int occurrences(String text, String needle) {
        int count = 0;
        int at = text.indexOf(needle);
        while (at >= 0) {
            count++;
            at = text.indexOf(needle, at + needle.length());
        }
        return count;
    }
}
