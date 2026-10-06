package dev.nocturne.injector;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 验证从命令行提取**游戏版本号**：目录名优先、{@code --version} 的多种写法、引号剥离、未知兜底，
 * 以及两条刻意的取舍——实例名不当版本号、加载器版本（{@code 0.x.y}）不当游戏版本。
 */
class GameVersionTest {

    /** 版本目录名优先于 --version 参数。 */
    @Test
    void prefersVersionsDirectory() {
        assertEquals("1.8.9",
                GameVersion.fromCommandLine("java ... --version 1.21.4 ... versions/1.8.9/minecraft.jar"));
    }

    /** L-64 回归：--version 的等号、冒号、引号写法都要认出来。 */
    @Test
    void acceptsVersionArgumentVariants() {
        assertEquals("1.8.9", GameVersion.fromCommandLine("java -jar client.jar --version 1.8.9"));
        assertEquals("1.8.9", GameVersion.fromCommandLine("java -jar client.jar --version=1.8.9"));
        assertEquals("1.8.9", GameVersion.fromCommandLine("java -jar client.jar --version=\"1.8.9\""));
        assertEquals("1.8.9", GameVersion.fromCommandLine("java -jar client.jar --version '1.8.9'"));
    }

    /** 无可用信息时返回破折号占位。 */
    @Test
    void unknownWhenNothingMatches() {
        assertEquals("\u2014", GameVersion.fromCommandLine(null));
        assertEquals("\u2014", GameVersion.fromCommandLine(""));
        assertEquals("\u2014", GameVersion.fromCommandLine("java -jar launcher.jar"));
    }

    /** 实例名里没有版本号时显示"未识别"，不把实例名当版本号（用户看到的 fpsmaster 就是这样来的）。 */
    @Test
    @DisplayName("实例名不当版本号")
    void doesNotShowInstanceNameAsVersion() {
        assertEquals("\u2014",
                GameVersion.fromCommandLine("javaw -cp ... versions/fpsmaster/fpsmaster.jar"));
        assertEquals("\u2014",
                GameVersion.fromCommandLine("javaw -cp ... --version fpsmaster"));
    }

    /** 目录名里没有版本号时继续看 --version，而不是直接放弃。 */
    @Test
    @DisplayName("目录名无版本号时继续看 --version")
    void fallsBackToVersionArgument() {
        assertEquals("1.21.4",
                GameVersion.fromCommandLine("java -cp ... versions/myinstance/... --version 1.21.4"));
    }

    /** Fabric 加载器版本（0.x.y）不是游戏版本，必须跳过。 */
    @Test
    @DisplayName("跳过加载器版本 0.x.y")
    void ignoresLoaderVersion() {
        assertEquals("\u2014", GameVersion.fromCommandLine("java -cp ... --version Fabric 0.19.5"));
        assertEquals("26.3",
                GameVersion.fromCommandLine("java -cp versions/26.3-Fabric\\ 0.19.5/... 0.19.5"));
    }
}
