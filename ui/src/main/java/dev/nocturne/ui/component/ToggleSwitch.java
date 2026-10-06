package dev.nocturne.ui.component;

import dev.nocturne.ui.anim.Animation;
import dev.nocturne.ui.render.Color;
import dev.nocturne.ui.render.Renderer;
import dev.nocturne.ui.theme.Theme;

import java.util.function.Consumer;

/**
 * MD3 风格的开关：滑块在轨道两端之间滑动，切换时尺寸与位置同步过渡
 *（关 8px / 开 12px，内缩关 4 / 开 2，规格见 {@link Theme}）。
 */
public class ToggleSwitch extends Component {

    private final Consumer<Boolean> onChange;
    /** 权威值回读器；可为 null。写入 {@code Value} 后回读，保证显示态与实际态一致。 */
    private final java.util.function.Supplier<Boolean> valueReader;
    private final Animation slide = new Animation(Theme.EXPAND_MS, Animation.Easing.EASE_OUT_CUBIC, 0f);
    private boolean enabled;
    /**
     * 最近一次 {@link #update(long)} 注入的时钟。
     *
     * <p>切换动画的时间基准必须与驱动它的时钟同源：若这里改用 {@code System.currentTimeMillis}
     * 而 {@code update} 收到的是别的时间轴（测试或自定义时钟），elapsed 会变成巨大的负数，
     * 缓动函数随即产出 -8e29 级别的值并把负尺寸交给 GL。
     */
    private long lastNowMs;

    public ToggleSwitch(boolean enabled, Consumer<Boolean> onChange) {
        this(enabled, onChange, null);
    }

    /**
     * @param enabled     初始状态
     * @param onChange    状态变化回调，允许为 null
     * @param valueReader 权威值回读器（写入后读取 {@code Value.get()}），允许为 null
     */
    public ToggleSwitch(boolean enabled, Consumer<Boolean> onChange,
                        java.util.function.Supplier<Boolean> valueReader) {
        this.valueReader = valueReader;
        this.enabled = readBackEnabled(enabled);
        this.onChange = onChange;
        slide.set(this.enabled ? 1f : 0f);
    }

    public boolean isEnabled() {
        return enabled;
    }

    /** 使用最近一次 {@link #update(long)} 的时钟切换状态。 */
    public void setEnabled(boolean value) {
        setEnabled(value, lastNowMs);
    }

    public void setEnabled(boolean value, long nowMs) {
        // 先与权威值对齐：外部可能已直接改过 Value，本地状态不能作为判重依据
        boolean synced = readBackEnabled(this.enabled);
        if (synced != this.enabled) {
            this.enabled = synced;
            slide.animateTo(synced ? 1f : 0f, nowMs);
        }
        if (this.enabled == value) {
            return;
        }
        this.enabled = value;
        slide.animateTo(value ? 1f : 0f, nowMs);
        if (onChange != null) {
            onChange.accept(value);
        }
        // 写入后回读：Value 侧若有归一化/拒绝，以权威值为准重定向动画
        boolean authoritative = readBackEnabled(value);
        if (authoritative != this.enabled) {
            this.enabled = authoritative;
            slide.animateTo(authoritative ? 1f : 0f, nowMs);
        }
    }

    /** @return 回读到的权威状态；读取器缺失或返回 null 时回退为 {@code fallback} */
    private boolean readBackEnabled(boolean fallback) {
        if (valueReader == null) {
            return fallback;
        }
        Boolean actual = valueReader.get();
        return actual == null ? fallback : actual.booleanValue();
    }

    @Override
    public void update(long nowMs) {
        this.lastNowMs = nowMs;
        slide.update(nowMs);
    }

    @Override
    public void render(Renderer renderer) {
        if (!visible) {
            return;
        }
        float t = slide.value();

        // 轨道由 SURFACE_CONTAINER_HIGHEST 过渡到 PRIMARY，滑块由 OUTLINE 过渡到 ON_PRIMARY
        Color track = Theme.SURFACE_CONTAINER_HIGHEST.mix(Theme.PRIMARY, t);
        Color knobColor = Theme.OUTLINE.mix(Theme.ON_PRIMARY, t);

        // 滑块尺寸 8→12、左缘从「内缩 4」移动到「右端内缩 2」
        float knobSize = Theme.SWITCH_HANDLE_OFF
                + (Theme.SWITCH_HANDLE_ON - Theme.SWITCH_HANDLE_OFF) * t;
        float rawLeft = Theme.SWITCH_INSET_OFF
                + (width - Theme.SWITCH_INSET_ON - Theme.SWITCH_HANDLE_ON - Theme.SWITCH_INSET_OFF) * t;
        // 控件窄于滑块时 rawLeft 会变成负值，把滑块画到控件左侧之外；夹取到 [0, width - knobSize]
        float maxLeft = Math.max(0f, width - knobSize);
        float left = Math.max(0f, Math.min(rawLeft, maxLeft));

        renderer.roundedRect(x, y, width, height, height / 2f, track);
        renderer.roundedRect(x + left, y + (height - knobSize) / 2f, knobSize, knobSize,
                knobSize / 2f, knobColor);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0 || !contains(mx, my)) {
            return false;
        }
        setEnabled(!enabled, lastNowMs);
        return true;
    }
}
