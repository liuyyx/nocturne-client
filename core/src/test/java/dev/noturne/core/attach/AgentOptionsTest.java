package dev.noturne.core.attach;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证注入器与 agent 之间那串选项的组装，以及版本标签的归一化。
 *
 * <p>这是跨模块硬契约：注入器（或 core 的命令行路径）组装、agent 解析。两侧都只看这几个字符串，
 * 所以格式与归一化必须被钉住——实例名千奇百怪（{@code 1.8.9优化}、{@code 26.3-Fabric 0.19.5}），
 * 压不出版本号时宁可为 {@code unknown}（运行时退化为恒等映射并打日志），也不能猜。
 */
class AgentOptionsTest {

    /** 版本标签归一化：从实例名里取出版本号。 */
    @Test
    void versionFamilyExtractsTheVersionNumber() {
        assertEquals("1.8.9", AgentOptions.versionFamily("1.8.9优化"));
        assertEquals("1.8.9", AgentOptions.versionFamily("1.8.9-Rise"));
        assertEquals("26.3", AgentOptions.versionFamily("26.3-Fabric 0.19.5"));
        assertEquals("1.21.11", AgentOptions.versionFamily("1.21.11 voxy 优化"));
        assertEquals("1.20.1", AgentOptions.versionFamily("1.20.1优化"));
    }

    /** 取不到版本号时必须是 unknown，而不是空串或原标签。 */
    @Test
    void versionFamilyIsUnknownWhenThereIsNoNumber() {
        assertEquals(AgentOptions.UNKNOWN_VERSION, AgentOptions.versionFamily("TLauncher"));
        assertEquals(AgentOptions.UNKNOWN_VERSION, AgentOptions.versionFamily("\u2014"));
        assertEquals(AgentOptions.UNKNOWN_VERSION, AgentOptions.versionFamily(null));
        assertFalse(AgentOptions.isKnown(AgentOptions.UNKNOWN_VERSION));
        assertFalse(AgentOptions.isKnown(null));
        assertFalse(AgentOptions.isKnown(""));
        assertTrue(AgentOptions.isKnown("26.3"));
    }

    /** 命令行里优先取 versions/<实例>/（最贴近实际加载的版本），其次 --version。 */
    @Test
    void familyFromCommandLinePrefersTheVersionDirectory() {
        String dir = "javaw -Xmx2G -cp C:/.minecraft/libraries/a.jar;"
                + "C:/.minecraft/versions/1.8.9-Rise/1.8.9-Rise.jar net.minecraft.launchwrapper.Launch"
                + " --version 1.8.9 --gameDir .";
        assertEquals("1.8.9", AgentOptions.familyFromCommandLine(dir));

        String argumentOnly = "java -cp x.jar net.minecraft.client.main.Main --version 26.3"
                + " --accessToken 0";
        assertEquals("26.3", AgentOptions.familyFromCommandLine(argumentOnly));
    }

    /** 拿不到命令行时必须返回 unknown，让运行时退化为恒等映射。 */
    @Test
    void familyFromCommandLineIsUnknownWithoutInformation() {
        assertEquals(AgentOptions.UNKNOWN_VERSION, AgentOptions.familyFromCommandLine(null));
        assertEquals(AgentOptions.UNKNOWN_VERSION, AgentOptions.familyFromCommandLine(""));
        assertEquals(AgentOptions.UNKNOWN_VERSION, AgentOptions.familyFromCommandLine("java -jar launcher.jar"));
    }

    /** 选项串组装：版本未知时省略该项，agent 侧退回默认开关键。 */
    @Test
    void composeKeepsTheAgreedFormat() {
        assertEquals("guiKey=54,mcVersion=1.8.9", AgentOptions.compose(54, "1.8.9"));
        assertEquals("guiKey=344", AgentOptions.compose(344, AgentOptions.UNKNOWN_VERSION));
        assertEquals("mcVersion=26.3", AgentOptions.composeVersion("26.3"));
        assertEquals("", AgentOptions.composeVersion(AgentOptions.UNKNOWN_VERSION));
    }
}
