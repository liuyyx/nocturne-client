package dev.noturne.ui.skija;

import dev.noturne.ui.gl.OverlayGui;
import dev.noturne.ui.render.Renderer;

import io.github.humbleui.skija.Canvas;
import io.github.humbleui.skija.ClipMode;
import io.github.humbleui.types.Rect;

/**
 * HUD 编辑器：把 HUD 的每一块（客户端名 / 模块文本 / 帧率 / 模块列表）变成可拖动的方块。
 *
 * <p>与 {@link SetsunaClickGui} 并列实现 {@link OverlayGui}，由叠加层在两者之间切换（点 ClickGUI
 * 标题栏的「编辑 HUD」进入，点「完成」或按 Esc 返回）。绘制与命中测试都用
 * {@link SetsunaHud#bounds(String, int, int, float)}——与 HUD 自身同一套几何，否则拖动热区会与
 * 看到的位置错开。
 *
 * <p>位置写在 {@link HudLayout} 里（与 HUD 共用同一份），因此拖动结果立刻生效、也不会在下一帧被
 * 默认位置覆盖。
 */
public final class SetsunaHudEditor implements OverlayGui {

    /** Esc 键码（AWT VK_ESCAPE）。 */
    private static final int KEY_ESCAPE = 27;
    /** 可拖动元素（顺序即标签顺序）。 */
    private static final String[] ELEMENT_IDS = {
            SetsunaHud.ID_BRAND, SetsunaHud.ID_ROWS, SetsunaHud.ID_FPS, SetsunaHud.ID_MODULES,
    };
    private static final String[] ELEMENT_LABELS = {"客户端名", "模块文本", "帧率", "模块列表"};

    /** 底部按钮尺寸与边距。 */
    private static final float BUTTON_WIDTH = 84f;
    private static final float BUTTON_HEIGHT = 24f;
    private static final float BUTTON_MARGIN = 12f;
    /** 顶部提示条高度。 */
    private static final float HINT_HEIGHT = 26f;

    private final SetsunaHud hud;
    private boolean open;
    private int viewportWidth;
    private int viewportHeight;
    private double mouseX;
    private double mouseY;
    /** 正在拖动的元素 id；{@code null} 表示没有拖动。 */
    private String draggingId;
    /** 按下点相对元素左上角的偏移，拖动时保持不变（否则方块会跳到指针下）。 */
    private float dragOffsetX;
    private float dragOffsetY;
    /** 供 HUD 预览使用的帧率（由叠加层每帧写入）。 */
    private float fps;
    /** 关闭时的回调（叠加层用它切回 ClickGUI）。 */
    private Runnable onClose;

    /**
     * @param hud 要编辑的 HUD（位置表与它共用）
     */
    public SetsunaHudEditor(SetsunaHud hud) {
        this.hud = hud;
    }

    /** 设置关闭回调；为 {@code null} 时关闭不触发任何动作（lab 场景）。 */
    public void setOnClose(Runnable callback) {
        this.onClose = callback;
    }

    /** 写入当前帧率（预览里的帧率卡会跟着变）。 */
    public void setFps(float value) {
        this.fps = value;
    }

    /** @return 正在拖动的元素 id；没有拖动时为 {@code null}（供测试断言） */
    public String draggingId() {
        return draggingId;
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public void setOpen(boolean value) {
        if (this.open == value) {
            return;
        }
        this.open = value;
        if (!value) {
            cancelInteractions();
        }
    }

    @Override
    public void toggle() {
        setOpen(!open);
    }

    @Override
    public void setViewport(int width, int height) {
        this.viewportWidth = width;
        this.viewportHeight = height;
    }

    @Override
    public void update(long nowMs, double mouseX, double mouseY) {
        this.mouseX = mouseX;
        this.mouseY = mouseY;
    }

    // ------------------------------------------------------------------ 绘制

    @Override
    public void render(Renderer renderer, Canvas canvas) {
        if (!open || canvas == null) {
            return;
        }
        int width = viewportWidth > 0 ? viewportWidth : 854;
        int height = viewportHeight > 0 ? viewportHeight : 480;

        // 编辑态压暗背景：既让 HUD 方块看得清，也提示"现在不是正常游玩状态"。
        // 用半透明而不是不透明：现场（准星位置、画面明暗）对摆放位置是有意义的参考。
        SkijaUi.fill(canvas, 0, 0, width, height, 0x66000000);
        // HUD 预览：画的就是真实 HUD，位置即拖动结果
        hud.render(canvas, width, height, fps);
        // 元素外框与标签
        for (int i = 0; i < ELEMENT_IDS.length; i++) {
            String id = ELEMENT_IDS[i];
            float[] box = hud.bounds(id, width, height, fps);
            if (box == null) {
                continue;
            }
            boolean active = id.equals(draggingId);
            boolean hovered = contains(box, mouseX, mouseY);
            int stroke = active ? SkijaTheme.accent()
                    : (hovered ? SkijaTheme.withAlpha(SkijaTheme.accent(), 200)
                    : SkijaTheme.withAlpha(SkijaTheme.BORDER_STRONG, 220));
            // 1px 描边 + 极淡填充：既能看见可拖范围，又不至于盖住 HUD 自己的卡片
            SkijaUi.rounded(canvas, box[0] - 1f, box[1] - 1f, box[2] + 2f, box[3] + 2f,
                    3f, active ? SkijaTheme.withAlpha(SkijaTheme.accent(), 40) : 0x14FFFFFF);
            SkijaUi.rounded(canvas, box[0] - 1f, box[1] - 1f, box[2] + 2f, box[3] + 2f,
                    3f, stroke);
            SkijaUi.text(canvas, ELEMENT_LABELS[i], box[0], box[1] - 12f, 12f, stroke, 8f);
        }
        drawHint(canvas, width);
        drawButtons(canvas, width, height);
    }

    /** 顶部提示条。 */
    private void drawHint(Canvas canvas, int width) {
        String text = "拖动方块摆放 HUD · Esc 或「完成」返回";
        float textSize = 8.5f;
        float textWidth = SkijaUi.textWidth(text, textSize);
        float boxWidth = textWidth + 24f;
        SkijaControls.Box box = new SkijaControls.Box((width - boxWidth) * 0.5f, 6f, boxWidth,
                HINT_HEIGHT);
        card(canvas, box, SkijaControls.STROKE_STRONG);
        SkijaUi.text(canvas, text, box.x + 12f, box.y, HINT_HEIGHT, SkijaControls.TEXT, textSize);
    }

    /** 右下角：重置位置 + 完成。 */
    private void drawButtons(Canvas canvas, int width, int height) {
        SkijaControls.Box done = doneButton(width, height);
        SkijaControls.button(canvas, done, "完成", done.contains(mouseX, mouseY), true,
                SkijaControls.Tone.PRIMARY);
        SkijaControls.Box reset = resetButton(width, height);
        SkijaControls.button(canvas, reset, "重置位置", reset.contains(mouseX, mouseY), true,
                SkijaControls.Tone.NORMAL);
    }

    private SkijaControls.Box doneButton(int width, int height) {
        return new SkijaControls.Box(width - BUTTON_MARGIN - BUTTON_WIDTH,
                height - BUTTON_MARGIN - BUTTON_HEIGHT, BUTTON_WIDTH, BUTTON_HEIGHT);
    }

    private SkijaControls.Box resetButton(int width, int height) {
        return new SkijaControls.Box(width - BUTTON_MARGIN - BUTTON_WIDTH * 2f - 6f,
                height - BUTTON_MARGIN - BUTTON_HEIGHT, BUTTON_WIDTH, BUTTON_HEIGHT);
    }

    private static void card(Canvas canvas, SkijaControls.Box box, int stroke) {
        SkijaControls.surface(canvas, box, SkijaControls.CARD, stroke, SkijaControls.RADIUS_SMALL);
    }

    // ------------------------------------------------------------------ 输入

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (!open || button != 0) {
            return false;
        }
        int width = viewportWidth > 0 ? viewportWidth : 854;
        int height = viewportHeight > 0 ? viewportHeight : 480;
        if (doneButton(width, height).contains(mx, my)) {
            setOpen(false);
            if (onClose != null) {
                onClose.run();
            }
            return true;
        }
        if (resetButton(width, height).contains(mx, my)) {
            hud.layout().resetAll();
            draggingId = null;
            return true;
        }
        // 从上到下命中：后画的元素在视觉上层，但这里元素互不重叠，顺序无关
        for (String id : ELEMENT_IDS) {
            float[] box = hud.bounds(id, width, height, fps);
            if (box != null && contains(box, mx, my)) {
                draggingId = id;
                dragOffsetX = (float) mx - box[0];
                dragOffsetY = (float) my - box[1];
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        if (!open || draggingId == null) {
            return false;
        }
        int width = viewportWidth > 0 ? viewportWidth : 854;
        int height = viewportHeight > 0 ? viewportHeight : 480;
        float[] box = hud.bounds(draggingId, width, height, fps);
        if (box == null) {
            return false;
        }
        float x = (float) mx - dragOffsetX;
        float y = (float) my - dragOffsetY;
        // 拖动时就把位置夹在屏幕内：允许拖出去一点点（bounds 里还会再夹一次），但不能完全拖没
        x = Math.max(-box[2] * 0.5f, Math.min(x, width - box[2] * 0.5f));
        y = Math.max(0f, Math.min(y, height - box[3]));
        hud.layout().set(draggingId, x, y);
        return true;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        boolean wasDragging = draggingId != null;
        draggingId = null;
        return open && wasDragging;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double amount) {
        // 编辑态不滚动：位置是拖出来的，滚动会让"我到底移到哪了"变得难以复现
        return open;
    }

    @Override
    public boolean keyPressed(int keyCode, int modifiers) {
        if (!open) {
            return false;
        }
        if (keyCode == KEY_ESCAPE) {
            setOpen(false);
            if (onClose != null) {
                onClose.run();
            }
            return true;
        }
        return false;
    }

    @Override
    public void cancelInteractions() {
        draggingId = null;
    }

    /** 指针是否落在元素框内（半开区间，与其它命中测试一致）。 */
    private static boolean contains(float[] box, double mx, double my) {
        return mx >= box[0] && mx < box[0] + box[2] && my >= box[1] && my < box[1] + box[3];
    }
}
