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
 * <p>方法在运行时都是 intermediary 名，解析策略是「固定名优先 + 签名兜底」：
 * <ul>
 *   <li>填充 {@code method_25294(int,int,int,int,int)}（1.20–1.21 未变）</li>
 *   <li>文字 {@code method_25303}（无阴影）/ {@code method_51433}（带阴影），字体 {@code class_327}</li>
 *   <li>宽度度量 {@code class_327.method_1727(String) -> int}</li>
 *   <li>矩阵栈 {@code method_51448()} 返回 JOML 的 {@code Matrix3x2fStack}——公开类，方法名不混淆，
 *       文字缩放直接走 {@code pushMatrix / translate / scale / popMatrix}</li>
 *   <li>裁剪 {@code method_44379}（enableScissor）/ {@code method_44380}（disableScissor）</li>
 * </ul>
 * 所有探测失败都退化为「不绘制」或估算值，不影响其余功能。
 */
public final class DrawContextBackend implements UiBackend {

    /** MC 字体基准行高（像素）：字号按它换算为矩阵缩放系数。 */
    private static final float FONT_BASE_HEIGHT = 9f;

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
    /** 字体宽度度量；解析失败为 {@code null}，此时宽度退化为估算值。 */
    private Method fontWidth;
    /** 矩阵栈入口 {@code method_51448()}，返回 JOML 的 Matrix3x2fStack。 */
    private Method matrices;
    /** 矩阵栈的压栈 / 出栈 / 平移 / 缩放；全部来自 JOML 的公开 API。 */
    private Method matrixPush;
    private Method matrixPop;
    private Method matrixTranslate;
    private Method matrixScale;
    /** 裁剪压栈与出栈；两者必须成对解析成功才会启用。 */
    private Method enableScissor;
    private Method disableScissor;

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
     * @return 可用的后端；连填充与尺寸都无法解析时返回 {@code null}（调用方应保持原后端）
     */
    public static DrawContextBackend bind(Object drawContext) {
        if (drawContext == null) {
            return null;
        }
        Class<?> contextClass = drawContext.getClass();

        // 填充：固定名优先（intermediary 名跨 1.20–1.21 稳定），失败再按签名挑候选
        Method fill = Reflect.method(contextClass, "method_25294",
                int.class, int.class, int.class, int.class, int.class);
        if (fill == null) {
            fill = pickFill(contextClass);
        }

        // 缩放尺寸：两个无参 int getter（横屏下较大者为宽，声明顺序不保证）
        Method sizeA = null;
        Method sizeB = null;
        for (Method method : contextClass.getMethods()) {
            if (method.getParameterCount() == 0 && method.getReturnType() == int.class
                    && !"hashCode".equals(method.getName())) {
                if (sizeA == null) {
                    sizeA = method;
                } else if (sizeB == null) {
                    sizeB = method;
                }
            }
        }
        if (fill == null || sizeA == null || sizeB == null) {
            return null;
        }

        DrawContextBackend backend = new DrawContextBackend(fill, sizeA, sizeB);
        backend.drawText = resolveDrawText(contextClass);
        backend.font = resolveFont(contextClass.getClassLoader());
        backend.fontWidth = resolveFontWidth(backend.font);
        backend.resolveMatrixStack(contextClass);
        backend.resolveScissor(contextClass);
        backend.update(drawContext);
        System.out.println("[noturne] draw context bound; fill=" + fill.getName()
                + "; drawText=" + (backend.drawText == null ? "none" : backend.drawText.getName())
                + "; textScale=" + (backend.matrixScale == null ? "none" : "ok")
                + "; clip=" + (backend.enableScissor == null ? "none" : "ok")
                + "; font=" + (backend.font == null ? "none" : "ok")
                + "; fontWidth=" + (backend.fontWidth == null ? "none" : "ok"));
        return backend;
    }

    /**
     * 兜底：按签名挑选填充方法。
     *
     * <p>五参 int 的候选通常有两个（实心填充与画边框），签名完全相同、无法从反射区分。
     * 实测：列表里第一个只画线框，第二个才是实心填充，因此优先取第二个。
     */
    private static Method pickFill(Class<?> contextClass) {
        Method first = null;
        Method second = null;
        for (Method method : contextClass.getMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (parameters.length == 5 && allInts(parameters)
                    && method.getReturnType() == void.class) {
                if (first == null) {
                    first = method;
                } else if (second == null) {
                    second = method;
                }
            }
        }
        return second != null ? second : first;
    }

    /**
     * 解析文字绘制入口：签名是 {@code drawText(Font, String, int, int, int[, boolean])}，
     * 第一个参数是字体对象 —— 按「第二参为 String、第一参非 int」筛出来；优先无阴影的五参重载。
     */
    private static Method resolveDrawText(Class<?> contextClass) {
        Method five = null;
        Method six = null;
        for (Method method : contextClass.getMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (parameters.length >= 5 && parameters[1] == String.class
                    && parameters[0] != int.class && method.getReturnType() == void.class) {
                if (parameters.length == 5) {
                    if (five == null) {
                        five = method;
                    }
                } else if (six == null) {
                    six = method;
                }
            }
        }
        return five != null ? five : six;
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

    /** 解析字体宽度度量：固定名 {@code method_1727(String)} 优先，失败按 {@code (String) -> int} 兜底。 */
    private static Method resolveFontWidth(Object font) {
        if (font == null) {
            return null;
        }
        Method named = Reflect.method(font.getClass(), "method_1727", String.class);
        if (named != null) {
            return named;
        }
        for (Method method : font.getClass().getMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (parameters.length == 1 && parameters[0] == String.class
                    && method.getReturnType() == int.class) {
                return method;
            }
        }
        return null;
    }

    /**
     * 解析矩阵栈：{@code method_51448()} 返回 JOML 的 {@code Matrix3x2fStack}。
     *
     * <p>JOML 是公开库，{@code pushMatrix / popMatrix / translate / scale} 不混淆，可直接按名取；
     * {@code translate} 与 {@code scale} 声明在父类 {@code Matrix3x2f} 上，必须走
     * {@link Class#getMethod}（含继承）而不是 {@code getDeclaredMethod}。
     */
    private void resolveMatrixStack(Class<?> contextClass) {
        Method matrices = Reflect.method(contextClass, "method_51448");
        if (matrices == null) {
            for (Method method : contextClass.getMethods()) {
                if (method.getParameterCount() == 0
                        && method.getReturnType().getName().contains("Matrix3x2fStack")) {
                    matrices = method;
                    break;
                }
            }
        }
        if (matrices == null) {
            return;
        }
        Class<?> stackClass = matrices.getReturnType();
        Method push = findPublicMethod(stackClass, "pushMatrix");
        Method pop = findPublicMethod(stackClass, "popMatrix");
        Method translate = findPublicMethod(stackClass, "translate", float.class, float.class);
        Method scale = findPublicMethod(stackClass, "scale", float.class, float.class);
        if (push == null || pop == null || translate == null || scale == null) {
            return;
        }
        this.matrices = matrices;
        this.matrixPush = push;
        this.matrixPop = pop;
        this.matrixTranslate = translate;
        this.matrixScale = scale;
    }

    /** 解析裁剪：{@code method_44379(int,int,int,int)} 压栈、{@code method_44380()} 弹栈。 */
    private void resolveScissor(Class<?> contextClass) {
        Method enable = Reflect.method(contextClass, "method_44379",
                int.class, int.class, int.class, int.class);
        Method disable = Reflect.method(contextClass, "method_44380");
        if (enable != null && disable != null) {
            this.enableScissor = enable;
            this.disableScissor = disable;
        }
    }

    /** 在类及其父类上查找公开方法；未找到返回 {@code null}。 */
    private static Method findPublicMethod(Class<?> owner, String name, Class<?>... parameterTypes) {
        try {
            Method method = owner.getMethod(name, parameterTypes);
            method.setAccessible(true);
            return method;
        } catch (Throwable t) {
            return null;
        }
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
        int x1 = Math.round(x);
        int y1 = Math.round(y);
        int x2 = Math.round(x + width);
        int y2 = Math.round(y + height);
        // 亚像素尺寸（如 0.5px 的分隔线）取整后会退化到零宽高，强制保留 1px。
        if (x2 <= x1) {
            x2 = x1 + 1;
        }
        if (y2 <= y1) {
            y2 = y1 + 1;
        }
        Reflect.call(fill, drawContext, x1, y1, x2, y2, color.packed());
    }

    @Override
    public void roundedRect(float x, float y, float width, float height, float radius, Color color) {
        float r = Math.min(radius, Math.min(width, height) / 2f);
        if (r <= 1f) {
            rect(x, y, width, height, color);
            return;
        }
        // DrawContext 没有圆角原语：用整数像素分层逼近四角。
        // 每层边界都取整、层与层不重叠——半透明色重叠会叠出可见的横纹；
        // 内缩量按层的垂直中点计算，台阶在 UI 尺寸下不可辨。
        int steps = Math.max(2, (int) Math.ceil(r));
        for (int i = 0; i < steps; i++) {
            int y0 = Math.round(i * r / steps);
            int y1 = Math.round((i + 1) * r / steps);
            int band = y1 - y0;
            if (band <= 0) {
                continue;
            }
            int inset = Math.round(cornerInset(r, (y0 + y1) * 0.5f));
            rect(x + inset, y + y0, width - 2f * inset, band, color);
            rect(x + inset, y + height - y1, width - 2f * inset, band, color);
        }
        // 中段：上下圆角之间是完整的矩形
        rect(x, y + r, width, height - 2f * r, color);
    }

    /**
     * 计算圆角在距该圆角起始边 {@code dy} 处的水平内缩量。
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
        if (drawText == null || font == null) {
            // 字体没解析出来时退化为一条细矩形，至少让布局可见。
            rect(x, y + size * 0.75f, Math.min(value.length() * size * 0.5f, size * 8f), 1f, color);
            return;
        }
        float scale = size <= 0f ? 1f : size / FONT_BASE_HEIGHT;
        if (scale != 1f && matrices != null) {
            // 走矩阵栈：平移到位、缩放到目标字号，文字本身在原点绘制。
            // pushMatrix / translate / scale 成功时都返回 this（非 null），失败时 Reflect 返回 null。
            Object stack = Reflect.call(matrices, drawContext);
            if (stack != null && Reflect.call(matrixPush, stack) != null) {
                boolean positioned = Reflect.call(matrixTranslate, stack, x, y) != null
                        && Reflect.call(matrixScale, stack, scale, scale) != null;
                if (positioned) {
                    callDrawText(value, 0f, 0f, color);
                }
                Reflect.call(matrixPop, stack);
                if (positioned) {
                    return;
                }
            }
        }
        callDrawText(value, x, y, color);
    }

    /** 调用文字绘制入口；六参重载追加「无阴影」标志。 */
    private void callDrawText(String value, float x, float y, Color color) {
        if (drawText.getParameterCount() >= 6) {
            Reflect.call(drawText, drawContext, font, value,
                    Math.round(x), Math.round(y), color.packed(), false);
        } else {
            Reflect.call(drawText, drawContext, font, value,
                    Math.round(x), Math.round(y), color.packed());
        }
    }

    @Override
    public float textWidth(String value, float size) {
        if (value == null || value.isEmpty()) {
            return 0f;
        }
        float scale = size <= 0f ? 1f : size / FONT_BASE_HEIGHT;
        if (font != null && fontWidth != null) {
            Object measured = Reflect.call(fontWidth, font, value);
            if (measured instanceof Number) {
                return ((Number) measured).floatValue() * scale;
            }
        }
        // 无字体度量时的估算：每字符半宽
        return value.length() * size * 0.5f;
    }

    @Override
    public float textHeight(float size) {
        return size;
    }

    @Override
    public void pushClip(float x, float y, float width, float height) {
        if (drawContext == null || enableScissor == null) {
            return;
        }
        // enableScissor 收的是「左上 + 右下」两对坐标（缩放后坐标）
        Reflect.call(enableScissor, drawContext,
                Math.round(x), Math.round(y), Math.round(x + width), Math.round(y + height));
    }

    @Override
    public void popClip() {
        if (drawContext == null || disableScissor == null) {
            return;
        }
        Reflect.call(disableScissor, drawContext);
    }
}
