package dev.nocturne.ui.fatality;

import dev.nocturne.client.value.Value;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** 左侧标签 + 右侧深色方框；勾选时画一个白色对勾（带弹性缩放动画）。 */
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
        float size = FaTheme.CHECK;
        float boxX = right() - size;
        float used = accessory(ctx, boxX - 6.0f, centerY, alpha);
        drawLabel(ctx, x, centerY, boxX - used - x - FaTheme.ROW_PAD - 6.0f, alpha);
        int box = FaTheme.mix(FaTheme.FIELD, FaTheme.FIELD_HOVER, hover.value());
        ctx.draw.rect(boxX, centerY - size * 0.5f, size, size, 2.0f, FaTheme.alpha(box, alpha));
        if (t > 0.01f) {
            // 对勾从 70% 放大到 100%，让开关读起来像"弹出"而不是"闪烁"。
            // 缩放画布而不是换字号：每种字号都会新建一张字形图集。
            try (FaDraw.Scope scope = ctx.draw.push()) {
                ctx.draw.translate(boxX + size * 0.5f, centerY);
                ctx.draw.scale(0.7f + 0.3f * t);
                ctx.draw.icon(FaTheme.I_CHECK, 0.0f, 0.0f, 10.0f, FaTheme.alpha(FaTheme.TICK, alpha * t));
            }
        }
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
