package dev.nocturne.agent;

import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.runtime.FrameListener;

import java.util.Locale;
import java.util.function.BooleanSupplier;

/**
 * 我们的界面打开时，顶掉"已知外部客户端"用 Minecraft 自己的 screen 机制弹出来的界面。
 *
 * <p>为什么需要它：我们的界面是画在帧上的叠加层，不占用 {@code Minecraft.currentScreen}；
 * 而 FPSMaster Edge 这类客户端把它的 ClickGUI 做成真正的 {@code GuiScreen}
 * （其 {@code ClientSettings.keyBind} 默认同样是右 Shift）。结果是按一次键两边都开：它的面板
 * 是真 screen，会盖在我们上面并吃掉输入，看起来就像"我们的注入没生效"。
 *
 * <p>对照实现：Vape 的界面本身就是 {@code GuiScreen}，一打开就把别人的面板顶掉了——这里用
 * 「每帧在我们界面打开期间检查 currentScreen 并关闭已知外部界面」达到同样效果，代价是要多
 * 看一眼当前 screen 的类名。
 *
 * <p>只在<b>我们界面打开期间</b>生效：玩家单独使用对方客户端时不受影响。任何异常都被吞掉——
 * 这个回调每帧都在跑，绝不能因为它影响游戏。
 */
final class ForeignScreenGuard implements FrameListener {

    /** 已知会自己弹 {@code GuiScreen} 的客户端包名前缀（小写比较）。 */
    private static final String[] FOREIGN_PACKAGES = {
            "top.fpsmaster.",
    };

    /** 读游戏状态与调用 {@code displayGuiScreen} 用的桥。 */
    private final GameBridge bridge;

    /** 我们的界面是否打开（由 UI 侧提供，避免这里再引一套界面状态）。 */
    private final BooleanSupplier guiOpen;

    /** 只记一次"顶替了谁"的日志：每帧都打会把游戏日志刷爆。 */
    private boolean loggedOnce;

    /**
     * @param bridge  游戏桥（必须已可用；未解析时本守护什么都不做）
     * @param guiOpen 返回"我们的界面是否打开"的判定
     */
    ForeignScreenGuard(GameBridge bridge, BooleanSupplier guiOpen) {
        this.bridge = bridge;
        this.guiOpen = guiOpen;
    }

    /**
     * 判断某个界面类是否属于"已知会自己弹 screen 的外部客户端"。
     *
     * @param className 界面的类名（{@code screen.getClass().getName()}），可为 {@code null}
     * @return 命中已知前缀时返回 {@code true}
     */
    static boolean isForeignScreen(String className) {
        if (className == null) {
            return false;
        }
        String name = className.toLowerCase(Locale.ROOT);
        for (String prefix : FOREIGN_PACKAGES) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void onFrame() {
        try {
            if (!guiOpen.getAsBoolean()) {
                return;
            }
            Object minecraft = bridge.minecraft();
            if (minecraft == null) {
                return;
            }
            Object screen = bridge.readField(minecraft, ClassType.MINECRAFT, "currentScreen");
            if (screen == null) {
                return;
            }
            Class<?> screenClass = screen.getClass();
            if (!isForeignScreen(screenClass.getName())) {
                return;
            }
            bridge.callMapped(minecraft, ClassType.MINECRAFT, "displayGuiScreen",
                    new Object[]{null});
            if (!loggedOnce) {
                loggedOnce = true;
                System.out.println("[nocturne] suppressed foreign screen while our GUI is open: "
                        + screenClass.getName());
            }
        } catch (Throwable ignored) {
            // 每帧回调：这里的任何问题都不该影响游戏。
        }
    }
}
