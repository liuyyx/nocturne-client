package dev.nocturne.ui.fatality;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Module;

/**
 * Render 分类的本地玩家模型预览。原生库存渲染入口不可用时明确说明，不画假的人形/头像。
 *
 * <p>在抽取式绘制帧内委托游戏的库存界面提交人物模型，不接管 GL，也不暂存过期的帧上下文；
 * ESP / Nametags 药丸仍切换真实模块。
 */
final class FaPreviewCard extends FaBox {

    /** 卡片高。 */
    static final float HEIGHT = 288.0f;
    /** 底部开关行高。 */
    private static final float FOOTER = 40.0f;
    /** 开关药丸高。 */
    private static final float PILL_H = 24.0f;
    /** ESP 模块名。 */
    private static final String ESP_MODULE = "ESP";
    /** 标签模块名。 */
    private static final String NAMETAGS_MODULE = "NameTags";

    /** 位置动画。 */
    private final FaMotion x = new FaMotion(Float.NaN);
    /** 位置动画。 */
    private final FaMotion y = new FaMotion(Float.NaN);
    /** 开关药丸的悬停动画（ESP / Nametags）。 */
    private final FaMotion[] pillHover = {new FaMotion(0.0f), new FaMotion(0.0f)};
    /** 上一帧算出的两个开关矩形。 */
    private final float[][] pills = {new float[4], new float[4]};
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

    // ── 真实模块开关 ────────────────────────────────────────────────────────

    /** 按名字切换模块；模块不存在时忽略。 */
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
        ctx.draw.textMid("Player", gx + FaTheme.ROW_PAD, gy + FaTheme.GROUP_TITLE_H * 0.5f,
                FaTheme.title(), FaTheme.alpha(FaTheme.TITLE, alpha));

        NocturneClient client = NocturneClient.get();
        GameBridge bridge = client == null ? null : client.gameBridge();
        Object player = bridge == null ? null : bridge.player();
        if (player == null) {
            ctx.draw.textCenter("Enter a world first", gx + width * 0.5f, gy + 111.0f,
                    FaTheme.small(), FaTheme.alpha(FaTheme.MUTED, alpha));
        } else {
            float modelX = gx + FaTheme.ROW_PAD, modelY = gy + FaTheme.GROUP_TITLE_H + 4.0f;
            float modelW = Math.max(0.0f, width - FaTheme.ROW_PAD * 2.0f), modelH = 126.0f;
            if (!ctx.draw.player(player, modelX, modelY, modelW, modelH, ctx.mouseX, ctx.mouseY)) {
                ctx.draw.textCenter("Model preview unavailable", gx + width * 0.5f, gy + 111.0f,
                        FaTheme.small(), FaTheme.alpha(FaTheme.MUTED, alpha));
            }
            drawInfo(ctx, gx, gy, width, alpha, bridge, player);
        }
        drawFooter(ctx, gx, gy + HEIGHT - FOOTER, width, client, alpha);
    }

    /** 模型下沿保留玩家的实时状态；失败字段少画一行，不使预览消失。 */
    private void drawInfo(FaContext ctx, float gx, float gy, float width, float alpha,
                          GameBridge bridge, Object player) {
        FaFont font = FaTheme.small();
        float left = gx + FaTheme.ROW_PAD;
        float y = gy + 188.0f;
        float lineHeight = 20.0f;

        Object name = bridge.callMapped(player, ClassType.ENTITY, "getName");
        if (name != null) {
            Object text = name instanceof String ? name : bridge.callMapped(name, ClassType.CHAT_COMPONENT, "getString");
            if (text instanceof String) {
                row(ctx, font, left, y, width - FaTheme.ROW_PAD * 2.0f, "Name", (String) text, alpha);
                y += lineHeight;
            }
        }
        Object health = bridge.callMapped(player, ClassType.LIVING_ENTITY, "getHealth");
        if (health instanceof Number) {
            row(ctx, font, left, y, width - FaTheme.ROW_PAD * 2.0f, "Health",
                    String.format(java.util.Locale.ROOT, "%.1f", ((Number) health).floatValue()), alpha);
            y += lineHeight;
        }
        Object eye = bridge.callMapped(player, ClassType.ENTITY, "getEyePosition");
        if (eye != null) {
            Object ex = bridge.readField(eye, ClassType.VEC3, "x");
            Object ey = bridge.readField(eye, ClassType.VEC3, "y");
            Object ez = bridge.readField(eye, ClassType.VEC3, "z");
            if (ex instanceof Number && ey instanceof Number && ez instanceof Number) {
                row(ctx, font, left, y, width - FaTheme.ROW_PAD * 2.0f, "Pos", String.format(java.util.Locale.ROOT,
                        "%.0f %.0f %.0f", ((Number) ex).doubleValue(), ((Number) ey).doubleValue(),
                        ((Number) ez).doubleValue()), alpha);
                y += lineHeight;
            }
        }
    }

    /** 画一行「标签 + 值」，值超出右边界时截断。 */
    private static void row(FaContext ctx, FaFont font, float x, float y, float width,
                            String label, String value, float alpha) {
        ctx.draw.textMid(label, x, y, font, FaTheme.alpha(FaTheme.MUTED, alpha));
        String shown = FaTheme.fit(FaStrings.t(value), font, width * 0.55f);
        ctx.draw.textRightMid(shown, x + width, y, font, FaTheme.alpha(FaTheme.LABEL, alpha));
    }

    /** 底部两个开关药丸。 */
    private void drawFooter(FaContext ctx, float gx, float fy, float width, NocturneClient client, float alpha) {
        FaFont font = FaTheme.small();
        float px = gx + FaTheme.ROW_PAD;
        float mid = fy + FOOTER * 0.5f;
        ctx.draw.rect(px, fy, width - FaTheme.ROW_PAD * 2.0f, 0.5f,
                FaTheme.alpha(FaTheme.DIVIDER, alpha));
        for (int i = 0; i < pills.length; i++) {
            String label = i == 0 ? "ESP" : "Name Tags";
            Module module = client == null ? null : client.modules().get(i == 0 ? ESP_MODULE : NAMETAGS_MODULE);
            boolean on = module != null && module.isEnabled();
            float w = font.width(FaStrings.t(label)) + 24.0f;
            float[] pill = pills[i];
            pill[0] = px;
            pill[1] = mid - PILL_H * 0.5f;
            pill[2] = w;
            pill[3] = PILL_H;
            float hover = pillHover[i].toward(ctx.hovered(px, mid - PILL_H * 0.5f, w, PILL_H) ? 1.0f : 0.0f,
                    ctx.dt, 16.0f);
            ctx.draw.rect(px, mid - PILL_H * 0.5f, w, PILL_H, PILL_H * 0.5f,
                    FaTheme.alpha(FaTheme.mix(FaTheme.FIELD, FaTheme.FIELD_HOVER, hover), alpha));
            ctx.draw.rect(px + 6.0f, mid - 1.5f, 3.0f, 3.0f, 1.5f,
                    FaTheme.alpha(on ? FaTheme.ACCENT : 0xFF3A3A3A, alpha));
            int color = on ? FaTheme.mix(FaTheme.LABEL, FaTheme.LABEL_HOVER, hover)
                    : FaTheme.mix(FaTheme.FIELD_TEXT, FaTheme.FIELD_TEXT_HOVER, hover);
            ctx.draw.textMid(label, px + 14.0f, mid, font, FaTheme.alpha(color, alpha));
            px += w + 8.0f;
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
                toggleModule(i == 0 ? ESP_MODULE : NAMETAGS_MODULE);
                return true;
            }
        }
        // 卡片其余区域只吃掉点击：这里没有任何可拖拽的东西，不让它落到下面的控件上。
        return true;
    }

    @Override
    void mouseDragged(FaContext ctx, double mx, double my) {
        // 无拖拽交互。
    }

    @Override
    void mouseReleased() {
        // 无拖拽交互。
    }

}
