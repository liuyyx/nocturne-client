package dev.nocturne.ui.skija;

import dev.nocturne.client.module.Module;
import dev.nocturne.client.module.ModuleRegistry;

import io.github.humbleui.skija.Canvas;

import java.util.ArrayList;
import java.util.List;

/**
 * Setsuna 风格的 HUD：直接用 Skija 画布绘制，**不铺背板**（叠加在游戏画面上）。
 *
 * <p>数据全部来自现有抽象，不碰 Minecraft：启用的模块列表来自 {@link ModuleRegistry}，
 * 模块发布的文本行来自 {@link SkijaHudSink}，帧率由调用方（叠加层）算好传入。这样 HUD 在
 * 1.8.9 与 26.x 上是同一份代码，也不需要映射表。
 *
 * <p>四块元素的位置来自 {@link HudLayout}（可被 {@link SetsunaHudEditor} 拖动改写）：
 * 品牌卡、模块文本行、帧率卡、已启用模块列表。绘制与编辑器共用 {@link #bounds(String, int, int, float)}
 * 计算几何——两处各算一次的话，拖动的热区会与显示位置错开。
 *
 * <p>卡片一律用半透明填充：HUD 要能看清底下的游戏画面，实心块会挡住方块边缘与准星。
 */
public final class SetsunaHud {

    /** 品牌卡。 */
    public static final String ID_BRAND = "brand";
    /** 模块发布的文本行（整体一块，内部逐行排列）。 */
    public static final String ID_ROWS = "rows";
    /** 帧率卡。 */
    public static final String ID_FPS = "fps";
    /** 已启用模块列表。 */
    public static final String ID_MODULES = "modules";

    /** 卡片距屏幕边缘的默认边距（仅用于默认位置）。 */
    private static final float MARGIN = 6f;
    /** 卡片内文本的左右内缩。 */
    private static final float CARD_PADDING = 7f;
    /** 品牌卡高度。 */
    private static final float BRAND_HEIGHT = 20f;
    /** 文本行高度与行距。 */
    private static final float ROW_HEIGHT = 15f;
    private static final float ROW_GAP = 3f;
    /** 模块列表每行高度。 */
    private static final float MODULE_ROW_HEIGHT = 14f;
    /** 同时显示的模块名上限：超出就折叠成一行计数，避免长列表吃掉半屏。 */
    private static final int MAX_MODULE_ROWS = 14;

    private final ModuleRegistry registry;
    private final SkijaHudSink sink;
    private final HudLayout layout;

    /**
     * @param registry 模块注册表（取「已启用」列表）
     * @param sink     模块文本行的汇；为 {@code null} 时不画文本行
     * @param layout   位置表；为 {@code null} 时创建一份内部默认
     */
    public SetsunaHud(ModuleRegistry registry, SkijaHudSink sink, HudLayout layout) {
        this.registry = registry;
        this.sink = sink;
        this.layout = layout == null ? new HudLayout() : layout;
    }

    /** @return 位置表（编辑器要读写同一份） */
    public HudLayout layout() {
        return layout;
    }

    /**
     * 画一帧 HUD。
     *
     * @param canvas 本帧画布
     * @param width  绘制区宽度（像素）
     * @param height 绘制区高度（像素）
     * @param fps    当前帧率（由叠加层平滑后传入）
     */
    public void render(Canvas canvas, int width, int height, float fps) {
        if (canvas == null || width <= 0 || height <= 0) {
            return;
        }
        drawBrand(canvas, width, height);
        drawRows(canvas, width, height);
        drawFps(canvas, width, height, fps);
        drawModuleList(canvas, width, height);
    }

    /**
     * 计算某个元素的边界（左上角 + 宽高），并在首次调用时懒初始化默认位置。
     *
     * <p>绘制与编辑器都用它：编辑器据此画虚线外框与作为拖动热区，因此**必须**是同一个函数。
     *
     * @param id     元素 id（{@link #ID_BRAND} 等）
     * @param width  屏幕宽
     * @param height 屏幕高
     * @param fps    当前帧率（仅帧率卡需要）
     * @return {x, y, width, height}；未知 id 返回 {@code null}
     */
    public float[] bounds(String id, int width, int height, float fps) {
        float[] size = sizeOf(id, width, fps);
        if (size == null) {
            return null;
        }
        float[] defaults = defaultPosition(id, width, height, size[0], size[1]);
        layout.ensureDefault(id, defaults[0], defaults[1]);
        float x = layout.x(id);
        float y = layout.y(id);
        // 夹取到屏幕内：分辨率变小或位置被外部写坏时，元素不会跑到看不见的地方
        x = Math.max(-size[0] * 0.5f, Math.min(x, width - size[0] * 0.5f));
        y = Math.max(0f, Math.min(y, height - size[1]));
        return new float[]{x, y, size[0], size[1]};
    }

    /** 元素自身的尺寸（与位置无关）。 */
    private float[] sizeOf(String id, int width, float fps) {
        if (ID_BRAND.equals(id)) {
            return new float[]{CARD_PADDING * 2f + 10f + SkijaControls.brandWidth("NOCTURNE", 9f, 1.6f),
                    BRAND_HEIGHT};
        }
        if (ID_ROWS.equals(id)) {
            List<String> values = sink == null ? null : sink.values();
            if (values == null || values.isEmpty()) {
                return null;
            }
            float textSize = 8.5f;
            float widest = 0f;
            for (String value : values) {
                widest = Math.max(widest, SkijaUi.textWidth(value, textSize));
            }
            float height = values.size() * ROW_HEIGHT + (values.size() - 1) * ROW_GAP;
            return new float[]{CARD_PADDING * 2f + widest, height};
        }
        if (ID_FPS.equals(id)) {
            return new float[]{CARD_PADDING * 2f + SkijaUi.textWidth(Math.round(fps) + " FPS", 9f),
                    BRAND_HEIGHT};
        }
        if (ID_MODULES.equals(id)) {
            List<String> names = enabledNames();
            if (names.isEmpty()) {
                return null;
            }
            int shown = Math.min(names.size(), MAX_MODULE_ROWS);
            float textSize = 8.5f;
            float widest = 0f;
            for (int i = 0; i < shown; i++) {
                widest = Math.max(widest, SkijaUi.textWidth(names.get(i), textSize));
            }
            if (names.size() > shown) {
                widest = Math.max(widest, SkijaUi.textWidth("+" + (names.size() - shown) + " more", textSize));
            }
            float rows = shown + (names.size() > shown ? 1 : 0);
            float height = rows * MODULE_ROW_HEIGHT + (rows - 1) * ROW_GAP;
            return new float[]{CARD_PADDING * 2f + 8f + widest, height};
        }
        return null;
    }

    /** 默认位置：品牌与文本行靠左上，帧率与模块列表靠右上（右上需要宽度，所以在用的时候才算得出）。 */
    private static float[] defaultPosition(String id, int width, int height, float boxWidth,
                                           float boxHeight) {
        if (ID_BRAND.equals(id)) {
            return new float[]{MARGIN, MARGIN};
        }
        if (ID_ROWS.equals(id)) {
            return new float[]{MARGIN, MARGIN + BRAND_HEIGHT + ROW_GAP};
        }
        if (ID_FPS.equals(id)) {
            return new float[]{width - MARGIN - boxWidth, MARGIN};
        }
        return new float[]{width - MARGIN - boxWidth, MARGIN + BRAND_HEIGHT + ROW_GAP};
    }

    private List<String> enabledNames() {
        List<String> names = new ArrayList<String>();
        if (registry == null) {
            return names;
        }
        for (Module module : registry.all()) {
            if (module.isEnabled()) {
                names.add(module.name());
            }
        }
        return names;
    }

    /** 品牌卡：强调色圆点 + 客户端名。 */
    private void drawBrand(Canvas canvas, int width, int height) {
        float[] box = bounds(ID_BRAND, width, height, 0f);
        String name = "NOCTURNE";
        card(canvas, box, SkijaControls.STROKE_STRONG);
        SkijaControls.disc(canvas, box[0] + CARD_PADDING, box[1] + BRAND_HEIGHT * 0.5f - 3f, 6f,
                SkijaTheme.accent());
        SkijaControls.brand(canvas, name, box[0] + CARD_PADDING + 10f, box[1], BRAND_HEIGHT,
                SkijaControls.TEXT, 9f, 1.6f);
    }
    /** 模块发布的文本行（整块一张卡片，内部逐行排列）。 */
    private void drawRows(Canvas canvas, int width, int height) {
        if (sink == null) {
            return;
        }
        List<String> values = sink.values();
        if (values.isEmpty()) {
            return;
        }
        float[] box = bounds(ID_ROWS, width, height, 0f);
        if (box == null) {
            return;
        }
        card(canvas, box, SkijaControls.STROKE);
        float textSize = 8.5f;
        float y = box[1] + (ROW_HEIGHT - textSize) * 0.5f - 1f;
        for (String value : values) {
            SkijaUi.text(canvas, value, box[0] + CARD_PADDING, y, ROW_HEIGHT,
                    SkijaControls.TEXT, textSize);
            y += ROW_HEIGHT + ROW_GAP;
        }
    }

    /** 帧率卡。 */
    private void drawFps(Canvas canvas, int width, int height, float fps) {
        float[] box = bounds(ID_FPS, width, height, fps);
        card(canvas, box, SkijaControls.STROKE_STRONG);
        SkijaUi.text(canvas, Math.round(fps) + " FPS", box[0] + CARD_PADDING, box[1],
                BRAND_HEIGHT, SkijaControls.TEXT, 9f);
    }

    /** 已启用模块列表（强调色侧条 + 名称）。 */
    private void drawModuleList(Canvas canvas, int width, int height) {
        List<String> names = enabledNames();
        if (names.isEmpty()) {
            return;
        }
        float[] box = bounds(ID_MODULES, width, height, 0f);
        if (box == null) {
            return;
        }
        card(canvas, box, SkijaControls.STROKE);
        int shown = Math.min(names.size(), MAX_MODULE_ROWS);
        float textSize = 8.5f;
        float y = box[1];
        for (int i = 0; i < shown; i++) {
            row(canvas, box[0], y, box[2], names.get(i), SkijaControls.TEXT_MUTED, textSize);
            y += MODULE_ROW_HEIGHT + ROW_GAP;
        }
        if (names.size() > shown) {
            row(canvas, box[0], y, box[2], "+" + (names.size() - shown) + " more",
                    SkijaControls.TEXT_FAINT, textSize);
        }
    }

    /** 列表里的一行：强调色侧条 + 文本。 */
    private static void row(Canvas canvas, float x, float y, float width, String text, int color,
                            float textSize) {
        SkijaUi.rounded(canvas, x + 3f, y + 3f, 2f, MODULE_ROW_HEIGHT - 6f, 1f,
                SkijaTheme.accent());
        SkijaUi.text(canvas, text, x + 8f + CARD_PADDING - 4f, y, MODULE_ROW_HEIGHT, color,
                textSize);
    }

    /** HUD 卡片：半透明填充 + 1px 描边（比 {@code SkijaControls.section} 更轻，不铺重阴影）。 */
    private static void card(Canvas canvas, float[] bounds, int stroke) {
        SkijaControls.surface(canvas, new SkijaControls.Box(bounds[0], bounds[1], bounds[2],
                bounds[3]), SkijaControls.CARD, stroke, SkijaControls.RADIUS_SMALL);
    }
}
