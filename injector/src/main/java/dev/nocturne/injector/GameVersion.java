package dev.nocturne.injector;

import dev.nocturne.core.attach.AgentOptions;

/**
 * 注入器 UI 需要的**游戏版本号**显示值。
 *
 * <p>判定逻辑只有一份：{@link AgentOptions#familyFromCommandLine(String)}（core 模块，注入与运行时
 * 共用同一套规则）。这里只负责把"未识别"翻译成界面上的破折号——UI 不该显示空串，也不该把
 * {@code fpsmaster} 这样的实例名当版本号（用户会误以为识别到了一个叫 fpsmaster 的版本）。
 *
 * <p>之所以不再自己写一套正则：重复实现过两份（core 与 injector），规则一改就只改一处，
 * 于是界面显示的版本与真正传给 agent 的 {@code mcVersion} 会对不上——那正是"界面看着识别到了、
 * 游戏里却用恒等映射"这类诡异现象的来源。
 */
public final class GameVersion {

    /** 无法识别时使用的占位符（em dash）。 */
    private static final String UNKNOWN = "\u2014";

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
        String family = AgentOptions.familyFromCommandLine(commandLine);
        return AgentOptions.isKnown(family) ? family : UNKNOWN;
    }
}
