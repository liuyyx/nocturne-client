package dev.noturne.ui.component;

import dev.noturne.ui.render.Color;
import dev.noturne.ui.render.Renderer;
import dev.noturne.ui.theme.Theme;

import java.util.function.IntConsumer;

/**
 * 取色控件：上半条为色相带，下半条为当前色相与饱和度下的明度带，
 * 点击或拖动对应区域即更新颜色，并通过构造时传入的 {@code onChange} 回写新的 ARGB。
 *
 * <p>饱和度与 alpha 不参与交互，只随 {@link #setArgb(int)} 整体变化；
 * 渐变一律用固定段数的竖条近似，不逐像素绘制，也不在每帧分配大数组
 * （色相带内容恒定，静态构建一次；明度带仅在色相/饱和度变化时重建缓存）。
 *
 * <p>颜色模型换算使用 {@code java.awt.Color} 的 HSB 工具方法（Java 8 API）；
 * 因与本包的 {@link Color} 同名，使用处一律全限定名。
 */
public class ColorPicker extends Component {

    /** 渐变条的近似段数；段数固定，渲染开销与控件宽度无关。 */
    private static final int SEGMENTS = 48;
    /** 两条渐变带之间的间隙（像素）。 */
    private static final float BAR_GAP = 3f;
    /** 指示标记的宽度（像素）。 */
    private static final float MARKER_WIDTH = 2f;

    /** 未在拖动。 */
    private static final int DRAG_NONE = 0;
    /** 正在拖动色相带。 */
    private static final int DRAG_HUE = 1;
    /** 正在拖动明度带。 */
    private static final int DRAG_BRIGHTNESS = 2;

    /** 色相带分段颜色；内容恒定，类加载时构建一次，供所有实例共享。 */
    private static final Color[] HUE_STRIPS = buildHueStrips();

    /** 数值变化回调；可为 null（表示仅更新内部状态，不外发）。 */
    private final IntConsumer onChange;
    /** 当前打包颜色（{@code 0xAARRGGBB}），是唯一权威状态，HSV 分量均由它派生。 */
    private int argb;
    /** 当前色相 0–1。 */
    private float hue;
    /** 当前饱和度 0–1；交互中保持不变。 */
    private float saturation;
    /** 当前明度 0–1。 */
    private float brightness;
    /** 当前拖动目标，取值见 DRAG_* 常量。 */
    private int dragging = DRAG_NONE;
    /** 明度带分段颜色缓存；仅在色相/饱和度变化时重建，避免每帧分配。 */
    private final Color[] brightnessStrips = new Color[SEGMENTS];
    /** {@link #brightnessStrips} 对应的色相/饱和度量化键；与当前值不一致说明缓存已过期。 */
    private int brightnessKey = -1;

    /**
     * @param initialArgb 初始颜色，打包为 {@code 0xAARRGGBB}
     * @param onChange    颜色变化回调，允许为 null
     */
    public ColorPicker(int initialArgb, IntConsumer onChange) {
        this.onChange = onChange;
        applyArgb(initialArgb);
    }

    /** @return 当前打包颜色（{@code 0xAARRGGBB}） */
    public int argb() {
        return argb;
    }

    /**
     * 设置新颜色并（值确有变化时）触发回调；与当前值相同则直接返回，
     * 与 {@link Slider#setValue(float)} 的「无变化不回调」约定一致。
     */
    public void setArgb(int newArgb) {
        if (newArgb == argb) {
            return;
        }
        applyArgb(newArgb);
        if (onChange != null) {
            onChange.accept(argb);
        }
    }

    @Override
    public void render(Renderer renderer) {
        if (!visible) {
            return;
        }
        float barHeight = (height - BAR_GAP) / 2f;
        float hueY = y;
        float brightnessY = y + barHeight + BAR_GAP;

        renderGradient(renderer, hueY, barHeight, true);
        renderGradient(renderer, brightnessY, barHeight, false);

        // 指示标记略微超出条带上下缘，便于在相近颜色上也能看清当前位置
        drawMarker(renderer, x + width * hue, hueY, barHeight);
        drawMarker(renderer, x + width * brightness, brightnessY, barHeight);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        // 仅响应左键且必须点在控件内；上半条归色相、下半条归明度
        if (button != 0 || !contains(mx, my)) {
            return false;
        }
        dragging = my < y + height / 2f ? DRAG_HUE : DRAG_BRIGHTNESS;
        applyFromMouse(mx);
        return true;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        // 拖动目标在按下时锁定：即使指针纵向越到另一条带上，也继续调节按下时的分量
        if (dragging == DRAG_NONE) {
            return false;
        }
        applyFromMouse(mx);
        return true;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        // 未在拖动则交回事件；一旦开始拖动就独占该次释放
        if (dragging == DRAG_NONE) {
            return false;
        }
        dragging = DRAG_NONE;
        return true;
    }

    /** 写入权威颜色并派生出 HSV 分量（alpha 原样保留在 {@link #argb} 中）。 */
    private void applyArgb(int newArgb) {
        argb = newArgb;
        float[] hsv = java.awt.Color.RGBtoHSB((newArgb >>> 16) & 0xFF, (newArgb >>> 8) & 0xFF,
                newArgb & 0xFF, null);
        hue = hsv[0];
        saturation = hsv[1];
        brightness = hsv[2];
    }

    /** 由指针横坐标更新当前拖动目标对应的分量；允许越界，由 clamp01 收敛。 */
    private void applyFromMouse(double mx) {
        float fraction = clamp01((float) ((mx - x) / width));
        if (dragging == DRAG_HUE) {
            // 色相 1.0 与 0.0 同为红色，直接线性映射即可
            hue = fraction;
        } else {
            brightness = fraction;
        }
        // 由 HSV 与既有 alpha 合成新颜色；setArgb 内部保证值未变时不回调
        int rgb = java.awt.Color.HSBtoRGB(hue, saturation, brightness) & 0xFFFFFF;
        setArgb((argb & 0xFF000000) | rgb);
    }

    /**
     * 用固定 {@link #SEGMENTS} 段竖条近似一条渐变带。
     *
     * @param hueBar {@code true} 画全饱和全亮的色相带；{@code false} 画当前色相/饱和度下的明度带
     */
    private void renderGradient(Renderer renderer, float barY, float barHeight, boolean hueBar) {
        float segmentWidth = width / SEGMENTS;
        for (int i = 0; i < SEGMENTS; i++) {
            Color strip = hueBar ? HUE_STRIPS[i] : brightnessStrip(i);
            float sx = x + segmentWidth * i;
            // 相邻段重叠 1px 消除接缝；最后一段补齐到右边缘，消除浮点累计误差
            float sw = i == SEGMENTS - 1 ? x + width - sx : segmentWidth + 1f;
            renderer.rect(sx, barY, sw, barHeight, strip);
        }
    }

    /** 返回明度带第 {@code i} 段颜色；色相或饱和度变化时整体重建缓存。 */
    private Color brightnessStrip(int i) {
        int key = (Math.round(hue * 360f) << 9) | Math.round(saturation * 255f);
        if (key != brightnessKey) {
            brightnessKey = key;
            for (int j = 0; j < SEGMENTS; j++) {
                float b = j / (float) (SEGMENTS - 1);
                brightnessStrips[j] = Color.of(0xFF000000
                        | (java.awt.Color.HSBtoRGB(hue, saturation, b) & 0xFFFFFF));
            }
        }
        return brightnessStrips[i];
    }

    /** 在条带上 {@code centerX} 处画一个居中的指示标记。 */
    private void drawMarker(Renderer renderer, float centerX, float barY, float barHeight) {
        renderer.rect(centerX - MARKER_WIDTH / 2f, barY - 1f, MARKER_WIDTH, barHeight + 2f,
                Theme.TEXT_PRIMARY);
    }

    /** 构建恒定的色相带分段颜色（全饱和、全亮、不透明）。 */
    private static Color[] buildHueStrips() {
        Color[] strips = new Color[SEGMENTS];
        for (int i = 0; i < SEGMENTS; i++) {
            strips[i] = Color.of(java.awt.Color.HSBtoRGB(i / (float) SEGMENTS, 1f, 1f));
        }
        return strips;
    }

    /** 将 {@code v} 夹紧到 {@code [0, 1]}。 */
    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }
}
