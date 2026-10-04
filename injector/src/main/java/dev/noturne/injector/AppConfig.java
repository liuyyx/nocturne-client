package dev.noturne.injector;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

/**
 * 持久化到工作目录下 {@code config.json} 的界面偏好。
 *
 * <p>读取永不抛异常：文件缺失、不可读或内容损坏都退回默认值——偏好文件坏了不该让注入器起不来。
 */
public final class AppConfig {

    /** 共享的 Gson 实例；格式化输出便于用户手改。 */
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** 磁盘结构变更时递增，便于将来迁移旧文件。 */
    public int version = 1;

    /** 界面缩放百分比，取值 80–150。 */
    public int uiScale = 100;

    /** 动画速度百分比，100 表示正常。 */
    public int animationSpeed = 100;

    /** 背景模糊强度百分比，取值 0–100。 */
    public int blurStrength = 50;

    /** 打开游戏内 GUI 的快捷键，GLFW 风格命名，例如 {@code RSHIFT}。 */
    public String guiBind = "RSHIFT";

    /**
     * 从磁盘读取偏好；任何失败都退回默认值。
     *
     * <p>不抛异常，也不做 I/O 之外的阻塞等待。
     *
     * @return 已归一化的配置实例，永不为 {@code null}
     */
    public static AppConfig load() {
        // 文件不存在时直接走默认值：首次启动没有 config.json 是正常情况。
        File file = file();
        if (file.isFile()) {
            // try-with-resources 保证文件句柄在解析失败时也被关闭。
            try (InputStreamReader reader =
                         new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
                AppConfig config = GSON.fromJson(reader, AppConfig.class);
                // fromJson 对空文件返回 null，必须显式判空。
                if (config != null) {
                    config.normalise();
                    return config;
                }
            } catch (Throwable ignored) {
                // 内容损坏或读不到：继续走默认值，不打扰用户。
            }
        }

        // 走到这里说明文件不存在、解析失败或内容为空：统一用默认值。
        AppConfig defaults = new AppConfig();
        defaults.normalise();
        return defaults;
    }

    /**
     * 把当前偏好写回磁盘。
     *
     * <p>不抛异常：写失败（只读目录、磁盘满）只是丢一次设置，不该弹错误。
     */
    public void save() {
        // 落盘前先归一化：确保写出去的值都在合法区间内。
        normalise();
        File target = file();
        // 主目录下的 .noturne 可能尚不存在；先建目录，否则写盘必然失败，
        // 而异常又会被下面的 catch 吞掉，表现为「设置怎么都存不住」。
        File parent = target.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        try (OutputStreamWriter writer =
                     new OutputStreamWriter(new FileOutputStream(target), StandardCharsets.UTF_8)) {
            GSON.toJson(this, writer);
        } catch (Throwable ignored) {
            // 偏好是尽力而为的；写不进去也不该作为错误上报。
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
     * @return {@code ~/.noturne/config.json} 的 {@link File}；文件不必存在
     */
    static File file() {
        return new File(new File(System.getProperty("user.home", "."), ".noturne"), "config.json");
    }
}
