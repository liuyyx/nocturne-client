package dev.noturne.ui.component;

import dev.noturne.ui.render.Renderer;
import dev.noturne.ui.theme.Theme;

import java.util.function.Consumer;

/**
 * 水平数值滑块：在轨道上任意位置点击即把旋钮跳到该处，按住拖动则连续更新。
 *
 * <p>数值始终被夹紧在 {@code [min, max]} 内；每次变化（无论来自拖动还是代码设置）都会
 * 触发构造时传入的 {@code onChange} 回调，把新值回写到对应的配置项。
 */
public class Slider extends Component {

    /** 允许的最小值（含）。 */
    private final float min;
    /** 允许的最大值（含），构造时保证严格大于 {@link #min}。 */
    private final float max;
    /** 数值变化回调；可为 null（表示仅更新内部状态，不外发）。 */
    private final Consumer<Float> onChange;
    /**
     * 权威值回读器；可为 null。
     *
     * <p>绑定的 {@code Value} 可能在 {@code set} 时被 {@code coerce} 改写（钳制到区间、按 step 对齐），
     * 此时控件持有的请求值会与真正生效值不一致。传入该读取器后，每次写入都会回读权威值并以其为准，
     * 从而保证「界面显示值 == value.get()」。
     */
    private final java.util.function.Supplier<Float> valueReader;
    /** 当前值，恒处于 {@code [min, max]}。 */
    private float value;
    /** 是否处于拖动中；决定 {@link #mouseDragged} / {@link #mouseReleased} 是否响应。 */
    private boolean dragging;

    /**
     * @param min     最小值
     * @param max     最大值，必须大于 {@code min}
     * @param value   初始值，超出范围的会被夹紧
     * @param onChange 值变化回调，允许为 null
     * @throws IllegalArgumentException 当 {@code max <= min}
     */
    public Slider(float min, float max, float value, Consumer<Float> onChange) {
        this(min, max, value, onChange, null);
    }

    /**
     * @param min         最小值
     * @param max         最大值，必须大于 {@code min}
     * @param value       初始值，超出范围的会被夹紧
     * @param onChange    值变化回调，允许为 null
     * @param valueReader 权威值回读器（写入后读取 {@code Value.get()}），允许为 null；
     *                    非 null 时控件位置与显示文本始终以回读值为准
     * @throws IllegalArgumentException 当 {@code max <= min}
     */
    public Slider(float min, float max, float value, Consumer<Float> onChange,
                  java.util.function.Supplier<Float> valueReader) {
        if (max <= min) {
            throw new IllegalArgumentException("max must be greater than min");
        }
        this.min = min;
        this.max = max;
        this.onChange = onChange;
        this.valueReader = valueReader;
        this.value = clamp(value);
    }

    /** 返回当前值（已夹紧在 {@code [min, max]} 内）。 */
    public float value() {
        return value;
    }

    /**
     * 设置新值并（值确有变化时）触发回调，随后回读权威值。
     *
     * <p>夹紧后与当前值相同也会回读一次：外部可能已改过 {@code Value}，回读保证显示与生效值一致。
     * 回读发生在回调之后——{@code coerce}（钳制/步长对齐）只有写入后才知道结果。
     */
    public void setValue(float newValue) {
        float clamped = clamp(newValue);
        if (clamped == value) {
            readBack();
            return;
        }
        value = clamped;
        if (onChange != null) {
            onChange.accept(value);
        }
        readBack();
    }

    /** 从权威值回读并夹紧；读取器缺失或返回非法值时保持现状。 */
    private void readBack() {
        if (valueReader == null) {
            return;
        }
        Float authoritative = valueReader.get();
        if (authoritative == null || Float.isNaN(authoritative)) {
            return;
        }
        value = clamp(authoritative);
    }

    /** 返回当前是否正在被拖动。 */
    public boolean isDragging() {
        return dragging;
    }

    @Override
    public void render(Renderer renderer) {
        if (!visible) {
            return;
        }
        // 当前值在区间中的归一化位置，决定已填充轨道长度与把手横向位置
        float fraction = (value - min) / (max - min);

        // 轨道整高、圆角 7：未填充部分铺底，已填充部分按进度覆盖为 PRIMARY
        renderer.roundedRect(x, y, width, height, Theme.CONTROL_RADIUS,
                Theme.SURFACE_CONTAINER_HIGHEST);
        float filled = width * fraction;
        if (filled > 0f) {
            renderer.roundedRect(x, y, filled, height, Theme.CONTROL_RADIUS, Theme.PRIMARY);
        }

        // 把手：深色小圆点，在浅色填充与深色轨道上都保持可见；夹取在轨道两端内
        float knob = 8f;
        float cx = x + Math.min(Math.max(filled, knob / 2f), width - knob / 2f);
        renderer.roundedRect(cx - knob / 2f, y + (height - knob) / 2f, knob, knob, knob / 2f,
                Theme.ON_PRIMARY);

        // 数值文本右对齐到控件右边缘（留出内容缩进）
        String label = format(value);
        renderer.text(label,
                x + width - renderer.textWidth(label, Theme.FONT_SIZE_SMALL) - Theme.ROW_CONTENT_INSET,
                y + (height - renderer.textHeight(Theme.FONT_SIZE_SMALL)) / 2f,
                Theme.FONT_SIZE_SMALL, Theme.TEXT_SECONDARY);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        // 仅响应左键且必须点在控件内；命中即立即跳转并进入拖动态
        if (button != 0 || !contains(mx, my)) {
            return false;
        }
        dragging = true;
        // 点击即视为开始拖动，旋钮直接跳到点击位置（而非从原位渐近）
        applyFromMouse(mx);
        return true;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        // 拖动不校验指针是否仍在控件内，允许拖出边界后继续跟随，最后由 clamp 收敛。
        // 只认左键：右键释放/拖动不得打断正在进行的左键拖动。
        if (!dragging || button != 0) {
            return false;
        }
        applyFromMouse(mx);
        return true;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        // 未在拖动或非左键则交回事件；一旦开始拖动就独占该次释放
        if (!dragging || button != 0) {
            return false;
        }
        dragging = false;
        return true;
    }

    @Override
    public void cancelInteractions() {
        super.cancelInteractions();
        dragging = false;
    }

    /** 由指针横坐标换算出新值并写入（含越界收敛）。 */
    private void applyFromMouse(double mx) {
        if (width <= 0f) {
            // 宽度为 0 时 (mx - x) / width 得到 ±Infinity/NaN，会永久污染配置项
            return;
        }
        // 允许 fraction 越界（指针拖出轨道），由 clamp 在 setValue 中收敛
        float fraction = (float) ((mx - x) / width);
        if (Float.isNaN(fraction)) {
            return;
        }
        setValue(min + fraction * (max - min));
    }


    /** 将 {@code v} 夹紧到 {@code [min, max]}。 */
    private float clamp(float v) {
        return v < min ? min : (v > max ? max : v);
    }

    /**
     * 将数值格式化为标签文本：整数不带小数点，非整数保留两位小数。
     *
     * <p>固定使用 {@link java.util.Locale#ROOT}：默认区域会把小数点写成逗号（如 de_DE 的
     * 「3,50」），与 {@code coerce} 使用的 '.' 语义冲突，也会让同屏标签风格不一致。
     *
     * @param v 待格式化的数值
     * @return 展示用字符串
     */
    protected String format(float v) {
        if (v == Math.rint(v)) {
            return Integer.toString((int) v);
        }
        return String.format(java.util.Locale.ROOT, "%.2f", v);
    }
}
