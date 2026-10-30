package dev.nocturne.ui.fatality;

import dev.nocturne.client.value.BoundsNumberValue;
import dev.nocturne.client.value.NumberBounds;

/** 双端区间设置：左右两条滑轨分别控制下限与上限，写入后自动保持有序。 */
final class FaBoundsRow extends FaRow {

    private static final float GAP = 4.0f;
    private static final float TRACK_W = (FaTheme.FIELD_W - GAP) * 0.5f;
    private final BoundsNumberValue bounds;
    private int dragging = -1;

    FaBoundsRow(BoundsNumberValue bounds) {
        super(bounds, bounds.name());
        this.bounds = bounds;
    }

    @Override
    protected void draw(FaContext ctx, float x, float centerY, float w, float alpha) {
        float fx = fieldX(FaTheme.FIELD_W);
        drawLabel(ctx, x, centerY, fx - x - FaTheme.ROW_PAD - 6.0f, alpha);
        NumberBounds value = bounds.get();
        drawTrack(ctx, fx, 0, value.min(), dragging == 0, alpha);
        drawTrack(ctx, fx + TRACK_W + GAP, 1, value.max(), dragging == 1, alpha);
    }

    private void drawTrack(FaContext ctx, float fx, int side, double value, boolean active, float alpha) {
        float fy = sliderY();
        boolean hot = active || ctx.hovered(fx, fieldY(), TRACK_W, FaTheme.FIELD_H);
        ctx.draw.rect(fx, fy, TRACK_W, FaTheme.SLIDER_H, FaTheme.FIELD_RADIUS,
                FaTheme.alpha(hot ? FaTheme.FIELD_HOVER : FaTheme.FIELD, alpha));
        float fraction = fraction(value);
        float fill = Math.max(0.0f, (TRACK_W - 3.0f) * fraction);
        if (fill > 0.25f) {
            ctx.draw.rect(fx + 1.5f, fy + 1.5f, fill, FaTheme.SLIDER_H - 3.0f,
                    FaTheme.FIELD_RADIUS - 0.5f, FaTheme.alpha(FaTheme.ACCENT, alpha));
        }
        String text = formatNumber(value, bounds.step());
        FaFont font = FaTheme.field();
        ctx.draw.textMid(text, fx + (TRACK_W - font.width(text)) * 0.5f, centerY(), font,
                FaTheme.alpha(hot ? FaTheme.FIELD_TEXT_HOVER : FaTheme.FIELD_TEXT, alpha));
    }

    private float fraction(double value) {
        return (float) Math.max(0.0, Math.min(1.0,
                (value - bounds.min()) / (bounds.max() - bounds.min())));
    }

    @Override
    boolean mouseClicked(FaContext ctx, double mx, double my, int button) {
        if (button != 0 || !shown || !FaContext.inside(mx, my, fieldX(FaTheme.FIELD_W), fieldY(),
                FaTheme.FIELD_W, FaTheme.FIELD_H)) {
            return false;
        }
        dragging = side(mx);
        apply(mx);
        return true;
    }

    @Override
    void mouseDragged(FaContext ctx, double mx, double my) {
        if (dragging >= 0) {
            apply(mx);
        }
    }

    @Override
    void mouseReleased() {
        dragging = -1;
    }

    @Override
    boolean mouseScrolled(FaContext ctx, double mx, double my, double amount) {
        if (!shown || !FaContext.inside(mx, my, fieldX(FaTheme.FIELD_W), fieldY(),
                FaTheme.FIELD_W, FaTheme.FIELD_H)) {
            return false;
        }
        int side = side(mx);
        double current = side == 0 ? bounds.get().min() : bounds.get().max();
        double step = bounds.step() > 0 ? bounds.step() : (bounds.max() - bounds.min()) / 100.0;
        set(side, current + Math.signum(amount) * step);
        return true;
    }

    private int side(double mx) {
        return mx <= fieldX(FaTheme.FIELD_W) + TRACK_W + GAP * 0.5 ? 0 : 1;
    }

    private void apply(double mx) {
        float trackX = fieldX(FaTheme.FIELD_W) + (dragging == 0 ? 0.0f : TRACK_W + GAP);
        double fraction = Math.max(0.0, Math.min(1.0, (mx - trackX) / TRACK_W));
        set(dragging, bounds.min() + fraction * (bounds.max() - bounds.min()));
    }

    private void set(int side, double raw) {
        double step = bounds.step();
        double snapped = step > 0 ? Math.round((raw - bounds.min()) / step) * step + bounds.min() : raw;
        snapped = Math.round(snapped * 1e6) / 1e6;
        snapped = Math.max(bounds.min(), Math.min(bounds.max(), snapped));
        NumberBounds current = bounds.get();
        bounds.set(side == 0 ? current.withMin(snapped) : current.withMax(snapped));
    }
}
