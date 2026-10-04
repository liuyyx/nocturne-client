/*
 * 移植自 Setsuna（上游 commit e4915ae093748d48d92ee47c38cdb8b2746a4730，作者 ShiYi，
 * 许可 GPL-3.0-or-later，见 THIRD-PARTY-NOTICES.md）。本文件相对上游的改动：
 * 1. 包名 com.setsuna.ui.hud → dev.noturne.ui.skija；类名 HudRenderUtil → SkijaHudPrimitives，
 *    可见性 final class → public final class，方法 → public static（上游同包调用，本项目跨包调用）。
 * 2. 设计令牌 UiTheme.* → SkijaTheme.*。
 * 3. com.setsuna.render.SkijaUi 改为同包内直接调用（不再需要 import）。
 * 4. IntSetting 参数改为原始 int：读取（normalizedPosition）搬到调用方；写入
 *    （setNormalizedPosition）改为返回归一化值，由调用方写回设置。
 * 5. HudFusionManager.Edges 内联为本类的嵌套 public enum Edges（上游是 Java 16 record；
 *    这里穷举四边全部 16 种组合，等价于上游 new Edges(left, right, top, bottom)）。
 * 6. BorderMode 由包私有提升为 public 嵌套枚举（上游同包使用，本项目需对外暴露）。
 * 7. 删除 blur(..) 两个重载：它们依赖 SkijaRenderer.drawBlurredBackdrop，即 Minecraft
 *    帧缓冲快照 / 纹理桥，本项目基座不提供该能力；调用方接入纹理桥后再自行补，本类不提供空实现。
 * 8. 其余面板/边框/渐变/彩虹边框的数值与算法逐行与上游一致。
 */
package dev.noturne.ui.skija;

import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ClipMode;
import io.github.humbleui.skija.Paint;
import io.github.humbleui.skija.PaintMode;
import io.github.humbleui.skija.PaintStrokeCap;
import io.github.humbleui.skija.PaintStrokeJoin;
import io.github.humbleui.skija.Path;
import io.github.humbleui.skija.PathBuilder;
import io.github.humbleui.skija.PathDirection;
import io.github.humbleui.skija.PathMeasure;
import io.github.humbleui.skija.Shader;
import io.github.humbleui.types.RRect;
import io.github.humbleui.types.Rect;

import java.awt.Color;

/**
 * HUD 表面与定宽标签共享的小型绘制原语：面板、表面、边框、进度条、发丝线，以及
 * 位置归一化与文本裁剪。
 *
 * <p>这些原语只操作 Skija 的 {@link Canvas}，不接触 Minecraft 类型，因此可在任意目标版本复用。
 * 需要游戏帧缓冲（背景模糊）的能力不在这里，见类头改动说明第 7 条。
 *
 * <p>"定宽标签"指 {@link #stableDigits(String)}：把每个数字字形换成同一个宽数字，数值跳动时
 * 文本宽度不变，避免 HUD 每帧抖动。
 */
public final class SkijaHudPrimitives {

    /** 边框着色方式：单色 / 双色线性渐变 / 随时间流动的彩虹。 */
    public enum BorderMode {
        Single,
        Gradient,
        Rainbow
    }

    /**
     * 面板与相邻面板"融合"时被贴附的边集合。
     *
     * <p>为什么是枚举而不是上游的 record：上游 {@code HudFusionManager.Edges} 是 Java 16 的
     * {@code record Edges(boolean left, boolean right, boolean top, boolean bottom)}，本项目基座为
     * Java 8，且移植约定要求以内嵌枚举承载，故此处穷举四条边的全部 16 种组合；
     * {@link #of(boolean, boolean, boolean, boolean)} 与上游的
     * {@code new Edges(left, right, top, bottom)} 等价。
     *
     * <p>为什么穷举而不是只留 {@code NONE}：融合面板可能同时贴附多条边（例如左侧 + 顶部），
     * 这些组合直接决定圆角形状（{@link #fusionShape}）与贴附边的裁剪（{@link #clipAttachedEdges}）；
     * 若只保留单边值，组合输入无法表达，圆角与裁剪会画错。
     */
    public enum Edges {
        NONE(false, false, false, false),
        LEFT(true, false, false, false),
        RIGHT(false, true, false, false),
        TOP(false, false, true, false),
        BOTTOM(false, false, false, true),
        LEFT_RIGHT(true, true, false, false),
        LEFT_TOP(true, false, true, false),
        LEFT_BOTTOM(true, false, false, true),
        RIGHT_TOP(false, true, true, false),
        RIGHT_BOTTOM(false, true, false, true),
        TOP_BOTTOM(false, false, true, true),
        LEFT_RIGHT_TOP(true, true, true, false),
        LEFT_RIGHT_BOTTOM(true, true, false, true),
        LEFT_TOP_BOTTOM(true, false, true, true),
        RIGHT_TOP_BOTTOM(false, true, true, true),
        ALL(true, true, true, true);

        /** 索引 = 掩码 left(1) | right(2) | top(4) | bottom(8)，供 {@link #of} 常数时间查表。 */
        private static final Edges[] BY_MASK = new Edges[16];

        static {
            for (Edges edges : values()) {
                BY_MASK[mask(edges.left, edges.right, edges.top, edges.bottom)] = edges;
            }
        }

        private final boolean left;
        private final boolean right;
        private final boolean top;
        private final boolean bottom;

        Edges(boolean left, boolean right, boolean top, boolean bottom) {
            this.left = left;
            this.right = right;
            this.top = top;
            this.bottom = bottom;
        }

        /** 上游 {@code new Edges(left, right, top, bottom)} 的等价构造。 */
        public static Edges of(boolean left, boolean right, boolean top, boolean bottom) {
            return BY_MASK[mask(left, right, top, bottom)];
        }

        private static int mask(boolean left, boolean right, boolean top, boolean bottom) {
            return (left ? 1 : 0) | (right ? 2 : 0) | (top ? 4 : 0) | (bottom ? 8 : 0);
        }

        public boolean left() {
            return left;
        }

        public boolean right() {
            return right;
        }

        public boolean top() {
            return top;
        }

        public boolean bottom() {
            return bottom;
        }

        /** 是否至少贴附一条边（上游保留成员）。 */
        public boolean any() {
            return left || right || top || bottom;
        }
    }

    // 复用 Paint 实例：HUD 每帧要画几十个表面/边框，逐次 new Paint 会持续制造原生对象。
    private static final Paint BORDER_PAINT = new Paint()
            .setAntiAlias(true)
            .setMode(PaintMode.STROKE)
            .setStrokeCap(PaintStrokeCap.ROUND)
            .setStrokeJoin(PaintStrokeJoin.ROUND);
    private static final Paint SURFACE_PAINT = new Paint().setAntiAlias(true);

    private SkijaHudPrimitives() {
    }

    public static void panel(Canvas canvas, float x, float y, float width, float height, int opacity) {
        int alpha = clamp(opacity, 0, 255);
        coloredPanel(canvas, x, y, width, height, SkijaTheme.withAlpha(SkijaTheme.SURFACE, alpha));
    }

    /**
     * 描边 + 内缩 1px 底色的双色面板。
     *
     * <p>描边透明度由底色透明度推导（+16 再夹到 104..224）：底色越实，描边越明显，
     * 面板越"浮"；这样同一套调用点能同时服务浅底与实底两种风格。
     */
    public static void coloredPanel(Canvas canvas, float x, float y, float width, float height, int color) {
        if (width <= 1.0F || height <= 1.0F) return;
        int alpha = (color >>> 24) & 0xFF;
        float radius = Math.min(SkijaTheme.RADIUS_SMALL, Math.min(width, height) * 0.5F);
        SkijaUi.rounded(canvas, x, y, width, height, radius,
                SkijaTheme.withAlpha(SkijaTheme.BORDER, Math.min(224, Math.max(104, alpha + 16))));
        SkijaUi.rounded(canvas, x + 1.0F, y + 1.0F, width - 2.0F, height - 2.0F,
                Math.max(1.0F, radius - 1.0F), color);
    }

    public static void surface(Canvas canvas, float x, float y, float width, float height, int opacity) {
        surface(canvas, x, y, width, height, opacity, Edges.NONE);
    }

    public static void surface(Canvas canvas, float x, float y, float width, float height, int opacity,
                               Edges edges) {
        coloredSurface(canvas, x, y, width, height,
                SkijaTheme.withAlpha(SkijaTheme.SURFACE, clamp(opacity, 0, 255)), edges);
    }

    public static void coloredSurface(Canvas canvas, float x, float y, float width, float height, int color) {
        coloredSurface(canvas, x, y, width, height, color, Edges.NONE);
    }

    public static void coloredSurface(Canvas canvas, float x, float y, float width, float height,
                                      int color, Edges edges) {
        if (width <= 1.0F || height <= 1.0F) return;
        float radius = Math.min(SkijaTheme.RADIUS_SMALL, Math.min(width, height) * 0.5F);
        coloredSurface(canvas, x, y, width, height, radius, color, edges);
    }

    public static void coloredSurface(Canvas canvas, float x, float y, float width, float height,
                                      float radius, int color, Edges edges) {
        if (width <= 1.0F || height <= 1.0F) return;
        SURFACE_PAINT.setColor(color);
        canvas.drawRRect(fusionShape(x, y, width, height, radius, edges), SURFACE_PAINT);
    }

    public static void coloredPath(Canvas canvas, Path path, int color) {
        if (path == null) return;
        SURFACE_PAINT.setColor(color);
        canvas.drawPath(path, SURFACE_PAINT);
    }

    public static void border(Canvas canvas, float x, float y, float width, float height,
                              float radius, float strokeWidth, float progress,
                              BorderMode mode, int singleColor, int startColor, int endColor,
                              int alpha) {
        border(canvas, x, y, width, height, radius, strokeWidth, progress,
                mode, singleColor, startColor, endColor, alpha,
                Edges.NONE);
    }

    /**
     * 边框；{@code progress} 控制可见弧长，用于"描边生长"动画。
     *
     * <p>先用 {@link PathMeasure} 截取轮廓的前 {@code progress} 段，再单独绘制；贴附边
     * （{@code edges}）在这次绘制期间被裁掉半笔宽，避免与相邻面板的边框重叠出双线。
     */
    public static void border(Canvas canvas, float x, float y, float width, float height,
                              float radius, float strokeWidth, float progress,
                              BorderMode mode, int singleColor, int startColor, int endColor,
                              int alpha, Edges edges) {
        float clamped = clamp(progress, 0.0F, 1.0F);
        if (clamped <= 0.001F || width <= strokeWidth || height <= strokeWidth) return;

        float inset = strokeWidth * 0.5F;
        float left = x + (edges.left() ? 0.0F : inset);
        float top = y + (edges.top() ? 0.0F : inset);
        float right = x + width - (edges.right() ? 0.0F : inset);
        float bottom = y + height - (edges.bottom() ? 0.0F : inset);
        RRect bounds = fusionShape(left, top, right - left, bottom - top,
                Math.max(0.0F, radius - inset), edges);
        try (PathBuilder outlineBuilder = new PathBuilder()) {
            outlineBuilder.addRRect(bounds, PathDirection.CLOCKWISE, 0);
            try (Path outline = outlineBuilder.detach()) {
                Path visible = outline;
                if (clamped < 0.999F) {
                    try (PathMeasure measure = new PathMeasure(outline, true);
                         PathBuilder visibleBuilder = new PathBuilder()) {
                        float length = measure.getLength();
                        if (!measure.getSegment(0.0F, length * clamped, visibleBuilder, true)) return;
                        visible = visibleBuilder.detach();
                    }
                }
                try {
                    int clipSave = canvas.save();
                    clipAttachedEdges(canvas, x, y, width, height, strokeWidth, edges);
                    try {
                        drawBorderPath(canvas, visible, x, y, width, height, strokeWidth,
                                mode, singleColor, startColor, endColor, alpha);
                    } finally {
                        canvas.restoreToCount(clipSave);
                    }
                } finally {
                    if (visible != outline) visible.close();
                }
            }
        }
    }

    /**
     * 融合形状：贴附边所在角归零，使两个面板拼在一起时接缝处是直角。
     *
     * <p>半径先夹到不超过短边一半，否则 Skia 会画出自我交叉的圆角。
     */
    private static RRect fusionShape(float x, float y, float width, float height,
                                     float radius, Edges edges) {
        float safeRadius = Math.max(0.0F, Math.min(radius, Math.min(width, height) * 0.5F));
        float topLeft = edges.left() || edges.top() ? 0.0F : safeRadius;
        float topRight = edges.right() || edges.top() ? 0.0F : safeRadius;
        float bottomRight = edges.right() || edges.bottom() ? 0.0F : safeRadius;
        float bottomLeft = edges.left() || edges.bottom() ? 0.0F : safeRadius;
        return RRect.makeComplexXYWH(x, y, width, height, new float[]{
                topLeft, topLeft, topRight, topRight,
                bottomRight, bottomRight, bottomLeft, bottomLeft
        });
    }

    /**
     * 裁掉贴附边上的半个笔宽。用 0.58 而不是 0.5，是为了把抗锯齿外溢的那一点也裁掉；
     * 下限 0.5 保证极细边框也裁得干净。
     */
    private static void clipAttachedEdges(Canvas canvas, float x, float y,
                                          float width, float height, float strokeWidth,
                                          Edges edges) {
        float half = Math.max(0.5F, strokeWidth * 0.58F);
        if (edges.left()) {
            canvas.clipRect(Rect.makeLTRB(x - half, y, x + half, y + height),
                    ClipMode.DIFFERENCE, false);
        }
        if (edges.right()) {
            canvas.clipRect(Rect.makeLTRB(x + width - half, y,
                    x + width + half, y + height), ClipMode.DIFFERENCE, false);
        }
        if (edges.top()) {
            canvas.clipRect(Rect.makeLTRB(x, y - half, x + width, y + half),
                    ClipMode.DIFFERENCE, false);
        }
        if (edges.bottom()) {
            canvas.clipRect(Rect.makeLTRB(x, y + height - half,
                    x + width, y + height + half), ClipMode.DIFFERENCE, false);
        }
    }

    /**
     * 沿路径描边。单色直接用 Paint 颜色；渐变/彩虹改用线性着色器。
     *
     * <p>渐变方向固定为包围盒左上 → 右下：这是上游的取法，与边框形状无关，
     * 因此融合形状也保持同一渐变走向。
     */
    private static void drawBorderPath(Canvas canvas, Path path,
                                       float x, float y, float width, float height,
                                       float strokeWidth, BorderMode mode,
                                       int singleColor, int startColor, int endColor,
                                       int alpha) {
        BORDER_PAINT.setStrokeWidth(strokeWidth).setShader(null);
        if (mode == BorderMode.Single) {
            BORDER_PAINT.setColor(withAlpha(singleColor, alpha));
            canvas.drawPath(path, BORDER_PAINT);
            return;
        }

        int[] colors = mode == BorderMode.Gradient
                ? new int[]{withAlpha(startColor, alpha), withAlpha(endColor, alpha)}
                : rainbowColors(alpha);
        try (Shader shader = Shader.makeLinearGradient(
                x, y, x + width, y + height, colors)) {
            BORDER_PAINT.setShader(shader).setColor(0xFFFFFFFF);
            canvas.drawPath(path, BORDER_PAINT);
        } finally {
            // 着色器随 try 结束被释放；把 Paint 恢复成"无着色器"状态，避免下一帧用到已释放对象。
            BORDER_PAINT.setShader(null).setColor(0xFFFFFFFF);
        }
    }

    public static void borderPath(Canvas canvas, Path path, Rect bounds, float strokeWidth,
                                  BorderMode mode, int singleColor, int startColor, int endColor,
                                  int alpha) {
        if (path == null || bounds == null || strokeWidth <= 0.0F) return;
        drawBorderPath(canvas, path, bounds.getLeft(), bounds.getTop(),
                bounds.getWidth(), bounds.getHeight(), strokeWidth,
                mode, singleColor, startColor, endColor, alpha);
    }

    /**
     * 彩虹色带：每 4 秒循环一周色相，取样点间隔 0.25。
     *
     * <p>用 5 个取样点是上游的固定选择（线性渐变按顺序均分色标），改动会让流动速度观感变化。
     */
    private static int[] rainbowColors(int alpha) {
        float phase = (System.currentTimeMillis() % 4000L) / 4000.0F;
        int[] colors = new int[5];
        for (int index = 0; index < colors.length; index++) {
            int rgb = Color.HSBtoRGB((phase + index * 0.25F) % 1.0F, 0.82F, 1.0F);
            colors[index] = withAlpha(rgb, alpha);
        }
        return colors;
    }

    private static int withAlpha(int color, int alpha) {
        return (clamp(alpha, 0, 255) << 24) | (color & 0x00FFFFFF);
    }

    /** 小号状态条（如列表项左侧的细条）：3px 圆角 + 1.5px 内缩底色。 */
    public static void strip(Canvas canvas, float x, float y, float width, float height, int opacity) {
        if (width <= 1.0F || height <= 1.0F) return;
        int alpha = clamp(opacity, 0, 255);
        SkijaUi.rounded(canvas, x, y, width, height, 3.0F,
                SkijaTheme.withAlpha(SkijaTheme.BORDER, Math.min(190, Math.max(88, alpha))));
        SkijaUi.rounded(canvas, x + 0.75F, y + 0.75F, width - 1.5F, height - 1.5F, 2.25F,
                SkijaTheme.withAlpha(SkijaTheme.SURFACE, Math.min(232, alpha)));
    }

    /** 1px 级分隔线；直接用 {@link SkijaUi#fill}，不做抗锯齿（发丝线要的就是硬边）。 */
    public static void hairline(Canvas canvas, float x, float y, float width, float height, int color) {
        if (width <= 0.0F || height <= 0.0F) return;
        SkijaUi.fill(canvas, x, y, width, height, color);
    }

    /**
     * 进度条：先画 180 alpha 的整条轨道，再按 {@code progress} 覆盖实心前景。
     *
     * <p>前景半径随已填充宽度收缩（{@code min(radius, filled * 0.5)}），
     * 使得进度极小时不会出现"圆角大于长度"的畸形。
     */
    public static void progress(Canvas canvas, float x, float y, float width, float height,
                                float progress, int color) {
        if (width <= 0.0F || height <= 0.0F) return;
        float radius = Math.min(2.0F, height * 0.5F);
        SkijaUi.rounded(canvas, x, y, width, height, radius,
                SkijaTheme.withAlpha(SkijaTheme.BORDER, 180));
        float filled = width * clamp(progress, 0.0F, 1.0F);
        if (filled > 0.0F) {
            SkijaUi.rounded(canvas, x, y, filled, height, Math.min(radius, filled * 0.5F), color);
        }
    }

    /** 两个一维区间是否相交（HUD 撞边检测用）。 */
    public static boolean overlaps(float startA, float sizeA, float startB, float sizeB) {
        return startA < startB + sizeB && startA + sizeA > startB;
    }

    /**
     * 把持久化的 0..1000 坐标映射到"可用行程"上的绝对像素。
     *
     * <p>为什么是 0..1000 而不是 0..1：整数设置项的精度与跨分辨率稳定性；用分数会在
     * 分辨率变化时反复取整丢精度。
     *
     * <p>上游接收 {@code IntSetting} 并在方法内 {@code get()}；本项目把读取移到调用方，
     * 本方法只接受已读出的原始值（{@code stored}）。
     */
    public static float normalizedPosition(int stored, float screenSize, float elementSize) {
        float travel = Math.max(0.0F, screenSize - elementSize);
        return travel * clamp(stored / 1000.0F, 0.0F, 1.0F);
    }

    /**
     * {@link #normalizedPosition(int, float, float)} 的逆运算：像素 → 0..1000。
     *
     * <p>上游是 {@code setNormalizedPosition(IntSetting, ...)}，直接写回设置对象。
     * 为去掉对 {@code com.setsuna.setting.settings} 的依赖，这里改为返回值，调用方自行
     * {@code setting.set(...)}；除"由谁写回"外，取值范围、夹取与四舍五入与上游逐行一致。
     */
    public static int normalizedFromPixel(float pixel, float screenSize, float elementSize) {
        float travel = Math.max(0.0F, screenSize - elementSize);
        return travel <= 0.0F ? 0 : Math.round(clamp(pixel / travel, 0.0F, 1.0F) * 1000.0F);
    }

    /**
     * 按可用宽度把文本裁到能放下为止，结尾补 "..."。
     *
     * <p>用二分找最长可容纳前缀，而不是逐字符加：HUD 每帧对多段文本调用，逐字符是 O(n²) 的
     * 宽度测量。宽度测量本身走 {@link #width}，因此粗体与常规体各自按真实字体度量裁剪。
     */
    public static String fit(String value, float maxWidth, float fontSize, boolean bold) {
        String text = value == null ? "" : value;
        if (maxWidth <= 0.0F) return "";
        if (width(text, fontSize, bold) <= maxWidth) return text;

        String suffix = "...";
        float suffixWidth = width(suffix, fontSize, bold);
        if (suffixWidth > maxWidth) return "";

        int low = 0;
        int high = text.length();
        while (low < high) {
            int mid = (low + high + 1) >>> 1;
            String candidate = text.substring(0, mid) + suffix;
            if (width(candidate, fontSize, bold) <= maxWidth) {
                low = mid;
            } else {
                high = mid - 1;
            }
        }
        return text.substring(0, low) + suffix;
    }

    public static float width(String text, float fontSize, boolean bold) {
        return bold ? SkijaUi.boldTextWidth(text, fontSize) : SkijaUi.textWidth(text, fontSize);
    }

    /**
     * 把每个数字字形换成宽数字 '8'，让数值变化时标签宽度稳定。
     *
     * <p>只替换 ASCII '0'..'9'，其余字符原样保留；空串与 null 返回空串。
     */
    public static String stableDigits(String value) {
        if (value == null || value.isEmpty()) return "";
        StringBuilder result = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            result.append(character >= '0' && character <= '9' ? '8' : character);
        }
        return result.toString();
    }

    public static float clamp(float value, float min, float max) {
        if (max < min) return min;
        return Math.max(min, Math.min(max, value));
    }

    public static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }
}
