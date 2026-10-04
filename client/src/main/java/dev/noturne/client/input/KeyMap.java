package dev.noturne.client.input;

/**
 * 把注入器与 UI 之间约定的“规范键码”翻译成游戏输入后端真正认识的键码。
 *
 * <p><b>为什么需要它</b>：{@code core} 通过 agent options 传给 {@code guiKey} 的只是<b>一个数字</b>，
 * 而同一个物理键在不同输入栈里编号毫无关系——右 Shift 在 LWJGL2 是 54、在 GLFW 是 344，字母 R
 * 在 LWJGL2 是 19、在 GLFW 是 82。若不翻译，1.8.9 会拿到 344（LWJGL2 {@code Keyboard.isKeyDown}
 * 的合法上界是 289，越界恒为 false），而 1.13+ 会拿到 19（对应的是别的键）。翻译表必须同时覆盖
 * 两代，才能让同一份配置在所有版本上生效。
 *
 * <p><b>规范键码空间（本表输入的约定）</b>：普通按键一律采用 {@code java.awt.event.KeyEvent.VK_*}
 * 的数值（字母 A–Z 为 65–90、数字 0–9 为 48–57、方向键 37–40、F1–F12 为 112–123 等），因为
 * 注入器侧的按键录制正是 Swing {@code KeyEvent}，其 {@code getKeyCode()} 天然产出这一组值。
 * 对 AWT 无法区分左右、却又必须区分的修饰键，使用本类公开的扩展常量：
 * <ul>
 *   <li>左/通用 Shift = 16，右 Shift = {@link #VK_RIGHT_SHIFT}（54，跨任务契约 K1 的默认开关按键）</li>
 *   <li>左/通用 Ctrl = 17，右 Ctrl = 157</li>
 *   <li>左/通用 Alt = 18，右 Alt = 184</li>
 *   <li>左/通用 Win = 524，右 Win = 220</li>
 * </ul>
 *
 * <p><b>已知限制</b>：主键盘数字键 {@code 6} 的 AWT VK 恰好也是 54，与右 Shift 冲突。本表按契约
 * 把 54 解析为右 Shift，因此数字 {@code 6} 无法作为绑定键（其余数字键不受影响）。这是 K1 固定
 * “右 Shift = 54”带来的固有取舍，改动它需先修改跨任务契约。
 *
 * <p>表值来源（均为可复核的真实常量的直接拷贝）：LWJGL2 {@code org.lwjgl.input.Keyboard}
 * （2.9.4，{@code .minecraft/libraries/.../lwjgl-2.9.4-nightly-20150209.jar}）、LWJGL3
 * {@code org.lwjgl.glfw.GLFW}（3.3.x）、LWJGL3 {@code org.lwjgl.sdl.SDLScancode}（3.4.3，
 * 26.3 输入栈已由 GLFW 换成 SDL，{@code .minecraft/libraries/.../lwjgl-sdl-3.4.3.jar}）与 JDK
 * {@code java.awt.event.KeyEvent}。
 *
 * <p>SDL 侧的值不是 GLFW 的按键码（{@code SDL_Keycode}），而是 <b>scancode</b>：26.3 的
 * {@code SDL_GetKeyboardState()} 返回的 {@code ByteBuffer} 正是按 scancode 索引的（容量
 * {@code SDL_SCANCODE_COUNT=512}），与 {@code SDL_Keycode} 索引互不通用。
 */
public final class KeyMap {

    /** K1 约定的开关默认键：右 Shift（AWT 侧无独立常量，取 LWJGL2 的 54）。 */
    public static final int VK_RIGHT_SHIFT = 54;

    /** 左侧/通用 Shift（等于 {@code KeyEvent.VK_SHIFT}）。 */
    public static final int VK_LEFT_SHIFT = 16;
    /** 左侧/通用 Control（等于 {@code KeyEvent.VK_CONTROL}）。 */
    public static final int VK_LEFT_CONTROL = 17;
    /** 右侧 Control（AWT 无法区分，取 LWJGL2 的 RCTRL）。 */
    public static final int VK_RIGHT_CONTROL = 157;
    /** 左侧/通用 Alt（等于 {@code KeyEvent.VK_ALT}）。 */
    public static final int VK_LEFT_ALT = 18;
    /** 右侧 Alt（AWT 无法区分，取 LWJGL2 的 RMENU）。 */
    public static final int VK_RIGHT_ALT = 184;
    /** 右侧 Win（AWT 无法区分，取 LWJGL2 的 RWIN）。 */
    public static final int VK_RIGHT_WIN = 220;
    /** AltGr（等于 {@code KeyEvent.VK_ALT_GRAPH}）。 */
    public static final int VK_ALT_GRAPH = 65406;

    /** 无法翻译时的返回值；调用方据此把该键视为“永不按下”。 */
    public static final int NONE = -1;

    /** 规范键码，升序排列，供二分查找。 */
    private static final int[] CANONICAL = {
            8, 9, 10, 16, 17, 18, 19, 20, 27, 32,
            33, 34, 35, 36, 37, 38, 39, 40, 44, 45,
            46, 47, 48, 49, 50, 51, 52, 53, 54, 55,
            56, 57, 59, 61, 65, 66, 67, 68, 69, 70,
            71, 72, 73, 74, 75, 76, 77, 78, 79, 80,
            81, 82, 83, 84, 85, 86, 87, 88, 89, 90,
            91, 92, 93, 96, 97, 98, 99, 100, 101, 102,
            103, 104, 105, 106, 107, 109, 110, 111, 112, 113,
            114, 115, 116, 117, 118, 119, 120, 121, 122, 123,
            127, 144, 145, 154, 155, 157, 184, 192, 220, 222,
            524, 525, 61440, 61441, 61442, 61443, 61444, 61445, 61446, 65406,
    };

    /** 与 {@link #CANONICAL} 一一对应的 LWJGL2 键码；无对应项时为 {@link #NONE}。 */
    private static final int[] LWJGL2 = {
            14, 15, 28, 42, 29, 56, 197, 58, 1, 57,
            201, 209, 207, 199, 203, 200, 205, 208, 51, 12,
            52, 53, 11, 2, 3, 4, 5, 6, 54, 8,
            9, 10, 39, 13, 30, 48, 46, 32, 18, 33,
            34, 35, 23, 36, 37, 38, 50, 49, 24, 25,
            16, 19, 31, 20, 22, 47, 17, 45, 21, 44,
            26, 43, 27, 82, 79, 80, 81, 75, 76, 77,
            71, 72, 73, 55, 78, 74, 83, 181, 59, 60,
            61, 62, 63, 64, 65, 66, 67, 68, 87, 88,
            211, 69, 70, 183, 210, 157, 184, 41, 220, 40,
            219, 221, 100, 101, 102, 103, 104, 105, 113, 184,
    };

    /** 与 {@link #CANONICAL} 一一对应的 GLFW 键码；无对应项时为 {@link #NONE}。 */
    private static final int[] GLFW = {
            259, 258, 257, 340, 341, 342, 284, 280, 256, 32,
            266, 267, 269, 268, 263, 265, 262, 264, 44, 45,
            46, 47, 48, 49, 50, 51, 52, 53, 344, 55,
            56, 57, 59, 61, 65, 66, 67, 68, 69, 70,
            71, 72, 73, 74, 75, 76, 77, 78, 79, 80,
            81, 82, 83, 84, 85, 86, 87, 88, 89, 90,
            91, 92, 93, 320, 321, 322, 323, 324, 325, 326,
            327, 328, 329, 332, 334, 333, 330, 331, 290, 291,
            292, 293, 294, 295, 296, 297, 298, 299, 300, 301,
            261, 282, 281, 283, 260, 345, 346, 96, 347, 39,
            343, 348, 302, 303, 304, 305, 306, 307, 308, 346,
    };

    /** 工具类，禁止实例化。 */
    private KeyMap() {
    }

    /**
     * 把规范键码翻译为 LWJGL2 {@code Keyboard.isKeyDown(int)} 可用的键码。
     *
     * @param vk 规范键码
     * @return LWJGL2 键码；无对应项时返回 {@link #NONE}
     */
    public static int lwjgl2(int vk) {
        return lookup(vk, LWJGL2);
    }

    /**
     * 把规范键码翻译为 GLFW {@code glfwGetKey} 可用的键码。
     *
     * @param vk 规范键码
     * @return GLFW 键码；无对应项时返回 {@link #NONE}
     */
    public static int glfw(int vk) {
        return lookup(vk, GLFW);
    }

    /** 在升序规范键码表中定位 {@code vk}，命中则返回对应后端表中的值。 */
    private static int lookup(int vk, int[] backend) {
        int index = java.util.Arrays.binarySearch(CANONICAL, vk);
        return index < 0 ? NONE : backend[index];
    }
}
