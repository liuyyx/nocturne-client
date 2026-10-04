package dev.noturne.ui.clickgui;

import dev.noturne.client.module.Category;
import dev.noturne.client.module.Module;
import dev.noturne.client.module.ModuleRegistry;
import dev.noturne.ui.RecordingRenderer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 {@link ClickGui} 契约：关闭时不绘制也不吞输入、点击模块行切换模块状态、
 * 折叠分类面板隐藏行并收缩高度、以及 ESC 键关闭界面。
 */
class ClickGuiTest {

    /** 测试替身模块：只提供名称与分类，用于构造最小可用的 {@link ModuleRegistry} */
    static final class TestModule extends Module {
        /** 模块显示名 */
        private final String name;
        /** 模块所属分类，决定它出现在哪个分类面板中 */
        private final Category category;

    /**
     * 构造替身模块。
     *
     * @param name 模块显示名
     * @param category 所属分类
     */
        TestModule(String name, Category category) {
            this.name = name;
            this.category = category;
        }

        /** @return 构造时设定的模块名 */
        @Override
        public String name() {
            return name;
        }

        /** @return 构造时设定的分类 */
        @Override
        public Category category() {
            return category;
        }
    }

    /**
     * 用给定模块搭建一个 ClickGui。
     *
     * @param modules 要注册到 registry 的模块
     * @return 绑定该 registry 的 ClickGui 实例，默认关闭
     */
    private static ClickGui guiWith(TestModule... modules) {
        ModuleRegistry registry = new ModuleRegistry();
        for (TestModule module : modules) {
            registry.register(module);
        }
        return new ClickGui(registry);
    }

    /** 关闭状态下不产生任何绘制调用，点击也不被消费（返回 false 以便事件继续下传） */
    @Test
    void closedGuiDrawsNothingAndSwallowsNoInput() {
        ClickGui gui = guiWith(new TestModule("Fly", Category.MOVEMENT));
        RecordingRenderer renderer = new RecordingRenderer();

        gui.render(renderer);
        assertTrue(renderer.calls.isEmpty(), "closed GUI must not draw");

        assertFalse(gui.mouseClicked(20, 20, 0));
    }

    /** 打开 GUI 后定位 MOVEMENT 分类面板，点击首个模块行应把对应模块从禁用切为启用 */
    @Test
    void clickingModuleRowTogglesIt() {
        TestModule fly = new TestModule("Fly", Category.MOVEMENT);
        ClickGui gui = guiWith(fly);
        gui.setOpen(true);

        CategoryPanel movement = null;
        for (CategoryPanel panel : gui.panels()) {
            if (panel.category() == Category.MOVEMENT) {
                movement = panel;
            }
        }
        assertTrue(movement != null, "MOVEMENT panel must exist");

        ModuleRow row = movement.rows().get(0);
        assertFalse(fly.isEnabled());
        assertTrue(row.mouseClicked(row.x() + 2, row.y() + 2, 0));
        assertTrue(fly.isEnabled());
    }

    /** 点击分类面板标题折叠/展开：折叠后高度变小、宽度收窄为窄条且模块行隐藏，再点一次恢复 */
    @Test
    void collapseHidesRowsAndShrinksPanel() {
        ClickGui gui = guiWith(new TestModule("Fly", Category.MOVEMENT));
        CategoryPanel panel = null;
        for (CategoryPanel candidate : gui.panels()) {
            if (candidate.category() == Category.MOVEMENT) {
                panel = candidate;
            }
        }
        float fullHeight = panel.height();
        float fullWidth = panel.width();
        assertTrue(panel.isExpanded());

        assertTrue(panel.mouseClicked(panel.x() + 2, panel.y() + 2, 0), "header press is consumed");
        // 折叠发生在释放时：标题栏同时是拖动把手，只有「按下后原地松开」才算点击
        assertTrue(panel.mouseReleased(panel.x() + 2, panel.y() + 2, 0), "header release collapses");
        assertFalse(panel.isExpanded());
        assertTrue(panel.height() < fullHeight);
        assertTrue(panel.width() < fullWidth, "collapsed rail narrows");
        assertFalse(panel.rows().get(0).isVisible());

        panel.mouseClicked(panel.x() + 2, panel.y() + 2, 0);
        panel.mouseReleased(panel.x() + 2, panel.y() + 2, 0);
        assertTrue(panel.isExpanded());
        assertEquals(fullWidth, panel.width(), 0.01f);
        assertTrue(panel.rows().get(0).isVisible());
    }

    /** 拖动标题栏应整列跟随移动，且行随之同步平移（行持有绝对坐标，不会自动跟随父控件） */
    @Test
    void draggingHeaderMovesPanelAndItsRows() {
        ClickGui gui = guiWith(new TestModule("Fly", Category.MOVEMENT));
        CategoryPanel panel = panelOf(gui, Category.MOVEMENT);
        ModuleRow row = panel.rows().get(0);

        float panelX = panel.x();
        float panelY = panel.y();
        float rowX = row.x();
        float rowY = row.y();

        panel.mouseClicked(panelX + 2, panelY + 2, 0);
        // 越过 3px 阈值才算拖动；这里一次移动 40px，必然进入拖动状态
        panel.mouseDragged(panelX + 42, panelY + 22, 0, 40, 20);
        panel.mouseReleased(panelX + 42, panelY + 22, 0);

        assertEquals(panelX + 40f, panel.x(), 0.01f, "panel follows the pointer");
        assertEquals(panelY + 20f, panel.y(), 0.01f);
        assertEquals(rowX + 40f, row.x(), 0.01f, "rows move with the panel");
        assertEquals(rowY + 20f, row.y(), 0.01f);
        // 拖动过的手势不应再触发折叠
        assertTrue(panel.isExpanded());
    }

    /** 右键点击模块行唤出设置面板；右键点空白处收起 */
    @Test
    void rightClickOpensAndClosesConfigPanel() {
        ClickGui gui = guiWith(new TestModule("Fly", Category.MOVEMENT));
        gui.setOpen(true);
        CategoryPanel panel = panelOf(gui, Category.MOVEMENT);
        ModuleRow row = panel.rows().get(0);

        assertFalse(gui.configPanel().isVisible(), "hidden until requested");
        assertTrue(gui.mouseClicked(row.x() + 2, row.y() + 2, 1));
        assertTrue(gui.configPanel().isVisible());
        assertEquals("Fly", gui.configPanel().module().name());

        // 点在没有模块行的位置应收起面板
        assertTrue(gui.mouseClicked(5, 5, 1));
        assertFalse(gui.configPanel().isVisible());
    }

    /** 滚轮滚动整列：向上滚把内容下移，但不会让内容顶越过上边距 */
    @Test
    void scrollMovesPanelsAndClampsAtTopMargin() {
        ClickGui gui = guiWith(new TestModule("Fly", Category.MOVEMENT));
        gui.setOpen(true);
        CategoryPanel panel = panelOf(gui, Category.MOVEMENT);
        float top = panel.y();

        // 向上滚（正增量）让内容上移，从而看到更下方的模块
        assertTrue(gui.mouseScrolled(panel.x() + 2, panel.y() + 2, 1d));
        assertTrue(panel.y() < top, "content moves up");

        // 连续向下滚远超过内容高度：夹取到顶部边距，而不是无限下移
        for (int i = 0; i < 40; i++) {
            gui.mouseScrolled(panel.x() + 2, panel.y() + 2, -1d);
        }
        assertEquals(12f, panel.y(), 0.01f, "clamped to the top margin");
    }

    /** 定位指定分类的面板 */
    private static CategoryPanel panelOf(ClickGui gui, Category category) {
        for (CategoryPanel panel : gui.panels()) {
            if (panel.category() == category) {
                return panel;
            }
        }
        throw new AssertionError("no panel for " + category);
    }

    /** 按下 ESC（键码 256）返回 true 表示已处理，并使 GUI 关闭 */
    @Test
    void escapeClosesGui() {
        ClickGui gui = guiWith(new TestModule("Fly", Category.MOVEMENT));
        gui.setOpen(true);
        assertTrue(gui.keyPressed(256, 0));
        assertFalse(gui.isOpen());
    }
}
