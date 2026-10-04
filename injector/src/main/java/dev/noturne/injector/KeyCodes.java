package dev.noturne.injector;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把设置里录制的快捷键名（{@code RSHIFT}、{@code R}、{@code F5}）换算成游戏输入后端使用的键码。
 *
 * <p><b>为什么必须分两套表</b>：Minecraft ≤1.12 走 LWJGL2 的 {@code Keyboard}，1.13+ 走 GLFW 的
 * {@code glfwGetKey}，同一个键在两代里的编号毫无关系——右 Shift 是 54 与 344，字母 R 是 19 与 82。
 * agent 只能按数字轮询，无法自行换算，所以由注入器按目标进程的版本挑好再传过去。
 *
 * <p>版本无法判定时（命令行读不到、是启动器实例名）按 GLFW 处理：现代版本占绝大多数，
 * 且猜错的代价只是快捷键不生效，不会影响注入本身。
 */
final class KeyCodes {

    /** agent 侧解析 GUI 开关按键所用的参数名前缀，两侧必须一致。 */
    static final String OPTION_GUI_KEY = "guiKey=";

    /** 无法识别键名时的兜底：GLFW 的右 Shift。 */
    static final int GLFW_FALLBACK = 344;
    /** 无法识别键名时的兜底：LWJGL2 的右 Shift。 */
    static final int LWJGL2_FALLBACK = 54;

    /** 从版本标签里取主次版本号，用于判断输入栈年代。 */
    private static final Pattern VERSION = Pattern.compile("(\\d+)\\.(\\d+)");

    /** GLFW（1.13+）键码表，键为去空格大写的键名。 */
    private static final Map<String, Integer> GLFW = new HashMap<String, Integer>();
    /** LWJGL2（≤1.12）键码表，键名约定与 {@link #GLFW} 相同。 */
    private static final Map<String, Integer> LWJGL2 = new HashMap<String, Integer>();

    static {
        // 字母：GLFW 直接用 ASCII 码，LWJGL2 是一组历史键码，必须逐个列出。
        String letters = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";
        int[] lwjglLetters = {
                30, 48, 46, 32, 18, 33, 34, 35, 23, 36, 37, 38, 50,
                49, 24, 25, 16, 19, 31, 20, 22, 47, 17, 45, 21, 44};
        for (int i = 0; i < letters.length(); i++) {
            put(String.valueOf(letters.charAt(i)), 65 + i, lwjglLetters[i]);
        }

        // 数字键：LWJGL2 的 0 排在 9 之后，不能按下标直接推。
        int[] lwjglDigits = {11, 2, 3, 4, 5, 6, 7, 8, 9, 10};
        for (int i = 0; i < 10; i++) {
            put(String.valueOf(i), 48 + i, lwjglDigits[i]);
        }

        // F1–F12：GLFW 连续，LWJGL2 在 F10 与 F11 之间断开。
        int[] lwjglFunction = {59, 60, 61, 62, 63, 64, 65, 66, 67, 68, 87, 88};
        for (int i = 0; i < lwjglFunction.length; i++) {
            put("F" + (i + 1), 290 + i, lwjglFunction[i]);
        }

        // 修饰键：左右必须分开，游戏把它们当作不同的绑定。
        put("LSHIFT", 340, 42);
        put("RSHIFT", 344, 54);
        put("LCTRL", 341, 29);
        put("RCTRL", 345, 157);
        put("LALT", 342, 56);
        put("RALT", 346, 184);
        put("LWIN", 343, 219);
        put("RWIN", 347, 220);

        // 编辑与导航键。
        put("SPACE", 32, 57);
        put("ENTER", 257, 28);
        put("TAB", 258, 15);
        put("ESCAPE", 256, 1);
        put("BACKSPACE", 259, 14);
        put("DELETE", 261, 211);
        put("INSERT", 260, 210);
        put("UP", 265, 200);
        put("DOWN", 264, 208);
        put("LEFT", 263, 203);
        put("RIGHT", 262, 205);
        put("PAGEUP", 266, 201);
        put("PAGEDOWN", 267, 209);
        put("HOME", 268, 199);
        put("END", 269, 207);

        // 锁定与系统键。
        put("CAPSLOCK", 280, 58);
        put("NUMLOCK", 282, 69);
        put("SCROLLLOCK", 281, 70);
        put("PRINTSCREEN", 283, 183);
        put("PAUSE", 284, 197);

        // 符号键：名字取自 {@code KeyEvent.getKeyText}，已去空格并大写。
        put("MINUS", 45, 12);
        put("EQUALS", 61, 13);
        put("OPENBRACKET", 91, 26);
        put("CLOSEBRACKET", 93, 27);
        put("BACKSLASH", 92, 43);
        put("SEMICOLON", 59, 39);
        put("QUOTE", 39, 40);
        put("BACKQUOTE", 96, 41);
        put("COMMA", 44, 51);
        put("PERIOD", 46, 52);
        put("SLASH", 47, 53);
    }

    /** 工具类，禁止实例化。 */
    private KeyCodes() {
    }

    /** 把同一个键名同时登记进两套表，避免两边键名写歪。 */
    private static void put(String name, int glfwCode, int lwjgl2Code) {
        GLFW.put(name, glfwCode);
        LWJGL2.put(name, lwjgl2Code);
    }

    /**
     * 把录制到的绑定换算成目标版本可用的键码。
     *
     * @param bind    录制得到的绑定名，可含修饰键前缀（如 {@code CTRL+F5}）；允许为 {@code null}
     * @param version 目标进程的版本标签（如 {@code 1.8.9}）；无法识别时按 GLFW 处理
     * @return 对应输入后端的键码；键名无法识别时返回该后端的右 Shift
     */
    static int codeFor(String bind, String version) {
        boolean legacy = usesLwjgl2(version);
        Integer code = (legacy ? LWJGL2 : GLFW).get(primaryKey(bind));
        if (code != null) {
            return code;
        }
        return legacy ? LWJGL2_FALLBACK : GLFW_FALLBACK;
    }

    /**
     * 组装 attach 时传给 agent 的选项串。
     *
     * <p>格式必须与 agent 侧的解析保持一致：{@code guiKey=<十进制键码>}，多项时以逗号分隔
     * （JDK attach 的 options 约定）。把它单独抽出来是为了让这个跨模块契约可被测试锁住——
     * 格式一旦漂移，快捷键会静默失效且难以察觉。
     *
     * @param bind    录制得到的绑定名；允许为 {@code null}
     * @param version 目标进程版本标签；无法识别时按 GLFW 处理
     * @return 形如 {@code guiKey=344} 的选项串
     */
    static String attachOptions(String bind, String version) {
        return OPTION_GUI_KEY + codeFor(bind, version);
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

    /**
     * 判断目标版本是否使用 LWJGL2 输入栈。
     *
     * @param version 版本标签；允许为 {@code null}
     * @return 主版本为 1 且次版本 ≤12 时返回 true；无法判定时返回 false（按 GLFW 处理）
     */
    static boolean usesLwjgl2(String version) {
        if (version == null) {
            return false;
        }
        Matcher matcher = VERSION.matcher(version);
        if (!matcher.find()) {
            return false;
        }
        try {
            int major = Integer.parseInt(matcher.group(1));
            int minor = Integer.parseInt(matcher.group(2));
            // 1.13 起 Minecraft 换用 LWJGL3/GLFW；26.x 之类的新式版本号同样属于 GLFW 一档。
            return major == 1 && minor <= 12;
        } catch (NumberFormatException malformed) {
            return false;
        }
    }
}
