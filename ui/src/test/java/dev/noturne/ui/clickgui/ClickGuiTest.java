package dev.noturne.ui.clickgui;

import dev.noturne.client.module.Category;
import dev.noturne.client.module.Module;
import dev.noturne.client.module.ModuleRegistry;
import dev.noturne.client.value.NumberValue;
import dev.noturne.ui.FakeBackend;
import dev.noturne.ui.FakeInput;
import dev.noturne.ui.RecordingRenderer;
import dev.noturne.ui.component.Slider;
import dev.noturne.ui.gl.GuiOverlay;
import dev.noturne.ui.theme.Theme;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证 {@link ClickGui} 契约：关闭时不绘制也不吞输入、点击模块行切换模块状态、
 * 折叠分类面板隐藏行并收缩高度、拖拽标题栏整列跟随、设置面板落点在视口内、
 * 滚轮夹取，以及 Esc 经由 {@link GuiOverlay} 的生产派发路径可达。
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

    /** 带一个数值设置项的模块，用于唤出设置面板并取得 {@link Slider} */
    static final class Configurable extends Module {
        /** 滑块驱动的数值设置 */
        final NumberValue speed = add(new NumberValue("Speed", 5.0, 0.0, 10.0, 1.0));

        @Override
        public String name() {
            return "Configurable";
        }

        @Override
        public Category category() {
            return Category.MOVEMENT;
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

    /** 点击分类面板标题折叠/展开：折叠后高度收为标题栏（宽度不变）且模块行隐藏，再点一次恢复 */
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
        assertEquals(fullWidth, panel.width(), 0.01f, "collapse keeps the panel width");
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

    /**
     * L-06 回归：唤出的设置面板必须落在视口内（不被钳出屏幕外而点不到）。
     *
     * <p>旧用例只断言可见性与绑定模块，面板越界问题不会被发现。
     */
    @Test
    void configPanelLandsInsideTheViewport() {
        ClickGui gui = guiWith(new TestModule("Fly", Category.MOVEMENT));
        gui.setOpen(true);
        gui.setViewport(800, 600);
        CategoryPanel panel = panelOf(gui, Category.MOVEMENT);
        ModuleRow row = panel.rows().get(0);

        assertTrue(gui.mouseClicked(row.x() + 2, row.y() + 2, 1));
        ModuleConfigPanel config = gui.configPanel();
        assertTrue(config.isVisible());
        assertTrue(config.x() >= Theme.PANEL_MARGIN, "panel left edge must stay on screen");
        assertTrue(config.y() >= Theme.PANEL_MARGIN, "panel top edge must stay on screen");
        assertTrue(config.right() <= 800 - Theme.PANEL_MARGIN + 0.01f, "panel right edge must stay on screen");
        assertTrue(config.bottom() <= 600 - Theme.PANEL_MARGIN + 0.01f, "panel bottom edge must stay on screen");
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
        assertEquals(Theme.PANEL_MARGIN, panel.y(), 0.01f, "clamped to the top margin");
    }

    /**
     * L-07 回归：设置视口后，滚动的底部夹取路径才不是死代码。
     *
     * <p>内容高于视口时向上滚（正增量 → 内容上移），底部最终停在视口下边距处；
     * 反向下滚应回到顶部边距。
     */
    @Test
    void scrollClampsContentInsideTheViewport() {
        TestModule[] many = new TestModule[20];
        for (int i = 0; i < many.length; i++) {
            many[i] = new TestModule("Mod" + i, Category.MOVEMENT);
        }
        ClickGui gui = guiWith(many);
        gui.setOpen(true);
        gui.setViewport(800, 200);   // 视口很矮，内容必然超出
        CategoryPanel panel = panelOf(gui, Category.MOVEMENT);
        assertTrue(panel.height() > 200, "fixture must overflow the viewport");

        for (int i = 0; i < 50; i++) {
            gui.mouseScrolled(panel.x() + 2, panel.y() + 2, 1d);
        }
        assertEquals(200 - Theme.PANEL_MARGIN, panel.bottom(), 0.01f,
                "bottom clamp must keep the content flush with the viewport bottom");

        for (int i = 0; i < 50; i++) {
            gui.mouseScrolled(panel.x() + 2, panel.y() + 2, -1d);
        }
        assertEquals(Theme.PANEL_MARGIN, panel.y(), 0.01f, "scrolling back down must stop at the top margin");
    }

    /**
     * H-19 回归：关闭 GUI 必须复位编辑器交互态并收起设置面板。
     *
     * <p>丢失的释放事件会让 {@code Slider.dragging} 残留，重开后首次移动鼠标就改写数值。
     */
    @Test
    void closingGuiResetsEditorInteractionState() {
        ModuleRegistry registry = new ModuleRegistry();
        registry.register(new Configurable());
        ClickGui gui = new ClickGui(registry);
        gui.setOpen(true);
        gui.setViewport(800, 600);

        CategoryPanel panel = panelOf(gui, Category.MOVEMENT);
        ModuleRow row = panel.rows().get(0);
        assertTrue(gui.mouseClicked(row.x() + 2, row.y() + 2, 1));

        ModuleConfigPanel config = gui.configPanel();
        assertTrue(config.isVisible());
        Slider slider = (Slider) config.editors().get(0);
        slider.mouseClicked(slider.x() + 5, slider.y() + 5, 0);
        assertTrue(slider.isDragging(), "slider must be dragging after the press");

        gui.setOpen(false);
        assertFalse(slider.isDragging(), "closing the GUI must cancel the drag gesture");
        assertFalse(config.isVisible(), "closing the GUI must hide the settings panel");

        gui.setOpen(true);
        assertFalse(config.isVisible(), "reopening must not resurrect the stale settings panel");
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

    /**
     * L-23 回归：Esc 关闭必须经由 {@link GuiOverlay} 的生产派发路径可达。
     *
     * <p>直接调 {@code gui.keyPressed} 会掩盖「生产路径从不派发键盘事件」——L-51 曾让 Esc 分支
     * 完全不可达。这里只通过 {@code overlay.onFrame()} 驱动，输入由替身提供。
     */
    @Test
    void escapeClosesGuiThroughTheProductionDispatchPath() {
        ModuleRegistry registry = new ModuleRegistry();
        registry.register(new TestModule("Fly", Category.MOVEMENT));
        FakeInput input = new FakeInput();
        FakeBackend backend = new FakeBackend(800, 600);
        GuiOverlay overlay = new GuiOverlay(registry, backend, input, GuiOverlay.KEY_RIGHT_SHIFT);
        // 模拟「游戏中」：进入 GUI 前指针是被游戏捕获的（转视角状态）
        input.pointerGrabbed = true;

        // 第 1 帧：开关按键按下沿 → 打开 GUI（并把指针交还给 GUI）
        input.keys.add(GuiOverlay.KEY_RIGHT_SHIFT);
        overlay.onFrame();
        assertTrue(overlay.gui().isOpen(), "toggle key press must open the GUI");
        assertFalse(input.pointerGrabbed, "opening the GUI must release the pointer");

        // 第 2 帧：松开开关按键，GUI 保持打开
        input.keys.clear();
        overlay.onFrame();
        assertTrue(overlay.gui().isOpen());

        // 第 3 帧：按下 Esc（AWT VK 27）→ 必须经由叠加层的键盘派发关闭
        input.keys.add(27);
        overlay.onFrame();
        assertFalse(overlay.gui().isOpen(), "Esc must close the GUI through production dispatch");
    }

    /**
     * 指针捕获交接回归：GUI 打开时释放、关闭时**恢复打开前的状态**，而不是无条件捕获。
     *
     * <p>曾经的实现是「每帧强制 {@code setPointerGrabbed(!open)}」——在主菜单（游戏本来就不捕获
     * 指针）里注入后，光标会被叠加层锁死，玩家连菜单都点不了。这条用例同时钉住两个方向：
     * 游戏内打开再关闭必须恢复捕获；主菜单打开再关闭必须保持可见光标。
     */
    @Test
    void pointerGrabIsHandedOverAndRestoredInsteadOfForced() {
        ModuleRegistry registry = new ModuleRegistry();
        registry.register(new TestModule("Fly", Category.MOVEMENT));
        FakeInput input = new FakeInput();
        FakeBackend backend = new FakeBackend(800, 600);
        GuiOverlay overlay = new GuiOverlay(registry, backend, input, GuiOverlay.KEY_RIGHT_SHIFT);

        // 场景一：游戏中（打开前被捕获）→ 打开释放、关闭恢复捕获
        input.pointerGrabbed = true;
        openViaToggle(overlay, input);
        assertFalse(input.pointerGrabbed, "in-game: opening must release the pointer");
        closeViaEscape(overlay, input);
        assertTrue(input.pointerGrabbed, "in-game: closing must restore the game's capture");

        // 场景二：主菜单（打开前未被捕获）→ 关闭后光标必须仍然可见
        input.pointerGrabbed = false;
        openViaToggle(overlay, input);
        assertFalse(input.pointerGrabbed);
        closeViaEscape(overlay, input);
        assertFalse(input.pointerGrabbed, "main menu: closing must NOT grab the cursor");
    }

    /** 用开关按键打开 GUI（按下沿 + 松开两帧）。 */
    private static void openViaToggle(GuiOverlay overlay, FakeInput input) {
        input.keys.add(GuiOverlay.KEY_RIGHT_SHIFT);
        overlay.onFrame();
        input.keys.clear();
        overlay.onFrame();
        assertTrue(overlay.gui().isOpen());
    }

    /** 用 Esc 关闭 GUI。 */
    private static void closeViaEscape(GuiOverlay overlay, FakeInput input) {
        input.keys.add(27);
        overlay.onFrame();
        input.keys.clear();
        overlay.onFrame();
        assertFalse(overlay.gui().isOpen());
    }

    /**
     * 关闭状态下叠加层不得消费 Esc，也不得碰指针状态。
     *
     * <p>后半句是主菜单可用性的关键：GUI 从没打开过时调用 {@code setPointerGrabbed} 会把
     * 光标从玩家手里抢走。
     */
    @Test
    void escapeIsIgnoredAndPointerUntouchedWhileTheGuiIsClosed() {
        ModuleRegistry registry = new ModuleRegistry();
        registry.register(new TestModule("Fly", Category.MOVEMENT));
        FakeInput input = new FakeInput();
        FakeBackend backend = new FakeBackend(800, 600);
        GuiOverlay overlay = new GuiOverlay(registry, backend, input, GuiOverlay.KEY_RIGHT_SHIFT);

        input.keys.add(27);
        overlay.onFrame();
        assertFalse(overlay.gui().isOpen());
        assertEquals(0, input.pointerGrabCalls, "closed GUI must not touch the pointer grab state");
        assertFalse(input.pointerGrabbed, "cursor must stay visible when the GUI was never opened");
    }
}
