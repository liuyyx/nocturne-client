package dev.nocturne.agent;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ForeignScreenGuard} 的判定契约：只认已知外部客户端的界面类，其它一律放过。
 *
 * <p>为什么这条判定必须钉住：它决定"我们界面打开时要不要关掉当前 screen"。判宽了会在玩家打开
 * 箱子/聊天时也去关（把玩家的界面吃掉）；判窄了则 FPSMaster 的面板继续盖着我们，也就是
 * 玩家一直抱怨的"注入了但看不到我们的界面"。
 *
 * <p>注意这里只覆盖判定函数：端到端的按键验证没法自动化——1.8.9 的 LWJGL2 走 DirectInput，
 * 注入的合成按键事件到不了游戏（实测：SendInput 的右 Shift 不触发游戏内的按键状态）。
 */
class ForeignScreenGuardTest {

    @Test
    void recognisesKnownForeignClientScreens() {
        assertTrue(ForeignScreenGuard.isForeignScreen("top.fpsmaster.ui.click.MainPanel"));
        assertTrue(ForeignScreenGuard.isForeignScreen("top.fpsmaster.ui.hud.HudEditorScreen"));
    }

    @Test
    void leavesVanillaAndOtherScreensAlone() {
        assertFalse(ForeignScreenGuard.isForeignScreen("net.minecraft.client.gui.GuiIngameMenu"));
        assertFalse(ForeignScreenGuard.isForeignScreen("net.minecraft.client.gui.inventory.GuiChest"));
        assertFalse(ForeignScreenGuard.isForeignScreen("com.example.fpsmasterlike.Panel"));
        assertFalse(ForeignScreenGuard.isForeignScreen(null));
    }

    /**
     * 鼠标抓取的判定契约。26.3 实测：界面开着时视角照转、点击落空——因为游戏仍抓着鼠标
     * （SDL 相对模式下系统光标被锁死，我们的界面拿不到指针位置），而当时只写了
     * {@code inGameHasFocus}（那个字段在现代版本根本不存在）。
     */
    @Test
    void releasesTheGameMouseWhileOurGuiIsOpen() {
        // 界面开着 + 游戏抓着鼠标（现代版本）→ 放开
        assertEquals(ForeignScreenGuard.MouseAction.RELEASE,
                ForeignScreenGuard.decideMouseAction(true, true, true, false));
        // 每帧复查：游戏会把鼠标抓回去，所以要继续放开（此时我们已放开过，状态位为 true）
        assertEquals(ForeignScreenGuard.MouseAction.RELEASE,
                ForeignScreenGuard.decideMouseAction(true, true, true, true));
        // 已经放开了就不重复调
        assertEquals(ForeignScreenGuard.MouseAction.NONE,
                ForeignScreenGuard.decideMouseAction(true, true, false, true));
    }

    @Test
    void restoresTheMouseOnlyWhenWeReleasedIt() {
        // 我们放开过 → 关闭界面时还给游戏
        assertEquals(ForeignScreenGuard.MouseAction.GRAB,
                ForeignScreenGuard.decideMouseAction(false, true, false, true));
        // 游戏本来就没抓（例如在主菜单打开我们的界面）→ 关掉时什么都不做，
        // 否则会把主菜单的光标锁死
        assertEquals(ForeignScreenGuard.MouseAction.NONE,
                ForeignScreenGuard.decideMouseAction(false, true, false, false));
        // 没有鼠标面的版本（1.13 及更早）→ 一律不动，由 inGameHasFocus 那条路负责
        assertEquals(ForeignScreenGuard.MouseAction.NONE,
                ForeignScreenGuard.decideMouseAction(true, false, false, false));
    }
}
