package dev.nocturne.ui.fatality;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Properties;

/**
 * 界面窗口位置与大小的保存/恢复。
 *
 * <p>位置按「相对绘制区的比例」保存（换分辨率后仍落在同一相对位置），大小按设计单位保存。
 * 内存里始终有一份（本次会话内关闭再打开立即生效），同时尽力写盘
 * （{@code <user.dir>/nocturne/clickgui.properties}）：写失败只记一次日志，绝不影响界面。
 */
final class LayoutStore {

    /** 保存文件相对工作目录的路径。 */
    private static final String FILE = "nocturne/clickgui.properties";

    /** 相对绘制区的横向比例；{@code NaN} 表示尚未保存过。 */
    private static float ratioX = Float.NaN;
    /** 相对绘制区的纵向比例。 */
    private static float ratioY = Float.NaN;
    /** 窗口宽（设计单位）。 */
    private static float width = Float.NaN;
    /** 窗口高（设计单位）。 */
    private static float height = Float.NaN;
    /** 上次选中的分类名。 */
    private static String category = "";
    /** 是否已从磁盘读过一次。 */
    private static boolean loaded;
    /** 读写失败是否已记过日志。 */
    private static boolean failureLogged;

    private LayoutStore() {
    }

    /** @return 是否有一份可用的布局（比例与尺寸都有效） */
    static boolean hasLayout() {
        load();
        return isFinite(ratioX) && isFinite(ratioY) && isFinite(width) && isFinite(height);
    }

    static float ratioX() {
        return ratioX;
    }

    static float ratioY() {
        return ratioY;
    }

    static float savedWidth() {
        return width;
    }

    static float savedHeight() {
        return height;
    }

    /** @return 上次选中的分类名；没有时返回空串 */
    static String savedCategory() {
        return category == null ? "" : category;
    }

    /** 记录窗口布局（内存），并写盘。 */
    static void save(float x, float y, float w, float h, String categoryName) {
        ratioX = x;
        ratioY = y;
        width = w;
        height = h;
        category = categoryName == null ? "" : categoryName;
        store();
    }

    /** 读取磁盘上的布局；只读一次。 */
    private static void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        Path path = path();
        if (!Files.isRegularFile(path)) {
            return;
        }
        Properties properties = new Properties();
        try (InputStream stream = Files.newInputStream(path)) {
            properties.load(stream);
        } catch (Throwable error) {
            logOnce("read", error);
            return;
        }
        ratioX = parseFloat(properties.getProperty("ratioX"), ratioX);
        ratioY = parseFloat(properties.getProperty("ratioY"), ratioY);
        width = parseFloat(properties.getProperty("width"), width);
        height = parseFloat(properties.getProperty("height"), height);
        category = properties.getProperty("category", category);
    }

    /** 写盘；失败只记一次日志。 */
    private static void store() {
        Path path = path();
        Properties properties = new Properties();
        properties.setProperty("ratioX", Float.toString(ratioX));
        properties.setProperty("ratioY", Float.toString(ratioY));
        properties.setProperty("width", Float.toString(width));
        properties.setProperty("height", Float.toString(height));
        properties.setProperty("category", category == null ? "" : category);
        try {
            Path parent = path.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (OutputStream stream = Files.newOutputStream(path)) {
                properties.store(stream, "nocturne ClickGUI layout");
            }
        } catch (Throwable error) {
            logOnce("write", error);
        }
    }

    /** @return 保存文件路径 */
    private static Path path() {
        return Paths.get(System.getProperty("user.dir", "."), FILE);
    }

    /** 解析浮点数；失败时返回兜底值。 */
    private static float parseFloat(String text, float fallback) {
        if (text == null) {
            return fallback;
        }
        try {
            return Float.parseFloat(text.trim());
        } catch (NumberFormatException ignored) {
            return fallback;
        }
    }

    /** @return 是否为有限值 */
    private static boolean isFinite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }

    /** 读写失败只报一次，避免每帧刷屏。 */
    private static void logOnce(String phase, Throwable error) {
        if (failureLogged) {
            return;
        }
        failureLogged = true;
        System.out.println("[nocturne] clickgui layout " + phase + " failed ("
                + (error instanceof IOException ? "io" : error.getClass().getSimpleName()) + "): " + error);
    }
}
