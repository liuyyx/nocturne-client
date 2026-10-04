package dev.noturne.ui.gl;

import java.nio.IntBuffer;

/**
 * 只提供 <b>LWJGL2 形态</b>视口查询的 GL 替身：{@code glGetInteger(int, IntBuffer)}。
 *
 * <p>存在的意义：LWJGL2 的 {@code GL11} 没有 {@code glGetIntegerv(int, int[])}。若绑定层只认数组形态，
 * 1.8.9 上视口恒为 0x0，{@link GlRenderer#beginFrame()} 会据此调用非法的 {@code glOrtho(0,0,0,0)}
 * （GL_INVALID_VALUE），GUI 永久不可见。本替身让该回归可被单元测试抓住。
 */
public final class FakeGlBufferOnly {

    /** 模拟视口 {x, y, width, height}；测试可改写。 */
    public static int[] viewport = {0, 0, 1280, 720};

    /** 缓冲区形态的查询次数。 */
    public static int bufferQueryCalls;

    /** 裁剪调用次数。 */
    public static int scissorCalls;

    private FakeGlBufferOnly() {
    }

    /** 重置状态。 */
    public static void reset() {
        viewport = new int[]{0, 0, 1280, 720};
        bufferQueryCalls = 0;
        scissorCalls = 0;
    }

    // ---- GlApi.bind 要求的最小句柄集合 ----

    /** 记录一次颜色设置。 */
    public static void glColor4f(float r, float g, float b, float a) {
    }

    /** 记录一次图元开始。 */
    public static void glBegin(int mode) {
    }

    /** 记录一次图元结束。 */
    public static void glEnd() {
    }

    /** 记录一个顶点。 */
    public static void glVertex2f(float x, float y) {
    }

    // ---- LWJGL2 形态的视口查询 ----

    /**
     * 把 {@link #viewport} 写进 {@code out}（模拟 LWJGL2 的 {@code glGetInteger(int, IntBuffer)}）。
     *
     * @param target GL 枚举；本替身只处理 {@link GlApi#GL_VIEWPORT}
     * @param out    目标缓冲，容量不足时不写入（与 LWJGL2 的 checkBuffer 行为一致的失败面）
     */
    public static void glGetInteger(int target, IntBuffer out) {
        bufferQueryCalls++;
        if (target != GlApi.GL_VIEWPORT || out == null || out.remaining() < 4) {
            return;
        }
        out.put(0, viewport[0]);
        out.put(1, viewport[1]);
        out.put(2, viewport[2]);
        out.put(3, viewport[3]);
    }

    /** 记录一次裁剪矩形设置。 */
    public static void glScissor(int x, int y, int width, int height) {
        scissorCalls++;
    }
}
