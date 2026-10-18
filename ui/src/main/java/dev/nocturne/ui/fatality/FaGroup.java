package dev.nocturne.ui.fatality;

import dev.nocturne.client.module.Module;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.ColorValue;
import dev.nocturne.client.value.ModeValue;
import dev.nocturne.client.value.NumberValue;
import dev.nocturne.client.value.Value;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 一个模块在界面上的一张卡片：标题压在顶边上，下面第一行是 "Enabled" 开关，
 * 其余每一行对应模块的一个设置项。搜索可以把行收窄。
 */
final class FaGroup extends FaBox {

    /** 标题相对卡片顶边的中心偏移。 */
    private static final float TITLE_RISE = 1.5f;

    /** 本卡片对应的模块。 */
    final Module module;
    /** 全部行（第 0 行是开关）。 */
    private final List<FaRow> rows = new ArrayList<FaRow>();
    /** 每行是否被搜索命中。 */
    private final boolean[] matched;
    /** 位置动画。 */
    private final FaMotion x = new FaMotion(Float.NaN);
    /** 位置动画。 */
    private final FaMotion y = new FaMotion(Float.NaN);
    /** 侧边栏点击时的高亮脉冲。 */
    private final FaMotion flash = new FaMotion(0.0f);
    /** 目标位置。 */
    private float targetX;
    /** 目标位置。 */
    private float targetY;
    /** 上一帧的绘制矩形，用于命中测试。 */
    private float lastX;
    private float lastY;
    private float lastW;
    private float lastH;
    /** 上一帧是否画出来了（被滚动裁掉时为 false）。 */
    private boolean drawn;
    /** 当前是否处于搜索过滤状态。 */
    private boolean filtered;

    FaGroup(Module module) {
        this.module = module;
        rows.add(new FaEnableRow(module));
        for (Value<?> value : module.values()) {
            FaRow row = create(value);
            if (row != null) {
                rows.add(row);
            }
        }
        matched = new boolean[rows.size()];
        java.util.Arrays.fill(matched, true);
    }

    /**
     * 把一个设置项映射成界面行。
     *
     * <p>本项目只有四类设置项（{@link BooleanValue}/{@link NumberValue}/{@link ModeValue}/
     * {@link ColorValue}），其余类型跳过——画不出来的行不如不画。
     *
     * @return 对应的行；无法映射时返回 {@code null}
     */
    static FaRow create(Value<?> value) {
        if (value instanceof BooleanValue) {
            final BooleanValue bool = (BooleanValue) value;
            return new FaCheckRow(bool, bool.name(), new java.util.function.BooleanSupplier() {
                @Override
                public boolean getAsBoolean() {
                    return bool.get();
                }
            }, new java.util.function.Consumer<Boolean>() {
                @Override
                public void accept(Boolean next) {
                    bool.set(next);
                }
            });
        }
        if (value instanceof NumberValue) {
            return new FaSliderRow((NumberValue) value);
        }
        if (value instanceof ModeValue) {
            return FaComboRow.single((ModeValue) value);
        }
        if (value instanceof ColorValue) {
            return new FaColorRow((ColorValue) value);
        }
        return null;
    }

    /** @return 卡片标题（模块名） */
    String title() {
        return module.name();
    }

    /** @return 卡片内的行（第 0 行为 Enabled 开关），供测试与搜索使用 */
    List<FaRow> rows() {
        return rows;
    }

    /**
     * 按搜索词收窄行。
     *
     * @param query 已小写的搜索词；空串表示不过滤
     * @return 本卡片是否应该出现在结果里
     */
    boolean filter(String query) {
        filtered = !query.isEmpty();
        if (!filtered) {
            java.util.Arrays.fill(matched, true);
            return true;
        }
        boolean titleMatch = title().toLowerCase(Locale.ROOT).contains(query);
        boolean any = titleMatch;
        for (int i = 0; i < rows.size(); i++) {
            matched[i] = titleMatch || i == 0 || rows.get(i).matches(query);
            if (i > 0 && matched[i] && !titleMatch) {
                any = true;
            }
        }
        return any;
    }

    @Override
    float targetHeight() {
        float height = FaTheme.GROUP_TITLE_H + FaTheme.GROUP_BOTTOM;
        for (int i = 0; i < rows.size(); i++) {
            if (matched[i]) {
                height += rows.get(i).targetHeight();
            }
        }
        return height;
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

    /** 侧边栏点击时触发一次高亮脉冲。 */
    void flash() {
        flash.snap(1.0f);
    }

    @Override
    void render(FaContext ctx, float originX, float originY, float width, float viewTop, float viewBottom,
                float alpha) {
        float gx = originX + x.toward(targetX, ctx.dt, 16.0f);
        float gy = originY + y.toward(targetY, ctx.dt, 16.0f);
        float height = FaTheme.GROUP_TITLE_H + FaTheme.GROUP_BOTTOM;
        float[] rowHeights = new float[rows.size()];
        for (int i = 0; i < rows.size(); i++) {
            rowHeights[i] = matched[i] ? rows.get(i).height(ctx.dt) : 0.0f;
            height += rowHeights[i];
        }
        lastX = gx;
        lastY = gy;
        lastW = width;
        lastH = height;
        drawn = gy + height >= viewTop && gy <= viewBottom;
        float glow = flash.toward(0.0f, ctx.dt, 2.2f);
        if (!drawn) {
            for (FaRow row : rows) {
                row.shown = false;
            }
            return;
        }

        ctx.draw.rect(gx, gy, width, height, FaTheme.GROUP_RADIUS, FaTheme.alpha(FaTheme.GROUP, alpha));
        ctx.draw.outline(gx, gy, width, height, FaTheme.GROUP_RADIUS, 0.5f,
                FaTheme.alpha(FaTheme.mix(FaTheme.GROUP_BORDER, FaTheme.ACCENT_DIM, glow), alpha));

        // 与参考实现一致：标题压在卡片顶边上，不占一条独立的标题带。
        FaFont titleFont = FaTheme.title();
        ctx.draw.textMid(FaTheme.fit(title(), titleFont, width - 20.0f), gx + 9.5f, gy + TITLE_RISE, titleFont,
                FaTheme.alpha(FaTheme.mix(FaTheme.TITLE, FaTheme.ACCENT, glow), alpha));

        float rowY = gy + FaTheme.GROUP_TITLE_H;
        for (int i = 0; i < rows.size(); i++) {
            float rowHeight = rowHeights[i];
            FaRow row = rows.get(i);
            if (rowHeight <= 0.01f || rowY + rowHeight < viewTop || rowY > viewBottom) {
                row.shown = false;
                rowY += rowHeight;
                continue;
            }
            float rowAlpha = alpha * row.visibility();
            if (rowHeight < FaTheme.ROW_H - 0.05f) {
                // 高度动画中：裁剪到当前行高，避免内容溢到相邻行。
                try (FaDraw.Scope clip = ctx.draw.clip(gx, rowY, width, rowHeight)) {
                    row.render(ctx, gx, rowY, width, rowAlpha);
                }
            } else {
                row.render(ctx, gx, rowY, width, rowAlpha);
            }
            rowY += rowHeight;
        }
    }

    /** @return 指针是否命中本卡片（且本卡片确实画出来了） */
    boolean contains(double mx, double my) {
        return drawn && FaContext.inside(mx, my, lastX, lastY, lastW, lastH);
    }

    @Override
    boolean mouseClicked(FaContext ctx, double mx, double my, int button) {
        if (!contains(mx, my)) {
            return false;
        }
        for (int i = 0; i < rows.size(); i++) {
            if (matched[i] && rows.get(i).mouseClicked(ctx, mx, my, button)) {
                return true;
            }
        }
        return true;
    }

    @Override
    void mouseDragged(FaContext ctx, double mx, double my) {
        for (FaRow row : rows) {
            row.mouseDragged(ctx, mx, my);
        }
    }

    @Override
    void mouseReleased() {
        for (FaRow row : rows) {
            row.mouseReleased();
        }
    }

    @Override
    boolean mouseScrolled(FaContext ctx, double mx, double my, double amount) {
        if (!contains(mx, my)) {
            return false;
        }
        for (int i = 0; i < rows.size(); i++) {
            if (matched[i] && rows.get(i).mouseScrolled(ctx, mx, my, amount)) {
                return true;
            }
        }
        return false;
    }
}
