package dev.nocturne.ui.fatality;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.module.Module;

import java.util.Locale;

/**
 * 客户端信息浮层：品牌与版本、模块数量、当前绘制后端与缩放、内存占用。
 *
 * <p>诊断信息收纳在这里，主标题栏不再重复展示后端与缩放数字。
 */
final class FaInfoPopup extends FaPopup {

    /** 内边距。 */
    private static final float PAD = 14.0f;
    /** 行高。 */
    private static final float LINE_H = 22.0f;

    /** 所属界面；用于取后端名、缩放与字体模式。 */
    private final FaClickGui gui;

    FaInfoPopup(Object owner, FaClickGui gui) {
        super(owner);
        this.gui = gui;
        w = 260.0f;
        h = PAD * 2.0f + LINE_H * 6.0f;
    }

    @Override
    protected void draw(FaContext ctx, float alpha) {
        float lx = x + PAD;
        float ly = y + PAD;
        FaFont label = FaTheme.label();
        FaFont small = FaTheme.small();

        ctx.draw.textMid(FaTheme.BRAND, lx, ly + LINE_H * 0.5f, label, FaTheme.alpha(FaTheme.TITLE, alpha));
        ctx.draw.textRightMid(version(), x + w - PAD, ly + LINE_H * 0.5f, small,
                FaTheme.alpha(FaTheme.ACCENT, alpha));

        int total = 0;
        int enabled = 0;
        NocturneClient client = NocturneClient.get();
        if (client != null) {
            for (Module module : client.modules().all()) {
                total++;
                if (module.isEnabled()) {
                    enabled++;
                }
            }
        }
        row(ctx, lx, ly + LINE_H * 1.5f, "modules", total + " (" + enabled + " on)", label, small, alpha);
        row(ctx, lx, ly + LINE_H * 2.5f, "renderer", gui.backendName(), label, small, alpha);
        row(ctx, lx, ly + LINE_H * 3.5f, "ui scale", String.format(Locale.ROOT, "%.2f", gui.scale())
                + " · " + (gui.atlasFonts() ? "glyph atlas" : "game font"), label, small, alpha);
        row(ctx, lx, ly + LINE_H * 4.5f, "memory", memory(), label, small, alpha);
        row(ctx, lx, ly + LINE_H * 5.5f, "tabs", gui.categorySummary(), label, small, alpha);
    }

    /** 画一行「标签 + 值」。 */
    private void row(FaContext ctx, float lx, float centerY, String name, String value, FaFont label,
                     FaFont small, float alpha) {
        ctx.draw.textMid(name, lx, centerY, small, FaTheme.alpha(FaTheme.MUTED, alpha));
        ctx.draw.textRightMid(value, x + w - PAD, centerY, small, FaTheme.alpha(FaTheme.LABEL, alpha));
    }

    /** @return 内存占用（已用 / 上限，MB） */
    private static String memory() {
        Runtime runtime = Runtime.getRuntime();
        long used = (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L);
        long max = runtime.maxMemory() / (1024L * 1024L);
        return used + " / " + max + " MB";
    }

    /** @return 客户端版本；取不到包实现版本时返回 {@code dev} */
    private static String version() {
        String version = NocturneClient.class.getPackage() == null ? null
                : NocturneClient.class.getPackage().getImplementationVersion();
        return version == null || version.isEmpty() ? "dev" : version;
    }
}
