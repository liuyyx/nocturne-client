package dev.noturne.ui.gl;

import dev.noturne.client.game.GameBridge;
import dev.noturne.client.game.Reflect;
import dev.noturne.client.mapping.ClassType;
import dev.noturne.ui.render.Color;

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
    /** 字体实例（{@code FontRenderer} 或 {@code Font}），非 {@code null}。 */
    private final Object fontRenderer;
    /** 固定管线 GL 绑定；解析不到或只有核心 profile 时为 {@code null}（不做缩放）。 */
    private final GlApi gl;
    /** 规范名宽度度量 {@code width(String)} / {@code getStringWidth(String)}；为 {@code null} 时走映射桥。 */
    private final Method fontWidth;
    /** 字体原生行高（像素）：26.x 取 {@code lineHeight}，其余保持 9。 */
    private final int nativeHeight;
    /** 「drawString 无可用重载」是否已提示过，保证只打印一次。 */
    private boolean loggedMissing;
    /** 「现代字体不再提供 drawString」是否已提示过，保证只打印一次。 */
    private boolean loggedNoDrawString;
    /**
     * 字体是否提供 drawString：null=尚未探测。getMethods 全量扫描每 draw 做一次太贵（D12），
     * 字体类运行期不变，查一次缓存。
     */
    private Boolean hasDrawString;
    /** fontWidth 句柄连续失败次数；到 3 次就只走映射桥（P12）。 */
    private int fontWidthFailures;

    /** 仅由 {@link #bind} 创建——必须先在游戏里定位到字体实例。 */
    private MinecraftTextRenderer(GameBridge bridge, Object fontRenderer, GlApi gl,
                                  Method fontWidth, int nativeHeight) {
        this.bridge = bridge;
        this.fontRenderer = fontRenderer;
        this.gl = gl;
        this.fontWidth = fontWidth;
        this.nativeHeight = nativeHeight;
    }

    /**
     * 绑定到游戏正在使用的字体。
     *
     * <p>字体实例优先取 26.x 的可读字段 {@code Minecraft.font}，其次走映射表的
     * {@code fontRenderer}（1.8.9 混淆名），最后按字段类型兜底；三者都取不到才放弃。
     *
     * @param bridge 与游戏的桥
     * @return 绑定结果；游戏不可达、映射缺失或任何反射异常时返回 {@code null}
     */
    public static MinecraftTextRenderer bind(GameBridge bridge) {
        if (bridge == null) {
            return null;
        }
        try {
            Object minecraft = bridge.minecraft();
            if (minecraft == null) {
                return null;
            }
            Object font = resolveFont(bridge, minecraft);
            if (font == null) {
                return null;
            }
            return new MinecraftTextRenderer(bridge, font, resolveFixedPipeline(font),
                    resolveWidth(font), resolveNativeHeight(font));
        } catch (Throwable t) {
            return null;
        }
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
     * 解析规范名宽度度量：26.x 的 {@code width(String)}，或 1.8.9–1.19 未混淆构建的
     * {@code getStringWidth(String)}。
     *
     * <p>混淆构建上这两个名字都不存在（真实名是单字母），解析失败返回 {@code null}，
     * 由 {@link #width} 继续走映射桥，从而不改变 1.8.9 的既有行为。
     */
    private static Method resolveWidth(Object font) {
        Class<?> type = font.getClass();
        Method width = Reflect.method(type, "width", String.class);
        if (width == null) {
            width = Reflect.method(type, "getStringWidth", String.class);
        }
        return width != null && width.getReturnType() == int.class ? width : null;
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
                Object result = bridge.callMapped(fontRenderer, ClassType.FONT_RENDERER, "drawString",
                        text, 0, 0, rgb);
                logIfMissing(result);
            } finally {
                // P10：绘制抛异常也必须弹栈，否则矩阵栈每错一帧泄漏一层。
                gl.popMatrix();
            }
            return;
        }
        Object result = bridge.callMapped(fontRenderer, ClassType.FONT_RENDERER, "drawString",
                text, Math.round(x), Math.round(y), rgb);
        logIfMissing(result);
    }
    /**
     * 判断当前字体是否已经不负责绘制（26.x 的 {@code Font} 没有 {@code drawString}）。
     *
     * <p>此时无论怎么调用都不会有文字出现，直接跳过并提示一次，指向正确的绘制路径
     * （{@code GuiGraphicsExtractor} / {@code DrawContext}），而不是每帧空转映射桥。
     */
    private boolean drawUnsupported() {
        if (fontWidth == null) {
            return false;
        }
        if (hasDrawString == null) {
            boolean found = false;
            for (Method method : fontRenderer.getClass().getMethods()) {
                if ("drawString".equals(method.getName())) {
                    found = true;
                    break;
                }
            }
            hasDrawString = found ? Boolean.TRUE : Boolean.FALSE;
        }
        if (hasDrawString.booleanValue()) {
            return false;
        }
        if (!loggedNoDrawString) {
            loggedNoDrawString = true;
            System.err.println("[noturne] game font exposes no drawString (modern MC);"
                    + " text must be drawn through the game's draw context/extractor");
        }
        return true;
    }

    /** 只在 drawString 完全找不到重载时提示一次（避免每帧刷屏），便于定位文字缺失。 */
    private void logIfMissing(Object result) {
        if (result == null && !loggedMissing) {
            loggedMissing = true;
            System.err.println("[noturne] drawString bridge call returned null;"
                    + " text will be missing (mapping=" + bridge.mapping().describe() + ")");
        }
    }

    @Override
    public float width(String text, float size) {
        if (text == null || text.isEmpty()) {
            return 0f;
        }
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
