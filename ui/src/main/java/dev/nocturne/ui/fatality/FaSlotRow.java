package dev.nocturne.ui.fatality;

import dev.nocturne.client.value.SlotValue;

/** 原版九槽选择器；模型仍为 0–8，格子标注为 1–9。 */
final class FaSlotRow extends FaRow {

    private static final float CELL_W = FaTheme.FIELD_W / SlotValue.SLOTS;
    private final SlotValue slot;

    FaSlotRow(SlotValue slot) {
        super(slot, slot.name());
        this.slot = slot;
    }

    @Override
    protected void draw(FaContext ctx, float x, float centerY, float w, float alpha) {
        float fx = fieldX(FaTheme.FIELD_W);
        float fy = fieldY();
        drawLabel(ctx, x, centerY, fx - x - FaTheme.ROW_PAD - 6.0f, alpha);
        FaFont font = FaTheme.field();
        for (int i = 0; i < SlotValue.SLOTS; i++) {
            float cx = fx + i * CELL_W;
            boolean hovered = ctx.hovered(cx, fy, CELL_W, FaTheme.FIELD_H);
            int background = i == slot.get() ? FaTheme.ACCENT
                    : hovered ? FaTheme.FIELD_HOVER : FaTheme.FIELD;
            ctx.draw.rect(cx, fy + 2.0f, CELL_W - 1.0f, FaTheme.FIELD_H - 4.0f, 1.5f,
                    FaTheme.alpha(background, alpha));
            ctx.draw.textMid(String.valueOf(i + 1), cx + 1.5f, centerY, font,
                    FaTheme.alpha(i == slot.get() ? 0xFFFFFFFF : FaTheme.FIELD_TEXT, alpha));
        }
    }

    @Override
    boolean mouseClicked(FaContext ctx, double mx, double my, int button) {
        if (button != 0 || !shown || !FaContext.inside(mx, my, fieldX(FaTheme.FIELD_W), fieldY(),
                FaTheme.FIELD_W, FaTheme.FIELD_H)) {
            return false;
        }
        int index = (int) ((mx - fieldX(FaTheme.FIELD_W)) / CELL_W);
        slot.set(Math.max(0, Math.min(SlotValue.SLOTS - 1, index)));
        return true;
    }

    @Override
    boolean mouseScrolled(FaContext ctx, double mx, double my, double amount) {
        if (!shown || !FaContext.inside(mx, my, fieldX(FaTheme.FIELD_W), fieldY(),
                FaTheme.FIELD_W, FaTheme.FIELD_H)) {
            return false;
        }
        slot.set(slot.get() - (int) Math.signum(amount));
        return true;
    }

    @Override
    boolean matches(String query) {
        return super.matches(query) || slot.display().contains(query);
    }
}
