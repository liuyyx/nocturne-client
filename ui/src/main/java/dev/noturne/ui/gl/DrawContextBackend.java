package dev.noturne.ui.gl;

import dev.noturne.client.game.Reflect;
import dev.noturne.ui.render.Color;

import java.lang.reflect.Method;

/**
 * 用 Minecraft 自己的 {@code DrawContext} 绘制 UI 的后端。
 *
 * <p>MC 1.21.9+ 把渲染改成了自己的管线（RenderPipeline / GpuBuffer）：直接发 GL 调用会被管线的
 * 状态与提交顺序覆盖，画面上什么都不会留下。模组路径下唯一可靠的做法是让 MC 自己提交绘制，
 * 也就是调用 {@code DrawContext} 的填充方法。
 *
 * <p>类名与方法名在运行时都是 intermediary（{@code class_332} / {@code method_25294} …），
 * 因此全部按签名反射解析：矩形填充是唯一一个「五个 int 参数」的方法；缩放后的宽高则是两个
 * 无参 int getter（横屏下较大者为宽，声明顺序不保证）。
 *
 * <p>文字渲染暂未接入（需要 MC 的字体 API），当前只保证图形部分可见。
 */
public final class DrawContextBackend implements UiBackend {

    /** 每帧由帧驱动同步进来的 DrawContext 实例。 */
    private Object drawContext;
    /** 矩形填充：五参 int，返回 void。 */
    private final Method fill;
    /** 缩放尺寸的两个无参 int getter。 */
    private final Method sizeA;
    private final Method sizeB;

    /** 当前缩放后的绘制区域宽度。 */
    private int width;
    /** 当前缩放后的绘制区域高度。 */
    private int height;

    private DrawContextBackend(Method fill, Method sizeA, Method sizeB) {
        this.fill = fill;
        this.sizeA = sizeA;
        this.sizeB = sizeB;
    }

    /**
     * 从一个 DrawContext 实例上解析所需方法。
     *
     * @param drawContext 本帧的 DrawContext 实例
     * @return 可用的后端；签名解析失败时返回 {@code null}（调用方应保持原后端）
     */
    public static DrawContextBackend bind(Object drawContext) {
        if (drawContext == null) {
            return null;
        }
        Method candidateA = null;
        Method candidateB = null;
        Method first = null;
        Method second = null;
        for (Method method : drawContext.getClass().getMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (parameters.length == 5 && allInts(parameters)
                    && method.getReturnType() == void.class) {
                if (candidateA == null) {
                    candidateA = method;
                } else if (candidateB == null) {
                    candidateB = method;
                }
                continue;
            }
            if (parameters.length == 0 && method.getReturnType() == int.class
                    && !"hashCode".equals(method.getName())) {
                if (first == null) {
                    first = method;
                } else if (second == null) {
                    second = method;
                }
            }
        }
        // 五参 int 的候选有两个（实心填充与画边框），签名完全相同、无法从反射区分。
        // 实测：列表里第一个只画线框，第二个才是实心填充，因此优先取第二个。
        Method fill = candidateB != null ? candidateB : candidateA;
        if (fill == null || first == null || second == null) {
            return null;
        }
        try {
            fill.setAccessible(true);
            first.setAccessible(true);
            second.setAccessible(true);
        } catch (Throwable ignored) {
            // 拿不到访问权限就视为不可用，交由调用方回退。
            return null;
        }
        DrawContextBackend backend = new DrawContextBackend(fill, first, second);
        backend.update(drawContext);
        return backend;
    }

    /** @return 参数类型是否全为 {@code int} */
    private static boolean allInts(Class<?>[] types) {
        for (Class<?> type : types) {
            if (type != int.class) {
                return false;
            }
        }
        return true;
    }

    /**
     * 每帧同步 DrawContext 实例与缩放尺寸。
     *
     * @param drawContext 本帧的 DrawContext 实例
     */
    public void update(Object drawContext) {
        this.drawContext = drawContext;
        Object a = Reflect.call(sizeA, drawContext);
        Object b = Reflect.call(sizeB, drawContext);
        if (a instanceof Number && b instanceof Number) {
            int x = ((Number) a).intValue();
            int y = ((Number) b).intValue();
            width = Math.max(x, y);
            height = Math.min(x, y);
        }
    }

    @Override
    public String backendName() {
        return "mc-draw-context";
    }

    @Override
    public int width() {
        return width;
    }

    @Override
    public int height() {
        return height;
    }

    @Override
    public void beginFrame() {
        // DrawContext 已经处在 MC 的渲染流程里，不需要额外的状态准备。
    }

    @Override
    public void endFrame() {
        // 同上：不持有需要释放的资源。
    }

    @Override
    public void rect(float x, float y, float width, float height, Color color) {
        if (drawContext == null || color == null || width <= 0f || height <= 0f) {
            return;
        }
        // DrawContext 的填充接口收的是「左上 + 右下」两对坐标。
        Reflect.call(fill, drawContext,
                Math.round(x), Math.round(y),
                Math.round(x + width), Math.round(y + height),
                color.packed());
    }

    @Override
    public void roundedRect(float x, float y, float width, float height, float radius, Color color) {
        // DrawContext 没有圆角原语；先用直角矩形保证内容可见，圆角留待后续近似。
        rect(x, y, width, height, color);
    }

    @Override
    public void outline(float x, float y, float width, float height, float lineWidth, Color color) {
        float t = Math.max(1f, lineWidth);
        rect(x, y, width, t, color);
        rect(x, y + height - t, width, t, color);
        rect(x, y + t, t, height - 2f * t, color);
        rect(x + width - t, y + t, t, height - 2f * t, color);
    }

    @Override
    public void text(String value, float x, float y, float size, Color color) {
        // 文字需要 MC 的字体 API（尚未接入）：用一条细矩形占位，至少让布局可见。
        if (value == null || value.isEmpty()) {
            return;
        }
        rect(x, y + size * 0.75f, Math.min(value.length() * size * 0.5f, size * 8f), 1f, color);
    }

    @Override
    public float textWidth(String value, float size) {
        return value == null ? 0f : value.length() * size * 0.5f;
    }

    @Override
    public float textHeight(float size) {
        return size;
    }

    @Override
    public void pushClip(float x, float y, float width, float height) {
        // 裁剪需要确认 enableScissor 的签名，暂不实现（不影响整体可见性）。
    }

    @Override
    public void popClip() {
        // 见 pushClip。
    }
}
