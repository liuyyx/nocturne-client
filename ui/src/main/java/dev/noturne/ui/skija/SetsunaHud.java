package dev.noturne.ui.skija;

import dev.noturne.client.module.Module;
import dev.noturne.client.module.ModuleRegistry;

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
 * <p>布局（与上游 HUD 的取向一致，读数集中在四角）：
 * <ul>
 *   <li>左上：品牌卡（强调色圆点 + 字距排版的客户端名），下面是模块发布的文本行；</li>
 *   <li>右上：帧率卡；</li>
 *   <li>右侧偏上：已启用模块列表（每行一张小卡片 + 强调色侧条）。</li>
 * </ul>
 *
 * <p>卡片一律用半透明填充：HUD 要能看清底下的游戏画面，实心块会挡住方块边缘与准星。
 */
public final class SetsunaHud {

    /** 卡片距屏幕边缘的边距。 */
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

    /**
     * @param registry 模块注册表（取「已启用」列表）
     * @param sink     模块文本行的汇；为 {@code null} 时不画文本行
     */
    public SetsunaHud(ModuleRegistry registry, SkijaHudSink sink) {
        this.registry = registry;
        this.sink = sink;
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
        drawBrand(canvas);
        drawRows(canvas);
        drawFps(canvas, width, fps);
        drawModuleList(canvas, width, height);
    }

    /** 左上品牌卡：强调色圆点 + 客户端名。 */
    private void drawBrand(Canvas canvas) {
        String name = "NOTURNE";
        float textSize = 9f;
        float tracking = 1.6f;
        float textWidth = SkijaControls.brandWidth(name, textSize, tracking);
        float boxWidth = CARD_PADDING * 2f + 10f + textWidth;
        SkijaControls.Box box = new SkijaControls.Box(MARGIN, MARGIN, boxWidth, BRAND_HEIGHT);
        card(canvas, box, SkijaControls.STROKE_STRONG);
        SkijaControls.disc(canvas, box.x + CARD_PADDING, box.y + BRAND_HEIGHT * 0.5f - 3f, 6f,
                SkijaTheme.accent());
        SkijaControls.brand(canvas, name, box.x + CARD_PADDING + 10f, box.y, BRAND_HEIGHT,
                SkijaControls.TEXT, textSize, tracking);
    }

    /** 品牌卡下方：模块发布的文本行（每行一张卡片，左对齐）。 */
    private void drawRows(Canvas canvas) {
        if (sink == null) {
            return;
        }
        List<String> values = sink.values();
        if (values.isEmpty()) {
            return;
        }
        float y = MARGIN + BRAND_HEIGHT + ROW_GAP;
        float textSize = 8.5f;
        for (String value : values) {
            float textWidth = SkijaUi.textWidth(value, textSize);
            SkijaControls.Box box = new SkijaControls.Box(MARGIN, y,
                    CARD_PADDING * 2f + textWidth, ROW_HEIGHT);
            card(canvas, box, SkijaControls.STROKE);
            SkijaUi.text(canvas, value, box.x + CARD_PADDING, box.y, ROW_HEIGHT,
                    SkijaControls.TEXT, textSize);
            y += ROW_HEIGHT + ROW_GAP;
        }
    }

    /** 右上帧率卡。 */
    private void drawFps(Canvas canvas, int width, float fps) {
        String text = Math.round(fps) + " FPS";
        float textSize = 9f;
        float textWidth = SkijaUi.textWidth(text, textSize);
        float boxWidth = CARD_PADDING * 2f + textWidth;
        SkijaControls.Box box = new SkijaControls.Box(width - MARGIN - boxWidth, MARGIN,
                boxWidth, BRAND_HEIGHT);
        card(canvas, box, SkijaControls.STROKE_STRONG);
        SkijaUi.text(canvas, text, box.x + CARD_PADDING, box.y, BRAND_HEIGHT,
                SkijaControls.TEXT, textSize);
    }

    /** 右侧：已启用模块列表（强调色侧条 + 名称，右对齐）。 */
    private void drawModuleList(Canvas canvas, int width, int height) {
        if (registry == null) {
            return;
        }
        List<Module> enabled = new ArrayList<Module>();
        for (Module module : registry.all()) {
            if (module.isEnabled()) {
                enabled.add(module);
            }
        }
        if (enabled.isEmpty()) {
            return;
        }
        int shown = Math.min(enabled.size(), MAX_MODULE_ROWS);
        float textSize = 8.5f;
        float y = MARGIN + BRAND_HEIGHT + ROW_GAP;
        for (int i = 0; i < shown; i++) {
            String name = enabled.get(i).name();
            float textWidth = SkijaUi.textWidth(name, textSize);
            float boxWidth = CARD_PADDING * 2f + 8f + textWidth;
            SkijaControls.Box box = new SkijaControls.Box(width - MARGIN - boxWidth, y,
                    boxWidth, MODULE_ROW_HEIGHT);
            card(canvas, box, SkijaControls.STROKE);
            // 强调色侧条：与 ClickGUI 的菜单行同一套语言，指示"这个功能正在生效"
            SkijaUi.rounded(canvas, box.x + 3f, box.y + 3f, 2f, MODULE_ROW_HEIGHT - 6f, 1f,
                    SkijaTheme.accent());
            SkijaUi.text(canvas, name, box.x + 3f + 5f + CARD_PADDING - 4f, box.y,
                    MODULE_ROW_HEIGHT, SkijaControls.TEXT_MUTED, textSize);
            y += MODULE_ROW_HEIGHT + ROW_GAP;
        }
        if (enabled.size() > shown) {
            String more = "+" + (enabled.size() - shown) + " more";
            float textWidth = SkijaUi.textWidth(more, textSize);
            float boxWidth = CARD_PADDING * 2f + textWidth;
            SkijaControls.Box box = new SkijaControls.Box(width - MARGIN - boxWidth, y,
                    boxWidth, MODULE_ROW_HEIGHT);
            card(canvas, box, SkijaControls.STROKE);
            SkijaUi.text(canvas, more, box.x + CARD_PADDING, box.y, MODULE_ROW_HEIGHT,
                    SkijaControls.TEXT_FAINT, textSize);
        }
    }

    /** HUD 卡片：半透明填充 + 1px 描边（比 {@code SkijaControls.section} 更轻，不铺重阴影）。 */
    private static void card(Canvas canvas, SkijaControls.Box box, int stroke) {
        SkijaControls.surface(canvas, box, SkijaControls.CARD, stroke, SkijaControls.RADIUS_SMALL);
    }
}
