package dev.noturne.ui.render;

/**
 * 不可变的 0xAARRGGBB 颜色值。
 *
 * <p>刻意保持为一个裸 {@code int}：它能零成本转换为 OpenGL 顶点数据，
 * 并且可以直接用 {@code ==} 比较。
 */
public final class Color {

    /** 全透明（alpha = 0），常用于“存在但不可见”的占位背景。 */
    public static final Color TRANSPARENT = new Color(0x00000000);

    /** 不透明白色。 */
    public static final Color WHITE = new Color(0xFFFFFFFF);

    /** 不透明黑色。 */
    public static final Color BLACK = new Color(0xFF000000);

    /**
     * 打包后的颜色分量，布局为 {@code 0xAARRGGBB}：
     * 最高 8 位为 alpha，其后依次为红、绿、蓝，最低 8 位为蓝。
     */
    public final int argb;

    /**
     * 仅由静态工厂创建——颜色是不可变值对象，不允许外部直接构造。
     *
     * @param argb 已打包的 0xAARRGGBB 值
     */
    private Color(int argb) {
        this.argb = argb;
    }

    /**
     * 由已打包的 0xAARRGGBB 值构造颜色。
     *
     * @param argb 已打包的 0xAARRGGBB 值
     * @return 新的颜色实例
     */
    public static Color of(int argb) {
        return new Color(argb);
    }

    /**
     * 构造不透明颜色。
     *
     * @param red   红色分量，取值 0–255，超范围会被截断
     * @param green 绿色分量，取值 0–255
     * @param blue  蓝色分量，取值 0–255
     * @return alpha 为 255 的颜色
     */
    public static Color rgb(int red, int green, int blue) {
        return argb(255, red, green, blue);
    }

    /**
     * 构造带透明度的颜色。
     *
     * @param alpha 透明度分量，0 完全透明、255 完全不透明，超范围会被截断
     * @param red   红色分量
     * @param green 绿色分量
     * @param blue  蓝色分量
     * @return 打包好的颜色
     */
    public static Color argb(int alpha, int red, int green, int blue) {
        return new Color(pack(alpha, red, green, blue));
    }

    /**
     * 解析 {@code RRGGBB} 或 {@code AARRGGBB} 格式的十六进制字符串（可带 {@code #} 前缀）；
     * 6 位形式按完全不透明处理。
     *
     * @throws IllegalArgumentException 长度既不是 6 也不是 8
     */
    public static Color hex(String hex) {
        String value = hex.startsWith("#") ? hex.substring(1) : hex;
        if (value.length() == 6) {
            return new Color(0xFF000000 | (int) Long.parseLong(value, 16));
        }
        if (value.length() == 8) {
            return new Color((int) Long.parseLong(value, 16));
        }
        throw new IllegalArgumentException("expected RRGGBB or AARRGGBB: " + hex);
    }

    /** @return alpha 分量，0–255 */
    public int a() {
        return (argb >>> 24) & 0xFF;
    }

    /** @return 红色分量，0–255 */
    public int r() {
        return (argb >>> 16) & 0xFF;
    }

    /** @return 绿色分量，0–255 */
    public int g() {
        return (argb >>> 8) & 0xFF;
    }

    /** @return 蓝色分量，0–255 */
    public int b() {
        return argb & 0xFF;
    }

    /** @return 打包后的原始值，布局 {@code 0xAARRGGBB}；供需要 int 的绘制后端使用 */
    public int packed() {
        return argb;
    }

    /** @return 归一化到 0–1 的 alpha，供 OpenGL 直接使用 */
    public float af() {
        return a() / 255f;
    }

    /** @return 归一化到 0–1 的红色分量 */
    public float rf() {
        return r() / 255f;
    }

    /** @return 归一化到 0–1 的绿色分量 */
    public float gf() {
        return g() / 255f;
    }

    /** @return 归一化到 0–1 的蓝色分量 */
    public float bf() {
        return b() / 255f;
    }

    /**
     * 返回替换了 alpha 的新颜色（颜色不可变，原值不受影响）。
     *
     * @param alpha 新的透明度分量
     * @return 带新 alpha 的颜色
     */
    public Color withAlpha(int alpha) {
        return argb(alpha, r(), g(), b());
    }

    /**
     * 线性插值：{@code t == 0} 返回本颜色，{@code t == 1} 返回 {@code other}。
     *
     * @param other 目标颜色
     * @param t     插值系数，内部夹取到 [0,1]
     * @return 插值得到的颜色
     */
    public Color mix(Color other, float t) {
        // NaN 按 0 处理（D26）：否则比较全 false 导致 clamped=NaN，
        // Math.round(NaN)=0 让整色穿透成透明黑。
        float clamped = Float.isNaN(t) ? 0f : (t < 0f ? 0f : (t > 1f ? 1f : t));
        return argb(
                Math.round(a() + (other.a() - a()) * clamped),
                Math.round(r() + (other.r() - r()) * clamped),
                Math.round(g() + (other.g() - g()) * clamped),
                Math.round(b() + (other.b() - b()) * clamped));
    }

    /** 把四个 0–255 分量按 AARRGGBB 布局打包；各分量越界时先夹取。 */
    private static int pack(int alpha, int red, int green, int blue) {
        return (clamp(alpha) << 24) | (clamp(red) << 16) | (clamp(green) << 8) | clamp(blue);
    }

    /** 把单个分量夹取到 [0,255]，防止移位时污染相邻位。 */
    private static int clamp(int channel) {
        return channel < 0 ? 0 : (channel > 255 ? 255 : channel);
    }

    /** 按打包后的整数值比较颜色是否相同。 */
    @Override
    public boolean equals(Object other) {
        return other instanceof Color && ((Color) other).argb == argb;
    }

    /** 哈希取打包值，与 {@link #equals(Object)} 保持一致。 */
    @Override
    public int hashCode() {
        return argb;
    }

    /** 调试输出：{@code #AARRGGBB} 形式的大写十六进制。 */
    @Override
    public String toString() {
        return String.format("#%08X", argb);
    }
}