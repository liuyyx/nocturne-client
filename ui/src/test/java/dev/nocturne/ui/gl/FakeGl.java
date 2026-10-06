package dev.nocturne.ui.gl;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * {@code org.lwjgl.opengl.GL11} 的替身类：既记录调用次数与最后一次参数，也记录
 * <b>有序</b>的调用事件、每类开关的最终状态与矩阵操作落在哪个矩阵模式，不做任何真实绘制，
 * 使 {@link GlApi} 与 {@link GlRenderer} 可在无 GL 上下文的单元测试中运行。
 *
 * <p>只计数不足以发现问题：把 {@code glOrtho} 挪到 {@code GL_MODELVIEW} 之后、或让
 * {@code popClip} 忘记关闭 {@code GL_SCISSOR_TEST}，旧替身都察觉不到。因此这里把
 * {@code glEnable}/{@code glDisable}/{@code glMatrixMode} 的调用顺序与当前模式一并留下。
 */
public final class FakeGl {

    /** {@code glColor4f} 调用次数 */
    public static int colorCalls;
    /** {@code glBegin} 调用次数 */
    public static int beginCalls;
    /** {@code glEnd} 调用次数 */
    public static int endCalls;
    /** {@code glVertex2f} 调用次数 */
    public static int vertexCalls;
    /** {@code glLineWidth} 调用次数 */
    public static int lineWidthCalls;
    /** 最后一次 {@code glBegin} 的图元模式；重置后为 -1 表示尚未绘制 */
    public static int lastBeginMode = -1;
    /** 最后一次 {@code glLineWidth} 的线宽；重置后为 -1 表示尚未设置 */
    public static float lastLineWidth = -1f;
    /** {@code glMatrixMode} 调用次数 */
    public static int matrixModeCalls;
    /** 最后一次 {@code glMatrixMode} 的目标；重置后为 -1 */
    public static int lastMatrixMode = -1;
    /** {@code glLoadIdentity} 调用次数 */
    public static int loadIdentityCalls;
    /** {@code glOrtho} 调用次数 */
    public static int orthoCalls;
    /** 最后一次 {@code glOrtho} 的边界参数（left, right, bottom, top, near, far）；未调用为 null */
    public static double[] lastOrtho;
    /** {@code glGetIntegerv} 调用次数 */
    public static int getIntegervCalls;
    /** {@code glScissor} 调用次数 */
    public static int scissorCalls;
    /** 最后一次 {@code glScissor} 的参数（x, y, width, height）；未调用为 null */
    public static int[] lastScissor;
    /** 模拟的视口（x, y, width, height），默认 1920×1080；测试可改写以模拟其他分辨率 */
    public static int[] viewport = {0, 0, 1920, 1080};

    /** {@code glEnable} 总数 */
    public static int enableCalls;
    /** {@code glDisable} 总数 */
    public static int disableCalls;
    /** {@code glPushMatrix} 总数 */
    public static int pushMatrixCalls;
    /** {@code glPopMatrix} 总数 */
    public static int popMatrixCalls;
    /** {@code glTranslatef} 总数 */
    public static int translateCalls;
    /** {@code glScalef} 总数 */
    public static int scaleCalls;

    /**
     * 有序调用事件流，元素形如 {@code "enable:3089"}、{@code "matrixMode:5889"}、
     * {@code "ortho@5889"}（{@code @} 后缀表示该调用发生时所在的矩阵模式）。
     */
    public static final List<String> events = new ArrayList<String>();
    /** {@code glMatrixMode} 的有序序列；用来断言投影/模型视图切换的次序 */
    public static final List<Integer> matrixModeSequence = new ArrayList<Integer>();

    /** 每个能力被开启的次数 */
    private static final Map<Integer, Integer> capEnableCounts = new HashMap<Integer, Integer>();
    /** 每个能力被关闭的次数 */
    private static final Map<Integer, Integer> capDisableCounts = new HashMap<Integer, Integer>();
    /** 当前处于开启状态的能力集合（模拟真实 GL 状态） */
    private static final Set<Integer> enabledCaps = new HashSet<Integer>();
    /** 当前矩阵模式（由 {@code glMatrixMode} 维护），-1 表示尚未设置 */
    private static int currentMatrixMode = -1;

    /** 工具类，禁止实例化 */
    private FakeGl() {
    }

    /** 清空全部静态计数与参数记录；测试须在每个用例前调用以隔离断言范围 */
    public static void reset() {
        colorCalls = 0;
        beginCalls = 0;
        endCalls = 0;
        vertexCalls = 0;
        lineWidthCalls = 0;
        lastBeginMode = -1;
        lastLineWidth = -1f;
        matrixModeCalls = 0;
        lastMatrixMode = -1;
        loadIdentityCalls = 0;
        orthoCalls = 0;
        lastOrtho = null;
        getIntegervCalls = 0;
        scissorCalls = 0;
        lastScissor = null;
        viewport = new int[]{0, 0, 1920, 1080};

        enableCalls = 0;
        disableCalls = 0;
        pushMatrixCalls = 0;
        popMatrixCalls = 0;
        translateCalls = 0;
        scaleCalls = 0;
        events.clear();
        matrixModeSequence.clear();
        capEnableCounts.clear();
        capDisableCounts.clear();
        enabledCaps.clear();
        currentMatrixMode = -1;
    }

    /** 清空仅与裁剪相关的观察，便于 beginFrame 之后单独断言 pushClip 的行为 */
    public static void resetScissorObservations() {
        scissorCalls = 0;
        lastScissor = null;
    }

    // ---- 断言辅助 ----

    /** @return 指定能力当前是否处于开启状态（按 enable/disable 调用序累计） */
    public static boolean isCapEnabled(int cap) {
        return enabledCaps.contains(cap);
    }

    /** @return 指定能力被 {@code glEnable} 的次数 */
    public static int enableCount(int cap) {
        Integer n = capEnableCounts.get(cap);
        return n == null ? 0 : n;
    }

    /** @return 指定能力被 {@code glDisable} 的次数 */
    public static int disableCount(int cap) {
        Integer n = capDisableCounts.get(cap);
        return n == null ? 0 : n;
    }

    /** @return 事件流中从 {@code from} 起首次出现 {@code event} 的下标；未出现返回 -1 */
    public static int indexOf(String event, int from) {
        for (int i = Math.max(0, from); i < events.size(); i++) {
            if (events.get(i).equals(event)) {
                return i;
            }
        }
        return -1;
    }

    /** @return 事件流中是否包含完全等于 {@code event} 的事件 */
    public static boolean hasEvent(String event) {
        return indexOf(event, 0) >= 0;
    }

    /** @return 以 {@code prefix} 开头的事件下标列表（按出现顺序） */
    public static List<Integer> indicesMatchingPrefix(String prefix) {
        List<Integer> out = new ArrayList<Integer>();
        for (int i = 0; i < events.size(); i++) {
            if (events.get(i).startsWith(prefix)) {
                out.add(i);
            }
        }
        return out;
    }

    /** @return 事件 {@code "@<mode>"} 后缀里记录的矩阵模式；无后缀时返回 Integer.MIN_VALUE */
    public static int modeOfEvent(String event) {
        int at = event.indexOf('@');
        if (at < 0) {
            return Integer.MIN_VALUE;
        }
        try {
            return Integer.parseInt(event.substring(at + 1));
        } catch (NumberFormatException e) {
            return Integer.MIN_VALUE;
        }
    }

    // ---- 立即模式与状态记录 ----

    /** 记录一次颜色设置 */
    public static void glColor4f(float r, float g, float b, float a) {
        colorCalls++;
    }

    /**
     * 记录一次图元开始。
     *
     * @param mode 图元模式，取值应为 {@link GlApi} 中的 GL 常量
     */
    public static void glBegin(int mode) {
        beginCalls++;
        lastBeginMode = mode;
    }

    /** 记录一次图元结束 */
    public static void glEnd() {
        endCalls++;
    }

    /** 记录一个顶点 */
    public static void glVertex2f(float x, float y) {
        vertexCalls++;
    }

    /**
     * 记录一次线宽设置。
     *
     * @param width 线宽
     */
    public static void glLineWidth(float width) {
        lineWidthCalls++;
        lastLineWidth = width;
    }

    /** 记录一次矩阵栈切换，并记住当前模式供后续矩阵操作归属。 */
    public static void glMatrixMode(int mode) {
        matrixModeCalls++;
        lastMatrixMode = mode;
        currentMatrixMode = mode;
        matrixModeSequence.add(mode);
        events.add("matrixMode:" + mode);
    }

    /** 记录一次矩阵重置，并标注其发生所在模式。 */
    public static void glLoadIdentity() {
        loadIdentityCalls++;
        events.add("loadIdentity@" + currentMatrixMode);
    }

    /**
     * 记录一次正交投影设置，并标注其发生所在模式。
     *
     * @param left,right,bottom,top,near,far 视景体边界，按 OpenGL 的调用顺序保存
     */
    public static void glOrtho(double left, double right, double bottom, double top,
                               double near, double far) {
        orthoCalls++;
        lastOrtho = new double[]{left, right, bottom, top, near, far};
        events.add("ortho@" + currentMatrixMode);
    }

    /**
     * 查询视口，把 {@link #viewport} 拷进 {@code out}。
     *
     * <p>替身不做真实 GL 查询：测试通过改写 {@code viewport} 来模拟不同分辨率。
     */
    public static void glGetIntegerv(int target, int[] out) {
        getIntegervCalls++;
        if (out != null && out.length >= 4) {
            System.arraycopy(viewport, 0, out, 0, 4);
        }
    }

    /** 记录一次裁剪矩形设置 */
    public static void glScissor(int x, int y, int width, int height) {
        scissorCalls++;
        lastScissor = new int[]{x, y, width, height};
        events.add("scissor:" + x + "," + y + "," + width + "," + height);
    }

    /** 开启某个 GL 能力，并更新状态记录。 */
    public static void glEnable(int cap) {
        enableCalls++;
        capEnableCounts.put(cap, enableCount(cap) + 1);
        enabledCaps.add(cap);
        events.add("enable:" + cap);
    }

    /** 关闭某个 GL 能力，并更新状态记录；不得再是空实现，否则 popClip 的裁剪泄漏不可见。 */
    public static void glDisable(int cap) {
        disableCalls++;
        capDisableCounts.put(cap, disableCount(cap) + 1);
        enabledCaps.remove(cap);
        events.add("disable:" + cap);
    }

    /** 设置混合函数；记录事件以便断言调用顺序。 */
    public static void glBlendFunc(int src, int dst) {
        events.add("blendFunc:" + src + "," + dst);
    }

    /** 压入矩阵栈；记录当时所处模式。 */
    public static void glPushMatrix() {
        pushMatrixCalls++;
        events.add("pushMatrix@" + currentMatrixMode);
    }

    /** 弹出矩阵栈；记录当时所处模式。 */
    public static void glPopMatrix() {
        popMatrixCalls++;
        events.add("popMatrix@" + currentMatrixMode);
    }

    /** 平移；记录当时所处模式。 */
    public static void glTranslatef(float x, float y, float z) {
        translateCalls++;
        events.add("translate@" + currentMatrixMode);
    }

    /** 缩放；记录当时所处模式。 */
    public static void glScalef(float x, float y, float z) {
        scaleCalls++;
        events.add("scale@" + currentMatrixMode);
    }

    /** 设置纹理坐标；无操作 */
    public static void glTexCoord2f(float u, float v) {
    }

    /** 绑定纹理；无操作 */
    public static void glBindTexture(int target, int texture) {
    }
}
