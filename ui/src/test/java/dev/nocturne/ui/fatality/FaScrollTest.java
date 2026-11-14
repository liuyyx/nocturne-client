package dev.nocturne.ui.fatality;

import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.Module;
import dev.nocturne.client.module.ModuleRegistry;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.NumberValue;
import dev.nocturne.ui.RecordingRenderer;
import dev.nocturne.ui.text.FontRasterizer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

/** 滚过滑条时移动面板，而不是悄悄改变模块参数。 */
class FaScrollTest {
    @AfterEach void reset() {
        LayoutStore.resetForTests();
        FontRasterizer.invalidate();
    }

    @Test void wheelOverSliderMovesContentWithoutChangingItsValue() throws Exception {
        LayoutStore.resetForTests();
        FontRasterizer.invalidate();
        final NumberValue value = new NumberValue("Level", 4, 1, 10, 1);
        Module module = new Module() {
            { add(value); for (int i = 0; i < 40; i++) add(new BooleanValue("Flag " + i, false)); }
            @Override public String name() { return "Scroll target"; }
            @Override public Category category() { return Category.MISC; }
        };
        ModuleRegistry registry = new ModuleRegistry();
        registry.register(module);
        FaClickGui gui = new FaClickGui(registry);
        RecordingRenderer renderer = new RecordingRenderer();
        renderer.textures = false;
        gui.setOpen(true);
        gui.setViewport(1142, 700);
        for (int i = 0; i < 20; i++) frame(gui, renderer);
        Field groupsField = FaClickGui.class.getDeclaredField("groups");
        groupsField.setAccessible(true);
        Map<?, ?> groups = (Map<?, ?>) groupsField.get(gui);
        FaGroup group = (FaGroup) ((List<?>) groups.get(Category.MISC)).get(0);
        Field rowsField = FaGroup.class.getDeclaredField("rows");
        rowsField.setAccessible(true);
        FaRow slider = null;
        for (Object row : (List<?>) rowsField.get(group)) {
            if (((FaRow) row).value == value) slider = (FaRow) row;
        }
        assertNotNull(slider);
        assertTrue(slider.shown);
        float beforeY = slider.y;
        double x = (slider.right() - FaTheme.FIELD_W * .5f) * gui.scale();
        double y = slider.centerY() * gui.scale();
        assertTrue(gui.mouseScrolled(x, y, -1));
        frame(gui, renderer);
        assertEquals(4.0, value.get(), 0.0, "滚动内容不得改动滑条数值");
        assertTrue(slider.y < beforeY - 1f, "滚轮经过滑条时，面板仍必须向下滚动");
    }

    private static void frame(FaClickGui gui, RecordingRenderer renderer) throws Exception {
        Field last = FaClickGui.class.getDeclaredField("lastNanos");
        last.setAccessible(true);
        last.setLong(gui, System.nanoTime() - 100000000L);
        renderer.clear();
        gui.render(renderer);
    }
}
