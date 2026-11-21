package dev.nocturne.ui.fatality;

import dev.nocturne.client.value.Value;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** 左侧标签 + 右侧滑动开关；整行点击，旋钮位置与颜色共同表达状态。 */
class FaCheckRow extends FaRow {
    /** 取值器。 */
    private final BooleanSupplier getter;
    /** 写入器。 */
    private final Consumer<Boolean> setter;
    /** 勾选动画。 */
    private final FaMotion check;

    FaCheckRow(Value<?> value, String label, BooleanSupplier getter, Consumer<Boolean> setter) {
        super(value, label);
        this.getter = getter;
        this.setter = setter;
        this.check = new FaMotion(getter.getAsBoolean() ? 1.0f : 0.0f);
    }

    @Override
    protected void draw(FaContext ctx, float x, float centerY, float w, float alpha) {
        float t = check.toward(getter.getAsBoolean() ? 1.0f : 0.0f, ctx.dt, 22.0f);
        float width = FaTheme.SWITCH_W;
        float height = FaTheme.SWITCH_H;
        float boxX = right() - width;
        float used = accessory(ctx, boxX - 8.0f, centerY, alpha);
        drawLabel(ctx, x, centerY, boxX - used - x - FaTheme.ROW_PAD - 8.0f, alpha);
        int off = FaTheme.mix(FaTheme.FIELD, FaTheme.FIELD_HOVER, hover.value());
        int on = FaTheme.mix(FaTheme.ACCENT, FaTheme.ACCENT_DIM, hover.value());
        ctx.draw.rect(boxX, centerY - height * 0.5f, width, height, height * 0.5f,
                FaTheme.alpha(FaTheme.mix(off, on, t), alpha));
        float knob = height - 6.0f;
        float knobX = boxX + 3.0f + (width - height) * t;
        ctx.draw.rect(knobX, centerY - knob * 0.5f, knob, knob, knob * 0.5f,
                FaTheme.alpha(FaTheme.mix(FaTheme.MUTED, FaTheme.TICK, t), alpha));
    }

    /**
     * 方框左侧的额外内容，右端到 {@code right}。
     *
     * @return 占用的宽度（供标签截断参考）
     */
    protected float accessory(FaContext ctx, float right, float centerY, float alpha) {
        return 0.0f;
    }

    @Override
    boolean mouseClicked(FaContext ctx, double mx, double my, int button) {
        if (button != 0 || !hit(mx, my)) {
            return false;
        }
        setter.accept(!getter.getAsBoolean());
        return true;
    }
}
