package dev.nocturne.ui.text;

import dev.nocturne.ui.render.Renderer;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 程序化生成的绘制纹理：柔和阴影与圆角描边的角弧。
 *
 * <p>为什么需要它们：{@link Renderer} 只有「填充矩形 / 圆角矩形 / 描边矩形 / 纹理四边形」四类原语，
 * 既没有模糊也没有描边环。阴影与圆角描边若用矩形堆叠近似，在深色底上会露出明显的色阶；
 * 这里改成在 CPU 上按解析解算好 alpha 场、上传成纹理，再用 {@link Renderer#texturedQuad} 一次画完。
 *
 * <p>三类纹理都只依赖形状参数（半径 / 线宽 / 模糊半径）与像素密度，与位置无关，因此可以跨帧缓存。
 * 上传与删除一律走 {@link Renderer#uploadTexture}/{@link Renderer#deleteTexture}，且只在渲染线程调用
 * （叠加层单线程驱动，故这里不做同步）。
 *
 * <p>纹理内容统一为「白色 + alpha 覆盖度」（非预乘），绘制时用颜色调制，与三个后端的混合口径一致。
 */
public final class RasterTextures {

    /** 阴影纹理长边上限（像素）：模糊本身低频，画得比这更细看不出差别，却要成倍显存。 */
    private static final int SHADOW_MAX_SIDE = 384;
    /** 角弧纹理半径上限（像素）：圆角半径通常 ≤ 8 设计单位，超过这个分辨率的细节看不出来。 */
    private static final int CORNER_MAX_SIDE = 96;
    /** 缓存条目上限；超出按最久未用淘汰并删除其纹理。 */
    private static final int CACHE_LIMIT = 32;

    /** 当前缓存所属的后端；换后端（或缩放变化清空）时必须整体失效。 */
    private static Renderer owner;
    /** 键为「类型 + 形状参数 + 密度」，值为纹理句柄；{@code accessOrder} 实现 LRU。 */
    private static final Map<String, Integer> CACHE =
            new LinkedHashMap<String, Integer>(16, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Integer> eldest) {
                    if (size() <= CACHE_LIMIT) {
                        return false;
                    }
                    if (owner != null) {
                        owner.deleteTexture(eldest.getValue());
                    }
                    return true;
                }
            };

    private RasterTextures() {
    }

    /** 绑定当前后端；后端变化时清空全部缓存（旧句柄属于旧上下文）。 */
    private static void bind(Renderer renderer) {
        if (owner == renderer) {
            return;
        }
        invalidate();
        owner = renderer;
    }

    /** 删除全部缓存纹理；后端切换、缩放变化或界面关闭时调用。 */
    public static void invalidate() {
        if (owner != null) {
            for (Integer id : CACHE.values()) {
                owner.deleteTexture(id);
            }
        }
        CACHE.clear();
    }

    /**
     * 缓存四角 RGBA 渐变。像素尺寸与目标逻辑尺寸一致，兼容只支持像素 UV 的游戏 blit。
     * 颜色为非预乘 ARGB，与其他纹理及游戏混合管线一致。
     */
    public static int gradient(Renderer renderer, int width, int height,
                               int topLeft, int topRight, int bottomRight, int bottomLeft) {
        if (!renderer.supportsTextures() || width <= 0 || height <= 0) {
            return 0;
        }
        bind(renderer);
        String key = "gradient|" + width + "x" + height + "|" + topLeft + "|" + topRight
                + "|" + bottomRight + "|" + bottomLeft;
        Integer cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        int[] pixels = new int[width * height];
        for (int y = 0; y < height; y++) {
            float v = (y + 0.5f) / height;
            for (int x = 0; x < width; x++) {
                float u = (x + 0.5f) / width;
                pixels[y * width + x] = interpolate(topLeft, topRight, bottomRight, bottomLeft, u, v);
            }
        }
        int id = renderer.uploadTexture(0, width, height, pixels);
        if (id > 0) {
            CACHE.put(key, id);
        }
        return id;
    }

    private static int interpolate(int tl, int tr, int br, int bl, float u, float v) {
        int result = 0;
        for (int shift = 0; shift <= 24; shift += 8) {
            float top = ((tl >>> shift) & 255) * (1f - u) + ((tr >>> shift) & 255) * u;
            float bottom = ((bl >>> shift) & 255) * (1f - u) + ((br >>> shift) & 255) * u;
            result |= Math.round(top * (1f - v) + bottom * v) << shift;
        }
        return result;
    }

    /**
     * 取（或生成）一块圆角矩形的柔和阴影纹理。
     *
     * @param renderer 绘制面（用于上传）
     * @param width    矩形宽（设计单位）
     * @param height   矩形高（设计单位）
     * @param radius   圆角半径（设计单位）
     * @param sigma    高斯衰减尺度（设计单位）
     * @param density  每设计单位多少像素（栅格密度）
     * @return 纹理句柄；后端不支持纹理时为 0
     */
    public static int shadow(Renderer renderer, float width, float height, float radius, float sigma,
                      float density) {
        if (!renderer.supportsTextures() || width <= 0f || height <= 0f || sigma <= 0f) {
            return 0;
        }
        bind(renderer);
        float margin = (float) Math.ceil(sigma * 3.0f);
        float designW = width + margin * 2.0f;
        float designH = height + margin * 2.0f;
        // 长边超过上限时按比例降密度：模糊是低频信号，降采样几乎看不出来。
        float k = Math.min(density, SHADOW_MAX_SIDE / Math.max(designW, designH));
        k = Math.max(k, 0.15f);
        int rw = Math.max(4, Math.round(designW * k));
        int rh = Math.max(4, Math.round(designH * k));
        String key = "shadow|" + Math.round(width) + "x" + Math.round(height) + "|r"
                + Math.round(radius * 10f) + "|s" + Math.round(sigma * 10f) + "|k"
                + Math.round(k * 100f);
        Integer cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        float radiusPx = radius * k;
        float sigmaPx = Math.max(0.6f, sigma * k);
        float inset = margin * k;
        int[] pixels = new int[rw * rh];
        for (int py = 0; py < rh; py++) {
            for (int px = 0; px < rw; px++) {
                float distance = roundedRectDistance(px + 0.5f, py + 0.5f, inset, inset,
                        rw - inset * 2.0f, rh - inset * 2.0f, radiusPx);
                float coverage;
                if (distance <= 0f) {
                    coverage = 1.0f;
                } else {
                    float t = distance / sigmaPx;
                    coverage = (float) Math.exp(-0.5f * t * t);
                }
                int alpha = Math.round(coverage * 255.0f);
                if (alpha <= 0) {
                    alpha = 0;
                } else if (alpha > 255) {
                    alpha = 255;
                }
                pixels[py * rw + px] = alpha << 24 | 0x00FFFFFF;
            }
        }
        int id = renderer.uploadTexture(0, rw, rh, pixels);
        if (id <= 0) {
            return 0;
        }
        CACHE.put(key, id);
        return id;
    }

    /**
     * 取（或生成）圆角描边的角弧纹理（四分之一环）。
     *
     * <p>纹理局部坐标 (0,0)-(1,1) 对应角方块本身（边长 = 半径），杯面向外。四条直边由普通矩形
     * 填充，只有四个角需要它，因此一张纹理可被所有同半径/线宽的角共用（用 UV 翻转复用）。
     *
     * @param radius  圆角半径（设计单位）
     * @param width   线宽（设计单位）
     * @param density 每设计单位多少像素
     * @return 纹理句柄；后端不支持纹理、参数退化时为 0
     */
    public static int cornerRing(Renderer renderer, float radius, float width, float density) {
        if (!renderer.supportsTextures() || radius <= 0.5f || width <= 0f) {
            return 0;
        }
        bind(renderer);
        float k = Math.min(density, CORNER_MAX_SIDE / Math.max(radius, 1f));
        k = Math.max(k, 0.5f);
        int side = Math.max(3, Math.round(radius * k));
        String key = "corner|" + Math.round(radius * 10f) + "|w" + Math.round(width * 10f) + "|k"
                + Math.round(k * 100f);
        Integer cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        float radiusPx = radius * k;
        float widthPx = Math.max(0.7f, width * k);
        int[] pixels = new int[side * side];
        for (int py = 0; py < side; py++) {
            for (int px = 0; px < side; px++) {
                // 圆心在角方块的外侧角落（局部 (radius, radius)），环带沿半径方向。
                float dx = px + 0.5f - radiusPx;
                float dy = py + 0.5f - radiusPx;
                float distance = (float) Math.sqrt(dx * dx + dy * dy);
                float outer = clamp((radiusPx - distance) / widthPx + 0.5f);
                float inner = clamp((distance - (radiusPx - widthPx)) / widthPx + 0.5f);
                int alpha = Math.round(outer * inner * 255.0f);
                pixels[py * side + px] = alpha << 24 | 0x00FFFFFF;
            }
        }
        int id = renderer.uploadTexture(0, side, side, pixels);
        if (id <= 0) {
            return 0;
        }
        CACHE.put(key, id);
        return id;
    }

    /** 圆角矩形的有符号距离场：内部为负、边界为 0、外部为正（像素单位）。 */
    private static float roundedRectDistance(float px, float py, float x, float y, float w, float h,
                                             float radius) {
        float halfW = w * 0.5f;
        float halfH = h * 0.5f;
        float cx = x + halfW;
        float cy = y + halfH;
        float r = Math.min(radius, Math.min(halfW, halfH));
        float dx = Math.abs(px - cx) - (halfW - r);
        float dy = Math.abs(py - cy) - (halfH - r);
        float outsideX = Math.max(dx, 0f);
        float outsideY = Math.max(dy, 0f);
        float outside = (float) Math.sqrt(outsideX * outsideX + outsideY * outsideY);
        float inside = Math.min(Math.max(dx, dy), 0f);
        return outside + inside - r;
    }

    /** 夹取到 0..1。 */
    private static float clamp(float value) {
        return value < 0f ? 0f : (value > 1f ? 1f : value);
    }
}
