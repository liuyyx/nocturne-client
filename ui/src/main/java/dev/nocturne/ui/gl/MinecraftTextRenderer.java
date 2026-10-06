package dev.nocturne.ui.gl;

import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.game.Reflect;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.ui.render.Color;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Locale;

/**
 * 由游戏自带字体渲染器支撑的 {@link TextRenderer} 实现。
 *
 * <p>在两代游戏上取的字体不是同一个东西，这里都要能定位：
 * <ul>
 *   <li><b>1.8.9</b>：{@code net.minecraft.client.gui.FontRenderer}（混淆名 {@code avn}），
 *       通过 {@code Minecraft.fontRendererObj}（{@code ave.k}）取得；度量用映射名
 *       {@code getStringWidth}。</li>
 *   <li><b>26.x</b>：{@code net.minecraft.client.gui.Font}，通过可读字段
 *       {@code Minecraft.font} 取得；度量用 {@code public int width(String)}，
 *       行高用 {@code public final int lineHeight}。注意这一代字体已经不再负责绘制
 *       （没有 {@code drawString}），文字必须交给绘制后端提交。</li>
 * </ul>
 * 前两条路径都是反射探测：类名/字段名不硬编码为某一代的混淆结果，探测失败才退化。
 *
 * <p>游戏字体的字形恒为约 9px 高，没有字号参数。要按 {@code Theme} 的目标字号绘制，
 * 只能用 GL 矩阵在绘制前缩放：本实现从字体所在类加载器解析固定管线 GL，
 * 包一层 {@code glPushMatrix / glTranslatef / glScalef / glPopMatrix}。
 * 当只有核心 profile（LWJGL3）可用时，固定管线矩阵调用不会生效，
 * 此时按原生行高绘制，且 {@link #width} 也按原生基准度量——两者必须同基准，
 * 否则布局会按目标字号换算而字形却停留在原生尺寸，产生错位。
 */
public final class MinecraftTextRenderer implements TextRenderer {

    /** 1.8.9 的原生行高为 9px；字体没有暴露 {@code lineHeight} 时用它作换算基准。 */
    private static final float BASE_HEIGHT = 9f;

    /** 与游戏交互的桥，负责按映射名反射调用。 */
    private final GameBridge bridge;
    /** 字体实例（{@code FontRenderer} 或 {@code Font}）；**晚绑定**，见 {@link #ensureFont()}。 */
    private volatile Object fontRenderer;
    /** 固定管线 GL 绑定；解析不到或只有核心 profile 时为 {@code null}（不做缩放）。 */
    private volatile GlApi gl;
    /** 规范名宽度度量 {@code width(String)} / {@code getStringWidth(String)}；为 {@code null} 时走映射桥。 */
    private volatile Method fontWidth;
    /** 字体原生行高（像素）：26.x 取 {@code lineHeight}，其余保持 9。 */
    private volatile int nativeHeight = (int) BASE_HEIGHT;
    /** 上次尝试解析字体的纳秒时间戳（用于重试退避）。 */
    private volatile long lastFontAttemptNanos;
    /** 是否已经打过一次"字体尚未就绪"的日志。 */
    private volatile boolean loggedPending;

    /** 字体重试间隔（纳秒）：500ms。游戏构造早期 GameBridge 能拿到实例，但字体字段可能还没赋值。 */
    private static final long FONT_RETRY_INTERVAL_NANOS = 500_000_000L;
    /** 「现代字体不再提供 drawString」是否已提示过，保证只打印一次。 */
    private boolean loggedNoDrawString;
    /**
     * 画字方法句柄，**按签名**解析（见 {@link #resolveDrawString}）：null 表示该字体不负责绘制
     * （26.x 的 {@code Font}）。用签名而不是映射名，是因为这个名字在原版混淆/SRG/未混淆三种
     * 环境下分别是 {@code a} / {@code func_78276_b} / {@code drawString}——按名字查表只覆盖一种。
     */
    private Method drawStringHandle;
    /** 是否已探测过画字句柄（getMethods 全量扫描只做一次）。 */
    private boolean drawStringProbed;
    /** 句柄是否使用 float 坐标（第二、三参为 float 的旧重载）。 */
    private boolean drawStringFloats;
    /** fontWidth 句柄连续失败次数；到 3 次就只走映射桥（P12）。 */
    private int fontWidthFailures;

    /** 仅由 {@link #bind} 创建——字体实例可能**稍后**才就绪，见 {@link #ensureFont()}。 */
    private MinecraftTextRenderer(GameBridge bridge) {
        this.bridge = bridge;
    }

    /**
     * 创建一个文字渲染器。
     *
     * <p><b>晚绑定</b>：构造时不强制要求字体已就绪。游戏构造早期 {@code GameBridge} 已经能拿到
     * Minecraft 实例，但它的字体字段可能还没赋值（Forge/launchwrapper 下尤其明显，实测同一实例
     * 两次注入一次有字一次没有），而绑定失败的后果是"界面能开、一个字都没有"。
     * 因此这里总是返回实例，真正的解析交给 {@link #ensureFont()} 在首次绘制时做，并按
     * {@link #FONT_RETRY_INTERVAL_NANOS} 退避重试，直到拿到字体。
     *
     * @param bridge 与游戏的桥；为 {@code null} 时返回 {@code null}（无桥可用，调用方也没法继续）
     * @return 渲染器（字体可能尚未就绪）
     */
    public static MinecraftTextRenderer bind(GameBridge bridge) {
        if (bridge == null) {
            return null;
        }
        MinecraftTextRenderer renderer = new MinecraftTextRenderer(bridge);
        renderer.resolveFontNow(true);
        return renderer;
    }

    /**
     * 若尚未拿到字体，按退避间隔重试解析一次。
     *
     * @param force 忽略退避（构造后的首次尝试用）
     * @return 字体是否已就绪
     */
    private boolean ensureFont() {
        if (fontRenderer != null) {
            return true;
        }
        long now = System.nanoTime();
        if (!forcedNextAttempt && now - lastFontAttemptNanos < FONT_RETRY_INTERVAL_NANOS) {
            return false;
        }
        forcedNextAttempt = false;
        lastFontAttemptNanos = now;
        return resolveFontNow(false);
    }

    /** 是否忽略下一次退避（构造后首次尝试）。 */
    private volatile boolean forcedNextAttempt;

    /**
     * 真正解析字体及其配套句柄。
     *
     * @param initial 是否为构造后的首次尝试（决定日志措辞）
     * @return 是否拿到字体
     */
    private boolean resolveFontNow(boolean initial) {
        lastFontAttemptNanos = System.nanoTime();
        Object font;
        try {
            Object minecraft = bridge.minecraft();
            if (minecraft == null) {
                if (initial) {
                    System.err.println("[nocturne] text renderer: game not reachable yet;"
                            + " font binding deferred");
                }
                return false;
            }
            font = resolveFont(bridge, minecraft);
        } catch (Throwable t) {
            System.err.println("[nocturne] text renderer: cannot locate game font: " + t);
            return false;
        }
        if (font == null) {
            if (!loggedPending) {
                loggedPending = true;
                System.err.println("[nocturne] text renderer: font not available yet"
                        + " (mapping=" + bridge.mapping().describe()
                        + "); will retry every 500ms");
            }
            return false;
        }
        // 以下三步各自容错：拿不到 GL 缩放句柄只意味着"字按原生行高画"，拿不到宽度句柄还有
        // 映射桥兜底。曾经这里任何一步抛异常都会被外层 catch 吞掉，结果是整个文字渲染失效
        // （界面能开、一个字都没有），而日志里没有任何线索——正是"所有版本都没字"的成因。
        GlApi newGl = null;
        try {
            newGl = resolveFixedPipeline(font);
        } catch (Throwable t) {
            System.err.println("[nocturne] text renderer: fixed-pipeline GL unavailable: " + t);
        }
        Method width = null;
        try {
            width = resolveWidth(font);
        } catch (Throwable t) {
            System.err.println("[nocturne] text renderer: width handle unavailable: " + t);
        }
        int height = (int) BASE_HEIGHT;
        try {
            height = resolveNativeHeight(font);
        } catch (Throwable t) {
            System.err.println("[nocturne] text renderer: line height unavailable: " + t);
        }
        this.fontWidth = width;
        this.nativeHeight = height;
        this.gl = newGl;
        this.fontRenderer = font;   // 最后赋值：其它字段先就位，绘制路径才看到"已就绪"
        System.err.println("[nocturne] text renderer bound: font=" + font.getClass().getName()
                + ", gl=" + (newGl == null ? "none (no scaling)" : "fixed pipeline")
                + ", widthHandle=" + (width == null ? "mapped" : width.getName())
                + ", lineHeight=" + height);
        return true;
    }

    /**
     * 定位游戏正在使用的字体。
     *
     * <p>顺序即优先级：可读字段名（26.1+ 官方发行版）→ 映射名（1.8.9 混淆）→ 类型兜底。
     * 前两步失败不缓存失败结果，因为类/字段晚一点才可见是正常情况。
     */
    private static Object resolveFont(GameBridge bridge, Object minecraft) {
        Object font = readField(minecraft, "font");
        if (font != null) {
            return font;
        }
        font = bridge.readField(minecraft, ClassType.MINECRAFT, "fontRenderer");
        if (font != null) {
            return font;
        }
        return readFontByType(minecraft);
    }

    /** 读取实例上的指定名字段（declared，含私有）；不可用返回 {@code null}。 */
    private static Object readField(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * 按类型兜底：字段类型以 {@code Font} / {@code FontRenderer} 结尾，且名字不含 {@code filter}。
     * 优先精确名 {@code font}，避免绑到 {@code fontFilterFishy} 之类的旁支。
     */
    private static Object readFontByType(Object minecraft) {
        Object fallback = null;
        for (Field field : minecraft.getClass().getDeclaredFields()) {
            String typeName = field.getType().getName();
            if (!typeName.endsWith(".Font") && !typeName.endsWith(".FontRenderer")) {
                continue;
            }
            if (field.getName().toLowerCase(Locale.ROOT).contains("filter")) {
                continue;
            }
            try {
                field.setAccessible(true);
                Object value = field.get(minecraft);
                if (value == null) {
                    continue;
                }
                if ("font".equals(field.getName())) {
                    return value;
                }
                if (fallback == null) {
                    fallback = value;
                }
            } catch (Throwable t) {
                // 单字段不可读不影响其余候选。
            }
        }
        return fallback;
    }

    /**
     * 置为可访问。
     *
     * <p>{@code accessible(Method)} 是 client 模块的包内方法，ui 模块用不了；这里按同一取舍
     * 本地实现：{@code setAccessible} 失败但方法本身 public 时仍返回句柄（Java 9+ 对未 open 的包
     * 会抛 {@code InaccessibleObjectException}，而 public 方法照样能 invoke）。
     */
    private static Method accessible(Method method) {
        try {
            method.setAccessible(true);
            return method;
        } catch (Throwable ignored) {
            return java.lang.reflect.Modifier.isPublic(method.getModifiers()) ? method : null;
        }
    }

    /**
     * 解析规范名宽度度量：26.x 的 {@code width(String)}，或 1.8.9–1.19 未混淆构建的
     * {@code getStringWidth(String)}，最后按签名 {@code (String)->int} 兜底。
     *
     * <p>签名兜底不是可有可无：Forge 等环境在运行期把成员重映射成 SRG 名
     * （{@code getStringWidth} → {@code func_78256_a}），前两个名字都不存在。此时按名字找
     * 必然失败，宽度会退化成 {@code 长度×6} 的估算，布局跟着错位。
     */
    private static Method resolveWidth(Object font) {
        Class<?> type = font.getClass();
        Method width = Reflect.method(type, "width", String.class);
        if (width == null) {
            width = Reflect.method(type, "getStringWidth", String.class);
        }
        if (width == null) {
            for (Method method : type.getMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if (parameters.length == 1 && parameters[0] == String.class
                        && method.getReturnType() == int.class) {
                    width = accessible(method);
                    break;
                }
            }
        }
        return width != null && width.getReturnType() == int.class ? width : null;
    }

    /**
     * 按签名解析「画一行字」的方法：{@code (String,int,int,int)->int}。
     *
     * <p>为什么不用映射名：这个方法在各代的名字完全不同——原版混淆是单字母（{@code a}）、
     * SRG 环境是 {@code func_78276_b}、未混淆构建是 {@code drawString}。按名字查表只覆盖其中
     * 一种，换环境就"界面能开、一个字都没有"。签名在所有命名方案下都一样。
     *
     * <p>1.8.9–1.21.x 的 {@code FontRenderer} 都有这个形状；26.x 的 {@code Font} 不负责绘制
     * （没有该方法），返回 {@code null} 让调用方按"现代字体"处理。
     */
    private static Method resolveDrawString(Object font) {
        Method floats = null;
        for (Method method : font.getClass().getMethods()) {
            Class<?>[] parameters = method.getParameterTypes();
            if (parameters.length != 4 || parameters[0] != String.class
                    || method.getReturnType() != int.class) {
                continue;
            }
            if (parameters[1] == int.class && parameters[2] == int.class
                    && parameters[3] == int.class) {
                return accessible(method);
            }
            if (parameters[1] == float.class && parameters[2] == float.class
                    && parameters[3] == int.class) {
                floats = method;
            }
        }
        return floats == null ? null : accessible(floats);
    }

    /** 读取字体原生行高（26.x 的 {@code public final int lineHeight}）；不可用时保持 9px。 */
    private static int resolveNativeHeight(Object font) {
        try {
            Field field = font.getClass().getField("lineHeight");
            if (field.getType() == int.class) {
                Object value = field.get(font);
                if (value instanceof Number && ((Number) value).intValue() > 0) {
                    return ((Number) value).intValue();
                }
            }
        } catch (Throwable t) {
            // 没有该字段就用默认基准。
        }
        return (int) BASE_HEIGHT;
    }

    /**
     * 从字体所在类加载器解析固定管线 GL。
     *
     * <p>仅当运行环境确实是 LWJGL2 固定管线时才返回绑定：LWJGL3 同时提供 {@code GL11C}
     * 这类 core 类，且 MC 1.17+ 运行在核心 profile 上，此时 {@code glScalef} 不会生效。
     * 判据是「{@code org.lwjgl.opengl.GL11C} 不存在」。
     */
    private static GlApi resolveFixedPipeline(Object font) {
        ClassLoader loader = font.getClass().getClassLoader();
        if (Reflect.loadWithoutInit("org.lwjgl.opengl.GL11C", loader) != null) {
            return null;
        }
        return GlApi.bind("org.lwjgl.opengl.GL11", loader);
    }

    /** @return 底层的字体实例，供诊断使用 */
    public Object fontRenderer() {
        return fontRenderer;
    }

    @Override
    public void draw(String text, float x, float y, float size, Color color) {
        if (text == null || text.isEmpty()) {
            return;
        }
        if (!ensureFont()) {
            return;   // 字体还没就绪：不画，但下面每帧都会再试（见 ensureFont 的退避）
        }
        if (drawUnsupported()) {
            return;
        }
        int rgb = color == null ? 0xFFFFFF : (color.argb & 0xFFFFFF);
        // P11：矩阵操作必须过 hasMatrixControl 门禁——gl 非空不代表有矩阵句柄，
        // 无句柄时 pushMatrix 空转，字形画在游戏投影下错位。
        boolean matrix = gl != null && gl.hasMatrixControl();
        // 字形要采样字体图集：本后端的填充绘制会把 GL_TEXTURE_2D 关掉，而游戏的 GlStateManager
        // 仍缓存着「纹理已启用」，它自己便不再 glEnable——不在这里补一刀，字形就退化成色块。
        if (gl != null) {
            gl.enable(GlApi.GL_TEXTURE_2D);
        }
        float scale = matrix ? effectiveScale(size) : 1f;
        if (scale != 1f) {
            // 固定管线路径：把整个字形按目标字号缩放后绘制，使实际字形尺寸与 width()/height() 一致。
            gl.pushMatrix();
            try {
                gl.translate(x, y, 0f);
                gl.scale(scale, scale, 1f);
                drawWithHandle(text, 0, 0, rgb);
            } finally {
                // P10：绘制抛异常也必须弹栈，否则矩阵栈每错一帧泄漏一层。
                gl.popMatrix();
            }
            return;
        }
        drawWithHandle(text, Math.round(x), Math.round(y), rgb);
    }

    /** 用按签名解析出的句柄画一行字（句柄已由 {@link #drawUnsupported} 保证非空）。 */
    private void drawWithHandle(String text, int x, int y, int rgb) {
        if (drawStringFloats) {
            Reflect.call(drawStringHandle, fontRenderer, text, (float) x, (float) y,
                    Integer.valueOf(rgb));
        } else {
            Reflect.call(drawStringHandle, fontRenderer, text, Integer.valueOf(x),
                    Integer.valueOf(y), Integer.valueOf(rgb));
        }
    }

    /**
     * 判断当前字体是否已经不负责绘制（26.x 的 {@code Font} 没有画字方法）。
     *
     * <p>判据是**签名**（{@code (String,int,int,int)->int}）而非名字：同一个方法在三种环境下分别叫
     * {@code a}（原版混淆）、{@code func_78276_b}（SRG/Forge）、{@code drawString}（未混淆）。
     * 曾经按名字扫 {@code getMethods()}，于是 Forge 环境下被判成"没有 drawString"、
     * 官方混淆环境下又被映射桥的名字挡住——两种环境都表现为"界面能开、一个字都没有"。
     *
     * <p>确实没有该签名的字体（26.x）直接跳过并提示一次，指向正确的绘制路径
     * （{@code GuiGraphicsExtractor} / {@code DrawContext}），而不是每帧空转。
     */
    private boolean drawUnsupported() {
        if (!drawStringProbed) {
            drawStringProbed = true;
            drawStringHandle = resolveDrawString(fontRenderer);
            if (drawStringHandle != null) {
                drawStringFloats = drawStringHandle.getParameterTypes()[1] == float.class;
            }
        }
        if (drawStringHandle != null) {
            return false;
        }
        if (!loggedNoDrawString) {
            loggedNoDrawString = true;
            System.err.println("[nocturne] game font exposes no (String,int,int,int) draw method"
                    + " (modern MC); text must be drawn through the game's draw context/extractor");
        }
        return true;
    }

    @Override
    public float width(String text, float size) {
        if (text == null || text.isEmpty()) {
            return 0f;
        }
        ensureFont();   // 字体可能还没就绪：退回按字符数估算，下一帧再试（布局不至于塌陷）
        if (fontWidth != null && fontWidthFailures < 3) {
            // P12：句柄失效时 Reflect.call 静默返 null，每字都走两条路径；
            // 连续失败 3 次就只走映射桥，不再试它。
            Object measured = Reflect.call(fontWidth, fontRenderer, text);
            if (measured instanceof Number) {
                fontWidthFailures = 0;
                return ((Number) measured).floatValue() * effectiveScale(size);
            }
            fontWidthFailures++;
        }
        Object result = bridge.callMapped(fontRenderer, ClassType.FONT_RENDERER, "getStringWidth", text);
        float base = result instanceof Number ? ((Number) result).floatValue() : text.length() * 6f;
        return base * effectiveScale(size);
    }

    @Override
    public float height(float size) {
        ensureFont();
        // 与 draw() 同基准：固定管线可用时按目标字号，否则字形恒为原生行高。
        return nativeHeight * effectiveScale(size);
    }

    /**
     * 文字实际可用的缩放系数。
     *
     * @param size 期望字号（像素）
     * @return 缩放系数；固定管线不可用或 size 非正时按 1（原生行高）处理
     */
    private float effectiveScale(float size) {
        if (size <= 0f || gl == null || !gl.hasMatrixControl()) {
            return 1f;
        }
        return size / nativeHeight;
    }
}
