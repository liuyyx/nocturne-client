package dev.nocturne.ui.fatality;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Module;

/**
 * Render 分类里的 "Player" 卡片：显示本地玩家的实时状态，并把视觉模块的开关放在同一处。
 *
 * <p>为什么不是 3D 模型预览：把玩家模型画进界面需要离屏渲染 + 纹理中转（旧 Skija 直写帧缓冲会盖黑
 * 游戏，见 docs/VERSION-MATRIX.md），这条路径还没落地。做一个"看起来像预览、实际点了没反应"的
 * 装饰（旧实现里的拖拽旋转就是这种）比不做更糟，所以这里改成显示真实数据：有玩家就显示名字与血量，
 * 没有玩家就明确说明。
 *
 * <p>底部两个开关（ESP / Nametags）直接切换真实模块，不是装饰。
 */
final class FaPreviewCard extends FaBox {

    /** 卡片高。 */
    static final float HEIGHT = 238.0f;
    /** 底部开关行高。 */
    private static final float FOOTER = 24.0f;
    /** 标题相对卡片顶边的中心偏移。 */
    private static final float TITLE_RISE = 1.5f;
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

    /** 两个开关的当前状态。 */
    private static final class Effects {
        /** ESP 是否启用。 */
        boolean esp;
        /** Nametags 是否启用。 */
        boolean nametags;
    }

    /** 读取两个开关的真实状态。 */
    private static Effects effects() {
        Effects effects = new Effects();
        NocturneClient client = NocturneClient.get();
        if (client == null) {
            return effects;
        }
        Module esp = client.modules().get(ESP_MODULE);
        Module nametags = client.modules().get(NAMETAGS_MODULE);
        effects.esp = esp != null && esp.isEnabled();
        effects.nametags = nametags != null && nametags.isEnabled();
        return effects;
    }

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
        ctx.draw.textMid("Player", gx + 9.5f, gy + TITLE_RISE, FaTheme.title(),
                FaTheme.alpha(FaTheme.TITLE, alpha));

        drawInfo(ctx, gx, gy, width, alpha);
        drawFooter(ctx, gx, gy + HEIGHT - FOOTER, width, effects(), alpha);
    }

    /**
     * 画玩家实时状态。
     *
     * <p>数据经映射表反射读取：任一字段在目标版本上解析不到时就少显示那一行，不会让整张卡片空掉。
     */
    private void drawInfo(FaContext ctx, float gx, float gy, float width, float alpha) {
        FaFont font = FaTheme.small();
        float left = gx + 10.0f;
        float y = gy + 26.0f;
        float lineHeight = 14.0f;
        float areaH = HEIGHT - 26.0f - FOOTER - 8.0f;

        NocturneClient client = NocturneClient.get();
        GameBridge bridge = client == null ? null : client.gameBridge();
        Object player = bridge == null ? null : bridge.player();
        if (player == null) {
            ctx.draw.textMid("Enter a world first", gx + width * 0.5f, gy + 26.0f + areaH * 0.5f,
                    font, FaTheme.alpha(FaTheme.MUTED, alpha));
            return;
        }

        Object name = bridge.callMapped(player, ClassType.ENTITY, "getName");
        if (name != null) {
            row(ctx, font, left, y, width - 20.0f, "Name", String.valueOf(name), alpha);
            y += lineHeight;
        }
        Object health = bridge.callMapped(player, ClassType.LIVING_ENTITY, "getHealth");
        if (health instanceof Number) {
            row(ctx, font, left, y, width - 20.0f, "Health",
                    String.format(java.util.Locale.ROOT, "%.1f", ((Number) health).floatValue()), alpha);
            y += lineHeight;
        }
        Object eye = bridge.callMapped(player, ClassType.ENTITY, "getEyePosition");
        if (eye != null) {
            Object ex = bridge.readField(eye, ClassType.VEC3, "x");
            Object ey = bridge.readField(eye, ClassType.VEC3, "y");
            Object ez = bridge.readField(eye, ClassType.VEC3, "z");
            if (ex instanceof Number && ey instanceof Number && ez instanceof Number) {
                row(ctx, font, left, y, width - 20.0f, "Pos", String.format(java.util.Locale.ROOT,
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
        String shown = FaTheme.fit(value, font, width * 0.55f);
        ctx.draw.textRightMid(shown, x + width, y, font, FaTheme.alpha(FaTheme.LABEL, alpha));
    }

    /** 底部两个开关药丸。 */
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
                    FaTheme.alpha(FaTheme.mix(FaTheme.FIELD, FaTheme.FIELD_HOVER, hover), alpha));
            ctx.draw.rect(px + 6.0f, mid - 1.5f, 3.0f, 3.0f, 1.5f,
                    FaTheme.alpha(on[i] ? FaTheme.ACCENT : 0xFF3A3A3A, alpha));
            int color = on[i] ? FaTheme.mix(FaTheme.LABEL, FaTheme.LABEL_HOVER, hover)
                    : FaTheme.mix(FaTheme.FIELD_TEXT, FaTheme.FIELD_TEXT_HOVER, hover);
            ctx.draw.textMid(labels[i], px + 12.0f, mid, font, FaTheme.alpha(color, alpha));
            px += w + 5.0f;
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

    @Override
    boolean mouseScrolled(FaContext ctx, double mx, double my, double amount) {
        return false;
    }
}
