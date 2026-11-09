package gg.vape.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import gg.vape.api.ApiHttpClient;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/**
 * Local, offline fallback for the online settings/profile storage. Writes a
 * single JSON document under %APPDATA%\.vapeclient\config.json so module
 * settings, profiles, friends and global preferences persist without the
 * loopback service.
 */
public final class LocalConfigStore {
    private static final String FILE_NAME = "config.json";
    /** 关闭钩子与保存 worker 可能并发写同一 config.json；读-改-写必须串行。 */
    private static final Object WRITE_LOCK = new Object();
    private static volatile boolean migrationAttempted = false;

    private LocalConfigStore() {
    }

    public static File baseDirectory() {
        String nativeDirectory = System.getProperty("vape.directory");
        if (nativeDirectory != null && !nativeDirectory.trim().isEmpty()) {
            return new File(nativeDirectory, ".vapeclient");
        }
        String appData = System.getenv("APPDATA");
        if (appData == null || appData.trim().isEmpty()) {
            appData = System.getProperty("user.home");
        }
        return new File(appData, ".vapeclient");
    }

    public static File directory() {
        File directory = baseDirectory();
        if (!directory.exists()) {
            directory.mkdirs();
        }
        hideDirectory(directory);
        migrateLegacyConfig(directory);
        return directory;
    }

    private static void hideDirectory(File directory) {
        try {
            java.nio.file.Path path = directory.toPath();
            if (java.nio.file.Files.exists(path)) {
                java.nio.file.Files.setAttribute(path, "dos:hidden", true);
            }
        }
        catch (Exception ignored) {
            // hiding is cosmetic; never fail config access over it
        }
    }

    private static void migrateLegacyConfig(File targetDirectory) {
        if (migrationAttempted) {
            return;
        }
        migrationAttempted = true;
        File target = new File(targetDirectory, FILE_NAME);
        if (target.isFile()) {
            return;
        }
        String appData = System.getenv("APPDATA");
        if (appData == null || appData.trim().isEmpty()) {
            appData = System.getProperty("user.home");
        }
        File legacy = new File(new File(appData, ".vapeclient"), FILE_NAME);
        if (!legacy.isFile()) {
            return;
        }
        try {
            Files.copy(legacy.toPath(), target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
        }
        catch (Exception ignored) {
        }
    }

    public static File configFile() {
        return new File(directory(), FILE_NAME);
    }

    private static JsonObject read() {
        File file = configFile();
        if (!file.isFile()) {
            return null;
        }
        try (InputStreamReader reader = new InputStreamReader(
                new FileInputStream(file), StandardCharsets.UTF_8)) {
            // new JsonParser().parse(Reader) works on every Gson generation;
            // JsonParser.parseReader(...) is missing on the Gson bundled with
            // 1.8.9-era Minecraft (NoSuchMethodError).
            return new JsonParser().parse(reader).getAsJsonObject();
        }
        catch (Exception ignored) {
            return null;
        }
    }

    private static boolean write(JsonObject root) {
        java.nio.file.Path temporary = null;
        try {
            File directory = directory();
            File target = new File(directory, FILE_NAME);
            // 每 writer 一个唯一 tmp：固定 config.json.tmp 会被并发 writer 互相截断。
            temporary = Files.createTempFile(directory.toPath(), FILE_NAME + ".", ".tmp");
            try (OutputStreamWriter writer = new OutputStreamWriter(
                    new FileOutputStream(temporary.toFile()), StandardCharsets.UTF_8)) {
                ApiHttpClient.GSON.toJson(root, writer);
            }
            try {
                Files.move(temporary, target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException fallback) {
                Files.move(temporary, target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        }
        catch (Exception ignored) {
            return false;
        }
        finally {
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (Exception ignored) {
                    // best effort: 失败时留下一个随机 tmp，不会污染 config.json
                }
            }
        }
    }

    private static JsonObject mutableRoot() {
        JsonObject root = read();
        if (root == null) {
            root = new JsonObject();
            root.addProperty("version", 1);
        }
        return root;
    }

    public static boolean saveSettings(JsonObject settings) {
        if (settings == null) {
            return false;
        }
        synchronized (WRITE_LOCK) {
            JsonObject root = mutableRoot();
            root.add("settings", settings);
            return write(root);
        }
    }

    public static boolean saveProfiles(JsonObject profiles) {
        if (profiles == null) {
            return false;
        }
        synchronized (WRITE_LOCK) {
            JsonObject root = mutableRoot();
            root.add("profiles", profiles);
            return write(root);
        }
    }

    public static boolean saveGlobal(JsonObject global) {
        if (global == null) {
            return false;
        }
        synchronized (WRITE_LOCK) {
            JsonObject root = mutableRoot();
            root.add("global", global);
            return write(root);
        }
    }

    public static JsonObject loadSettings() {
        JsonObject root = read();
        return root != null && root.has("settings")
                ? root.getAsJsonObject("settings") : null;
    }

    public static JsonObject loadProfiles() {
        JsonObject root = read();
        return root != null && root.has("profiles")
                ? root.getAsJsonObject("profiles") : null;
    }

    public static JsonObject loadGlobal() {
        JsonObject root = read();
        return root != null && root.has("global")
                ? root.getAsJsonObject("global") : null;
    }
}
