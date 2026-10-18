package dev.nocturne.ui.fatality;

import dev.nocturne.client.value.ColorValue;

import java.awt.Color;
import java.util.Locale;

/**
 * 调色板：饱和度/明度方板、垂直色相条、水平透明度条、复制/粘贴按钮与可编辑的
 * {@code #rrggbbaa} 输入框。
 *
 * <p>HSV 在本地保留，拖过灰/黑区域时不会丢掉色相（否则再拖动就回不到原来的颜色）。
 */
final class FaColorPopup extends FaPopup implements FaKeySink {

    /** 内边距。 */
    private static final float PAD = 8.0f;
    /** 饱和度/明度方板高。 */
    private static final float SV_H = 118.0f;
    /** 色相条宽。 */
    private static final float HUE_W = 9.0f;
    /** 区块间距。 */
    private static final float GAP = 6.0f;
    /** 透明度条高。 */
    private static final float ALPHA_H = 8.0f;
    /** 底部行高（复制/粘贴/HEX）。 */
    private static final float FOOT_H = 16.0f;
    /** 色相条的分段色标（首尾同色以闭合 0..360）。 */
    private static final int[] HUES = {
            0xFFFF0000, 0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF, 0xFF0000FF, 0xFFFF00FF, 0xFFFF0000,
    };

    /** 绑定的颜色设置。 */
    private final ColorValue color;
    /** 所属界面（焦点、提示）。 */
    private final FaClickGui gui;
    /** HEX 文本编辑。 */
    private final FaTextInput hex = new FaTextInput(9);
    /** 色相。 */
    private float hue;
    /** 饱和度。 */
    private float saturation;
    /** 明度。 */
    private float brightness;
    /** 不透明度。 */
    private float alpha;
    /** 上一次写回设置项的值；用于识别外部改动。 */
    private int lastWritten;
    /** 当前拖拽目标：0 方板、1 色相条、2 透明条、-1 无。 */
    private int dragging = -1;
    /** 是否正在编辑 HEX。 */
    private boolean editing;

    FaColorPopup(Object owner, ColorValue color, FaClickGui gui) {
        super(owner);
        this.color = color;
        this.gui = gui;
        w = 180.0f;
        h = height();
        load(color.argb());
        hex.onSubmit(this::applyHex).onCancel(new Runnable() {
            @Override
            public void run() {
                editing = false;
            }
        });
    }

    /** @return 浮层高度（无彩虹行） */
    private float height() {
        return PAD + SV_H + GAP + ALPHA_H + GAP + FOOT_H + PAD - 2.0f;
    }

    /** 由 ARGB 反解 HSV。 */
    private void load(int argb) {
        float[] hsb = Color.RGBtoHSB((argb >> 16) & 255, (argb >> 8) & 255, argb & 255, null);
        if (hsb[1] > 0.001f && hsb[2] > 0.001f) {
            // 灰/黑没有可用的色相，保留上一次的。
            hue = hsb[0];
        }
        saturation = hsb[1];
        brightness = hsb[2];
        alpha = ((argb >>> 24) & 255) / 255.0f;
        lastWritten = argb;
    }

    /** @return 由当前 HSV 组装出的 ARGB */
    private int current() {
        int rgb = Color.HSBtoRGB(hue, saturation, brightness) & 0xFFFFFF;
        return Math.round(alpha * 255.0f) << 24 | rgb;
    }

    /** 写回设置项。 */
    private void write() {
        int argb = current();
        color.set(argb);
        lastWritten = argb;
    }

    // ── 几何 ────────────────────────────────────────────────────────────────
    private float svX() {
        return x + PAD;
    }

    private float svY() {
        return y + PAD;
    }

    private float svW() {
        return w - PAD * 2.0f - HUE_W - GAP;
    }

    private float hueX() {
        return svX() + svW() + GAP;
    }

    private float alphaY() {
        return svY() + SV_H + GAP;
    }

    private float footY() {
        return alphaY() + ALPHA_H + GAP;
    }

    @Override
    protected void layout(FaContext ctx) {
        h = height();
        if (!editing && color.argb() != lastWritten) {
            load(color.argb());
        }
    }

    @Override
    protected void draw(FaContext ctx, float a) {
        float sx = svX();
        float sy = svY();
        float sw = svW();
        int pure = 0xFF000000 | (Color.HSBtoRGB(hue, 1.0f, 1.0f) & 0xFFFFFF);
        ctx.draw.horizontal(sx, sy, sw, SV_H, FaTheme.alpha(0xFFFFFFFF, a), FaTheme.alpha(pure, a));
        ctx.draw.vertical(sx, sy, sw, SV_H, 0x00000000, FaTheme.alpha(0xFF000000, a));
        float knobX = sx + saturation * sw;
        float knobY = sy + (1.0f - brightness) * SV_H;
        ctx.draw.outline(knobX - 3.5f, knobY - 3.5f, 7.0f, 7.0f, 3.5f, 1.0f, FaTheme.alpha(0xCC000000, a));
        ctx.draw.outline(knobX - 3.0f, knobY - 3.0f, 6.0f, 6.0f, 3.0f, 1.0f, FaTheme.alpha(0xFFFFFFFF, a));

        float hx = hueX();
        float segment = SV_H / 6.0f;
        for (int i = 0; i < 6; i++) {
            ctx.draw.vertical(hx, sy + i * segment, HUE_W, segment + 0.5f, FaTheme.alpha(HUES[i], a),
                    FaTheme.alpha(HUES[i + 1], a));
        }
        ctx.draw.rect(hx - 1.5f, sy + hue * SV_H - 1.0f, HUE_W + 3.0f, 2.0f, 1.0f,
                FaTheme.alpha(0xFFFFFFFF, a));

        float ay = alphaY();
        float aw = w - PAD * 2.0f;
        checker(ctx, sx, ay, aw, ALPHA_H, a);
        int rgb = current() & 0xFFFFFF;
        ctx.draw.horizontal(sx, ay, aw, ALPHA_H, FaTheme.alpha(0x00000000, a), FaTheme.alpha(0xFF000000 | rgb, a));
        ctx.draw.rect(sx + alpha * aw - 1.0f, ay - 1.5f, 2.0f, ALPHA_H + 3.0f, 1.0f,
                FaTheme.alpha(0xFFFFFFFF, a));

        float fy = footY();
        float mid = fy + FOOT_H * 0.5f;
        boolean copyHover = ctx.mouseX >= sx && ctx.mouseX <= sx + 14 && ctx.mouseY >= fy
                && ctx.mouseY <= fy + FOOT_H;
        boolean pasteHover = ctx.mouseX >= sx + 18 && ctx.mouseX <= sx + 32 && ctx.mouseY >= fy
                && ctx.mouseY <= fy + FOOT_H;
        ctx.draw.icon(FaTheme.I_COPY, sx + 7.0f, mid, 10.0f,
                FaTheme.alpha(copyHover ? FaTheme.ICON_HOVER : FaTheme.ICON, a));
        ctx.draw.icon(FaTheme.I_PASTE, sx + 25.0f, mid, 10.0f,
                FaTheme.alpha(pasteHover ? FaTheme.ICON_HOVER : FaTheme.ICON, a));
        FaFont font = FaTheme.field();
        float textX = sx + 42.0f;
        ctx.draw.rect(textX - 6.0f, fy, x + w - PAD - textX + 6.0f, FOOT_H, FaTheme.FIELD_RADIUS,
                FaTheme.alpha(editing ? FaTheme.FIELD_HOVER : FaTheme.FIELD, a));
        if (editing) {
            hex.render(ctx.draw, textX, mid, w - PAD - textX + x, font, FaTheme.alpha(0xFFDDDDDE, a), true, "", 0);
        } else {
            ctx.draw.textMid(hexString(current()), textX, mid, font, FaTheme.alpha(0xFFDDDDDE, a));
        }
    }

    /** 画棋盘格底（表现半透明）。 */
    private static void checker(FaContext ctx, float x, float y, float w, float h, float a) {
        ctx.draw.rect(x, y, w, h, FaTheme.alpha(0xFF3A3A3A, a));
        float cell = h / 2.0f;
        for (int column = 0; column * cell < w; column++) {
            for (int row = 0; row < 2; row++) {
                if ((column + row) % 2 == 0) {
                    continue;
                }
                ctx.draw.rect(x + column * cell, y + row * cell, Math.min(cell, w - column * cell), cell,
                        FaTheme.alpha(0xFF5A5A5A, a));
            }
        }
    }

    /** @return {@code #rrggbbaa} 形式（alpha 在末尾，与输入框显示一致） */
    static String hexString(int argb) {
        return String.format(Locale.ROOT, "#%06x%02x", argb & 0xFFFFFF, (argb >>> 24) & 255);
    }

    @Override
    boolean mouseClicked(FaContext ctx, double mx, double my, int button) {
        if (button != 0) {
            return true;
        }
        if (FaContext.inside(mx, my, svX(), svY(), svW(), SV_H)) {
            dragging = 0;
        } else if (FaContext.inside(mx, my, hueX() - 2, svY(), HUE_W + 4, SV_H)) {
            dragging = 1;
        } else if (FaContext.inside(mx, my, svX(), alphaY() - 2, w - PAD * 2, ALPHA_H + 4)) {
            dragging = 2;
        }
        if (dragging >= 0) {
            drag(mx, my);
            return true;
        }
        float fy = footY();
        if (FaContext.inside(mx, my, svX(), fy, 14, FOOT_H)) {
            Clipboard.set(hexString(current()));
            gui.toast("Copied " + hexString(current()));
        } else if (FaContext.inside(mx, my, svX() + 18, fy, 14, FOOT_H)) {
            Integer parsed = parseHex(Clipboard.get().trim());
            if (parsed != null) {
                load(parsed);
                write();
            }
        } else if (FaContext.inside(mx, my, svX() + 36, fy, w - PAD * 2 - 36, FOOT_H)) {
            editing = true;
            hex.set(hexString(current()));
            hex.focused();
            gui.focus(this);
        }
        return true;
    }

    @Override
    void mouseDragged(FaContext ctx, double mx, double my) {
        if (dragging >= 0) {
            drag(mx, my);
        }
    }

    @Override
    void mouseReleased() {
        dragging = -1;
    }

    /** 按拖拽目标更新 HSV。 */
    private void drag(double mx, double my) {
        switch (dragging) {
            case 0:
                saturation = clamp((float) ((mx - svX()) / svW()));
                brightness = 1.0f - clamp((float) ((my - svY()) / SV_H));
                break;
            case 1:
                hue = Math.min(0.9999f, clamp((float) ((my - svY()) / SV_H)));
                break;
            case 2:
                alpha = clamp((float) ((mx - svX()) / (w - PAD * 2.0f)));
                break;
            default:
                return;
        }
        write();
    }

    /** 提交 HEX 输入。 */
    private void applyHex(String text) {
        Integer parsed = parseHex(text.trim());
        if (parsed != null) {
            load(parsed);
            write();
        }
        editing = false;
        gui.focus(null);
    }

    /** 接受 {@code #rgb}、{@code #rrggbb} 与 {@code #rrggbbaa}（alpha 在末尾）。 */
    static Integer parseHex(String text) {
        String value = text.startsWith("#") ? text.substring(1) : text;
        try {
            if (value.length() == 3) {
                StringBuilder expanded = new StringBuilder();
                for (char c : value.toCharArray()) {
                    expanded.append(c).append(c);
                }
                return 0xFF000000 | Integer.parseInt(expanded.toString(), 16);
            }
            if (value.length() == 6) {
                return 0xFF000000 | Integer.parseInt(value, 16);
            }
            if (value.length() == 8) {
                long rgba = Long.parseLong(value, 16);
                return (int) ((rgba & 0xFF) << 24 | rgba >>> 8);
            }
        } catch (NumberFormatException ignored) {
            // 非法输入返回 null。
        }
        return null;
    }

    /** 夹取到 0..1。 */
    private static float clamp(float value) {
        return Math.max(0.0f, Math.min(1.0f, value));
    }

    @Override
    public boolean keyPressed(int key, int modifiers) {
        return editing && hex.keyPressed(key, modifiers);
    }

    @Override
    public boolean charTyped(char character) {
        return editing && (Character.digit(character, 16) >= 0 || character == '#') && hex.charTyped(character);
    }

    @Override
    public void focusLost() {
        editing = false;
    }

    @Override
    protected void onClose() {
        if (editing) {
            gui.focus(null);
        }
    }
}
