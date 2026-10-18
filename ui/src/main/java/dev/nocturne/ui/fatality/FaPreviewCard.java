package dev.nocturne.ui.fatality;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.module.Module;

/**
 * Render 分类里的 "Preview" 卡片：把本地玩家模型画进卡片，展示视觉模块对玩家的效果。
 *
 * <p>模型由 {@code dev.nocturne.ui.preview.PlayerPreview} 离屏渲染成纹理，再经
 * {@link FaDraw#player} 一次画进来；离屏渲染不可用时（类缺失、帧缓冲不完整、26.x 的 PiP 不可用）
 * 卡片降级为占位图标与说明文字——界面照常可用，区块不空着。
 *
 * <p>底部三个开关（ESP / 光照 / Nametags）直接切换真实模块，不是装饰：卡片显示的正是这些模块的
 * 当前状态，点一下就能看到世界里的变化。
 */
final class FaPreviewCard extends FaBox {

    /** 卡片高。 */
    static final float HEIGHT = 238.0f;
    /** 底部开关行高。 */
    private static final float FOOTER = 24.0f;
    /** 标题相对卡片顶边的中心偏移。 */
    private static final float TITLE_RISE = 1.5f;
    /** ESP 发光半径（设计单位）。 */
    private static final float GLOW_RADIUS = 9.0f;
    /** 开关药丸高。 */
    private static final float PILL_H = 13.0f;
    /** ESP 模块名。 */
    private static final String ESP_MODULE = "ESP";
    /** 标签模块名。 */
    private static final String NAMETAGS_MODULE = "NameTags";

    /** 位置动画。 */
    private final FaMotion x = new FaMotion(Float.NaN);
    /** 位置动画。 */
    private final FaMotion y = new FaMotion(Float.NaN);
    /** 头部偏航动画。 */
    private final FaMotion headYaw = new FaMotion(0.0f);
    /** 头部俯仰动画。 */
    private final FaMotion headPitch = new FaMotion(0.0f);
    /** 开关药丸的悬停动画（ESP / Nametags）。 */
    private final FaMotion[] pillHover = {new FaMotion(0.0f), new FaMotion(0.0f)};
    /** 上一帧算出的药丸矩形（{@code null} 表示该项本帧没画）。 */
    private final float[][] pills = new float[2][];
    /** 目标位置。 */
    private float targetX;
    /** 目标位置。 */
    private float targetY;
    /** 上一帧的绘制矩形。 */
    private float lastX;
    private float lastY;
    private float lastW;
    /** 上一帧是否画出来了。 */
    private boolean drawn;
    /** 模型朝向（度）。 */
    private float yaw = 22.0f;
    /** 松手后的惯性角速度（度/秒）。 */
    private float spin;
    /** 是否正在拖拽旋转。 */
    private boolean dragging;
    /** 拖拽参考点。 */
    private double dragX;

    @Override
    float targetHeight() {
        return HEIGHT;
    }

    @Override
    void place(float targetX, float targetY, boolean snap) {
        this.targetX = targetX;
        this.targetY = targetY;
        if (snap || Float.isNaN(x.value())) {
            x.snap(targetX);
            y.snap(targetY);
        }
    }

    @Override
    float targetY() {
        return targetY;
    }

    // ── 被预览的效果状态 ────────────────────────────────────────────────────

    /** 三个开关的当前状态。 */
    private static final class Effects {
        /** ESP 是否启用。 */
        boolean esp;
        /** 是否走发光（否则描边）。 */
        boolean glow;
        /** Nametags 是否启用。 */
        boolean nametags;
    }

    /** @return 从真实模块读到的开关状态 */
    private static Effects effects() {
        Effects state = new Effects();
        state.esp = isEnabled(ESP_MODULE);
        state.nametags = isEnabled(NAMETAGS_MODULE);
        // 没有单独的"着色器"开关：ESP 启用即按发光预览。
        state.glow = state.esp;
        return state;
    }

    /** @return 给定名字的模块是否已启用 */
    private static boolean isEnabled(String name) {
        NocturneClient client = NocturneClient.get();
        if (client == null) {
            return false;
        }
        Module module = client.modules().get(name);
        return module != null && module.isEnabled();
    }

    /** 切换给定名字的模块。 */
    private static void toggleModule(String name) {
        NocturneClient client = NocturneClient.get();
        if (client == null) {
            return;
        }
        Module module = client.modules().get(name);
        if (module != null) {
            module.toggle();
        }
    }

    // ── 绘制 ────────────────────────────────────────────────────────────────

    @Override
    void render(FaContext ctx, float originX, float originY, float width, float viewTop, float viewBottom,
                float alpha) {
        float gx = originX + x.toward(targetX, ctx.dt, 16.0f);
        float gy = originY + y.toward(targetY, ctx.dt, 16.0f);
        lastX = gx;
        lastY = gy;
        lastW = width;
        drawn = gy + HEIGHT >= viewTop && gy <= viewBottom;
        if (!drawn) {
            return;
        }

        ctx.draw.rect(gx, gy, width, HEIGHT, FaTheme.GROUP_RADIUS, FaTheme.alpha(FaTheme.GROUP, alpha));
        ctx.draw.outline(gx, gy, width, HEIGHT, FaTheme.GROUP_RADIUS, 0.5f,
                FaTheme.alpha(FaTheme.GROUP_BORDER, alpha));
        ctx.draw.textMid("Preview", gx + 9.5f, gy + TITLE_RISE, FaTheme.title(),
                FaTheme.alpha(FaTheme.TITLE, alpha));

        Effects effects = effects();
        float areaX = gx + 6.0f;
        float areaY = gy + 10.0f;
        float areaW = width - 12.0f;
        float areaH = HEIGHT - 10.0f - FOOTER;
        float modelH = Math.max(24.0f, Math.min(areaH - 8.0f, areaW * 1.45f));
        float feetX = gx + width * 0.5f;
        float feetY = areaY + areaH - 6.0f;

        updatePose(ctx, areaX, areaY, areaW, areaH, feetX, feetY - modelH * 0.9f);
        ctx.draw.shadow(feetX - modelH * 0.17f, feetY - 1.5f, modelH * 0.34f, 3.0f, 1.5f, 5.0f,
                FaTheme.alpha(0x90000000, alpha));
        int esp = !effects.esp ? FaDraw.ESP_NONE : effects.glow ? FaDraw.ESP_GLOW : FaDraw.ESP_OUTLINE;
        int colorA = FaTheme.ACCENT;
        FaDraw.PlayerView view = new FaDraw.PlayerView(yaw, headYaw.value(), headPitch.value(), esp,
                colorA, colorA, GLOW_RADIUS);
        boolean shown = ctx.draw.player(areaX, areaY, areaW, areaH, feetX, feetY, modelH, view);
        if (!shown) {
            ctx.draw.icon(FaTheme.I_PLAYER, feetX, areaY + areaH * 0.42f, 22.0f,
                    FaTheme.alpha(0xFF2E2E2E, alpha));
            ctx.draw.textCenter("Preview unavailable", feetX, areaY + areaH * 0.42f + 22.0f,
                    FaTheme.small(), FaTheme.alpha(FaTheme.MUTED, alpha));
        }
        drawFooter(ctx, gx, gy + HEIGHT - FOOTER, width, effects, alpha);
    }

    /** 推进模型朝向：拖拽改变朝向，悬停时头部略微跟随指针。 */
    private void updatePose(FaContext ctx, float ax, float ay, float aw, float ah, float eyeX, float eyeY) {
        if (dragging) {
            // 松手前先停住，否则一松手会把模型甩出去。
            spin *= (float) Math.exp(-ctx.dt * 18.0f);
        } else {
            yaw += spin * ctx.dt;
            spin *= (float) Math.exp(-ctx.dt * 3.5f);
            if (Math.abs(spin) < 0.5f) {
                spin = 0.0f;
            }
        }
        boolean hovered = !dragging && ctx.hovered(ax, ay, aw, ah);
        float targetYaw = hovered
                ? (float) Math.toDegrees(Math.atan((eyeX - ctx.mouseX) / 40.0f)) * 0.35f : 0.0f;
        float targetPitch = hovered
                ? -(float) Math.toDegrees(Math.atan((eyeY - ctx.mouseY) / 40.0f)) * 0.35f : 0.0f;
        headYaw.toward(targetYaw, ctx.dt, 9.0f);
        headPitch.toward(targetPitch, ctx.dt, 9.0f);
    }

    /** 底部三个开关药丸 + 右侧操作提示。 */
    private void drawFooter(FaContext ctx, float gx, float fy, float width, Effects effects, float alpha) {
        FaFont font = FaTheme.small();
        String[] labels = {"ESP", "Nametags"};
        boolean[] on = {effects.esp, effects.nametags};
        float px = gx + 10.0f;
        float mid = fy + FOOTER * 0.5f - 1.0f;
        for (int i = 0; i < labels.length; i++) {
            float w = font.width(labels[i]) + 18.0f;
            pills[i] = new float[]{px, mid - PILL_H * 0.5f, w, PILL_H};
            float hover = pillHover[i].toward(ctx.hovered(px, mid - PILL_H * 0.5f, w, PILL_H) ? 1.0f : 0.0f,
                    ctx.dt, 16.0f);
            ctx.draw.rect(px, mid - PILL_H * 0.5f, w, PILL_H, PILL_H * 0.5f,
                    FaTheme.alpha(FaTheme.mix(FaTheme.FIELD, 0xFF1F1F1F, hover), alpha));
            ctx.draw.rect(px + 6.0f, mid - 1.5f, 3.0f, 3.0f, 1.5f,
                    FaTheme.alpha(on[i] ? FaTheme.ACCENT : 0xFF3A3A3A, alpha));
            int color = on[i] ? FaTheme.mix(FaTheme.LABEL, FaTheme.LABEL_HOVER, hover)
                    : FaTheme.mix(FaTheme.FIELD_TEXT, FaTheme.FIELD_TEXT_HOVER, hover);
            ctx.draw.textMid(labels[i], px + 12.0f, mid, font, FaTheme.alpha(color, alpha));
            px += w + 5.0f;
        }
        String hint = "Drag to turn";
        if (px + font.width(hint) + 6.0f <= gx + width - 10.0f) {
            ctx.draw.textRightMid(hint, gx + width - 10.0f, mid, font, FaTheme.alpha(0xFF3C3C3C, alpha));
        }
    }

    // ── 输入 ────────────────────────────────────────────────────────────────

    @Override
    boolean mouseClicked(FaContext ctx, double mx, double my, int button) {
        if (!drawn || !FaContext.inside(mx, my, lastX, lastY, lastW, HEIGHT)) {
            return false;
        }
        if (button != 0) {
            return true;
        }
        for (int i = 0; i < pills.length; i++) {
            float[] pill = pills[i];
            if (pill != null && FaContext.inside(mx, my, pill[0], pill[1], pill[2], pill[3])) {
                toggle(i);
                return true;
            }
        }
        if (my < lastY + HEIGHT - FOOTER) {
            dragging = true;
            dragX = mx;
            spin = 0.0f;
        }
        return true;
    }

    /** 切换第 {@code index} 个开关。 */
    private static void toggle(int index) {
        toggleModule(index == 0 ? ESP_MODULE : NAMETAGS_MODULE);
    }

    @Override
    void mouseDragged(FaContext ctx, double mx, double my) {
        if (!dragging) {
            return;
        }
        float delta = (float) (mx - dragX);
        dragX = mx;
        yaw += delta * 1.6f;
        // 记住转动速度，松手后会继续慢慢转一小会儿再停。
        spin = ctx.dt > 0.0f ? delta * 1.6f / Math.max(ctx.dt, 1.0f / 240.0f) * 0.6f : 0.0f;
        spin = Math.max(-720.0f, Math.min(720.0f, spin));
    }

    @Override
    void mouseReleased() {
        dragging = false;
    }

    @Override
    boolean mouseScrolled(FaContext ctx, double mx, double my, double amount) {
        return false;
    }
}
