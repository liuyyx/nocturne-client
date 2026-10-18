package dev.nocturne.ui.fatality;

import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.Module;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.ColorValue;
import dev.nocturne.client.value.ModeValue;
import dev.nocturne.client.value.NumberValue;
import dev.nocturne.client.value.Value;

import dev.nocturne.ui.RecordingRenderer;
import dev.nocturne.ui.text.FontRasterizer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link FaGroup} 的设置项映射与搜索收窄测试。
 *
 * <p>钉住的是「哪些 {@code Value} 变成哪种行」：画不出来的类型必须被跳过而不是画出空行，
 * 而每种受支持的类型必须落到正确的控件上——映射错了界面上就是个点不动的死控件。
 */
class FaGroupMappingTest {

    /** 只用于驱动映射的测试模块：四种受支持的类型各一个，外加一个不受支持的类型。 */
    private static final class LabModule extends Module {
        /** 布尔设置。 */
        final BooleanValue flag = add(new BooleanValue("Flag", false));
        /** 数值设置。 */
        final NumberValue amount = add(new NumberValue("Amount", 5.0, 0.0, 10.0, 1.0));
        /** 枚举设置。 */
        final ModeValue mode = add(new ModeValue("Mode", "A", "A", "B"));
        /** 颜色设置。 */
        final ColorValue tint = add(new ColorValue("Tint", 0xFF112233));
        /** 不受支持的类型：必须被跳过。 */
        final Value<Integer> opaque = add(new Value<Integer>("Opaque", 1) {
            @Override
            public String display() {
                return "1";
            }
        });

        @Override
        public String name() {
            return "Lab";
        }

        @Override
        public Category category() {
            return Category.RENDER;
        }
    }

    /** 记录型绘制面（字体句柄需要一个后端）。 */
    private RecordingRenderer renderer;

    @BeforeEach
    void setUp() {
        FontRasterizer.invalidate();
        renderer = new RecordingRenderer();
        FontRasterizer.bind(renderer, 1.0f, 1.0f);
    }

    @AfterEach
    void tearDown() {
        FontRasterizer.invalidate();
    }

    /** 每种受支持的设置项都必须落到对应的控件行；未知类型被跳过。 */
    @Test
    void mapsEverySupportedValueToItsRowType() {
        FaGroup group = new FaGroup(new LabModule());
        List<FaRow> rows = group.rows();

        assertEquals(5, rows.size(), "Enabled + four supported values; the unknown type is skipped");
        assertTrue(rows.get(0) instanceof FaEnableRow, "the first row is the module switch");
        assertTrue(rows.get(1) instanceof FaCheckRow, "BooleanValue maps to a checkbox");
        assertTrue(rows.get(2) instanceof FaSliderRow, "NumberValue maps to a slider");
        assertTrue(rows.get(3) instanceof FaComboRow, "ModeValue maps to a combo");
        assertTrue(rows.get(4) instanceof FaColorRow, "ColorValue maps to a colour chip");
    }

    /** 未知类型必须被丢弃（返回 {@code null}）而不是抛异常。 */
    @Test
    void skipsUnsupportedValueTypes() {
        Value<String> unknown = new Value<String>("Text", "x") {
            @Override
            public String display() {
                return "x";
            }
        };

        assertNull(FaGroup.create(unknown));
    }

    /** 搜索按标签与选项文本收窄行；命中标题时整张卡片保留。 */
    @Test
    void filterNarrowsRowsAndReportsMatches() {
        FaGroup group = new FaGroup(new LabModule());

        assertTrue(group.filter("mode"), "a row label match keeps the card");
        assertTrue(group.filter("lab"), "the module name match keeps every row");
        assertFalse(group.filter("nothing-matches"), "no match must drop the card");
        assertTrue(group.filter(""), "an empty query restores everything");
    }

    /** 卡片高度随被过滤掉的行收缩，不会留下空白。 */
    @Test
    void filteredHeightShrinksWithHiddenRows() {
        FaGroup group = new FaGroup(new LabModule());
        float full = group.targetHeight();

        group.filter("mode");
        float narrowed = group.targetHeight();

        assertTrue(narrowed < full, "hidden rows must not contribute height");
        assertTrue(narrowed > 0f);
    }

    /** 图标字形在退化模式下换成 ASCII 别名：Lucide 的私用区码点在游戏字体里是个方框。 */
    @Test
    void iconGlyphsFallBackToAsciiAliases() {
        renderer.textures = false;
        // Provider 在构造时就定下走图集还是退化，因此改完能力必须重新绑定。
        FontRasterizer.invalidate();
        FontRasterizer.bind(renderer, 1.0f, 1.0f);

        assertFalse(FontRasterizer.atlasActive());
        assertEquals("v", FaTheme.glyph(FaTheme.I_CHECK));
        assertEquals("?", FaTheme.glyph(FaTheme.I_SEARCH));
        assertEquals("*", FaTheme.icon(Category.MISC));
    }

    /** 走图集时图标字形原样保留 Lucide 码点。 */
    @Test
    void iconGlyphsStayIntactWithTheAtlas() {
        assertTrue(FontRasterizer.atlasActive());
        assertEquals(FaTheme.I_CHECK, FaTheme.glyph(FaTheme.I_CHECK));
        assertEquals(FaTheme.I_MOVEMENT, FaTheme.icon(Category.MOVEMENT));
    }
}
