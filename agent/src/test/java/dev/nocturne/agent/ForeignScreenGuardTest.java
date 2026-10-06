package dev.nocturne.agent;

import org.junit.jupiter.api.Test;

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
}
