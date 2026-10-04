package dev.noturne.injector;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * 快捷键名与 AWT 虚拟键码（{@code KeyEvent.VK_*}）的互查表。
 *
 * <p><b>为什么只发 VK 码</b>：按跨模块契约 K1，注入器写给 agent 的 {@code guiKey} 统一是 AWT VK 码，
 * 由运行时 {@code KeyMap} 再翻译成目标版本的后端键码（LWJGL2 或 GLFW）。因此这里不再需要按版本挑
 * 两套后端表——版本识别失败（命令行读不到、标题被截断）也不会再让 1.8.9 拿到 GLFW 键码。
 *
 * <p>表同时承担两个方向的任务：{@link #codeFor(String)}（名字 → VK，供 attach）与
 * {@link #nameForVk(int)}（VK → 名字，供 {@code SettingsDialog} 录制时渲染）。两者必须互相可解析，
 * 否则录到的符号键/小键盘键会在 {@link #codeFor(String)} 兜底成右 Shift。
 */
final class KeyCodes {

    /** agent 侧解析 GUI 开关按键所用的参数名前缀，两侧必须一致。 */
    static final String OPTION_GUI_KEY = "guiKey=";

    /**
     * 无法识别键名时的兜底：右 Shift。
     *
     * <p>取值 54 与 {@code KeyMap} 的约定一致（AWT 无法区分左右 Shift，右 Shift 用这个哨兵值表达）；
     * 绝不能返回 0，0 在后端里代表「没有这个键」。
     */
    static final int FALLBACK = 54;

    /** 名字 → AWT VK 码。 */
    private static final Map<String, Integer> NAME_TO_VK = new HashMap<String, Integer>();
    /** AWT VK 码 → 规范名字。 */
    private static final Map<Integer, String> VK_TO_NAME = new HashMap<Integer, String>();

    static {
        // 字母：AWT 与 ASCII 一致。
        String letters = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        for (int i = 0; i < letters.length(); i++) {
            put(String.valueOf(letters.charAt(i)), 65 + i);
        }
        // 数字主键区。
        for (int i = 0; i < 10; i++) {
            put(String.valueOf(i), 48 + i);
        }
        // F1–F24：AWT 从 112 起连续。
        for (int i = 0; i < 24; i++) {
            put("F" + (i + 1), 112 + i);
        }

        // 修饰键。AWT 无法区分左右 Shift/Ctrl/Alt/Windows，右半边沿用与 RSHIFT=54 相同的约定：
        // 用后端右变体的哨兵值，保证录制出的左右键能各自解析回一个唯一的数字。
        put("LSHIFT", 16);
        put("RSHIFT", FALLBACK);
        put("LCTRL", 17);
        put("RCTRL", 157);
        put("LALT", 18);
        put("RALT", 184);
        put("LWIN", 524);
        put("RWIN", 220);
        // 已知冲突：AWT 的 VK_6 恰好也是 54，与 RSHIFT 撞号，因此「数字 6」作为绑定会被当成右
        // Shift。这是 K1 锚定 RSHIFT=54 带来的限制，需改动 K1 才能消除；此处保持 RSHIFT 为 54。

        // 编辑与导航键。
        put("SPACE", 32);
        put("ENTER", 10);
        put("TAB", 9);
        put("ESCAPE", 27);
        put("BACKSPACE", 8);
        put("DELETE", 127);
        put("INSERT", 155);
        put("UP", 38);
        put("DOWN", 40);
        put("LEFT", 37);
        put("RIGHT", 39);
        put("PAGEUP", 33);
        put("PAGEDOWN", 34);
        put("HOME", 36);
        put("END", 35);

        // 锁定与系统键。
        put("CAPSLOCK", 20);
        put("NUMLOCK", 144);
        put("SCROLLLOCK", 145);
        put("PRINTSCREEN", 154);
        put("PAUSE", 19);

        // 符号键：名字即录制时 {@code baseKeyName} 生成的规范名（去空格大写）。
        put("MINUS", 45);
        put("EQUALS", 61);
        put("OPENBRACKET", 91);
        put("CLOSEBRACKET", 93);
        put("BACKSLASH", 92);
        put("SEMICOLON", 59);
        put("QUOTE", 222);
        put("BACKQUOTE", 192);
        put("COMMA", 44);
        put("PERIOD", 46);
        put("SLASH", 47);

        // 小键盘。注意 AWT 的 VK_NUMPAD0..9 是 96..105，与主键区数字完全不同。
        for (int i = 0; i < 10; i++) {
            put("NUMPAD" + i, 96 + i);
        }
        put("NUMPADMULTIPLY", 106);
        put("NUMPADADD", 107);
        put("NUMPADSEPARATOR", 108);
        put("NUMPADSUBTRACT", 109);
        put("NUMPADDECIMAL", 110);
        put("NUMPADDIVIDE", 111);
        // 小键盘回车与主回车共用 VK_ENTER(10)；录制侧按 location 区分名字，这里只登记正向别名，
        // 反向映射保留主键区的 ENTER。
        alias("NUMPADENTER", 10);
    }

    /** 工具类，禁止实例化。 */
    private KeyCodes() {
    }

    /** 双向登记一个键名：既进「名字 → VK」，也进「VK → 名字」。 */
    private static void put(String name, int vk) {
        NAME_TO_VK.put(name, vk);
        VK_TO_NAME.put(vk, name);
    }

    /** 只登记「名字 → VK」的正向别名，不动反向映射（用于与已有 VK 冲突的名字）。 */
    private static void alias(String name, int vk) {
        NAME_TO_VK.put(name, vk);
    }

    /**
     * 把录制到的绑定换算成 AWT VK 码。
     *
     * @param bind 录制得到的绑定名，可含修饰键前缀（如 {@code CTRL+F5}）；允许为 {@code null}
     * @return 对应 AWT VK 码；键名无法识别时返回 {@link #FALLBACK} 并打印日志
     */
    static int codeFor(String bind) {
        String name = primaryKey(bind);
        Integer vk = NAME_TO_VK.get(name);
        if (vk != null) {
            return vk;
        }
        // 录制侧对未知键会生成 "KEY<code>"，据此还原 VK，保证任意键都能往返。
        if (name.startsWith("KEY")) {
            try {
                return Integer.parseInt(name.substring(3));
            } catch (NumberFormatException malformed) {
                // 落到下面的兜底日志。
            }
        }
        System.err.println("[noturne] 无法识别的快捷键 '" + bind + "'，回退到右 Shift（VK " + FALLBACK + "）");
        return FALLBACK;
    }

    /**
     * 返回某个 AWT VK 码对应的规范键名，供录制时渲染。
     *
     * @param vk AWT 虚拟键码
     * @return 规范名（如 {@code RSHIFT}、{@code MINUS}、{@code NUMPAD0}）；表外键返回 {@code KEY<code>}
     */
    static String nameForVk(int vk) {
        String name = VK_TO_NAME.get(vk);
        return name != null ? name : "KEY" + vk;
    }

    /**
     * 组装传给 agent 的选项串。
     *
     * <p>格式必须与 agent 侧的解析保持一致（见 {@link dev.noturne.core.attach.AgentOptions}）：
     * {@code guiKey=<AWT VK>} 加上（版本可判定时的）{@code mcVersion=<版本族>}，多项以逗号分隔。
     * 版本由注入器判定并传进去，运行时因此不需要任何版本探测。
     *
     * @param bind       录制得到的绑定名；允许为 {@code null}
     * @param versionLabel 目标进程的版本标签（如 {@code 1.8.9优化}）；允许为 {@code null}
     * @return 形如 {@code guiKey=54,mcVersion=1.8.9} 的选项串
     */
    static String attachOptions(String bind, String versionLabel) {
        return dev.noturne.core.attach.AgentOptions.compose(
                codeFor(bind),
                dev.noturne.core.attach.AgentOptions.versionFamily(versionLabel));
    }

    /**
     * 取组合键里的主键名。
     *
     * <p>agent 每帧只轮询一个键，无法表达「Ctrl 按住的同时按 F」这类组合，因此这里保留最后一段
     * （即真正的触发键）并丢弃修饰键前缀，而不是让整串名字匹配失败、静默退回右 Shift。
     *
     * @param bind 绑定名；允许为 {@code null}
     * @return 去空格大写的键名，无内容时返回空串
     */
    static String primaryKey(String bind) {
        if (bind == null) {
            return "";
        }
        String upper = bind.trim().toUpperCase(Locale.ROOT);
        int plus = upper.lastIndexOf('+');
        return (plus >= 0 ? upper.substring(plus + 1) : upper).trim();
    }
}
