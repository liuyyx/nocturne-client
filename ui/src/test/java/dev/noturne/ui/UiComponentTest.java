package dev.noturne.ui;

import dev.noturne.ui.anim.Animation;
import dev.noturne.ui.component.Button;
import dev.noturne.ui.component.Component;
import dev.noturne.ui.component.Panel;
import dev.noturne.ui.component.Slider;
import dev.noturne.ui.component.ToggleSwitch;
import dev.noturne.ui.render.Color;
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
        @Override public void render(dev.noturne.ui.render.Renderer renderer) { }
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

    /** 点击翻转开关并回调新状态；动画收敛后绘制开关本体与滑块两个圆角矩形 */
    @Test
    void toggleSwitchReportsChangesAndAnimates() {
        AtomicBoolean state = new AtomicBoolean(false);
        ToggleSwitch toggle = new ToggleSwitch(false, state::set);
        toggle.setBounds(0, 0, 20, 10);

        toggle.mouseClicked(5, 5, 0);
        assertTrue(toggle.isEnabled());
        assertTrue(state.get());

        // animation settles on the target value
        toggle.update(0L);
        toggle.update(10_000L);
        RecordingRenderer renderer = new RecordingRenderer();
        toggle.render(renderer);
        assertEquals(2, renderer.count("roundedRect"));
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
