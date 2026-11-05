package dev.nocturne.client.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/** 模块文本资源的 UTF-8 逐行读取；自定义路径与 jar 资源共用同一契约。 */
public final class LineStore {
    private LineStore() {
    }

    public static List<String> load(String resource, String customPath) {
        if (customPath != null && !customPath.trim().isEmpty()) {
            Path path = Paths.get(customPath.trim());
            try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.UTF_8)) {
                return read(reader);
            } catch (IOException failure) {
                System.out.println("[nocturne] could not read " + path + ": " + failure);
            }
        }
        InputStream stream = LineStore.class.getClassLoader().getResourceAsStream(resource);
        if (stream == null) {
            return new ArrayList<String>();
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            return read(reader);
        } catch (IOException failure) {
            System.out.println("[nocturne] could not read resource " + resource + ": " + failure);
            return new ArrayList<String>();
        }
    }

    private static List<String> read(BufferedReader reader) throws IOException {
        List<String> lines = new ArrayList<String>();
        String line;
        while ((line = reader.readLine()) != null) {
            line = line.trim();
            if (!line.isEmpty()) {
                lines.add(line.length() > 256 ? line.substring(0, 256) : line);
            }
        }
        return lines;
    }
}
