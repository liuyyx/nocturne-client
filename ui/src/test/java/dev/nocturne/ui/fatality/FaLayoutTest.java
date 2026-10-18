package dev.nocturne.ui.fatality;

import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.Module;
import dev.nocturne.client.module.ModuleRegistry;

import dev.nocturne.ui.RecordingRenderer;
import dev.nocturne.ui.text.FontRasterizer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FaClickGui} 的布局测试：默认窗口尺寸、列数与瀑布流不越界。
 *
 * <p>用记录型绘制面跑一遍真实渲染路径，断言的是<b>画出来的几何</b>——列数与窗口尺寸算错时，
 * 界面上表现为"卡片挤成一列"或"窗口比内容还小"，只测单独的数学函数抓不到。
 */
class FaLayoutTest {

    /** 测试画布宽度（设计单位）。 */
    private static final int VIEWPORT_W = 1142;
    /** 测试画布高度（设计单位）。 */
    private static final int VIEWPORT_H = 700;

    /** 画面记录器。 */
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
        renderer = new RecordingRenderer();
        // 布局会被持久化到工作目录；残留文件会让"默认窗口尺寸"这条断言随上次运行而变化。
        Files.deleteIfExists(Paths.get(System.getProperty("user.dir", "."), "nocturne/clickgui.properties"));
    }

    @AfterEach
    void tearDown() {
        FontRasterizer.invalidate();
    }

    /** 设计目标是 960×540 的画布；1142×700 下缩放为 1，默认窗口就是 744×540 并居中。 */
    @Test
    void defaultWindowKeepsItsDesignSizeAndCentres() {
        ModuleRegistry registry = new ModuleRegistry();
        registry.register(module("Alpha", Category.RENDER));
        FaClickGui gui = new FaClickGui(registry);

        gui.setOpen(true);
        gui.setViewport(VIEWPORT_W, VIEWPORT_H);
        gui.update(System.currentTimeMillis(), 10, 10);
        gui.render(renderer);

        assertEquals(1.0f, gui.scale(), 1e-6, "1142x700 fits the design canvas without shrinking");
        RecordingRenderer.DrawCall window = windowRect();
        assertEquals(FaTheme.DEFAULT_W, window.width, 0.5f, "default window width");
        assertEquals(FaTheme.DEFAULT_H, window.height, 0.5f, "default window height");
        assertEquals((VIEWPORT_W - FaTheme.DEFAULT_W) * 0.5f, window.x, 0.5f, "window is centred");
        // 纵向会被"打开动画"整体抬起最多 8 单位（首帧不可能已经停在终点），因此只夹一个区间。
        float expectedY = (VIEWPORT_H - FaTheme.DEFAULT_H) * 0.5f;
        assertTrue(window.y >= expectedY - 0.5f && window.y <= expectedY + 8.5f,
                "window is centred vertically (open animation lifts it by at most 8): " + window.y);
    }

    /** 646 设计单位宽的内容区排三列；恰好够两列时排两列，最窄一列。 */
    @Test
    void columnsFollowTheAvailableWidth() {
        FaClickGui gui = new FaClickGui(new ModuleRegistry());

        assertEquals(3, gui.columns(FaTheme.DEFAULT_W - FaTheme.SIDEBAR_W),
                "the default window fits three columns");
        float twoColumns = 2 * FaTheme.MIN_COLUMN_W + FaTheme.CONTENT_PAD + FaTheme.COLUMN_GAP;
        assertEquals(2, gui.columns(twoColumns), "exactly two columns fit at this width");
        assertEquals(1, gui.columns(FaTheme.MIN_COLUMN_W), "a single column when nothing else fits");
    }

    /** 瀑布流必须把卡片放在列起点上，且整体不越过窗口右边界。 */
    @Test
    void masonryKeepsCardsInsideTheContentArea() {
        ModuleRegistry registry = new ModuleRegistry();
        for (int i = 0; i < 7; i++) {
            // 用 MISC 避免 Render 分类额外插入的预览卡片混进计数。
            registry.register(module("Module" + i, Category.MISC));
        }
        FaClickGui gui = new FaClickGui(registry);

        gui.setOpen(true);
        gui.setViewport(VIEWPORT_W, VIEWPORT_H);
        gui.update(System.currentTimeMillis(), 10, 10);
        gui.render(renderer);

        RecordingRenderer.DrawCall window = windowRect();
        float contentX = window.x + FaTheme.SIDEBAR_W;
        float contentRight = window.x + window.width;
        Set<Float> columnStarts = new HashSet<Float>();
        int cards = 0;
        for (RecordingRenderer.DrawCall call : renderer.ofKind("roundedRect")) {
            if (call.radius != FaTheme.GROUP_RADIUS || call.x < contentX - 0.5f) {
                continue;
            }
            cards++;
            columnStarts.add(call.x);
            assertTrue(call.x + call.width <= contentRight + 0.5f,
                    "a card must not stick out of the window: " + call.x + " + " + call.width);
        }
        assertEquals(7, cards, "every module must be laid out");
        assertTrue(columnStarts.size() <= 3, "the masonry must use at most three columns");
        assertTrue(columnStarts.size() > 1, "seven cards must spread over more than one column");
    }

    /** @return 记录流里面积最大的圆角矩形——窗口本体（底色被淡入动画改写过，不能按颜色认） */
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
}
