package dev.nocturne.ui.fatality;

import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.Module;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 语言层的契约：**只影响显示**，并且切换必须是可逆的。
 *
 * <p>这三条是中文界面最容易回归的点：翻译漏了（英文混排）、切回英文后仍输出中文、
 * 以及搜索框输入中文查不到英文数据名。
 */
class FaStringsTest {

    @AfterEach
    void restoreChinese() {
        FaStrings.setChinese(true);
    }

    /** 默认中文：已知词条翻译，空值与空串保持安全。 */
    @Test
    void translatesKnownNamesAndPreservesEmptyText() {
        FaStrings.setChinese(true);
        assertEquals("疾跑", FaStrings.t("Sprint"));
        assertEquals("移动", FaStrings.t("MOVEMENT"));
        assertEquals("启用", FaStrings.t("Enabled"));
        assertEquals("仅白名单", FaStrings.t("Whitelist Only"));
        assertEquals("", FaStrings.t(""));
        assertEquals(null, FaStrings.t(null));
    }

    /** 切到英文：同一个键返回原文——这条挡住"切了语言但界面不变"。 */
    @Test
    void englishModeReturnsOriginalText() {
        FaStrings.setChinese(false);
        assertEquals("Sprint", FaStrings.t("Sprint"));
        assertEquals("MOVEMENT", FaStrings.t("MOVEMENT"));
        assertEquals("Whitelist Only", FaStrings.t("Whitelist Only"));
    }

    /** 中文查询要能命中英文数据名，否则中文界面下按名字搜索是坏的。 */
    @Test
    void chineseQueryMapsBackToEnglishKey() {
        assertEquals("Sprint", FaStrings.searchKey("疾跑"));
        assertEquals("sprint", FaStrings.searchKey("sprint"));
        assertEquals("", FaStrings.searchKey(""));
    }

    @Test
    void partialChineseQueryMatchesRealModuleCard() {
        FaGroup group = new FaGroup(new Module() {
            @Override
            public String name() {
                return "Kill Aura";
            }

            @Override
            public Category category() {
                return Category.COMBAT;
            }
        });
        assertTrue(group.filter("击杀"));
        assertTrue(group.filter("光环"));
    }
}
