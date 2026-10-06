package dev.nocturne.injector;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link AppConfig} 的读写契约测试（L-87）。
 *
 * <p>覆盖 M-98（原子写盘：先写临时文件再整体移动，失败不留半截目标文件、不留临时文件）与
 * M-99（逐字段解析：坏字段只回退自己；损坏 JSON 退回默认值且不抛异常；
 * {@code Error} 不被当成配置损坏吞掉）。
 *
 * <p>用临时目录覆盖 {@code user.home} 实现隔离，测试后递归清理。
 */
class AppConfigTest {

    private String savedHome;
    private Path tempHome;
    private Path configDir;
    private Path configFile;

    @BeforeEach
    void setUp() throws IOException {
        savedHome = System.getProperty("user.home");
        tempHome = Files.createTempDirectory("nocturne-config-test");
        System.setProperty("user.home", tempHome.toString());
        configDir = tempHome.resolve(".nocturne");
        configFile = configDir.resolve("config.json");
        Files.createDirectories(configDir);
    }

    @AfterEach
    void tearDown() throws IOException {
        System.setProperty("user.home", savedHome);
        deleteRecursively(tempHome);
    }

    /** 保存后再读取，所有字段应原样还原（并已归一化到合法区间） */
    @Test
    void saveThenLoadRoundTripsAllFields() throws IOException {
        AppConfig config = new AppConfig();
        config.uiScale = 120;
        config.animationSpeed = 150;
        config.blurStrength = 70;
        config.guiBind = "F5";
        config.save();

        assertTrue(Files.isRegularFile(configFile), "save must write " + configFile);

        AppConfig loaded = AppConfig.load();
        assertEquals(120, loaded.uiScale);
        assertEquals(150, loaded.animationSpeed);
        assertEquals(70, loaded.blurStrength);
        assertEquals("F5", loaded.guiBind);
    }

    /** M-98：写盘走临时文件，成功后不得留下临时文件，目标文件必须是完整可解析的 JSON */
    @Test
    void saveLeavesNoTemporaryFilesAndWritesCompleteJson() throws IOException {
        AppConfig config = new AppConfig();
        config.guiBind = "A";
        config.save();

        assertFalse(hasTempFiles(), "no *.tmp file may survive a successful save");
        String json = new String(Files.readAllBytes(configFile), StandardCharsets.UTF_8);
        assertTrue(json.contains("\"guiBind\""), "target must contain a complete object: " + json);
        assertTrue(json.trim().endsWith("}"), "target must be a complete JSON object");
    }

    /**
     * M-98：写盘失败（目标位置不可被文件替换）时不得抛异常、不得留下临时文件，
     * 也不得破坏目标位置上原有的内容。
     */
    @Test
    void failedSaveLeavesNoHalfWrittenTarget() throws IOException {
        // 把目标位置占成非空目录，使原子的文件替换必然失败
        Files.createDirectories(configFile);
        Path keep = configFile.resolve("keep.txt");
        Files.write(keep, "keep".getBytes(StandardCharsets.UTF_8));

        AppConfig config = new AppConfig();
        config.uiScale = 130;
        config.save();   // 不得抛异常

        assertTrue(Files.isDirectory(configFile), "the pre-existing target content must survive");
        assertTrue(Files.exists(keep), "the pre-existing target content must survive");
        assertFalse(hasTempFiles(), "a failed save must clean up its temporary file");
    }

    /** M-99：坏字段只回退它自己，其余合法字段必须保留 */
    @Test
    void brokenFieldFallsBackAloneWhileOthersSurvive() throws IOException {
        writeRaw("{\"version\":1,\"uiScale\":\"oops\",\"animationSpeed\":150,"
                + "\"blurStrength\":\"nope\",\"guiBind\":\"F5\"}");

        AppConfig loaded = AppConfig.load();
        assertEquals(100, loaded.uiScale, "malformed uiScale falls back to its default");
        assertEquals(150, loaded.animationSpeed, "a healthy sibling must survive");
        assertEquals(50, loaded.blurStrength, "malformed blurStrength falls back to its default");
        assertEquals("F5", loaded.guiBind, "a healthy string field must survive");
    }

    /** 缺失字段与空快捷键都退回默认值 */
    @Test
    void missingFieldsAndBlankBindFallBackToDefaults() throws IOException {
        writeRaw("{\"guiBind\":\"   \"}");

        AppConfig loaded = AppConfig.load();
        assertEquals(100, loaded.uiScale);
        assertEquals(100, loaded.animationSpeed);
        assertEquals(50, loaded.blurStrength);
        assertEquals("RSHIFT", loaded.guiBind, "blank bind must fall back to RSHIFT");
    }

    /** 文件里的越界值必须被归一化，而不是原样生效 */
    @Test
    void outOfRangeValuesAreClampedOnLoad() throws IOException {
        writeRaw("{\"uiScale\":9999,\"animationSpeed\":-5,\"blurStrength\":500}");

        AppConfig loaded = AppConfig.load();
        assertEquals(150, loaded.uiScale);
        assertEquals(0, loaded.animationSpeed);
        assertEquals(100, loaded.blurStrength);
    }

    /** M-99：损坏 JSON 不得抛异常，退回默认值 */
    @Test
    void corruptJsonFallsBackToDefaultsWithoutThrowing() throws IOException {
        writeRaw("{ this is not json");

        AppConfig loaded = AppConfig.load();
        assertEquals(100, loaded.uiScale);
        assertEquals(50, loaded.blurStrength);
        assertEquals("RSHIFT", loaded.guiBind);
    }

    /** 文件缺失时直接返回默认值 */
    @Test
    void missingFileYieldsDefaults() throws IOException {
        Files.deleteIfExists(configFile);
        AppConfig loaded = AppConfig.load();
        assertEquals(100, loaded.uiScale);
        assertEquals("RSHIFT", loaded.guiBind);
    }

    /**
     * M-99：字段解析只应捕获运行时异常；{@link OutOfMemoryError} 之类的 {@code Error}
     * 必须原样传播，不能被当成「配置损坏」吞掉。
     */
    @Test
    void errorsAreNotSwallowedAsConfigCorruption() throws Exception {
        JsonElement exploding = new JsonElement() {
            @Override
            public JsonElement deepCopy() {
                return this;
            }

            @Override
            public boolean isJsonNull() {
                return false;
            }

            @Override
            public int getAsInt() {
                throw new OutOfMemoryError("simulated");
            }
        };
        JsonObject object = new JsonObject();
        object.add("uiScale", exploding);

        Method intField = AppConfig.class.getDeclaredMethod("intField", JsonObject.class, String.class, int.class);
        intField.setAccessible(true);
        // 反射会把方法内抛出的 Error 包进 InvocationTargetException；cause 必须仍是 OutOfMemoryError
        java.lang.reflect.InvocationTargetException failure = assertThrows(
                java.lang.reflect.InvocationTargetException.class,
                () -> intField.invoke(null, object, "uiScale", Integer.valueOf(100)));
        assertTrue(failure.getCause() instanceof OutOfMemoryError,
                "Error must propagate instead of being swallowed as config corruption");
    }

    // ------------------------------------------------------------- helpers

    /** 写入原始配置文件内容 */
    private void writeRaw(String json) throws IOException {
        Files.createDirectories(configDir);
        Files.write(configFile, json.getBytes(StandardCharsets.UTF_8));
    }

    /** @return 配置目录下是否存在 {@code config*.tmp} 临时文件 */
    private boolean hasTempFiles() throws IOException {
        DirectoryStream<Path> stream = Files.newDirectoryStream(configDir, "config*.tmp");
        try {
            return stream.iterator().hasNext();
        } finally {
            stream.close();
        }
    }

    /** 递归删除目录（测试隔离用） */
    private static void deleteRecursively(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        Files.walk(root)
                .sorted(Comparator.reverseOrder())
                .forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException ignored) {
                        // 清理失败不影响断言结果
                    }
                });
    }
}
