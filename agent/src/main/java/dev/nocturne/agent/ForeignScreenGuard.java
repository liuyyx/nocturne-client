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
            // 只在**游戏内**顶替外部界面。主菜单/加载界面也是外部客户端自己的 screen，
            // 但在那里把它关掉会让 MC 无界面可渲染（没有世界 → 直接白屏），
            // 而玩家的诉求只是"开了我们的界面时别看到对方的 ClickGUI"——那发生在游戏内。
            if (!bridge.inWorld()) {
                return;
            }
            Object minecraft = bridge.minecraft();
            if (minecraft == null) {
                return;
            }
            Object screen = bridge.readField(minecraft, ClassType.MINECRAFT, "currentScreen");
            if (screen == null) {
                // 形状兜底：Forge 等环境把字段名重映射成 SRG（currentScreen → field_71462_r），
                // 映射表里的原版混淆名（m）在那里不存在，按名字读永远是 null，
                // 于是这个守护什么都不做、外部客户端的面板照样盖在我们上面。
                screen = readScreenByShape(minecraft);
            }
            if (screen == null) {
                return;
            }
            Class<?> screenClass = screen.getClass();
            if (!isForeignScreen(screenClass.getName())) {
                return;
            }
            if (!closeScreen(minecraft)) {
                return;
            }
            if (!loggedOnce) {
                loggedOnce = true;
                System.out.println("[nocturne] suppressed foreign screen while our GUI is open: "
                        + screenClass.getName());
            }
        } catch (Throwable ignored) {
            // 每帧回调：这里的任何问题都不该影响游戏。
        }
    }

    /**
     * 按形状读当前 screen：非 static、类型名以 {@code Screen} 结尾的字段（1.8.9 的
     * {@code GuiScreen}、26.x 的 {@code Screen} 都命中）。
     */
    private static Object readScreenByShape(Object minecraft) {
        for (java.lang.reflect.Field field : minecraft.getClass().getDeclaredFields()) {
            if (java.lang.reflect.Modifier.isStatic(field.getModifiers())
                    || !field.getType().getSimpleName().endsWith("Screen")) {
                continue;
            }
            try {
                field.setAccessible(true);
                return field.get(minecraft);
            } catch (Throwable ignored) {
                return null;
            }
        }
        return null;
    }

    /**
     * 关闭当前 screen。
     *
     * <p>只按签名找 {@code (…Screen)V}——不用映射名：{@code displayGuiScreen} 在 Forge 下是
     * {@code func_71411_a}、在 26.x 是 {@code setScreen}，按名字查表只覆盖一种；而这个形状
     * （单参、参数是 Screen、返回 void）在各代都唯一。
     */
    private static boolean closeScreen(Object minecraft) {
        for (java.lang.reflect.Method method : minecraft.getClass().getMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (parameters.length != 1
                    || !parameters[0].getSimpleName().endsWith("Screen")
                    || method.getReturnType() != void.class) {
                continue;
            }
            try {
                method.setAccessible(true);
                method.invoke(minecraft, new Object[]{null});
                return true;
            } catch (Throwable ignored) {
                return false;
            }
        }
        return false;
    }
}
