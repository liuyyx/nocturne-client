/*
 * 移植自 Setsuna（上游 commit e4915ae093748d48d92ee47c38cdb8b2746a4730，作者 ShiYi，
 * 许可 GPL-3.0-or-later，见 THIRD-PARTY-NOTICES.md）。
 * 本文件相对上游的改动：包名；可见性由包内提升为 public（供屏幕壳与 HUD 复用）。
 */
package dev.noturne.ui.skija;

/** 屏幕切换时的淡出黑场计时（Skija 屏与主题化的原版屏共用同一条曲线）。 */
public final class PageTransition {

    /** 过渡总时长：220ms——短到不挡操作，长到能遮住首帧的绘制抖动。 */
    private static final long DURATION_NANOS = 220_000_000L;

    private PageTransition() {
    }

    /**
     * 过渡遮罩的不透明度：从 255 起按三次曲线衰减到 0。
     *
     * @param startedAt 过渡开始时刻（{@link System#nanoTime()}）
     * @return 0–255 的 alpha；已超时返回 0
     */
    public static int overlayAlpha(long startedAt) {
        float progress = Math.max(0.0F, Math.min(1.0F,
                (System.nanoTime() - startedAt) / (float) DURATION_NANOS));
        float remaining = 1.0F - progress;
        return Math.round(255.0F * remaining * remaining * remaining);
    }
}
