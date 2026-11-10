package dev.nocturne.client.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 1.8.9 自带旧 Gson（2.2.x）：`JsonParser.parseString/parseReader` 与
 * `JsonObject.size()` 在旧版不存在，调用会在 inject 后抛 NoSuchMethodError，整个 client
 * 初始化失败。这个静态检查守住源码，避免以后再次引入新 API。
 *
 * <p>只扫生产源码（client/core/injector main）；测试代码不随游戏加载，但 CI 仍可用新版 Gson。
 */
class GsonApiCompatibilityTest {

    @Test
    void productionSourcesDoNotUseNewerGsonApis() throws IOException {
        Path root = repoRoot();
        List<String> bad = new ArrayList<String>();
        for (String module : new String[]{"client", "core", "injector"}) {
            Path main = root.resolve(module).resolve("src").resolve("main").resolve("java");
            if (!Files.isDirectory(main)) {
                continue;
            }
            try (Stream<Path> files = Files.walk(main)) {
                files.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                    try {
                        int lineNo = 0;
                        for (String line : Files.readAllLines(p, StandardCharsets.UTF_8)) {
                            lineNo++;
                            if (line.contains("JsonParser.parseString")
                                    || line.contains("JsonParser.parseReader")) {
                                bad.add(p + ":" + lineNo + ": " + line.trim());
                            }
                            // JsonObject 的 size() 是 2.8+；JsonArray.size() 旧版就有，不能误报。
                            if (line.contains("JsonObject") && line.contains(".size()")) {
                                bad.add(p + ":" + lineNo + ": " + line.trim());
                            }
                            if (line.contains("valuesNode.size()") || line.contains("root.size()")) {
                                bad.add(p + ":" + lineNo + ": " + line.trim());
                            }
                        }
                    } catch (IOException error) {
                        throw new RuntimeException(error);
                    }
                });
            }
        }
        assertTrue(bad.isEmpty(), "newer Gson API found:\n" + String.join("\n", bad));
    }

    @Test
    void oldParserConstructionIsUsed() throws IOException {
        Path root = repoRoot();
        Path store = root.resolve("client/src/main/java/dev/nocturne/client/config/ModuleConfigStore.java");
        String source = new String(Files.readAllBytes(store), StandardCharsets.UTF_8);
        assertTrue(source.contains("new JsonParser().parse(text)"),
                "ModuleConfigStore must use the Gson 2.2-compatible parser construction");
    }

    private static Path repoRoot() {
        Path dir = Paths.get(System.getProperty("user.dir", ".")).toAbsolutePath();
        for (Path p = dir; p != null; p = p.getParent()) {
            if (Files.isDirectory(p.resolve("client/src/main/java"))) {
                return p;
            }
        }
        throw new IllegalStateException("cannot locate repository root from " + dir);
    }
}
