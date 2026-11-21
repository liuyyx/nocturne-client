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
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FaClickGui} 的可见布局契约：专注设置、模块切换、搜索与窄窗不越界。
 *
 * <p>通过实际绘制和鼠标/键盘入口验证，避免把内部列数公式固定成产品行为。
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
        LayoutStore.resetForTests();
        renderer = new RecordingRenderer();
        // 布局会被持久化到工作目录；残留文件会让"默认窗口尺寸"这条断言随上次运行而变化。
        Files.deleteIfExists(Paths.get(System.getProperty("user.dir", "."), "nocturne/clickgui.properties"));
    }

    @AfterEach
    void tearDown() throws Exception {
        LayoutStore.resetForTests();
        Files.deleteIfExists(Paths.get(System.getProperty("user.dir", "."), "nocturne/clickgui.properties"));
        FontRasterizer.invalidate();
    }

    /** 足够大的画布中，窗口保持设计尺寸并居中。 */
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

    /** 选择模块后只显示它的设置，开关命中不得落到上一个模块。 */
    @Test
    void selectingAModuleShowsAndEditsOnlyItsSettings() {
        Module alpha = module("Alpha", Category.MISC);
        Module beta = module("Beta", Category.MISC);
        ModuleRegistry registry = new ModuleRegistry();
        registry.register(alpha);
        registry.register(beta);
        FaClickGui gui = new FaClickGui(registry);
        renderer.textures = false;
        gui.setOpen(true);
        gui.setViewport(VIEWPORT_W, VIEWPORT_H);
        gui.update(System.currentTimeMillis(), 10, 10);
        gui.render(renderer);

        RecordingRenderer.DrawCall window = windowRect();
        assertEquals(1, cards(window).size(), "默认只展开一个模块，避免重复导航和设置墙");
        RecordingRenderer.DrawCall betaLabel = renderer.ofKind("text").stream()
                .filter(call -> "Beta".equals(call.text) && call.x < window.x + FaTheme.SIDEBAR_W)
                .findFirst().orElseThrow(() -> new AssertionError("Beta 必须能从侧栏选择"));
        float clickX = betaLabel.x + 3.0f;
        float clickY = betaLabel.y + betaLabel.textSize * 0.5f;
        gui.mouseClicked(clickX, clickY, 0);
        gui.mouseReleased(clickX, clickY, 0);
        renderer.clear();
        gui.render(renderer);

        assertEquals(1, cards(windowRect()).size(), "切换后仍只显示当前模块");
        assertTrue(renderer.ofKind("text").stream()
                .anyMatch(call -> "Beta".equals(call.text) && call.x > window.x + FaTheme.SIDEBAR_W),
                "设置标题必须跟随侧栏选择");
        assertFalse(renderer.ofKind("text").stream()
                .anyMatch(call -> "Alpha".equals(call.text) && call.x > window.x + FaTheme.SIDEBAR_W),
                "旧模块设置必须退出绘制与命中");
        RecordingRenderer.DrawCall enabled = renderer.ofKind("text").stream()
                .filter(call -> FaStrings.t("Enabled").equals(call.text)).findFirst()
                .orElseThrow(() -> new AssertionError("当前模块开关必须可见"));
        gui.mouseClicked(enabled.x + 3.0f, enabled.y + enabled.textSize * 0.5f, 0);
        assertTrue(beta.isEnabled(), "点击当前设置行应修改 Beta");
        assertFalse(alpha.isEnabled(), "Alpha 不得残留命中");
    }

    /** 搜索保留多模块浏览；缩窄窗口时卡片继续保持内边距且互不覆盖。 */
    @Test
    void searchResultsStayInsideWideAndNarrowWindows() {
        ModuleRegistry registry = new ModuleRegistry();
        for (int i = 0; i < 7; i++) {
            registry.register(module("Module" + i, Category.MISC));
        }
        FaClickGui gui = new FaClickGui(registry);
        gui.setOpen(true);
        gui.setViewport(VIEWPORT_W, VIEWPORT_H);
        gui.update(System.currentTimeMillis(), 10, 10);
        gui.render(renderer);
        gui.keyPressed(java.awt.event.KeyEvent.VK_F, 2);
        for (char character : "Module".toCharArray()) {
            gui.charTyped(character);
        }
        renderer.clear();
        gui.render(renderer);
        RecordingRenderer.DrawCall window = windowRect();
        assertEquals(7, cards(window).size(), "宽窗搜索应展示全部匹配模块，而不是只显示当前选择");
        assertCardsStayInside(window);

        float gripX = window.x + window.width - 3.0f;
        float gripY = window.y + window.height - 3.0f;
        float shrink = window.width - FaTheme.MIN_W;
        gui.mouseClicked(gripX, gripY, 0);
        gui.mouseDragged(gripX - shrink, gripY, 0, -shrink, 0.0);
        gui.mouseReleased(gripX - shrink, gripY, 0);
        renderer.clear();
        gui.render(renderer);
        assertTrue(windowRect().width < window.width, "缩放手柄必须实际缩窄窗口");
        assertCardsStayInside(windowRect());
    }

    private List<RecordingRenderer.DrawCall> cards(RecordingRenderer.DrawCall window) {
        List<RecordingRenderer.DrawCall> cards = new ArrayList<RecordingRenderer.DrawCall>();
        for (RecordingRenderer.DrawCall call : renderer.ofKind("roundedRect")) {
            if (call.radius == FaTheme.GROUP_RADIUS && call.width >= FaTheme.MIN_COLUMN_W
                    && call.x >= window.x + FaTheme.SIDEBAR_W) {
                cards.add(call);
            }
        }
        return cards;
    }

    private void assertCardsStayInside(RecordingRenderer.DrawCall window) {
        List<RecordingRenderer.DrawCall> cards = cards(window);
        assertFalse(cards.isEmpty(), "设置内容不得因布局变化消失");
        for (int i = 0; i < cards.size(); i++) {
            RecordingRenderer.DrawCall card = cards.get(i);
            assertTrue(card.x >= window.x + FaTheme.SIDEBAR_W + FaTheme.CONTENT_PAD - 0.5f,
                    "内容区左侧必须保留留白");
            assertTrue(card.x + card.width <= window.x + window.width - FaTheme.CONTENT_PAD + 0.5f,
                    "内容区右侧必须保留留白");
            for (int j = i + 1; j < cards.size(); j++) {
                RecordingRenderer.DrawCall other = cards.get(j);
                float overlapX = Math.min(card.x + card.width, other.x + other.width) - Math.max(card.x, other.x);
                float overlapY = Math.min(card.y + card.height, other.y + other.height) - Math.max(card.y, other.y);
                assertTrue(overlapX <= 0.5f || overlapY <= 0.5f, "设置卡片不得互相覆盖");
            }
        }
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
