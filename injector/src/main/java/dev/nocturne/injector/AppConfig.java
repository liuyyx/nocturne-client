package dev.nocturne.injector;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 持久化到用户主目录下 {@code ~/.nocturne/config.json} 的界面偏好。
 *
 * <p>读取永不抛异常：文件缺失、不可读或内容损坏都退回默认值——偏好文件坏了不该让注入器起不来。
 * 单个字段损坏只影响它自己：其余可解析字段仍会被保留。
 */
public final class AppConfig {

    /** 共享的 Gson 实例；格式化输出便于用户手改。 */
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** 日志前缀，与项目其余部分保持一致，便于检索。 */
    private static final String LOG_PREFIX = "[nocturne] ";

    /** 磁盘结构变更时递增，便于将来迁移旧文件。 */
    public int version = 1;

    /** 界面缩放百分比，取值 80–150。 */
    public int uiScale = 100;

    /** 动画速度百分比，100 表示正常。 */
    public int animationSpeed = 100;

    /** 背景模糊强度百分比，取值 0–100。 */
    public int blurStrength = 50;

    /** 打开游戏内 GUI 的快捷键，命名见 {@link KeyCodes}，例如 {@code RSHIFT}。 */
    public String guiBind = "RSHIFT";

    /**
     * 从磁盘读取偏好；任何失败都退回默认值。
     *
     * <p>逐字段解析：某个字段类型不对只会让它自己退回默认值，不会连累其它合法字段。
     * 只捕获 I/O 与解析异常，{@link OutOfMemoryError} 之类不会被当成「配置损坏」吞掉。
     *
     * @return 已归一化的配置实例，永不为 {@code null}
     */
    public static AppConfig load() {
        File file = file();
        if (file.isFile()) {
            // try-with-resources 保证文件句柄在解析失败时也被关闭。
            try (InputStreamReader reader =
                         new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
                JsonElement root = JsonParser.parseReader(reader);
                if (root != null && root.isJsonObject()) {
                    return fromJson(root.getAsJsonObject());
                }
                System.err.println(LOG_PREFIX + "config.json 顶层不是 JSON 对象，改用默认设置");
            } catch (IOException | RuntimeException broken) {
                // 内容损坏或读不到：退回默认值，但留下可检索的日志。
                System.err.println(LOG_PREFIX + "读取设置失败，改用默认设置：" + broken);
            }
        }

        AppConfig defaults = new AppConfig();
        defaults.normalise();
        return defaults;
    }

    /**
     * 从 JSON 对象逐个字段构造配置；坏字段各自退回该字段的默认值。
     *
     * @param object 已解析的 JSON 对象
     * @return 已归一化的配置
     */
    private static AppConfig fromJson(JsonObject object) {
        AppConfig config = new AppConfig();
        config.version = intField(object, "version", config.version);
        config.uiScale = intField(object, "uiScale", config.uiScale);
        config.animationSpeed = intField(object, "animationSpeed", config.animationSpeed);
        config.blurStrength = intField(object, "blurStrength", config.blurStrength);
        config.guiBind = stringField(object, "guiBind", config.guiBind);
        config.normalise();
        return config;
    }

    /**
     * 读取一个整数字段；缺失、null 或类型不符时保留默认值并打印日志。
     *
     * @param object   JSON 对象
     * @param name     字段名
     * @param fallback 默认值
     * @return 解析出的整数或默认值
     */
    private static int intField(JsonObject object, String name, int fallback) {
        JsonElement element = object.get(name);
        if (element == null || element.isJsonNull()) {
            return fallback;
        }
        try {
            return element.getAsInt();
        } catch (RuntimeException malformed) {
            System.err.println(LOG_PREFIX + "设置项 " + name + " 无效，保留默认值 " + fallback + "：" + element);
            return fallback;
        }
    }

    /**
     * 读取一个字符串字段；缺失、null 或类型不符时保留默认值并打印日志。
     *
     * @param object   JSON 对象
     * @param name     字段名
     * @param fallback 默认值
     * @return 解析出的字符串或默认值
     */
    private static String stringField(JsonObject object, String name, String fallback) {
        JsonElement element = object.get(name);
        if (element == null || element.isJsonNull()) {
            return fallback;
        }
        try {
            return element.getAsString();
        } catch (RuntimeException malformed) {
            System.err.println(LOG_PREFIX + "设置项 " + name + " 无效，保留默认值：" + element);
            return fallback;
        }
    }

    /**
     * 把当前偏好原子地写回磁盘。
     *
     * <p>先写同目录下的临时文件，再整体移动覆盖目标：写盘中途中断时旧文件保持完整，
     * 不会留下半截 JSON 让下次启动整体退回默认值。
     *
     * <p>不抛异常：写失败（只读目录、磁盘满）只是丢一次设置，但会打印日志便于排查。
     */
    public void save() {
        // 落盘前先归一化：确保写出去的值都在合法区间内。
        normalise();
        File target = file();
        // 主目录下的 .nocturne 可能尚不存在；先建目录，否则写盘必然失败。
        File parent = target.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        Path targetPath = target.toPath();
        Path directory = targetPath.getParent();
        if (directory == null) {
            directory = new File(".").toPath();
        }
        Path temporary = null;
        try {
            temporary = Files.createTempFile(directory, "config", ".tmp");
            try (OutputStreamWriter writer =
                         new OutputStreamWriter(Files.newOutputStream(temporary), StandardCharsets.UTF_8)) {
                GSON.toJson(this, writer);
            }
            try {
                Files.move(temporary, targetPath,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException | UnsupportedOperationException notAtomic) {
                // 部分文件系统不支持原子替换：退化为普通覆盖移动。
                Files.move(temporary, targetPath, StandardCopyOption.REPLACE_EXISTING);
            }
            temporary = null;
        } catch (IOException | RuntimeException failed) {
            System.err.println(LOG_PREFIX + "保存设置失败：" + failed);
        } finally {
            // 移动成功后 temporary 已置空；失败时清掉残留的临时文件。
            if (temporary != null) {
                try {
                    Files.deleteIfExists(temporary);
                } catch (IOException ignored) {
                    // 清理失败无关紧要，不再打扰用户。
                }
            }
        }
    }

    /**
     * 把所有字段夹到合法区间，并补齐空的快捷键。
     *
     * <p>读写两侧都调用，因此无论文件里写了什么都不影响运行。
     */
    private void normalise() {
        uiScale = clamp(uiScale, 80, 150);
        animationSpeed = clamp(animationSpeed, 0, 200);
        blurStrength = clamp(blurStrength, 0, 100);
        // 快捷键为空串或空白时退回默认值，避免注入端拿到无法解析的绑定。
        if (guiBind == null || guiBind.trim().isEmpty()) {
            guiBind = "RSHIFT";
        }
    }

    /**
     * 把数值夹到 {@code [min, max]} 闭区间内。
     *
     * @param value 待夹取的数
     * @param min   下界
     * @param max   上界
     * @return 夹取后的值
     */
    private static int clamp(int value, int min, int max) {
        return value < min ? min : (value > max ? max : value);
    }

    /**
     * 返回配置文件路径。
     *
     * <p>放在用户主目录而非工作目录：双击 jar、从快捷方式启动、或从别的目录用命令行启动时，
     * 工作目录各不相同，配置会散落在多处；主目录在三种启动方式下都稳定。
     *
     * @return {@code ~/.nocturne/config.json} 的 {@link File}；文件不必存在
     */
    static File file() {
        return new File(new File(System.getProperty("user.home", "."), ".nocturne"), "config.json");
    }
}
