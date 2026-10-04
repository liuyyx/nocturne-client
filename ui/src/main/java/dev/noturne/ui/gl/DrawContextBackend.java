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
    /** 文字绘制入口；解析失败为 {@code null}。 */
    private Method drawText;
    /** 游戏字体对象（class_327）；为 {@code null} 时文字退化为占位矩形。 */
    private Object font;

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
        // 文字绘制入口：签名是 drawText(TextRenderer, String, int, int, int[, boolean])，
        // 第一个参数是字体对象 —— 按「第二参为 String、第一参非 int」筛出来。
        Method drawText = null;
        for (Method method : drawContext.getClass().getMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (parameters.length >= 5 && parameters[1] == String.class
                    && parameters[0] != int.class && method.getReturnType() == void.class) {
                if (drawText == null) {
                    drawText = method;
                }
            }
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
        backend.drawText = drawText;
        backend.font = resolveFont(drawContext.getClass().getClassLoader());
        backend.update(drawContext);
        System.out.println("[noturne] draw context bound; fill=" + fill.getName()
                + "; drawText=" + (drawText == null ? "none" : drawText.getName())
                + "; font=" + (backend.font == null ? "none" : "ok"));
        return backend;
    }

    /**
     * 解析游戏字体对象：{@code Minecraft.getInstance().font}。
     *
     * <p>两者都是混淆名，因此先找 {@code class_310} 上「无参、返回自身、且为静态」的方法
     * （即 getInstance），再在实例字段里找类型为 {@code class_327} 的那个。
     *
     * @param loader 游戏类加载器
     * @return 字体对象；解析失败返回 {@code null}（文字退化为占位矩形）
     */
    private static Object resolveFont(ClassLoader loader) {
        try {
            Class<?> minecraftClass = Class.forName("net.minecraft.class_310", false, loader);
            Class<?> fontClass = Class.forName("net.minecraft.class_327", false, loader);
            for (Method method : minecraftClass.getMethods()) {
                if (method.getParameterCount() != 0 || method.getReturnType() != minecraftClass
                        || !java.lang.reflect.Modifier.isStatic(method.getModifiers())) {
                    continue;
                }
                Object instance = method.invoke(null);
                if (instance == null) {
                    continue;
                }
                for (java.lang.reflect.Field field : minecraftClass.getDeclaredFields()) {
                    if (field.getType() != fontClass) {
                        continue;
                    }
                    field.setAccessible(true);
                    Object font = field.get(instance);
                    if (font != null) {
                        return font;
                    }
                }
            }
        } catch (Throwable t) {
            System.out.println("[noturne] font resolve failed: " + t);
        }
        return null;
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
        float r = Math.min(radius, Math.min(width, height) / 2f);
        if (r <= 1f) {
            rect(x, y, width, height, color);
            return;
        }
        // DrawContext 没有圆角原语：用若干条横向矩形按圆弧内缩近似四角。
        // 层数取半径的整数像素数（UI 尺寸下最多 8 层已经看不出台阶），
        // 相邻层多铺 0.5px 以避免出现缝隙。
        int steps = Math.max(2, Math.min(8, Math.round(r)));
        float band = r / steps;
        for (int i = 0; i < steps; i++) {
            float dy = i * band;
            float inset = cornerInset(r, dy);
            rect(x + inset, y + dy, width - 2f * inset, band + 0.5f, color);
        }
        // 中段：圆角之间是完整的矩形
        rect(x, y + r, width, height - 2f * r, color);
        // 下半部分与上半对称
        for (int i = 0; i < steps; i++) {
            float dy = i * band;
            float inset = cornerInset(r, dy);
            rect(x + inset, y + height - r + dy, width - 2f * inset, band + 0.5f, color);
        }
    }

    /**
     * 计算圆角在距顶部 {@code dy} 处的水平内缩量。
     *
     * @param radius 圆角半径
     * @param dy     距该圆角起始边的距离，取值 {@code [0, radius]}
     * @return 该行两端应内缩的像素数
     */
    private static float cornerInset(float radius, float dy) {
        float d = radius - dy;
        return radius - (float) Math.sqrt(Math.max(0f, radius * radius - d * d));
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
        if (value == null || value.isEmpty() || drawContext == null) {
            return;
        }
        if (drawText != null && font != null) {
            // 签名是 (font, text, x, y, color[, shadow])：坐标取整、颜色用 ARGB。
            if (drawText.getParameterCount() >= 6) {
                Reflect.call(drawText, drawContext, font, value,
                        Math.round(x), Math.round(y), color.packed(), false);
            } else {
                Reflect.call(drawText, drawContext, font, value,
                        Math.round(x), Math.round(y), color.packed());
            }
            return;
        }
        // 字体没解析出来时退化为一条细矩形，至少让布局可见。
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
