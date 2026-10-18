package dev.nocturne.ui.fatality;

import dev.nocturne.client.value.ModeValue;
import dev.nocturne.client.value.Value;

import java.util.function.Supplier;

/** 下拉选择：暗色数值文本 + 箭头；点击在下方展开列表。 */
final class FaComboRow extends FaRow {
    /** 下拉列表的数据模型。 */
    private final FaListPopup.Model model;
    /** 当前值的摘要文本（字段里显示的那一行）。 */
    private final Supplier<String> summary;
    /** 箭头翻转动画。 */
    private final FaMotion chevron = new FaMotion(0.0f);

    private FaComboRow(Value<?> value, FaListPopup.Model model, Supplier<String> summary) {
        super(value, value.name());
        this.model = model;
        this.summary = summary;
    }

    /** 由枚举型设置构造单选下拉。 */
    static FaComboRow single(final ModeValue mode) {
        FaListPopup.Model model = new FaListPopup.Model() {
            @Override
            public int size() {
                return mode.options().size();
            }

            @Override
            public String label(int index) {
                return mode.options().get(index);
            }

            @Override
            public boolean selected(int index) {
                return mode.options().get(index).equals(mode.get());
            }

            @Override
            public void click(int index) {
                mode.set(mode.options().get(index));
            }

            @Override
            public boolean multi() {
                return false;
            }
        };
        return new FaComboRow(mode, model, new Supplier<String>() {
            @Override
            public String get() {
                return mode.get();
            }
        });
    }

    @Override
    protected void draw(FaContext ctx, float x, float centerY, float w, float alpha) {
        float fw = FaTheme.FIELD_W;
        float fh = FaTheme.FIELD_H;
        float fx = fieldX(fw);
        float fy = fieldY();
        drawLabel(ctx, x, centerY, fx - x - FaTheme.ROW_PAD - 6.0f, alpha);
        boolean open = ctx.gui.isPopupOpen(this);
        boolean hot = open || ctx.hovered(fx, fy, fw, fh);
        drawField(ctx, fx, fy, fw, fh, summary.get(), hot, alpha);
        float turn = chevron.toward(open ? 1.0f : 0.0f, ctx.dt, 18.0f);
        ctx.draw.icon(turn > 0.5f ? FaTheme.I_CHEVRON_UP : FaTheme.I_CHEVRON_DOWN, fx + fw - 9.0f, centerY,
                9.5f, FaTheme.alpha(FaTheme.CHEVRON, alpha));
    }

    /** 画一个"输入框"样式的字段（数值右对齐省略）。 */
    static void drawField(FaContext ctx, float fx, float fy, float fw, float fh, String text, boolean hot,
                          float alpha) {
        ctx.draw.rect(fx, fy, fw, fh, FaTheme.FIELD_RADIUS,
                FaTheme.alpha(hot ? FaTheme.FIELD_HOVER : FaTheme.FIELD, alpha));
        FaFont font = FaTheme.field();
        String shown = FaTheme.fit(text, font, fw - 24.0f);
        ctx.draw.textMid(shown, fx + 7.0f, fy + fh * 0.5f, font,
                FaTheme.alpha(hot ? FaTheme.FIELD_TEXT_HOVER : FaTheme.FIELD_TEXT, alpha));
    }

    @Override
    boolean mouseClicked(FaContext ctx, double mx, double my, int button) {
        float fx = fieldX(FaTheme.FIELD_W);
        float fy = fieldY();
        if (button != 0 || !shown || !FaContext.inside(mx, my, fx, fy, FaTheme.FIELD_W, FaTheme.FIELD_H)) {
            return false;
        }
        ctx.gui.togglePopup(this, new Supplier<FaPopup>() {
            @Override
            public FaPopup get() {
                return new FaListPopup(FaComboRow.this, model, FaTheme.FIELD_W);
            }
        }, fx, fy, FaTheme.FIELD_W, FaTheme.FIELD_H, false);
        return true;
    }

    @Override
    boolean matches(String query) {
        if (super.matches(query)) {
            return true;
        }
        for (int i = 0; i < model.size(); i++) {
            if (model.label(i).toLowerCase(java.util.Locale.ROOT).contains(query)) {
                return true;
            }
        }
        return false;
    }
}
