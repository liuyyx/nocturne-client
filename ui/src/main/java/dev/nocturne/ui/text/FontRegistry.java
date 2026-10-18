package dev.nocturne.ui.text;

import java.awt.Font;
import java.io.InputStream;

/**
 * Fatality 界面的字体注册表：把打包的 TTF 资源加载成 {@link Font}。
 *
 * <p>只依赖 {@code java.desktop}（{@link Font#createFont(int, InputStream)}），没有第三方依赖：
 * 1.8.9 的官方 JRE 与 26.x 的 {@code java-runtime-epsilon} 都带 {@code java.desktop}，
 * 若某个启动器把它 jlink 掉了，这里会一次性报错并退化为「游戏字体 + ASCII 图标」路径
 * （见 {@link FontRasterizer}）。
 *
 * <p>字体文件按 id 懒加载并缓存；加载失败（资源缺失、模块裁剪）只记录一次并返回 {@code null}。
 */
public final class FontRegistry {

    /** 标题与 Logo 用的无衬线粗体。 */
    public static final int MANROPE_BOLD = 0;
    /** 界面正文（含中文）。 */
    public static final int MISANS = 1;
    /** 图标集。 */
    public static final int LUCIDE = 2;
    /** 备用实心图标集。 */
    public static final int FA_SOLID = 3;

    /** 资源目录；字体随 ui 模块打包。 */
    private static final String DIR = "/assets/nocturne/fonts/fatality/";

    /** 各 id 对应的资源文件名，下标即 id。 */
    private static final String[] FILES = {
            "manrope-bold.ttf",
            "misans.ttf",
            "lucide.ttf",
            "fa-solid.ttf",
    };

    /** 已加载的字体；未加载或加载失败为 {@code null}。 */
    private static final Font[] FONTS = new Font[FILES.length];
    /** 是否已尝试加载（失败也不重试）。 */
    private static final boolean[] LOADED = new boolean[FILES.length];
    /** 是否已就加载失败报过一次（避免每帧刷屏）。 */
    private static boolean failureLogged;

    private FontRegistry() {
    }

    /**
     * 取一份字体。
     *
     * @param id 字体 id（本类的常量之一）
     * @return 字体；资源不可用或 {@code id} 越界时返回 {@code null}
     */
    public static Font font(int id) {
        if (id < 0 || id >= FILES.length) {
            return null;
        }
        if (LOADED[id]) {
            return FONTS[id];
        }
        LOADED[id] = true;
        String path = DIR + FILES[id];
        try (InputStream stream = FontRegistry.class.getResourceAsStream(path)) {
            if (stream == null) {
                logFailure(path, "resource not found");
                return null;
            }
            FONTS[id] = Font.createFont(Font.TRUETYPE_FONT, stream);
        } catch (Throwable error) {
            // java.desktop 被裁剪（jlink）或字体文件损坏都会走这里。
            logFailure(path, String.valueOf(error));
            return null;
        }
        return FONTS[id];
    }

    /** @return 全部四个字体是否都可用（决定字形图集路径能否启用） */
    public static boolean available() {
        for (int i = 0; i < FILES.length; i++) {
            if (font(i) == null) {
                return false;
            }
        }
        return true;
    }

    /** 只打印一次加载失败，说明是资源缺失还是运行时缺 {@code java.desktop}。 */
    private static void logFailure(String path, String reason) {
        if (failureLogged) {
            return;
        }
        failureLogged = true;
        System.out.println("[nocturne] font unavailable: " + path + " (" + reason + ")"
                + "; text falls back to the game font and icons to ASCII glyphs");
    }
}
