/*
 * 移植自 Setsuna（上游 commit e4915ae093748d48d92ee47c38cdb8b2746a4730，作者 ShiYi，
 * 许可 GPL-3.0-or-later，见 THIRD-PARTY-NOTICES.md）。
 * 本文件相对上游的改动：包名；分组枚举换成本项目的 Category；switch 表达式降级为 switch 语句。
 */
package dev.noturne.ui.skija;

import dev.noturne.client.module.Category;

/**
 * 各功能分组使用的 Lucide 字形。
 *
 * <p>这些字形来自打包的 {@code lucide.ttf}（见 {@code SkijaUi.IconSet.LUCIDE}）——用图标字体而不是
 * 位图，是为了任意字号下都清晰，且换色只需改 {@code Paint} 的颜色。
 */
public final class CategoryGlyphs {

    /** 设置 / 配置面板的字形。 */
    public static final String CONFIG = "\uE247";

    private CategoryGlyphs() {
    }

    /**
     * 取分组对应的字形。
     *
     * <p>未登记的分组（含 {@code null}）回落到 {@link #CONFIG}——宁可显示一个通用图标，
     * 也不要出现空白格导致列宽跳动。
     */
    public static String forCategory(Category category) {
        if (category == null) {
            return CONFIG;
        }
        switch (category) {
            case MOVEMENT:
                return "\uE3B9";
            case RENDER:
                return "\uE1DD";
            case PLAYER:
                return "\uE19F";
            case MISC:
                return "\uE29C";
            default:
                return CONFIG;
        }
    }
}
