package dev.nocturne.ui.text;

import dev.nocturne.ui.render.Renderer;

import java.awt.AlphaComposite;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;

/**
 * 一个 {@code (fontId, pxSize)} 组合的字形图集：Java2D 运行期栅格单个字形，塞进一张方形位图，
 * 再由 {@link Renderer#uploadTexture} 上传成纹理。
 *
 * <p>字形像素做「白化」处理（RGB 恒为白、A 为覆盖度），绘制时用颜色调制即可，与三个后端的
 * 混合口径一致（非预乘 ARGB）。
 *
 * <p>图集写满时 {@link #glyph(int)} 返回 {@code null}：调用方（{@link FontRasterizer}）据此淘汰整张
 * 图集并新建一张——被淘汰的字形会在后续帧里按需重新栅格，代价是一次性重画，不会永久丢失。
 *
 * <p>线程：只在渲染线程使用（叠加层单线程驱动），故不做同步。
 */
final class GlyphAtlas {

    /** 图集边长（像素）。512 在 64px 字号下只放得下约 50 个字形，中文界面不够用，故取 1024。 */
    private static final int SIZE = 1024;
    /** 字形之间的留白，避免最近邻采样在边缘串色。 */
    private static final int PADDING = 1;
    /** 单个字形位图的边长上限；超过（异常字体/超大修饰字形）时只保留 advance，不栅格。 */
    private static final int MAX_GLYPH_SIDE = 256;

    /** 一个已栅格字形的元数据。 */
    static final class Glyph {
        /** 位图宽（像素）。 */
        final int width;
        /** 位图高（像素）。 */
        final int height;
        /** 图集 UV：左上。 */
        final float u0;
        /** 图集 UV：上。 */
        final float v0;
        /** 图集 UV：右下。 */
        final float u1;
        /** 图集 UV：下。 */
        final float v1;
        /** 步进宽度（像素）。 */
        final float advance;
        /** 相对笔位的横向偏移（像素）。 */
        final int bearingX;
        /** 相对文本盒上沿的纵向偏移（像素）。 */
        final int bearingY;

        Glyph(int width, int height, float u0, float v0, float u1, float v1, float advance,
              int bearingX, int bearingY) {
            this.width = width;
            this.height = height;
            this.u0 = u0;
            this.v0 = v0;
            this.u1 = u1;
            this.v1 = v1;
            this.advance = advance;
            this.bearingX = bearingX;
            this.bearingY = bearingY;
        }
    }

    /** 本图集的字体标识。 */
    final int fontId;
    /** 本图集的字号（像素）。 */
    final int pxSize;

    /** 派生到目标字号的字体。 */
    private final Font font;
    /** 栅格上下文：开抗锯齿、关分数度量（分数度量会让字形边界落在半像素上，反而更糊）。 */
    private final FontRenderContext context = new FontRenderContext(null, true, false);
    /** 图集像素。 */
    private final int[] pixels = new int[SIZE * SIZE];
    /** 已栅格的字形，键为码点。 */
    private final Map<Integer, Glyph> glyphs = new HashMap<Integer, Glyph>();
    /** 字体度量（取行高与 ascent）。 */
    private final FontMetrics metrics;

    /** 装箱游标。 */
    private int cursorX;
    /** 装箱游标。 */
    private int cursorY;
    /** 当前行已用高度。 */
    private int rowHeight;
    /** 是否已写满；写满后不再接收新字形。 */
    private boolean full;
    /** 是否有未上传的改动。 */
    private boolean dirty;
    /** 脏矩形（左/上/右/下，半开区间）；{@code dirty} 为真时有效。 */
    private int dirtyX0;
    private int dirtyY0;
    private int dirtyX1;
    private int dirtyY1;
    /** 纹理句柄；0 表示尚未上传。 */
    private int texture;

    GlyphAtlas(Font base, int fontId, int pxSize) {
        this.fontId = fontId;
        this.pxSize = pxSize;
        this.font = base.deriveFont((float) pxSize);
        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = probe.createGraphics();
        try {
            this.metrics = graphics.getFontMetrics(this.font);
        } finally {
            graphics.dispose();
        }
    }

    /** @return 行高（像素），用于 {@code FaFont.height()} */
    int lineHeight() {
        return Math.max(1, metrics.getHeight());
    }

    /** @return 是否已经写满（调用方据此淘汰本图集） */
    boolean full() {
        return full;
    }

    /** @return 已栅格的字形数（诊断用） */
    int size() {
        return glyphs.size();
    }

    /**
     * 取一个字形，必要时栅格并装箱。
     *
     * @return 字形；图集已满或字形不可栅格时返回 {@code null}
     */
    Glyph glyph(int codePoint) {
        Glyph existing = glyphs.get(codePoint);
        if (existing != null) {
            return existing;
        }
        if (full) {
            return null;
        }
        int[] source = new int[]{0, 0, 0, 0};
        float advance = rasterize(codePoint, source);
        if (source[2] > 0 && source[3] > 0) {
            int[] cell = allocate(source[2], source[3]);
            if (cell == null) {
                full = true;
                pending = null;
                return null;
            }
            Glyph glyph = build(source, advance, cell);
            glyphs.put(codePoint, glyph);
            dirty = true;
            return glyph;
        }
        // 无位图的字形（空格、组合标记）：只记步进。
        Glyph blank = new Glyph(0, 0, 0f, 0f, 0f, 0f, advance, 0, 0);
        glyphs.put(codePoint, blank);
        return blank;
    }

    /**
     * 栅格单个码点。
     *
     * @param out 输出参数：{@code [x, y, width, height]} 为字形位图相对笔位/文本盒的包围盒
     * @return 步进宽度（像素）
     */
    private float rasterize(int codePoint, int[] out) {
        String text = new String(Character.toChars(codePoint));
        GlyphVector vector = font.createGlyphVector(context, text);
        float advance = (float) vector.getGlyphPosition(vector.getNumGlyphs()).getX();
        Rectangle bounds = vector.getPixelBounds(context, 0.0f, 0.0f);
        if (bounds.width <= 0 || bounds.height <= 0
                || bounds.width > MAX_GLYPH_SIDE || bounds.height > MAX_GLYPH_SIDE) {
            return advance;
        }
        BufferedImage image = new BufferedImage(bounds.width, bounds.height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setComposite(AlphaComposite.Src);
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
                    RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,
                    RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
            graphics.drawGlyphVector(vector, -bounds.x, -bounds.y);
        } finally {
            graphics.dispose();
        }
        int width = bounds.width;
        int height = bounds.height;
        int[] argb = image.getRGB(0, 0, width, height, null, 0, width);
        for (int i = 0; i < argb.length; i++) {
            // 白化：只保留覆盖度，颜色由绘制时的调制色决定。
            argb[i] = (argb[i] & 0xFF000000) | 0x00FFFFFF;
        }
        stash(argb, width, height);
        out[0] = bounds.x;
        out[1] = metrics.getAscent() + bounds.y;
        out[2] = width;
        out[3] = height;
        return advance;
    }

    /** 本轮栅格的像素暂存；{@link #allocate} 之后由 {@link #build} 取用，避免一次装箱分配数组。 */
    private int[] pending;
    private int pendingWidth;
    private int pendingHeight;

    /** 记下待装箱的像素。 */
    private void stash(int[] argb, int width, int height) {
        pending = argb;
        pendingWidth = width;
        pendingHeight = height;
    }

    /** 在下一行/下一列找一块能放下 {@code width×height} 的位置。 */
    private int[] allocate(int width, int height) {
        int cellWidth = width + PADDING;
        int cellHeight = height + PADDING;
        if (cursorX + cellWidth > SIZE) {
            cursorX = 0;
            cursorY += rowHeight;
            rowHeight = 0;
        }
        if (cursorY + cellHeight > SIZE) {
            return null;
        }
        int[] cell = {cursorX, cursorY};
        cursorX += cellWidth;
        rowHeight = Math.max(rowHeight, cellHeight);
        return cell;
    }

    /** 把暂存像素写进图集并构造字形元数据。 */
    private Glyph build(int[] bounds, float advance, int[] cell) {
        int width = bounds[2];
        int height = bounds[3];
        int originX = cell[0];
        int originY = cell[1];
        for (int y = 0; y < height; y++) {
            System.arraycopy(pending, y * width, pixels, (originY + y) * SIZE + originX, width);
        }
        pending = null;
        pendingWidth = 0;
        pendingHeight = 0;
        markDirty(originX, originY, width, height);
        float u0 = originX / (float) SIZE;
        float v0 = originY / (float) SIZE;
        float u1 = (originX + width) / (float) SIZE;
        float v1 = (originY + height) / (float) SIZE;
        return new Glyph(width, height, u0, v0, u1, v1, advance, bounds[0], bounds[1]);
    }

    /**
     * 取本图集的纹理句柄，必要时上传。
     *
     * @return 纹理句柄；上传失败时为 0
     */
    int texture(Renderer renderer) {
        if (texture == 0) {
            texture = renderer.uploadTexture(0, SIZE, SIZE, pixels);
            dirty = false;
            return texture;
        }
        if (dirty) {
            // 只传变化的那一块：整张 1024² 是 4 MB，逐字形重传会让首次打开界面明显卡顿。
            int updated = renderer.uploadTextureRegion(texture, SIZE, SIZE, pixels,
                    dirtyX0, dirtyY0, dirtyX1 - dirtyX0, dirtyY1 - dirtyY0);
            if (updated <= 0) {
                // 后端不支持局部上传（或失败）：退回整张，保证画面正确。
                updated = renderer.uploadTexture(texture, SIZE, SIZE, pixels);
            }
            if (updated > 0) {
                texture = updated;
                dirty = false;
            }
        }
        return texture;
    }

    /** 把一块区域并入脏矩形。 */
    private void markDirty(int x, int y, int w, int h) {
        if (!dirty) {
            dirty = true;
            dirtyX0 = x;
            dirtyY0 = y;
            dirtyX1 = x + w;
            dirtyY1 = y + h;
            return;
        }
        dirtyX0 = Math.min(dirtyX0, x);
        dirtyY0 = Math.min(dirtyY0, y);
        dirtyX1 = Math.max(dirtyX1, x + w);
        dirtyY1 = Math.max(dirtyY1, y + h);
    }

    /** 释放纹理。 */
    void dispose(Renderer renderer) {
        if (texture > 0) {
            renderer.deleteTexture(texture);
            texture = 0;
        }
    }
}
