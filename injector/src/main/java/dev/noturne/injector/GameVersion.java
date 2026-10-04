package dev.noturne.injector;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从 Minecraft 启动命令行里尽力提取游戏/加载器版本标签。
 *
 * <p>启发式：优先取版本目录名，其次取 {@code --version} 参数。两者都没有时返回破折号占位。
 */
public final class GameVersion {

    /** 无法识别时使用的占位符（em dash）。 */
    private static final String UNKNOWN = "\u2014";

    /** 匹配 {@code --version 1.8.9} 形式的命令行参数。 */
    private static final Pattern VERSION_ARGUMENT =
            Pattern.compile("--version\\s+(\\S+)");

    /** 匹配 {@code .../versions/fpsmaster/...} 形式的版本目录名。 */
    private static final Pattern VERSION_DIRECTORY =
            Pattern.compile("versions[/\\\\]([^/\\\\\"\\s]+)");

    /** 工具类，不允许实例化。 */
    private GameVersion() {
    }

    /**
     * 从命令行解析版本标签。
     *
     * @param commandLine 目标 JVM 的完整命令行；允许为 {@code null} 或空串
     * @return 形如 {@code 1.8.9} 的短标签，或实例名（如 {@code fpsmaster}）；无可用信息时返回
     *         破折号——Windows 11 已移除 wmic，命令行本身可能就取不到
     */
    public static String fromCommandLine(String commandLine) {
        if (commandLine == null || commandLine.isEmpty()) {
            return UNKNOWN;
        }
        Matcher directory = VERSION_DIRECTORY.matcher(commandLine);
        if (directory.find()) {
            // 目录名通常就是版本号或实例名，比 --version 更贴近实际加载的版本。
            return shorten(directory.group(1));
        }
        Matcher argument = VERSION_ARGUMENT.matcher(commandLine);
        if (argument.find()) {
            // 目录匹配失败再退回命令行参数：某些启动器不建版本目录。
            return shorten(argument.group(1));
        }
        return UNKNOWN;
    }

    /**
     * 规整解析出的标签：去空白，空串退回占位符，过长则截断。
     *
     * @param value 匹配到的原始子串
     */
    private static String shorten(String value) {
        String trimmed = value.trim();
        // 目录名可能带引号或尾部分隔符，先 trim 掉再判空。
        if (trimmed.isEmpty()) {
            return UNKNOWN;
        }
        return trimmed.length() > 28 ? trimmed.substring(0, 27) + "\u2026" : trimmed;
    }
}
