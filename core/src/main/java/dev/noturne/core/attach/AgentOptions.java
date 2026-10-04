package dev.noturne.core.attach;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * agent 选项串的组装与版本标签归一化。
 *
 * <p>注入器与 agent 之间只通过 attach 的 options 传递配置（JDK attach 的约定：逗号分隔的
 * {@code key=value}）。这里集中两件事，避免两侧各写一份格式：
 * <ul>
 *   <li><b>组装</b>：{@code guiKey=<AWT VK>} + {@code mcVersion=<版本族>}；</li>
 *   <li><b>版本族归一化</b>：把启动器给的实例标签（{@code 1.8.9优化}、
 *       {@code 26.3-Fabric 0.19.5}）压成映射表能用的版本号（{@code 1.8.9}、{@code 26.3}）。</li>
 * </ul>
 *
 * <p>为什么版本由注入器传入而不是运行时探测：注入器看得见目标进程的命令行（含
 * {@code --version} 与 {@code versions/<实例>/} 路径），而 agent 在目标 JVM 里只能靠类结构猜。
 * 由注入器传一个字符串，运行时就不需要任何探测分支。
 */
public final class AgentOptions {

    /** GUI 开关键（AWT VK 码）的选项名。 */
    public static final String OPTION_GUI_KEY = "guiKey=";
    /** Minecraft 版本族（如 {@code 1.8.9}）的选项名。 */
    public static final String OPTION_MC_VERSION = "mcVersion=";
    /** 版本无法判定时使用的占位值。 */
    public static final String UNKNOWN_VERSION = "unknown";

    /** 从任意标签里取形如 {@code x.y} 或 {@code x.y.z} 的版本号。 */
    private static final Pattern VERSION = Pattern.compile("\\d+\\.\\d+(?:\\.\\d+)?");
    /** {@code --version <label>} / {@code --version=<label>}，含引号形式。 */
    private static final Pattern VERSION_ARGUMENT =
            Pattern.compile("--version\\s*[=:]?\\s*(\"[^\"]*\"|'[^']*'|\\S+)");
    /** {@code versions/<实例名>}。 */
    private static final Pattern VERSION_DIRECTORY = Pattern.compile("versions[/\\\\]([^/\\\\\"\\s]+)");

    private AgentOptions() {
    }

    /**
     * 组装 agent 选项串。
     *
     * @param guiKeyVk  GUI 开关键的 AWT VK 码
     * @param mcVersion Minecraft 版本族；为 {@code null} 或未知时该项被省略
     * @return 形如 {@code guiKey=54,mcVersion=1.8.9} 的选项串
     */
    public static String compose(int guiKeyVk, String mcVersion) {
        StringBuilder out = new StringBuilder(48);
        out.append(OPTION_GUI_KEY).append(guiKeyVk);
        if (isKnown(mcVersion)) {
            out.append(',').append(OPTION_MC_VERSION).append(mcVersion);
        }
        return out.toString();
    }

    /**
     * 只组装版本项的选项串（命令行注入路径用：那条路径没有录制的开关键，agent 侧会退回默认键）。
     *
     * @param mcVersion Minecraft 版本族；未知时返回空串
     * @return 形如 {@code mcVersion=1.8.9} 的选项串，或空串
     */
    public static String composeVersion(String mcVersion) {
        return isKnown(mcVersion) ? OPTION_MC_VERSION + mcVersion : "";
    }

    /**
     * 判断版本族是否可用。
     *
     * @param mcVersion 版本族字符串，可为 {@code null}
     * @return 既非空、也不是 {@link #UNKNOWN_VERSION} 时返回 true
     */
    public static boolean isKnown(String mcVersion) {
        return mcVersion != null && !mcVersion.isEmpty() && !UNKNOWN_VERSION.equals(mcVersion);
    }

    /**
     * 把启动器给的标签压成版本号。
     *
     * <p>{@code 1.8.9优化} → {@code 1.8.9}；{@code 26.3-Fabric 0.19.5} → {@code 26.3}；
     * {@code 1.21.11 voxy 优化} → {@code 1.21.11}；取不到数字时返回 {@link #UNKNOWN_VERSION}。
     *
     * @param label 任意标签，可为 {@code null}
     * @return 版本号或 {@link #UNKNOWN_VERSION}
     */
    public static String versionFamily(String label) {
        if (label == null) {
            return UNKNOWN_VERSION;
        }
        Matcher matcher = VERSION.matcher(label);
        return matcher.find() ? matcher.group() : UNKNOWN_VERSION;
    }

    /**
     * 从目标进程的命令行里取版本族。
     *
     * <p>优先取 {@code versions/<实例名>}（最贴近实际加载的版本），其次 {@code --version <label>}；
     * 都取不到时返回 {@link #UNKNOWN_VERSION}。命令行本身可能拿不到（非 Windows、或 WMI 被禁用），
     * 因此调用方必须允许未知。
     *
     * @param commandLine 目标 JVM 的完整命令行，可为 {@code null}
     * @return 版本号或 {@link #UNKNOWN_VERSION}
     */
    public static String familyFromCommandLine(String commandLine) {
        if (commandLine == null || commandLine.isEmpty()) {
            return UNKNOWN_VERSION;
        }
        Matcher directory = VERSION_DIRECTORY.matcher(commandLine);
        if (directory.find()) {
            String family = versionFamily(directory.group(1));
            if (isKnown(family)) {
                return family;
            }
        }
        Matcher argument = VERSION_ARGUMENT.matcher(commandLine);
        if (argument.find()) {
            return versionFamily(argument.group(1));
        }
        return UNKNOWN_VERSION;
    }
}
