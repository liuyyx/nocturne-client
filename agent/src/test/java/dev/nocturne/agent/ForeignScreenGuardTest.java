package dev.nocturne.agent;

import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.mapping.Mapping;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
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
    void restoresModernMouseEvenWhenGrabStateIsUnreadable() {
        assertEquals(ForeignScreenGuard.MouseAction.GRAB,
                ForeignScreenGuard.decideMouseAction(false, true, false, false, true, false));
        assertEquals(ForeignScreenGuard.MouseAction.NONE,
                ForeignScreenGuard.decideMouseAction(false, true, false, false, true, true));
        assertEquals(ForeignScreenGuard.MouseAction.NONE,
                ForeignScreenGuard.decideMouseAction(false, false, false, false, true, false),
                "没有 MouseHandler 的 legacy 版本仍不得调用现代鼠标 API");
    }

    @Test
    void restoresTheMouseOnlyWhenWeReleasedIt() {
        // 原生 Screen（暂停菜单/背包/聊天）开着时绝不能抓取：否则光标被锁进菜单。
        assertEquals(ForeignScreenGuard.MouseAction.NONE,
                ForeignScreenGuard.decideMouseAction(false, true, false, true, true));
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

    @Test
    void readsNativeScreenFromGuiWhenMinecraftFieldsAreAbsent() {
        Object panel = new Object();
        ScreenHost host = new ScreenHost();
        host.gui = new GuiHost(panel);
        ScreenHost.instance = host;
        try {
            ForeignScreenGuard guard = new ForeignScreenGuard(
                    new GameBridge(null, new HostMapping()), () -> false);
            assertSame(panel, guard.nativeScreen(host), "26.3 必须从 Gui.screen() 读取原生界面");
        } finally {
            ScreenHost.instance = null;
        }
    }

    public static final class ScreenHost {
        private static ScreenHost instance;
        public Object gui;

        public static ScreenHost getInstance() {
            return instance;
        }
    }

    public static final class GuiHost {
        private final Object screen;

        GuiHost(Object screen) {
            this.screen = screen;
        }

        public Object screen() {
            return screen;
        }
    }

    @Test
    void unreadableGrabStateReleasesAgainOnFollowingFrames() throws Exception {
        MouseHost host = new MouseHost();
        MouseHost.instance = host;
        try {
            ForeignScreenGuard guard = new ForeignScreenGuard(
                    new GameBridge(null, new MouseMapping()), () -> true);
            java.lang.reflect.Method apply = ForeignScreenGuard.class.getDeclaredMethod(
                    "applyGameInputSuppression", boolean.class);
            apply.setAccessible(true);
            apply.invoke(guard, true);
            apply.invoke(guard, true);
            assertEquals(2, host.mouseHandler.releases,
                    "抓取状态不可读时每个 GUI 帧都要重申释放");
        } finally {
            MouseHost.instance = null;
        }
    }

    public static final class MouseHost {
        private static MouseHost instance;
        private final MouseFace mouseHandler = new MouseFace();

        public static MouseHost getInstance() {
            return instance;
        }
    }

    public static final class MouseFace {
        private int releases;

        public void releaseMouse() {
            releases++;
        }
    }

    private static final class MouseMapping implements Mapping {
        @Override
        public String describe() {
            return "mouse-release-test";
        }

        @Override
        public String className(ClassType type) {
            return type == ClassType.MINECRAFT ? MouseHost.class.getName() : type.canonicalName();
        }

        @Override
        public String methodName(ClassType owner, String canonicalName, String descriptor) {
            return canonicalName;
        }

        @Override
        public String fieldName(ClassType owner, String canonicalName) {
            return canonicalName;
        }

        @Override
        public boolean isIdentity() {
            return false;
        }
    }

    private static final class HostMapping implements Mapping {
        @Override
        public String describe() {
            return "gui-screen-host";
        }

        @Override
        public String className(ClassType type) {
            return type == ClassType.MINECRAFT ? ScreenHost.class.getName() : type.canonicalName();
        }

        @Override
        public String methodName(ClassType owner, String canonicalName, String descriptor) {
            return canonicalName;
        }

        @Override
        public String fieldName(ClassType owner, String canonicalName) {
            return canonicalName;
        }

        @Override
        public boolean isIdentity() {
            return false;
        }
    }
}
