package dev.nocturne.ui.fatality;

import dev.nocturne.client.value.NumberValue;

import java.awt.event.KeyEvent;

/**
 * 数值输入：轨道内从左侧填充的强调色条 + 居中数值；数值被填充分割时由灰转白。
 * 拖拽设置、滚轮微调、右键直接输入。
 */
final class FaSliderRow extends FaRow implements FaKeySink {
    /** 填充条相对轨道的内缩（设计单位）；让填充看起来像嵌在凹槽里。 */
    static final float FILL_INSET = 1.5f;

    /** 绑定的数值设置。 */
    private final NumberValue number;
    /** 填充动画。 */
    private final FaMotion fill;
    /** 右键直接输入的编辑缓冲。 */
    private final FaTextInput entry = new FaTextInput(16);
    /** 是否正在拖拽。 */
    private boolean dragging;
    /** 是否处于直接输入状态。 */
    private boolean editing;

    FaSliderRow(NumberValue number) {
        super(number, number.name());
        this.number = number;
        this.fill = new FaMotion(fraction());
        entry.onSubmit(this::applyEntry).onCancel(new Runnable() {
            @Override
            public void run() {
                editing = false;
            }
        });
    }

    /** @return 当前值在区间内的归一化位置 */
    private float fraction() {
        double min = number.min();
        double max = number.max();
        return max <= min ? 0.0f : (float) ((number.get() - min) / (max - min));
    }

    @Override
    protected void draw(FaContext ctx, float x, float centerY, float w, float alpha) {
        float fw = FaTheme.FIELD_W;
        float fh = FaTheme.SLIDER_H;
        float fx = fieldX(fw);
        float fy = sliderY();
        drawLabel(ctx, x, centerY, fx - x - FaTheme.ROW_PAD - 6.0f, alpha);
        boolean hot = dragging || ctx.hovered(fx, fieldY(), fw, FaTheme.FIELD_H);
        ctx.draw.rect(fx, fy, fw, fh, FaTheme.FIELD_RADIUS,
                FaTheme.alpha(hot ? FaTheme.FIELD_HOVER : FaTheme.FIELD, alpha));
        FaFont font = FaTheme.field();
        if (editing) {
            entry.render(ctx.draw, fx + 6.0f, centerY, fw - 12.0f, font, FaTheme.alpha(FaTheme.LABEL, alpha),
                    true, "", 0);
            ctx.draw.outline(fx, fy, fw, fh, FaTheme.FIELD_RADIUS, 0.5f, FaTheme.alpha(FaTheme.ACCENT_DIM, alpha));
            return;
        }
        float t = Math.max(0.0f, Math.min(1.0f, fill.toward(fraction(), ctx.dt, dragging ? 40.0f : 18.0f)));
        float inset = FILL_INSET;
        float innerW = fw - inset * 2.0f;
        float innerH = fh - inset * 2.0f;
        float fillW = innerW * t;
        if (fillW > 0.25f) {
            ctx.draw.rect(fx + inset, fy + inset, Math.max(fillW, Math.min(innerW, innerH * 0.6f)), innerH,
                    FaTheme.FIELD_RADIUS - 0.5f, FaTheme.alpha(FaTheme.ACCENT, alpha));
        }
        String text = formatNumber(number.get(), number.step());
        float textX = fx + (fw - font.width(text)) * 0.5f;
        int grey = FaTheme.alpha(hot ? FaTheme.FIELD_TEXT_HOVER : FaTheme.FIELD_TEXT, alpha);
        int color = valueColor(fx + inset + fillW - textX, grey, FaTheme.alpha(0xFFFFFFFF, alpha));
        ctx.draw.textMid(text, textX, centerY, font, color);
    }

    /**
     * 数值在空轨道上时读灰、被填充覆盖时读白；整串同色比逐字形分割更耐看。
     *
     * @param cover 填充伸入文本的深度（设计单位）
     */
    static int valueColor(float cover, int grey, int white) {
        return FaTheme.mix(grey, white, cover / 4.0f);
    }

    @Override
    boolean mouseClicked(FaContext ctx, double mx, double my, int button) {
        float fx = fieldX(FaTheme.FIELD_W);
        float fy = fieldY();
        if (!shown || !FaContext.inside(mx, my, fx, fy, FaTheme.FIELD_W, FaTheme.FIELD_H)) {
            return false;
        }
        if (button == 1) {
            editing = true;
            entry.set(formatNumber(number.get(), number.step()));
            ctx.gui.focus(this);
            entry.focused();
            return true;
        }
        if (button != 0) {
            return false;
        }
        if (editing) {
            // 编辑中：点击不再拖动，避免把输入框里的数值带走。
            return true;
        }
        dragging = true;
        apply(mx);
        return true;
    }

    @Override
    void mouseDragged(FaContext ctx, double mx, double my) {
        if (dragging) {
            apply(mx);
        }
    }

    @Override
    void mouseReleased() {
        dragging = false;
    }

    @Override
    boolean mouseScrolled(FaContext ctx, double mx, double my, double amount) {
        if (!shown || !FaContext.inside(mx, my, fieldX(FaTheme.FIELD_W), fieldY(), FaTheme.FIELD_W,
                FaTheme.FIELD_H)) {
            return false;
        }
        double step = number.step() > 0 ? number.step() : (number.max() - number.min()) / 100.0;
        set(number.get() + Math.signum(amount) * step);
        return true;
    }

    /** 按指针位置设定值。 */
    private void apply(double mx) {
        float fx = fieldX(FaTheme.FIELD_W) + FILL_INSET;
        double t = Math.max(0.0, Math.min(1.0, (mx - fx) / (FaTheme.FIELD_W - FILL_INSET * 2.0f)));
        set(number.min() + t * (number.max() - number.min()));
    }

    /** 写入并按步长对齐；{@link NumberValue#set(Double)} 自身也会归一化。 */
    private void set(double raw) {
        double min = number.min();
        double max = number.max();
        double step = number.step();
        double snapped = step > 0 ? Math.round((raw - min) / step) * step + min : raw;
        // 先按微米取整，避免 0.1 步长累积出 0.30000000000000004。
        snapped = Math.round(snapped * 1e6) / 1e6;
        number.set(Math.max(min, Math.min(max, snapped)));
    }

    /** 提交直接输入。 */
    private void applyEntry(String text) {
        try {
            set(Double.parseDouble(text.trim().replace(',', '.')));
        } catch (NumberFormatException ignored) {
            // 非法输入保持原值。
        }
        editing = false;
    }

    @Override
    public boolean keyPressed(int key, int modifiers) {
        if (!editing) {
            return false;
        }
        boolean used = entry.keyPressed(key, modifiers);
        if (!editing) {
            return true;
        }
        return used || key == KeyEvent.VK_TAB;
    }

    @Override
    public boolean charTyped(char character) {
        return editing && (Character.isDigit(character) || character == '.' || character == '-'
                || character == ',') && entry.charTyped(character);
    }

    @Override
    public void focusLost() {
        if (editing) {
            applyEntry(entry.text());
        }
    }

    /** @return 是否处于直接输入状态 */
    boolean editing() {
        return editing;
    }
}
