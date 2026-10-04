package dev.noturne.injector;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 验证从命令行提取版本标签：目录名优先、{@code --version} 的多种写法、引号剥离与未知兜底。
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
}
