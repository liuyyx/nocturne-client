package dev.nocturne.core.attach;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link TargetHints} 的契约：只在该冲突真实存在时提示，且提示要指出可操作的做法。
 *
 * <p>背景：FPSMaster Edge（Forge 1.8.9）的 ClickGUI 默认键是 {@code KEY_RSHIFT}，与我们默认的
 * {@code guiKey=54} 撞键。没有这层提示时，玩家按右 Shift 看到的是 FPSMaster 的面板，
 * 会误判成"注入没生效"——这正是 fpsmaster 实例上反复出现的现象。
 */
class TargetHintsTest {

    /** 造一个含 mods 目录的实例目录。 */
    private String commandLineFor(Path instance, String... modNames) throws Exception {
        Path mods = instance.resolve("mods");
        Files.createDirectories(mods);
        for (String name : modNames) {
            Files.write(mods.resolve(name), new byte[]{1});
        }
        return "javaw -cp " + instance.resolve("x.jar")
                + " net.minecraft.launchwrapper.Launch --version fpsmaster --gameDir " + instance;
    }

    @Test
    void warnsWhenFpsmasterIsPresentAndDefaultKeyIsUsed(@TempDir Path temp) throws Exception {
        String commandLine = commandLineFor(temp, "FPSMaster-1.0.5-beta.2.jar");
        String hint = TargetHints.rightShiftConflict(commandLine, TargetHints.DEFAULT_GUI_KEY_VK);
        assertNotNull(hint);
        assertTrue(hint.contains("FPSMaster"), hint);
        assertTrue(hint.contains("右 Shift"), hint);
    }

    /** 已经改键的用户不该再看到这条提示。 */
    @Test
    void silentWhenKeyWasChanged(@TempDir Path temp) throws Exception {
        String commandLine = commandLineFor(temp, "FPSMaster-1.0.5-beta.2.jar");
        assertNull(TargetHints.rightShiftConflict(commandLine, 45)); // INSERT
    }

    /** 目标没装 FPSMaster 时不提示。 */
    @Test
    void silentForOtherTargets(@TempDir Path temp) throws Exception {
        String commandLine = commandLineFor(temp, "sodium-fabric-0.9.2.jar");
        assertNull(TargetHints.rightShiftConflict(commandLine, TargetHints.DEFAULT_GUI_KEY_VK));
    }

    /** 拿不到 gameDir / mods 时静默（提示是尽力而为，绝不能因此让注入失败）。 */
    @Test
    void silentWithoutGameDir() {
        assertNull(TargetHints.rightShiftConflict(null, TargetHints.DEFAULT_GUI_KEY_VK));
        assertNull(TargetHints.rightShiftConflict("java -cp x.jar Main", TargetHints.DEFAULT_GUI_KEY_VK));
    }
}
