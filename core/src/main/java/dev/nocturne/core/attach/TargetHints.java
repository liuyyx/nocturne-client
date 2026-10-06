package dev.nocturne.core.attach;

import java.io.File;
import java.util.Locale;

/**
 * 目标实例的**已知冲突**提示：只把"多半会踩到"的坑提前讲出来，不改变任何注入行为。
 *
 * <p>目前只有一条：FPSMaster Edge（Forge 1.8.9）把它的 ClickGUI 默认键定为
 * {@code Keyboard.KEY_RSHIFT}（见其 {@code ClientSettings.keyBind}），与我们的默认
 * {@code guiKey=54}（AWT 的 VK_RIGHT_SHIFT，在 LWJGL2 里同样是右 Shift）撞在同一个键上。
 * 结果是玩家按右 Shift 时两个客户端同时响应，画面上先看到的是 FPSMaster 的面板——
 * 看起来就像"我们的注入没生效"。
 *
 * <p>这类冲突无法在运行时消解（对方的键是它自己的配置），只能提示用户改键；
 * 因此这里返回一句可读的提示，由调用方打到日志里。
 */
public final class TargetHints {

    /** 已知默认占用右 Shift 开 GUI 的目标客户端：mod 文件名片段（小写）→ 展示名。 */
    private static final String[][] RIGHT_SHIFT_CLIENTS = {
            {"fpsmaster", "FPSMaster"},
    };

    /** 我们的默认 GUI 键（AWT VK_RIGHT_SHIFT）；只有用默认键时才谈得上冲突。 */
    public static final int DEFAULT_GUI_KEY_VK = 54;

    /** 工具类，禁止实例化。 */
    private TargetHints() {
    }

    /**
     * 检查目标实例的 mods 里是否有已知默认占用右 Shift 的客户端。
     *
     * @param commandLine 目标 JVM 的完整命令行（用于定位 {@code --gameDir}），可为 {@code null}
     * @param guiKeyVk    本次注入要传给 agent 的 GUI 开关键（AWT VK 码）
     * @return 冲突提示（可直接打印）；无冲突、或取不到实例目录时返回 {@code null}
     */
    public static String rightShiftConflict(String commandLine, int guiKeyVk) {
        if (guiKeyVk != DEFAULT_GUI_KEY_VK) {
            return null; // 已改键，不存在冲突
        }
        String gameDir = AgentOptions.gameDirectory(commandLine);
        if (gameDir == null || gameDir.isEmpty()) {
            return null;
        }
        File[] mods = new File(gameDir, "mods").listFiles();
        if (mods == null) {
            return null;
        }
        for (String[] client : RIGHT_SHIFT_CLIENTS) {
            for (File mod : mods) {
                if (mod.getName().toLowerCase(Locale.ROOT).contains(client[0])) {
                    return client[1] + " 的 ClickGUI 默认也绑在右 Shift 上，"
                            + "与本次的 guiKey=" + guiKeyVk + " 冲突：按右 Shift 会同时唤起它的面板，"
                            + "看起来像我们没生效。请在设置里把 GUI 键改成别的（例如 INSERT / 右 Ctrl）。";
                }
            }
        }
        return null;
    }
}
