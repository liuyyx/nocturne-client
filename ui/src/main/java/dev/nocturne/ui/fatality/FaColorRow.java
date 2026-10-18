package dev.nocturne.ui.fatality;

import dev.nocturne.client.value.ColorValue;

import java.util.Locale;

/** 右侧一个小的颜色方块；点击在下方打开调色板。 */
final class FaColorRow extends FaRow {

    /** 绑定的颜色设置。 */
    private final ColorValue color;

    FaColorRow(ColorValue color) {
        super(color, color.name());
        this.color = color;
    }

    /** @return 色块左边缘 */
    private float chipX() {
        return right() - FaTheme.CHIP_W;
    }

    @Override
    protected void draw(FaContext ctx, float x, float centerY, float w, float alpha) {
        float cx = chipX();
        float cy = centerY - FaTheme.CHIP_H * 0.5f;
        drawLabel(ctx, x, centerY, cx - x - FaTheme.ROW_PAD - 6.0f, alpha);
        int argb = color.argb();
        if ((argb >>> 24) < 255) {
            // 半透明：先铺一块棋盘格，否则看不出透明度。
            ctx.draw.rect(cx, cy, FaTheme.CHIP_W, FaTheme.CHIP_H, 2.0f, FaTheme.alpha(0xFF5A5A5A, alpha));
            ctx.draw.rect(cx, cy, FaTheme.CHIP_W * 0.5f, FaTheme.CHIP_H * 0.5f, 0.0f,
                    FaTheme.alpha(0xFF3A3A3A, alpha));
            ctx.draw.rect(cx + FaTheme.CHIP_W * 0.5f, cy + FaTheme.CHIP_H * 0.5f, FaTheme.CHIP_W * 0.5f,
                    FaTheme.CHIP_H * 0.5f, 0.0f, FaTheme.alpha(0xFF3A3A3A, alpha));
        }
        ctx.draw.rect(cx, cy, FaTheme.CHIP_W, FaTheme.CHIP_H, 2.0f, FaTheme.alpha(argb, alpha));
        if (ctx.gui.isPopupOpen(this) || ctx.hovered(cx - 2, cy - 2, FaTheme.CHIP_W + 4, FaTheme.CHIP_H + 4)) {
            ctx.draw.outline(cx - 1.0f, cy - 1.0f, FaTheme.CHIP_W + 2.0f, FaTheme.CHIP_H + 2.0f, 2.5f, 0.6f,
                    FaTheme.alpha(0x66FFFFFF, alpha));
        }
    }

    @Override
    boolean mouseClicked(FaContext ctx, double mx, double my, int button) {
        final float cx = chipX();
        float cy = centerY() - FaTheme.CHIP_H * 0.5f;
        if (button != 0 || !shown || !FaContext.inside(mx, my, cx - 3, cy - 4, FaTheme.CHIP_W + 6,
                FaTheme.CHIP_H + 8)) {
            return false;
        }
        ctx.gui.togglePopup(this, new java.util.function.Supplier<FaPopup>() {
            @Override
            public FaPopup get() {
                return new FaColorPopup(FaColorRow.this, color, ctx.gui);
            }
        }, cx - 12.0f, cy, FaTheme.CHIP_W, FaTheme.CHIP_H, false);
        return true;
    }

    @Override
    boolean matches(String query) {
        return super.matches(query)
                || FaColorPopup.hexString(color.argb()).toLowerCase(Locale.ROOT).contains(query);
    }
}
