package dev.nocturne.ui.fatality;

import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.Module;
import dev.nocturne.client.module.ModuleRegistry;
import dev.nocturne.ui.RecordingRenderer;
import dev.nocturne.ui.text.FontRasterizer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 交互回归：分辨率变化、右键释放、浮层命中与滚动偏移。
 *
 * <p>这些都是"看得到但算错了"的错位：画面正常，点到的却是另一项或窗口跑出屏幕。
 * 因此断言的是绘制/命中用到的几何，而不是内部的布尔标志。
 */
class FaInteractionTest {

    /** 记录型绘制面。 */
    private RecordingRenderer renderer;

    /** 造一个只用于占位的模块。 */
    private static Module module(String name, final Category category) {
        return new Module() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public Category category() {
                return category;
            }
        };
    }

    @BeforeEach
    void setUp() throws Exception {
        FontRasterizer.invalidate();
        LayoutStore.resetForTests();
        Files.deleteIfExists(layoutFile());
        renderer = new RecordingRenderer();
    }

    @AfterEach
    void tearDown() throws Exception {
        // 本测试会真的保存布局；静态字段与磁盘文件都必须清掉，否则后面的测试会按旧位置摆窗口。
        LayoutStore.resetForTests();
        Files.deleteIfExists(layoutFile());
        FontRasterizer.invalidate();
    }

    /** @return 布局文件（保存布局时写盘的目标）；本测试只关心它是否被写。 */
    private static Path layoutFile() {
        return Paths.get(System.getProperty("user.dir", "."), "nocturne/clickgui.properties");
    }

    /**
     * 分辨率变了、缩放没变时也必须按新视口重算，否则窗口夹取用的是旧画布并跑到屏外。
     *
     * <p>980×600 与 1142×700 的缩放都是 1（都放得下 960×540 设计画布），只有视口尺寸不同。
     */
    @Test
    void resolutionChangeWithSameScaleReclampsWindow() {
        FaClickGui gui = new FaClickGui(singleModuleRegistry());
        gui.setOpen(true);
        gui.setViewport(1142, 700);
        gui.update(System.currentTimeMillis(), 10, 10);
        gui.render(renderer);
        assertEquals(1.0f, gui.scale(), 1e-6, "1142x700 不缩小");

        gui.setViewport(980, 600);
        renderer.clear();
        gui.render(renderer);

        assertEquals(1.0f, gui.scale(), 1e-6, "980x600 仍然放得下设计画布，缩放不变");
        RecordingRenderer.DrawCall window = windowRect();
        assertTrue(window.x >= 0.0f && window.x + window.width <= 980.0f + 0.5f,
                "窗口必须水平留在新视口内: " + window.x + " + " + window.width);
        assertTrue(window.y + window.height <= 600.0f + 8.5f,
                "窗口必须垂直留在新视口内（打开动画最多下移 8）: " + window.y + " + " + window.height);
    }

    /** 右键松开不得结束左键拖动，也不得写盘；左键松开才收尾并保存。 */
    @Test
    void rightReleaseDoesNotEndLeftDragOrSaveLayout() {
        FaClickGui gui = new FaClickGui(singleModuleRegistry());
        gui.setOpen(true);
        gui.setViewport(1142, 700);
        gui.update(System.currentTimeMillis(), 10, 10);
        gui.render(renderer);
        RecordingRenderer.DrawCall window = windowRect();

        // 头部右侧空白（避开分类 Tab 与语言芯片），按下开始拖动窗口。
        float clickX = window.x + window.width - 4.0f;
        float clickY = window.y + 10.0f;
        assertTrue(gui.mouseClicked(clickX, clickY, 0), "头部空白处应被窗口消费");
        gui.mouseDragged(clickX - 30.0f, clickY, 0, -30.0, 0.0);

        renderer.clear();
        gui.render(renderer);
        assertEquals(window.x - 30.0f, windowRect().x, 0.5f, "拖动应移动窗口");

        gui.mouseReleased(clickX - 30.0f, clickY, 1);
        assertFalse(Files.exists(layoutFile()), "右键松开不应保存布局");

        gui.mouseDragged(clickX - 60.0f, clickY, 0, -30.0, 0.0);
        renderer.clear();
        gui.render(renderer);
        assertEquals(window.x - 60.0f, windowRect().x, 0.5f, "右键松开不能打断左键拖动");

        gui.mouseReleased(clickX - 60.0f, clickY, 0);
        assertTrue(Files.exists(layoutFile()), "左键松开结束拖动时才保存布局");
    }

    /** 搜索框聚焦时 FaClickGui 必须声明缩放键属于输入内容。 */
    @Test
    void searchFocusBlocksScaleKeys() {
        FaClickGui gui = new FaClickGui(singleModuleRegistry());
        gui.setOpen(true);
        gui.setViewport(1142, 700);
        gui.update(System.currentTimeMillis(), 10, 10);

        assertFalse(gui.blocksScaleKeys(), "没有输入焦点时缩放键可用");
        assertTrue(gui.keyPressed(java.awt.event.KeyEvent.VK_F, 2), "Ctrl+F 应打开搜索框");
        assertTrue(gui.blocksScaleKeys(), "搜索框聚焦时 = / - 是输入内容");
    }

    /**
     * 下拉列表的点击索引必须用绘制时的 scrollMotion 当前值，而不是滚轮目标值。
     *
     * <p>滚一格后目标偏移 29 单位（两项），动画尚未推进：用户看到的仍是未滚动的内容，
     * 点到第 6 行就必须选中第 6 项。
     */
    @Test
    void listPopupClickUsesRenderedScrollOffset() {
        RecordingModel model = new RecordingModel(20);
        FaListPopup popup = new FaListPopup("owner", model, 80.0f);
        popup.place(0.0f, 0.0f, 80.0f, 12.0f, 0.0f, 0.0f, 500.0f, 500.0f);
        FaContext ctx = new FaContext(new FaClickGui(new ModuleRegistry()));

        assertTrue(popup.mouseScrolled(ctx, popup.x + 5.0f, popup.y + 5.0f, -1.0),
                "滚轮应被列表接住");

        // ITEM_H = 14.5、PAD = 3.5；点第 6 项（索引 5）的中间。
        float clickY = popup.y + 3.5f + 5 * 14.5f + 1.0f;
        popup.mouseClicked(ctx, popup.x + 5.0f, clickY, 0);

        assertEquals(5, model.clicked, "点击必须落在画出来的那一项上");
    }

    /**
     * 浮层绘制时按出现动画上移最多 4 单位，命中测试必须用同一个矩形。
     */
    @Test
    void popupHitTestFollowsOpenAnimationOffset() throws Exception {
        ProbePopup popup = new ProbePopup();
        popup.x = 100.0f;
        popup.y = 100.0f;
        popup.w = 50.0f;
        popup.h = 40.0f;
        FaMotion open = openMotion(popup);

        open.snap(1.0f);
        assertFalse(popup.contains(120.0, 98.0), "完全展开时矩形从 y 开始");

        open.snap(0.5f);
        // 绘制矩形上移 (1 - 0.5) * -4 = -2：真实范围是 y-2 .. y+h-2。
        assertTrue(popup.contains(120.0, 99.0), "命中必须跟着动画上移");
        assertFalse(popup.contains(120.0, 97.0), "上移后的顶边之上不算命中");
    }

    /** @return 带一个 MISC 模块的注册表（空界面也能画，但有卡片更接近真实场景）。 */
    private static ModuleRegistry singleModuleRegistry() {
        ModuleRegistry registry = new ModuleRegistry();
        registry.register(module("Alpha", Category.MISC));
        return registry;
    }

    /** @return 记录流里面积最大的圆角矩形——窗口本体 */
    private RecordingRenderer.DrawCall windowRect() {
        RecordingRenderer.DrawCall best = null;
        for (RecordingRenderer.DrawCall call : renderer.ofKind("roundedRect")) {
            if (call.radius != FaTheme.RADIUS || call.width <= FaTheme.SIDEBAR_W) {
                continue;
            }
            if (best == null || call.width * call.height > best.width * best.height) {
                best = call;
            }
        }
        if (best == null) {
            throw new AssertionError("the window surface was never drawn: " + renderer.kinds());
        }
        return best;
    }

    /** @return 浮层的出现动画（测试里直接置值，避免依赖帧率迭代） */
    private static FaMotion openMotion(FaPopup popup) throws Exception {
        Field field = FaPopup.class.getDeclaredField("open");
        field.setAccessible(true);
        return (FaMotion) field.get(popup);
    }

    /** 只记录被点了第几项的下拉模型。 */
    private static final class RecordingModel implements FaListPopup.Model {
        private final int size;
        private int clicked = -1;

        RecordingModel(int size) {
            this.size = size;
        }

        @Override
        public int size() {
            return size;
        }

        @Override
        public String label(int index) {
            return "item" + index;
        }

        @Override
        public boolean selected(int index) {
            return false;
        }

        @Override
        public void click(int index) {
            clicked = index;
        }

        @Override
        public boolean multi() {
            return false;
        }
    }

    /** 只用来验证命中矩形的空浮层。 */
    private static final class ProbePopup extends FaPopup {
        ProbePopup() {
            super("probe");
        }

        @Override
        protected void draw(FaContext ctx, float alpha) {
        }
    }
}
