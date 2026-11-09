package gg.vape.lifecycle;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import gg.vape.config.LocalConfigStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回归：关闭钩子与保存 worker 会并发写同一 config.json；写入必须串行、
 * tmp 文件必须唯一，最终文件必须是完整可解析的 JSON。
 */
class LocalConfigStoreConcurrencyTest {

    private Path directory;
    private String previousDirectory;

    @BeforeEach
    void setUp() throws Exception {
        this.directory = Files.createTempDirectory("vape-config-test");
        this.previousDirectory = System.getProperty("vape.directory");
        System.setProperty("vape.directory", this.directory.toString());
    }

    @AfterEach
    void tearDown() throws Exception {
        if (this.previousDirectory == null) {
            System.clearProperty("vape.directory");
        } else {
            System.setProperty("vape.directory", this.previousDirectory);
        }
        if (this.directory != null) {
            Files.walk(this.directory)
                    .sorted(java.util.Comparator.reverseOrder())
                    .forEach(path -> {
                        try {
                            Files.deleteIfExists(path);
                        } catch (Exception ignored) {
                            // best effort
                        }
                    });
        }
    }

    @Test
    void concurrentSavesLeaveParseableJson() throws Exception {
        int writers = 8;
        int iterations = 25;
        List<Thread> threads = new ArrayList<Thread>();
        List<Throwable> failures = java.util.Collections.synchronizedList(new ArrayList<Throwable>());
        for (int writer = 0; writer < writers; writer++) {
            final int id = writer;
            Thread thread = new Thread(() -> {
                try {
                    for (int i = 0; i < iterations; i++) {
                        JsonObject settings = new JsonObject();
                        settings.addProperty("writer", id);
                        settings.addProperty("iteration", i);
                        JsonObject profiles = new JsonObject();
                        profiles.addProperty("writer", id);
                        assertTrue(LocalConfigStore.saveSettings(settings));
                        assertTrue(LocalConfigStore.saveProfiles(profiles));
                    }
                } catch (Throwable failure) {
                    failures.add(failure);
                }
            }, "config-test-writer-" + writer);
            threads.add(thread);
            thread.start();
        }
        for (Thread thread : threads) {
            thread.join(10_000L);
        }
        assertTrue(failures.isEmpty(), "并发保存不得抛异常: " + failures);

        File file = LocalConfigStore.configFile();
        assertTrue(file.isFile(), "config.json 必须存在");
        try (InputStreamReader reader = new InputStreamReader(
                Files.newInputStream(file.toPath()), StandardCharsets.UTF_8)) {
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            assertNotNull(root.getAsJsonObject("settings"));
            assertNotNull(root.getAsJsonObject("profiles"));
        }
        try (java.util.stream.Stream<Path> files =
                     Files.list(LocalConfigStore.directory().toPath())) {
            List<String> names = new ArrayList<String>();
            files.forEach(path -> names.add(path.getFileName().toString()));
            assertEquals(1, names.stream().filter("config.json"::equals).count(),
                    "只应留下一个 config.json: " + names);
            assertTrue(names.stream().noneMatch(name -> name.endsWith(".tmp")),
                    "不得残留 tmp 文件: " + names);
        }
    }
}
