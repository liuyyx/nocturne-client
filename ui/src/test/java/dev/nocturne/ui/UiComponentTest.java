package dev.nocturne.ui;

import dev.nocturne.client.value.NumberValue;
import dev.nocturne.ui.anim.Animation;
import dev.nocturne.ui.component.Button;
import dev.nocturne.ui.component.Component;
import dev.nocturne.ui.component.Panel;
import dev.nocturne.ui.component.Slider;
import dev.nocturne.ui.component.ToggleSwitch;
import dev.nocturne.ui.render.Color;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证通用控件契约：Panel 命中测试路由、Slider 拖拽与区间钳制、Button 仅在命中时触发、
 * ToggleSwitch 上报变更并产出动画图形，外加 Animation 与 Color 的基础语义。
 */
class UiComponentTest {

    /** 测试替身：固定边界的空 {@link Component}，每次点击计数一次，用于验证 Panel 的命中路由 */
    static final class Fixed extends Component {
        /** 累计收到的点击次数；断言时被上层子控件遮挡时应保持 0 */
        int clicks;
        /**
         * 构造固定边界的替身组件。
         *
         * @param x 左边界
         * @param y 上边界
         * @param w 宽度
         * @param h 高度
         */
        Fixed(float x, float y, float w, float h) {
            setBounds(x, y, w, h);
        }
        @Override public void render(dev.nocturne.ui.render.Renderer renderer) { }
        @Override public boolean mouseClicked(double mx, double my, int button) {
            clicks++;
            return true;
        }
    }

    /** 完全重叠的两个子控件中，只有后加入的那个收到点击 */
    @Test
    void panelRoutesClickToTopmostChild() {
        Panel panel = new Panel();
        panel.setBounds(0, 0, 400, 300);
        Fixed bottom = new Fixed(10, 10, 100, 50);
        Fixed top = new Fixed(10, 10, 100, 50);   // fully overlaps
        panel.add(bottom);
        panel.add(top);

        assertTrue(panel.mouseClicked(20, 20, 0));
        assertEquals(0, bottom.clicks, "covered child must not receive the click");
        assertEquals(1, top.clicks);
    }

    /** 点击把值置为指针位置对应比例，拖出边界时钳制到 [min, max]，释放后不再响应拖拽 */
    @Test
    void sliderDragUpdatesAndClampsValue() {
        final AtomicReference<Float> reported = new AtomicReference<Float>(Float.NaN);
        Slider slider = new Slider(0f, 100f, 0f, reported::set);
        slider.setBounds(0f, 0f, 100f, 20f);

        slider.mouseClicked(50, 10, 0);
        assertEquals(50f, slider.value(), 0.5f);
        assertEquals(50f, reported.get(), 0.5f);

        slider.mouseDragged(200, 10, 0, 0, 0);   // beyond the right edge
        assertEquals(100f, slider.value(), 0.001f);

        slider.mouseDragged(-50, 10, 0, 0, 0);   // beyond the left edge
        assertEquals(0f, slider.value(), 0.001f);

        assertTrue(slider.mouseReleased(0, 10, 0));
        assertFalse(slider.mouseDragged(50, 10, 0, 0, 0), "drag must end after release");
    }

    /**
     * M-57/M-61 回归：绑定 {@link NumberValue} 的 Slider 在写入（含钳制/步长对齐）后必须回读权威值，
     * 位置与显示文本以 {@code value.get()} 为准——step=1.0 时拖到 6.67 应持有 7.0，而不是停留在 6.67。
     */
    @Test
    void sliderReadsBackCoercedValueSoDisplayMatchesAuthority() {
        NumberValue number = new NumberValue("Speed", 0.0, 0.0, 10.0, 1.0);
        Slider slider = new Slider(0f, 10f, number.get().floatValue(),
                f -> number.set(f.doubleValue()), () -> number.get().floatValue());
        slider.setBounds(0f, 0f, 100f, 20f);

        // 66.7% 位置 → 请求 6.67，NumberValue 按 step=1 对齐到 7.0
        slider.mouseClicked(66.7, 10, 0);
        assertEquals(7.0, number.get(), 1e-9, "NumberValue 按 step=1 对齐");
        assertEquals(number.get().floatValue(), slider.value(), 1e-6f,
                "滑块持有值必须回读为实际生效值（界面显示 == value.get()）");

        // 越界拖动：请求 200 → 钳制到 max=10，回读后仍与权威值一致
        slider.mouseDragged(200, 10, 0, 0, 0);
        assertEquals(10.0, number.get(), 1e-9);
        assertEquals(number.get().floatValue(), slider.value(), 1e-6f);
        slider.mouseReleased(200, 10, 0);
    }

    /** 未命中时不返回 true 也不执行动作；命中时返回 true 并触发动作 */
    @Test
    void buttonRunsActionOnlyWhenHit() {
        AtomicBoolean fired = new AtomicBoolean(false);
        Button button = new Button("Apply", () -> fired.set(true));
        button.setBounds(10, 10, 60, 20);

        assertFalse(button.mouseClicked(200, 200, 0));
        assertFalse(fired.get());

        assertTrue(button.mouseClicked(20, 20, 0));
        assertTrue(fired.get());
    }

    /**
     * 点击翻转开关并回调新状态；动画必须由<b>生产路径</b>（容器的 {@code update}）驱动。
     *
     * <p>L-13 回归：旧用例手动调 {@code toggle.update}，掩盖了生产代码里 {@code ModuleConfigPanel}
     * 不向子控件转发时钟的事实。这里把开关放进 {@link Panel}，只调 {@code panel.update}，
     * 若容器不转发时钟则旋钮永停起点，断言失败（H-22）。
     */
    @Test
    void toggleSwitchReportsChangesAndAnimates() {
        AtomicBoolean state = new AtomicBoolean(false);
        ToggleSwitch toggle = new ToggleSwitch(false, state::set);
        toggle.setBounds(0, 0, dev.nocturne.ui.theme.Theme.SWITCH_WIDTH,
                dev.nocturne.ui.theme.Theme.SWITCH_HEIGHT);
        Panel host = new Panel();
        host.setBounds(0, 0, 200, 200);
        host.add(toggle);

        // 生产路径先注入时钟，再点击；开关用最近一次 update 的时钟作为动画基准
        host.update(0L);
        toggle.mouseClicked(5, 5, 0);
        assertTrue(toggle.isEnabled());
        assertTrue(state.get());

        RecordingRenderer before = new RecordingRenderer();
        toggle.render(before);
        RecordingRenderer after = new RecordingRenderer();
        host.update(100_000L);
        toggle.render(after);

        assertEquals(2, before.count("roundedRect"), "track + knob");
        // 第二个圆角矩形是旋钮；动画推进后它必须向右滑动且尺寸从 8 长到 12
        RecordingRenderer.DrawCall knobBefore = before.ofKind("roundedRect").get(1);
        RecordingRenderer.DrawCall knobAfter = after.ofKind("roundedRect").get(1);
        assertTrue(knobAfter.x > knobBefore.x, "容器驱动 update 时旋钮必须向右滑动");
        assertTrue(knobAfter.width > knobBefore.width, "旋钮必须长到开启态尺寸");
    }

    /** 线性缓动的 {@link Animation} 在半程取中点、终点取目标值，到达终点后停止运行 */
    @Test
    void animationReachesTarget() {
        Animation animation = new Animation(100L, Animation.Easing.LINEAR, 0f);
        animation.animateTo(1f, 0L);
        assertEquals(0.5f, animation.update(50L), 0.001f);
        assertEquals(1f, animation.update(100L), 0.001f);
        assertFalse(animation.isRunning());
    }

    /**
     * C-06 回归：每帧无条件重述同一目标时，{@code animateTo} 不得重置时间基准。
     *
     * <p>修复前实现每帧把 {@code startMs} 刷成当前帧，{@code elapsed} 恒为 0，值永远停在起点；
     * 本用例在第二帧用不同的 {@code nowMs} 重述同一目标，从而区分「每帧重置」与「连续同目标推进」。
     */
    @Test
    void repeatedAnimateToSameTargetKeepsAdvancing() {
        Animation animation = new Animation(100L, Animation.Easing.LINEAR, 0f);

        // 第 1 帧：目标 0 → 1，登记起点
        animation.animateTo(1f, 0L);
        animation.update(0L);

        // 第 2 帧：重述同一目标；若重置 startMs 则 elapsed=0，值仍为 0
        animation.animateTo(1f, 50L);
        assertEquals(0.5f, animation.update(50L), 0.001f, "同目标重复 animateTo 必须继续推进");

        // 第 3 帧：继续重述同一目标，动画应照常到达终点
        animation.animateTo(1f, 50L);
        assertEquals(1f, animation.update(100L), 0.001f);
        assertFalse(animation.isRunning());
    }

    /** {@code Color.hex} 解析与 {@code rgb} 等价、含 alpha 通道，{@code mix} 按比例在黑白间线性插值 */
    @Test
    void colorMixesAndParsesHex() {
        assertEquals(Color.hex("FF0000"), Color.rgb(255, 0, 0));
        assertEquals(Color.hex("80FF0000").a(), 128);
        assertEquals(Color.rgb(0, 0, 0), Color.BLACK.mix(Color.WHITE, 0f));
        assertEquals(Color.rgb(255, 255, 255), Color.BLACK.mix(Color.WHITE, 1f));
        assertEquals(128, Color.BLACK.mix(Color.WHITE, 0.5f).r(), 1);
    }
}
