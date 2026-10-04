package dev.noturne.ui.clickgui;

import dev.noturne.client.module.Category;
import dev.noturne.client.module.Module;
import dev.noturne.client.value.BooleanValue;
import dev.noturne.client.value.ColorValue;
import dev.noturne.client.value.ModeValue;
import dev.noturne.client.value.NumberValue;
import dev.noturne.ui.RecordingRenderer;
import dev.noturne.ui.component.ColorPicker;
import dev.noturne.ui.component.Component;
import dev.noturne.ui.component.ModeSelector;
import dev.noturne.ui.component.Slider;
import dev.noturne.ui.component.ToggleSwitch;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 {@link ModuleConfigPanel} 契约：按值类型生成对应编辑器（含颜色）、编辑器交互写回 {@code Value}、
 * 写回后由生产路径 {@code update(nowMs)} 驱动并反映到渲染上，以及模块为空时仍能渲染面板与标题。
 */
class ModuleConfigPanelTest {

    /** 测试替身模块：声明四种值（布尔/数值/模式/颜色），覆盖全部编辑器映射分支 */
    static final class Configured extends Module {
        /** 布尔值字段；应生成一个 {@link ToggleSwitch} */
        final BooleanValue enabledValue = add(new BooleanValue("Extra", false));
        /** 数值字段，范围 [1.0, 10.0]、步长 1.0；应生成一个 {@link Slider} */
        final NumberValue speed = add(new NumberValue("Speed", 5.0, 1.0, 10.0, 1.0));
        /** 模式字段，候选 A/B；应生成一个 {@link ModeSelector} */
        final ModeValue mode = add(new ModeValue("Mode", "A", "A", "B"));
        /** 颜色字段；应生成一个 {@link ColorPicker} */
        final ColorValue tint = add(new ColorValue("Tint", 0xFF00FF00));

        /** @return 固定的模块名 */
        @Override
        public String name() {
            return "Configured";
        }

        /** @return 固定归入 MISC 分类 */
        @Override
        public Category category() {
            return Category.MISC;
        }
    }

    /** 新建面板默认不可见；{@code show} 后按值类型各生成一个编辑器，且高度为正 */
    @Test
    void buildsOneEditorPerValueType() {
        ModuleConfigPanel panel = new ModuleConfigPanel();
        assertFalse(panel.isVisible());

        panel.show(new Configured(), 10f, 20f);
        assertTrue(panel.isVisible());
        assertEquals(4, panel.editors().size());

        int toggles = 0;
        int sliders = 0;
        int selectors = 0;
        int pickers = 0;
        for (Component editor : panel.editors()) {
            if (editor instanceof ToggleSwitch) {
                toggles++;
            } else if (editor instanceof Slider) {
                sliders++;
            } else if (editor instanceof ModeSelector) {
                selectors++;
            } else if (editor instanceof ColorPicker) {
                pickers++;
            }
        }
        assertEquals(1, toggles);
        assertEquals(1, sliders);
        assertEquals(1, selectors);
        assertEquals(1, pickers, "ColorValue must produce a ColorPicker");
        assertTrue(panel.height() > 0f);
    }

    /** 点击每个编辑器后，控件应把结果写回对应的 Value：布尔翻转、数值钳到上限、模式切到下一个候选、颜色改变 */
    @Test
    void editingControlsWritesBackToTheValue() {
        ModuleConfigPanel panel = new ModuleConfigPanel();
        Configured module = new Configured();
        panel.show(module, 0f, 0f);

        for (Component editor : panel.editors()) {
            if (editor instanceof ToggleSwitch) {
                editor.mouseClicked(editor.x() + 1, editor.y() + 1, 0);
            } else if (editor instanceof Slider) {
                editor.mouseClicked(editor.x() + editor.width() - 1, editor.y() + 1, 0);
            } else if (editor instanceof ModeSelector) {
                editor.mouseClicked(editor.x() + 1, editor.y() + 1, 0);
            } else if (editor instanceof ColorPicker) {
                editor.mouseClicked(editor.x() + 1, editor.y() + 1, 0);
            }
        }

        assertTrue(module.enabledValue.get(), "toggle writes back to the BooleanValue");
        assertEquals(10.0, module.speed.get(), 1e-9, "slider clamps into range");
        assertEquals("B", module.mode.get(), "mode selector cycles");
        assertNotEquals(0xFF00FF00, module.tint.get().intValue(), "color picker writes a new colour back");
    }

    /**
     * H-22 回归：写回后必须由面板的 {@code update(nowMs)} 驱动动画，且渲染要反映新状态。
     *
     * <p>旧用例只断言 {@code Value} 状态、从不调用 {@code update()}/{@code render()}，正好掩盖了
     * 「点击了但界面没反应」——这里只走生产路径（{@code panel.update}），若面板丢弃时钟则开关
     * 动画冻死、旋钮不动。
     */
    @Test
    void panelUpdateDrivesToggleAnimationAndRendersNewState() {
        ModuleConfigPanel panel = new ModuleConfigPanel();
        Configured module = new Configured();
        panel.show(module, 0f, 0f);

        ToggleSwitch toggle = null;
        for (Component editor : panel.editors()) {
            if (editor instanceof ToggleSwitch) {
                toggle = (ToggleSwitch) editor;
            }
        }
        assertNotNull(toggle);

        panel.update(0L, 0, 0);
        toggle.mouseClicked(toggle.x() + 1, toggle.y() + 1, 0);
        assertTrue(module.enabledValue.get());

        RecordingRenderer before = new RecordingRenderer();
        toggle.render(before);
        RecordingRenderer after = new RecordingRenderer();
        panel.update(100_000L, 0, 0);   // 生产路径：面板必须把时钟转发给子控件
        toggle.render(after);

        RecordingRenderer.DrawCall knobBefore = before.ofKind("roundedRect").get(1);
        RecordingRenderer.DrawCall knobAfter = after.ofKind("roundedRect").get(1);
        assertTrue(knobAfter.x > knobBefore.x, "面板驱动 update 后旋钮必须向右滑动");
        assertTrue(knobAfter.width > knobBefore.width, "旋钮必须长到开启态尺寸");
    }

    /** 写回后的数值必须体现在渲染文本上（滑块标签），而不是只改内存里的 Value */
    @Test
    void renderShowsWrittenBackSliderValue() {
        ModuleConfigPanel panel = new ModuleConfigPanel();
        Configured module = new Configured();
        panel.show(module, 0f, 0f);

        Slider slider = null;
        for (Component editor : panel.editors()) {
            if (editor instanceof Slider) {
                slider = (Slider) editor;
            }
        }
        assertNotNull(slider);
        slider.mouseClicked(slider.x() + slider.width() - 1, slider.y() + 1, 0);
        assertEquals(10.0, module.speed.get(), 1e-9);

        RecordingRenderer renderer = new RecordingRenderer();
        slider.render(renderer);
        assertTrue(renderer.count("text:10") >= 1, "滑块标签必须显示写回后的新值");
    }

    /** 传入 null 模块时不应抛异常，仍需绘制面板底色与标题文本 */
    @Test
    void missingModuleStillRendersAHeader() {
        ModuleConfigPanel panel = new ModuleConfigPanel();
        panel.show(null, 0f, 0f);
        RecordingRenderer renderer = new RecordingRenderer();
        panel.render(renderer);
        assertNotNull(renderer);
        assertTrue(renderer.count("roundedRect") >= 1, "panel background");
        assertTrue(renderer.count("text:settings") >= 1, "fallback title");
    }
}
