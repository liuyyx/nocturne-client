/*
 * 移植自 Setsuna 的 ui/UiTheme.java（上游 commit e4915ae093748d48d92ee47c38cdb8b2746a4730，
 * 作者 ShiYi，许可 GPL-3.0-or-later，见 THIRD-PARTY-NOTICES.md）。
 * 本文件相对上游的改动：包名与类名（UiTheme → SkijaTheme）；accent() 不再直接读取上游模块的
 * 设置对象，改为可注入的静态值（由配置层调用 setAccent 写入）。
 */
package dev.noturne.ui.skija;

/**
 * Setsuna 视觉语言的共享设计令牌：全部界面与 HUD 表面的颜色与圆角都取自这里。
 *
 * <p>与 {@code dev.noturne.ui.theme.Theme} 同源：两套令牌都是"青绿强调 + 近黑分层表面"这一套视觉，
 * 互不覆盖。本类是 Skija 侧的 int 色值版本（0xAARRGGBB，与 Paint#setColor 一致）。
 *
 * <p>色值一律 0xAARRGGBB，与 Skija 的 {@code Paint#setColor} 一致，避免每帧再做格式转换。
 */
public final class SkijaTheme {

    // ---- 背景与表面（由暗到亮，形成层级） ----
    /** 全屏背板。 */
    public static final int BACKDROP = argb(170, 3, 6, 7);
    /** 主表面（面板底）。 */
    public static final int SURFACE = argb(246, 12, 16, 18);
    /** 次级表面（面板内的分区）。 */
    public static final int SURFACE_ALT = argb(238, 17, 22, 24);
    /** 抬升表面（浮起的分组）。 */
    public static final int SURFACE_RAISED = argb(246, 25, 32, 34);
    /** 悬停表面。 */
    public static final int SURFACE_HOVER = argb(250, 31, 40, 42);
    /** 控件底色。 */
    public static final int CONTROL = argb(244, 21, 27, 29);
    /** 控件悬停底色。 */
    public static final int CONTROL_HOVER = argb(250, 34, 43, 45);
    /** 标题栏底色。 */
    public static final int HEADER = argb(248, 14, 19, 21);

    // ---- 描边 ----
    /** 常规描边。 */
    public static final int BORDER = rgb(53, 65, 67);
    /** 强调描边（悬停 / 选中）。 */
    public static final int BORDER_STRONG = rgb(73, 88, 90);
    /** 弱描边（分隔线）。 */
    public static final int BORDER_SOFT = argb(138, 56, 68, 70);
    /** 投影。 */
    public static final int SHADOW = argb(105, 0, 0, 0);

    // ---- 文字 ----
    /** 主文字。 */
    public static final int TEXT = rgb(241, 246, 244);
    /** 次级文字（说明、单位）。 */
    public static final int TEXT_MUTED = rgb(166, 178, 174);
    /** 弱文字（占位、禁用）。 */
    public static final int TEXT_FAINT = rgb(103, 117, 113);

    // ---- 语义色 ----
    /** 默认强调色（青绿）；实际值由 {@link #accent()} 给出，可被配置覆盖。 */
    public static final int ACCENT = rgb(62, 214, 180);
    /** 强调色的暗部，用于渐变与按下态。 */
    public static final int ACCENT_DARK = rgb(22, 112, 92);
    /** 强调色的半透明铺底。 */
    public static final int ACCENT_SOFT = argb(46, 62, 214, 180);
    /** 成功。 */
    public static final int SUCCESS = rgb(84, 211, 143);
    /** 警告。 */
    public static final int WARNING = rgb(244, 183, 86);
    /** 危险。 */
    public static final int DANGER = rgb(238, 100, 96);
    /** 信息。 */
    public static final int INFO = rgb(91, 174, 255);

    // ---- 几何 ----
    /** 面板圆角。 */
    public static final float RADIUS = 6.0F;
    /** 控件圆角。 */
    public static final float RADIUS_SMALL = 4.0F;

    /** 当前强调色；默认取 {@link #ACCENT}，由配置层通过 {@link #setAccent(int)} 覆盖。 */
    private static volatile int accent = ACCENT;

    private SkijaTheme() {
    }

    /** 当前强调色（0xAARRGGBB，alpha 恒为 255）。 */
    public static int accent() {
        return accent;
    }

    /**
     * 覆盖强调色。
     *
     * @param color 0xRRGGBB 或 0xAARRGGBB；alpha 一律按 255 处理（强调色不透明）
     */
    public static void setAccent(int color) {
        accent = withAlpha(color, 255);
    }

    /** 用给定 alpha 覆盖颜色的透明通道，保留 RGB。 */
    public static int withAlpha(int color, int alpha) {
        return (clamp(alpha) << 24) | (color & 0x00FFFFFF);
    }

    /** 不透明色。 */
    public static int rgb(int red, int green, int blue) {
        return argb(255, red, green, blue);
    }

    /** 组装 0xAARRGGBB，各通道自动夹取到 0–255。 */
    public static int argb(int alpha, int red, int green, int blue) {
        return (clamp(alpha) << 24) | (clamp(red) << 16) | (clamp(green) << 8) | clamp(blue);
    }

    private static int clamp(int value) {
        return Math.max(0, Math.min(255, value));
    }
}
