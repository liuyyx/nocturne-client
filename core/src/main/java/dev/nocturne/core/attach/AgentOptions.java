package dev.nocturne.core.attach;

import java.io.File;
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
    /** {@code --gameDir <dir>} / {@code --gameDir=<dir>}，含引号形式。 */
    private static final Pattern GAME_DIR_ARGUMENT =
            Pattern.compile("--gameDir\\s*[=:]?\\s*(\"[^\"]*\"|'[^']*'|\\S+)");
    /** {@code versions/<实例名>}。 */
    private static final Pattern VERSION_DIRECTORY = Pattern.compile("versions[/\\\\]([^/\\\\\"\\s]+)");
    /** 命令行里形如 {@code D:\...\versions\<实例>\} 的绝对实例目录（classpath 中的实例 jar）。 */
    private static final Pattern VERSION_ABSOLUTE_DIRECTORY =
            Pattern.compile("([A-Za-z]:[^;\"'<>|]*?[\\\\/]versions[\\\\/][^\\\\/;\"'<>|]+)[\\\\/]");

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
     * {@code 1.21.11 voxy 优化} → {@code 1.21.11}；{@code 0.19.5}（加载器版本）→
     * {@link #UNKNOWN_VERSION}；取不到数字时同样返回 {@link #UNKNOWN_VERSION}。
     *
     * @param label 任意标签，可为 {@code null}
     * @return 版本号或 {@link #UNKNOWN_VERSION}
     */
    public static String versionFamily(String label) {
        if (label == null) {
            return UNKNOWN_VERSION;
        }
        Matcher matcher = VERSION.matcher(label);
        while (matcher.find()) {
            String candidate = matcher.group();
            // 跳过 0.x.y：那是 Fabric/Quilt 的加载器版本（如 0.19.5），不是游戏版本。
            if (candidate.startsWith("0")) {
                continue;
            }
            return candidate;
        }
        return UNKNOWN_VERSION;
    }

    /**
     * 从目标进程的命令行里取版本族。
     *
     * <p>顺序（由强到弱）：
     * <ol>
     *   <li>{@code versions/<实例名>} 里的数字（最贴近实际加载的版本）；</li>
     *   <li>{@code --version <标签>} 里的数字；</li>
     *   <li>实例名里没有数字时（{@code fpsmaster}、{@code TLauncher} 这类自定义实例），
     *       读该实例的版本 json（{@code <gameDir>/<实例名>.json}）里的
     *       {@code clientVersion} / {@code inheritsFrom}——这是启动器自己写的权威字段，
     *       比按目录名猜可靠得多。Forge/PCL 的自定义实例几乎都带其中之一。</li>
     * </ol>
     * 都取不到时返回 {@link #UNKNOWN_VERSION}（运行时退化为恒等映射并打日志），绝不猜。
     *
     * <p>命令行本身可能拿不到（非 Windows、或 WMI 被禁用），因此调用方必须允许未知。
     *
     * @param commandLine 目标 JVM 的完整命令行，可为 {@code null}
     * @return 版本号或 {@link #UNKNOWN_VERSION}
     */
    public static String familyFromCommandLine(String commandLine) {
        if (commandLine == null || commandLine.isEmpty()) {
            return UNKNOWN_VERSION;
        }
        Matcher directory = VERSION_DIRECTORY.matcher(commandLine);
        String instanceName = directory.find() ? directory.group(1) : null;
        if (instanceName != null) {
            String family = versionFamily(instanceName);
            if (isKnown(family)) {
                return family;
            }
        }
        Matcher argument = VERSION_ARGUMENT.matcher(commandLine);
        String label = argument.find() ? unquote(argument.group(1)) : null;
        if (label != null) {
            String family = versionFamily(label);
            if (isKnown(family)) {
                return family;
            }
        }
        return familyFromInstanceJson(commandLine, label, instanceName);
    }

    /**
     * 读实例版本 json 里的权威版本字段。
     *
     * <p>只读启动器自己写的 {@code clientVersion}（原版实例）与 {@code inheritsFrom}
     * （Forge/PCL 派生的自定义实例）。任何一步失败都当作"没有这一级信息"，继续返回未知——
     * 注入路径不允许因为读不到文件就报错。
     *
     * @param commandLine  目标命令行（用于取 {@code --gameDir}）
     * @param versionLabel {@code --version} 的标签，可为 {@code null}
     * @param instanceName {@code versions/<name>} 的名字，可为 {@code null}
     * @return 版本号或 {@link UNKNOWN_VERSION}
     */
    private static String familyFromInstanceJson(String commandLine, String versionLabel,
                                                 String instanceName) {
        for (File candidate : instanceJsonCandidates(commandLine, versionLabel, instanceName)) {
            String family = readClientVersion(candidate);
            if (isKnown(family)) {
                return family;
            }
        }
        return UNKNOWN_VERSION;
    }

    /** 列出可能的实例 json 路径（去重、保序）；只做路径拼接，不碰文件系统。 */
    private static java.util.List<File> instanceJsonCandidates(String commandLine,
                                                               String versionLabel,
                                                               String instanceName) {
        java.util.LinkedHashSet<String> names = new java.util.LinkedHashSet<String>();
        if (instanceName != null && !instanceName.isEmpty()) {
            names.add(instanceName);
        }
        if (versionLabel != null && !versionLabel.isEmpty()) {
            names.add(versionLabel);
        }
        Matcher gameDir = GAME_DIR_ARGUMENT.matcher(commandLine);
        String directory = gameDir.find() ? unquote(gameDir.group(1)) : null;
        // classpath 里一定带着实例 jar（...\versions\<实例>\<实例>.jar），从它反推绝对目录：
        // 这条覆盖所有把 --gameDir 指向别处的启动器，不依赖相对路径。
        String instanceDirectory = absoluteVersionDirectory(commandLine);
        java.util.List<File> candidates = new java.util.ArrayList<File>(names.size() * 3);
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<String>();
        for (String name : names) {
            if (directory != null && !directory.isEmpty()) {
                // PCL 风格：--gameDir 直接就是 versions\<实例>，json 与目录同名。
                addCandidate(candidates, seen, new File(directory, name + ".json"));
                // 官启风格：--gameDir 是 .minecraft 根，实例在其 versions\<实例>\ 下。
                addCandidate(candidates, seen,
                        new File(new File(new File(directory, "versions"), name), name + ".json"));
            }
            if (instanceDirectory != null) {
                addCandidate(candidates, seen, new File(instanceDirectory, name + ".json"));
            }
        }
        return candidates;
    }

    /** 追加候选路径（去重，按绝对路径判断），保持插入顺序。 */
    private static void addCandidate(java.util.List<File> candidates,
                                     java.util.Set<String> seen, File candidate) {
        if (seen.add(candidate.getAbsolutePath())) {
            candidates.add(candidate);
        }
    }

    /** 从命令行里取形如 {@code C:\...\versions\<实例>} 的绝对目录；取不到返回 {@code null}。 */
    private static String absoluteVersionDirectory(String commandLine) {
        Matcher matcher = VERSION_ABSOLUTE_DIRECTORY.matcher(commandLine);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** 读 json 里的 {@code clientVersion}，其次 {@code inheritsFrom}；读不到返回 unknown。 */
    private static String readClientVersion(File json) {
        try {
            if (json == null || !json.isFile()) {
                return UNKNOWN_VERSION;
            }
            String text = new String(java.nio.file.Files.readAllBytes(json.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
            com.google.gson.JsonObject root =
                    com.google.gson.JsonParser.parseString(text).getAsJsonObject();
            for (String key : new String[]{"clientVersion", "inheritsFrom"}) {
                if (root.has(key) && root.get(key).isJsonPrimitive()) {
                    String family = versionFamily(root.get(key).getAsString());
                    if (isKnown(family)) {
                        return family;
                    }
                }
            }
        } catch (Throwable ignored) {
            // 读不到/解析失败都只是"这一级没有信息"，绝不因此让注入失败。
        }
        return UNKNOWN_VERSION;
    }

    /** 去掉命令行参数两侧可能存在的单/双引号。 */
    private static String unquote(String value) {
        if (value == null || value.length() < 2) {
            return value;
        }
        char first = value.charAt(0);
        char last = value.charAt(value.length() - 1);
        if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
            return value.substring(1, value.length() - 1);
        }
        return value;
    }

    /**
     * 从命令行里取 {@code --gameDir} 的值（去引号）。
     *
     * <p>供其他需要看目标实例目录的调用方复用（例如检查目标 mods 里的已知按键冲突），
     * 避免各处再写一遍正则。
     *
     * @param commandLine 目标 JVM 的完整命令行，可为 {@code null}
     * @return 目录路径；命令行里没有该项时返回 {@code null}
     */
    static String gameDirectory(String commandLine) {
        if (commandLine == null || commandLine.isEmpty()) {
            return null;
        }
        Matcher matcher = GAME_DIR_ARGUMENT.matcher(commandLine);
        return matcher.find() ? unquote(matcher.group(1)) : null;
    }
}
