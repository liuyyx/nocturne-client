package dev.noturne.injector;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从 Minecraft 启动命令行里提取**游戏版本号**。
 *
 * <p>启发式：优先版本目录名，其次 {@code --version} 参数。
 *
 * <p>关键取舍：取不到数字版本时返回破折号，**不**回退成"显示实例名"。把 {@code fpsmaster}
 * 这类实例名当版本号显示是误导——用户会以为识别到了一个叫 fpsmaster 的版本，而实际上是什么都没识别到。
 * 宁可明确显示"未识别"，让注入前就知道版本未知（运行时会退化为恒等映射并打日志）。
 */
public final class GameVersion {

    /** 无法识别时使用的占位符（em dash）。 */
    private static final String UNKNOWN = "\u2014";

    /** 匹配 {@code --version 1.8.9}、{@code --version=1.8.9}、{@code --version="1.8.9"} 等形式。 */
    private static final Pattern VERSION_ARGUMENT =
            Pattern.compile("--version\\s*[=:]?\\s*(\"[^\"]*\"|'[^']*'|\\S+)");

    /** 匹配 {@code .../versions/fpsmaster/...} 形式的版本目录名。 */
    private static final Pattern VERSION_DIRECTORY =
            Pattern.compile("versions[/\\\\]([^/\\\\\"\\s]+)");

    /** 版本号形态：{@code x.y} 或 {@code x.y.z}。 */
    private static final Pattern VERSION_NUMBER = Pattern.compile("\\d+\\.\\d+(?:\\.\\d+)?");

    /** 工具类，不允许实例化。 */
    private GameVersion() {
    }

    /**
     * 从命令行解析游戏版本号。
     *
     * @param commandLine 目标 JVM 的完整命令行；允许为 {@code null} 或空串
     * @return 形如 {@code 1.8.9} / {@code 26.3} 的版本号；识别不出时返回破折号
     *         （Windows 11 已移除 wmic，命令行本身可能就取不到）
     */
    public static String fromCommandLine(String commandLine) {
        if (commandLine == null || commandLine.isEmpty()) {
            return UNKNOWN;
        }
        Matcher directory = VERSION_DIRECTORY.matcher(commandLine);
        if (directory.find()) {
            // 目录名通常最贴近实际加载的版本；但实例名可能不含版本号（如 fpsmaster），
            // 那就继续试 --version，而不是在这里就放弃。
            String fromDirectory = versionOf(directory.group(1));
            if (!UNKNOWN.equals(fromDirectory)) {
                return fromDirectory;
            }
        }
        Matcher argument = VERSION_ARGUMENT.matcher(commandLine);
        if (argument.find()) {
            return versionOf(argument.group(1));
        }
        return UNKNOWN;
    }

    /**
     * 从实例名或参数值里取出游戏版本号。
     *
     * <p>跳过 {@code 0.x.y} 形态：那是 Fabric 加载器版本（如 {@code 0.19.5}），不是游戏版本。
     * 目录名 {@code 26.3-Fabric 0.19.5} 里第一个数字正好是 {@code 26.3}——取它。
     */
    private static String versionOf(String label) {
        Matcher matcher = VERSION_NUMBER.matcher(stripQuotes(label));
        while (matcher.find()) {
            String candidate = matcher.group();
            if (!candidate.startsWith("0")) {
                return candidate;
            }
        }
        return UNKNOWN;
    }

    /**
     * 去掉成对的首尾引号：{@code --version="1.8.9"} 这类写法只靠 trim 去不掉引号。
     *
     * @param value 原始子串，可为 {@code null}
     * @return 去引号并 trim 后的文本；{@code null} 归一化为空串
     */
    private static String stripQuotes(String value) {
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.length() >= 2) {
            char first = trimmed.charAt(0);
            char last = trimmed.charAt(trimmed.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
            }
        }
        return trimmed;
    }
}
