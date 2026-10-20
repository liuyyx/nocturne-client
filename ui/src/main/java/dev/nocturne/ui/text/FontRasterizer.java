package dev.nocturne.ui.text;

import dev.nocturne.ui.fatality.FaFont;
import dev.nocturne.ui.render.Color;
import dev.nocturne.ui.render.Renderer;

import java.awt.Font;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Fatality 界面的字体实现：把 {@link FaFont} 的需求接到字形图集（{@link GlyphAtlas}）上，
 * 与 {@link Renderer} 的纹理能力对接；后端不支持纹理或字体资源不可用时退化为游戏字体。
 *
 * <p>单位口径：设计单位 → 逻辑像素（{@code designScale}）→ 物理像素（{@code backendScale}），
 * 栅格密度取两者之积。这样字形的物理像素尺寸与实际显示尺寸一一对应，任何后端都不会被拉伸模糊。
 * 度量对外一律换算回设计单位，布局与绘制同源。
 *
 * <p>{@link #bind} 在「后端或缩放变化」时整体失效（删除纹理、清空图集）：图集是上下文相关的资源，
 * 换了 GL 上下文旧句柄就失效了。
 */
public final class FontRasterizer implements FaFont.Provider {

    /** 同时保留的字形图集上限；每个 {@code (fontId, pxSize)} 一张，256 张 1024² 会吃光显存。 */
    private static final int ATLAS_LIMIT = 8;

    /** 当前生效的实例（键为 renderer + 缩放）；{@link #bind} 维护。 */
    private static FontRasterizer current;
    /** 图集缓存，键 {@code (fontId << 20) | pxSize}，{@code accessOrder} 实现 LRU。 */
    private static final Map<Long, GlyphAtlas> ATLASES =
            new LinkedHashMap<Long, GlyphAtlas>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<Long, GlyphAtlas> eldest) {
                    if (size() <= ATLAS_LIMIT) {
                        return false;
                    }
                    if (current != null && current.renderer != null) {
                        eldest.getValue().dispose(current.renderer);
                    }
                    return true;
                }
            };
    /** 无后端时的退化 Provider（界面尚未开始绘制时读字体）。 */
    private static final FontRasterizer DETACHED = new FontRasterizer(null, 1.0f, 1.0f);

    /** 本实例绑定的绘制面；{@code null} 表示尚未绑定后端。 */
    private final Renderer renderer;
    /** 设计单位 → 逻辑像素。 */
    private final float designScale;
    /** 逻辑像素 → 物理像素（{@code UiBackend.scale()}）。 */
    private final float backendScale;
    /** 设计单位 → 物理像素（栅格密度）。 */
    private final float density;
    /** 是否走字形图集（否则全部字体走游戏字体）。 */
    private final boolean atlas;
    /** 按 {@code (fontId, 设计字号)} 缓存的字体句柄。 */
    private final Map<Long, FaFont> fonts = new HashMap<Long, FaFont>();

    private FontRasterizer(Renderer renderer, float designScale, float backendScale) {
        this.renderer = renderer;
        this.designScale = designScale <= 0f ? 1.0f : designScale;
        this.backendScale = backendScale <= 0f ? 1.0f : backendScale;
        this.density = this.designScale * this.backendScale;
        this.atlas = renderer != null && renderer.supportsTextures() && FontRegistry.available();
        if (System.getProperty("nocturne.ui.debug") != null) {
            System.out.println("[nocturne] fonts: atlas=" + this.atlas
                    + " renderer=" + (renderer == null ? "null" : renderer.getClass().getSimpleName())
                    + " textures=" + (renderer != null && renderer.supportsTextures())
                    + " registry=" + FontRegistry.available());
        }
    }

    /** @return 未绑定后端时可用的 Provider（字体全部走退化实现，不会画字） */
    public static synchronized FaFont.Provider provider() {
        return current == null ? DETACHED : current;
    }

    /**
     * 绑定本帧的后端与缩放；参数与上次相同则复用。
     *
     * @param renderer     本帧绘制面
     * @param designScale  设计单位 → 逻辑像素
     * @param backendScale 逻辑像素 → 物理像素（不放大传 1）
     * @return 可用的字体 Provider
     */
    public static synchronized FaFont.Provider bind(Renderer renderer, float designScale,
                                                    float backendScale) {
        FontRasterizer existing = current;
        if (existing != null && existing.renderer == renderer
                && existing.designScale == designScale && existing.backendScale == backendScale) {
            return existing;
        }
        invalidate();
        current = new FontRasterizer(renderer, designScale, backendScale);
        return current;
    }

    /**
     * 删除全部图集纹理并清空缓存；后端切换、缩放变化或界面关闭时调用。
     *
     * <p>同时丢弃当前 Provider：{@link #bind} 按「后端 + 缩放」复用实例，而实例在构造时就定下了
     * 走图集还是退化——不复位的话，同一个 (后端, 缩放) 上永远拿到旧判断。
     */
    public static synchronized void invalidate() {
        if (current != null && current.renderer != null) {
            for (GlyphAtlas atlas : ATLASES.values()) {
                atlas.dispose(current.renderer);
            }
        }
        ATLASES.clear();
        RasterTextures.invalidate();
        if (current != null) {
            current.fonts.clear();
            current = null;
        }
    }

    /** @return 当前是否启用字形图集（决定图标是否要退化成 ASCII 别名） */
    public static synchronized boolean atlasActive() {
        return current != null && current.atlas;
    }

    // ── FaFont.Provider ─────────────────────────────────────────────────────

    @Override
    public FaFont logo() {
        return font(FontRegistry.MANROPE_BOLD, FaFont.LOGO);
    }

    @Override
    public FaFont tab() {
        return font(FontRegistry.MISANS, FaFont.TAB);
    }

    @Override
    public FaFont title() {
        return font(FontRegistry.MISANS, FaFont.TITLE);
    }

    @Override
    public FaFont label() {
        return font(FontRegistry.MISANS, FaFont.LABEL);
    }

    @Override
    public FaFont field() {
        return font(FontRegistry.MISANS, FaFont.FIELD);
    }

    @Override
    public FaFont small() {
        return font(FontRegistry.MISANS, FaFont.SMALL);
    }

    @Override
    public FaFont icon(float size) {
        return font(FontRegistry.LUCIDE, size);
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    /** 取一个字体句柄；图集不可用时返回游戏字体退化实现。 */
    private FaFont font(int fontId, float designSize) {
        if (!atlas) {
            return new GameFallbackFont(renderer, designSize, designScale);
        }
        long key = ((long) fontId << 32) | (Float.floatToIntBits(designSize) & 0xFFFFFFFFL);
        FaFont cached = fonts.get(key);
        if (cached != null) {
            return cached;
        }
        Font base = FontRegistry.font(fontId);
        if (base == null) {
            FaFont fallback = new GameFallbackFont(renderer, designSize, designScale);
            fonts.put(key, fallback);
            return fallback;
        }
        int pxSize = Math.max(1, Math.round(designSize * density));
        FaFont raster = new RasterFont(this, fontId, pxSize, designSize);
        fonts.put(key, raster);
        return raster;
    }

    /** 取（或新建）一个字形图集；已满时淘汰重建。 */
    private GlyphAtlas atlas(GlyphAtlas previous, int fontId, int pxSize) {
        long key = ((long) fontId << 20) | pxSize;
        GlyphAtlas existing = ATLASES.get(key);
        if (existing != null && existing != previous) {
            return existing;
        }
        if (existing != null) {
            // 同一张且已满：淘汰它并新建一张（旧字形会在后续帧里按需重栅格）。
            if (renderer != null) {
                existing.dispose(renderer);
            }
            ATLASES.remove(key);
        }
        Font base = FontRegistry.font(fontId);
        if (base == null) {
            return null;
        }
        GlyphAtlas created = new GlyphAtlas(base, fontId, pxSize);
        ATLASES.put(key, created);
        return created;
    }

    /** @return 每物理像素对应多少逻辑像素（绘制四边形时的换算因子） */
    private float devicePerPx() {
        return 1.0f / backendScale;
    }

    /** @return 每物理像素对应多少设计单位（度量换算因子） */
    private float designPerPx() {
        return 1.0f / density;
    }

    /**
     * 走字形图集的字体实现。
     *
     * <p>每次度量/绘制都从缓存里取当前图集（而不是持有引用）：图集写满后会被淘汰重建，
     * 持有旧引用会让字形永远拿不到。
     */
    private static final class RasterFont implements FaFont {
        private final FontRasterizer owner;
        private final int fontId;
        private final int pxSize;
        /** 最近一次 {@link #glyph(int)} 返回的字形所属图集（可能已被替换）。 */
        private GlyphAtlas glyphAtlas;
        private final float designSize;

        RasterFont(FontRasterizer owner, int fontId, int pxSize, float designSize) {
            this.owner = owner;
            this.fontId = fontId;
            this.pxSize = pxSize;
            this.designSize = designSize;
        }

        @Override
        public float width(String text) {
            if (text == null || text.isEmpty()) {
                return 0.0f;
            }
            float total = 0.0f;
            for (int i = 0; i < text.length(); ) {
                int codePoint = text.codePointAt(i);
                i += Character.charCount(codePoint);
                GlyphAtlas.Glyph glyph = glyph(codePoint);
                if (glyph != null) {
                    total += glyph.advance;
                }
            }
            return total * owner.designPerPx();
        }

        @Override
        public float height() {
            GlyphAtlas atlas = owner.atlas(null, fontId, pxSize);
            return atlas == null ? designSize : atlas.lineHeight() * owner.designPerPx();
        }

        @Override
        public int pxSize() {
            return pxSize;
        }

        @Override
        public int fontId() {
            return fontId;
        }

        @Override
        public void draw(Renderer renderer, String text, float deviceX, float deviceY, int argb) {
            if (text == null || text.isEmpty()) {
                return;
            }
            GlyphAtlas atlas = owner.atlas(null, fontId, pxSize);
            if (atlas == null) {
                return;
            }
            int texture = atlas.texture(renderer);
            if (texture <= 0) {
                return;
            }
            float perPx = owner.devicePerPx();
            float pen = 0.0f;
            for (int i = 0; i < text.length(); ) {
                int codePoint = text.codePointAt(i);
                i += Character.charCount(codePoint);
                GlyphAtlas.Glyph glyph = glyph(codePoint);
                if (glyph == null) {
                    continue;
                }
                if (glyph.width > 0 && glyph.height > 0) {
                    // 句柄必须取自**字形所在**的图集：图集写满时旧图集已被释放，
                    // 继续用旧对象的句柄会把新 UV 画到一张空纹理上。
                    GlyphAtlas source = glyphAtlas == null ? atlas : glyphAtlas;
                    texture = source.texture(renderer);
                    float gx = deviceX + (pen + glyph.bearingX) * perPx;
                    float gy = deviceY + glyph.bearingY * perPx;
                    renderer.texturedQuad(texture, gx, gy, glyph.width * perPx, glyph.height * perPx,
                            glyph.u0, glyph.v0, glyph.u1, glyph.v1, argb);
                }
                pen += glyph.advance;
            }
        }

        /** 取字形；图集写满时淘汰重建并重试一次。 */
        /**
         * 取一个字形，并把「它来自哪张图集」记录到 {@link #glyphAtlas}。
         *
         * <p>图集写满时 {@code owner.atlas(previous, …)} 会淘汰旧图集并新建一张，旧句柄随即释放。
         * 因此绘制时必须用这里记下的图集取纹理——否则会拿旧（已释放）句柄配新图集的 UV，
         * 采样到别的字形或空白。绘制是单线程的（见类注释），字段安全。
         */
        private GlyphAtlas.Glyph glyph(int codePoint) {
            GlyphAtlas atlas = owner.atlas(null, fontId, pxSize);
            if (atlas == null) {
                glyphAtlas = null;
                return null;
            }
            GlyphAtlas.Glyph glyph = atlas.glyph(codePoint);
            if (glyph != null) {
                glyphAtlas = atlas;
                return glyph;
            }
            if (!atlas.full()) {
                glyphAtlas = atlas;
                return null;
            }
            GlyphAtlas replacement = owner.atlas(atlas, fontId, pxSize);
            if (replacement == null) {
                glyphAtlas = null;
                return null;
            }
            glyphAtlas = replacement;
            return replacement.glyph(codePoint);
        }
    }

    /**
     * 后端不支持纹理（或字体资源不可用）时的退化字体：交给游戏的字体渲染器。
     *
     * <p>字号按同一换算（设计单位 × {@code designScale}）下发给 {@link Renderer}，
     * 度量再换算回设计单位，因此布局口径与图集路径一致，只是字形不同。
     */
    private static final class GameFallbackFont implements FaFont {
        private final Renderer renderer;
        private final float designSize;
        private final float designScale;

        GameFallbackFont(Renderer renderer, float designSize, float designScale) {
            this.renderer = renderer;
            this.designSize = designSize;
            this.designScale = designScale <= 0f ? 1.0f : designScale;
        }

        /** @return 下发给游戏字体的逻辑像素字号 */
        private float pixelSize() {
            return Math.max(1.0f, designSize * designScale);
        }

        @Override
        public float width(String text) {
            if (text == null || text.isEmpty()) {
                return 0.0f;
            }
            if (renderer == null) {
                // 尚未绑定后端（界面还没开始绘制）：按半宽估算，避免布局塌陷成 0。
                return text.length() * designSize * 0.5f;
            }
            return renderer.textWidth(text, pixelSize()) / designScale;
        }

        @Override
        public float height() {
            if (renderer == null) {
                return designSize;
            }
            return renderer.textHeight(pixelSize()) / designScale;
        }

        @Override
        public int pxSize() {
            return 0;
        }

        @Override
        public int fontId() {
            return -1;
        }

        @Override
        public void draw(Renderer target, String text, float deviceX, float deviceY, int argb) {
            if (target == null || text == null || text.isEmpty()) {
                return;
            }
            target.text(text, deviceX, deviceY, pixelSize(), Color.of(argb));
        }
    }
}
