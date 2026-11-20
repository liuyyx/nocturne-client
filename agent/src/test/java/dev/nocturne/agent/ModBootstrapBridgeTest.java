package dev.nocturne.agent;

import org.junit.jupiter.api.Test;

import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Fabric mod 形态：资源桥必须由游戏加载器的父级提供，且缺条目时不得继续启动。 */
class ModBootstrapBridgeTest {

    /** 模拟 Knot 的父级动态 URL 加载器。 */
    private static final class DynamicUrls extends URLClassLoader {
        DynamicUrls() {
            super(new URL[0], null);
        }

        @Override
        public void addURL(URL url) {
            super.addURL(url);
        }
    }

    /** 模拟 Knot：公开 addUrlFwd，但实际由父级 URL 加载器定义桥类。 */
    private static final class GameLoader extends ClassLoader {
        private final DynamicUrls urls;

        GameLoader(DynamicUrls urls) {
            super(urls);
            this.urls = urls;
        }

        public void addUrlFwd(URL url) {
            urls.addURL(url);
        }
    }

    private static String[][] compiledEntries() {
        return new String[][]{
                {"dev/nocturne/bootstrap/KeySink.class", "dev/nocturne/bootstrap/KeySink.class"},
                {"dev/nocturne/bootstrap/GameplaySink.class",
                        "dev/nocturne/bootstrap/GameplaySink.class"},
                {"dev/nocturne/bootstrap/LegacyMouse.class",
                        "dev/nocturne/bootstrap/LegacyMouse.class"},
                {"dev/nocturne/client/bootstrap/GameplayHook.class",
                        "dev/nocturne/client/bootstrap/GameplayHook.class"},
                {"dev/nocturne/client/bootstrap/GameplayHook$Sink.class",
                        "dev/nocturne/client/bootstrap/GameplayHook$Sink.class"},
        };
    }

    @Test
    void exposesAllResourceClassesThroughTheParentLoader() throws Exception {
        DynamicUrls urls = new DynamicUrls();
        GameLoader loader = new GameLoader(urls);
        try {
            assertThrows(ClassNotFoundException.class,
                    () -> Class.forName("dev.nocturne.bootstrap.KeySink", true, loader));

            assertTrue(NocturneAgent.installModBridge(loader, compiledEntries()));

            Class<?> keySink = Class.forName("dev.nocturne.bootstrap.KeySink", true, loader);
            assertSame(urls, keySink.getClassLoader());
            keySink.getMethod("setBlocked", boolean.class).invoke(null, true);
            assertEquals(Boolean.TRUE, keySink.getMethod("shouldBlock").invoke(null));
            assertEquals(1, urls.getURLs().length);

            // 启动失败后的重试不得重复添加 URL 或定义第二份桥。
            assertTrue(NocturneAgent.installModBridge(loader, compiledEntries()));
            assertEquals(1, urls.getURLs().length);
        } finally {
            urls.close();
            for (URL url : urls.getURLs()) {
                Path path = Paths.get(url.toURI());
                Files.deleteIfExists(path);
            }
        }
    }

    @Test
    void refusesToStartWhenAnyBridgeEntryIsMissing() throws Exception {
        DynamicUrls urls = new DynamicUrls();
        GameLoader loader = new GameLoader(urls);
        try {
            String[][] entries = compiledEntries();
            entries[2][0] = "missing/LegacyMouse.class";

            assertFalse(NocturneAgent.installModBridge(loader, entries));
            assertEquals(0, urls.getURLs().length, "失败时不得暴露不完整的桥");
            assertThrows(ClassNotFoundException.class,
                    () -> Class.forName("dev.nocturne.bootstrap.KeySink", true, loader));
        } finally {
            urls.close();
        }
    }
}
