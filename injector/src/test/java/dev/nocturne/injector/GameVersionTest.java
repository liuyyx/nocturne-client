package dev.nocturne.injector;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 验证注入器界面显示的**游戏版本号**：目录名优先、{@code --version} 的多种写法、引号剥离、
 * 未知兜底、加载器版本（{@code 0.x.y}）跳过，以及自定义实例（{@code fpsmaster}）改由实例
 * json 的 {@code clientVersion} 判定。
 *
 * <p>规则本体在 {@code core} 的 {@code AgentOptions}（注入与运行时共用一份），本类只钉住
 * "UI 把未知显示成破折号、不把实例名当版本号"这层约定。
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

    /**
     * 实例名里没有版本号、也读不到实例 json 时显示"未识别"。
     *
     * <p>这里的命令行只有相对路径（没有盘符），因此不存在可读的实例 json——正是"什么都拿不到"
     * 的情形；一旦实例 json 存在，则应显示真实版本（见下一条）。
     */
    @Test
    @DisplayName("既无版本号也读不到实例 json 时显示未识别")
    void doesNotShowInstanceNameAsVersion() {
        assertEquals("\u2014",
                GameVersion.fromCommandLine("javaw -cp ... versions/fpsmaster/fpsmaster.jar"));
        assertEquals("\u2014",
                GameVersion.fromCommandLine("javaw -cp ... --version fpsmaster"));
    }

    /** 自定义实例（fpsmaster）：从实例 json 的 clientVersion 读出真实版本，界面显示 1.8.9。 */
    @Test
    @DisplayName("自定义实例读实例 json")
    void readsInstanceJsonForCustomInstances(@TempDir Path temp) throws Exception {
        Path instance = temp.resolve("versions").resolve("fpsmaster");
        Files.createDirectories(instance);
        Files.write(instance.resolve("fpsmaster.json"),
                "{\"id\":\"fpsmaster\",\"clientVersion\":\"1.8.9\"}".getBytes(StandardCharsets.UTF_8));
        String commandLine = "javaw -cp " + instance.resolve("fpsmaster.jar")
                + " net.minecraft.launchwrapper.Launch --version fpsmaster --gameDir " + instance;
        assertEquals("1.8.9", GameVersion.fromCommandLine(commandLine));
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
