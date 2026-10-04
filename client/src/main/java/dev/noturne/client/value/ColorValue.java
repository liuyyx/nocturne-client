package dev.noturne.client.value;

/**
 * 颜色设置，持有一个打包好的 ARGB 颜色（{@code 0xAARRGGBB}）。
 *
 * <p>任意 int 都是合法的打包颜色，不存在「非法值」，因此 {@link #coerce(Integer)} 原样返回；
 * 仅 {@code null} 回退为不透明黑色，与 {@link NumberValue} 对 null 回退下界的处理一致。
 */
public final class ColorValue extends Value<Integer> {

    /**
     * 构造颜色设置，初始值即为默认颜色。
     *
     * @param name        设置项名称
     * @param defaultArgb 默认颜色，打包为 {@code 0xAARRGGBB}
     */
    public ColorValue(String name, int defaultArgb) {
        super(name, defaultArgb);
        // 与 NumberValue 一致：构造时过一遍 coerce，保证当前值必为归一化结果
        set(defaultArgb);
    }

    /** @return 当前打包颜色（{@code 0xAARRGGBB}） */
    public int argb() {
        return get();
    }

    /** 颜色没有非法值，任意 int 原样接受；{@code null} 回退为不透明黑色。 */
    @Override
    protected Integer coerce(Integer newValue) {
        return newValue == null ? 0xFF000000 : newValue;
    }

    /** 忽略 alpha，输出 {@code #RRGGBB} 形式的大写六位十六进制，不足补零。 */
    @Override
    public String display() {
        return String.format("#%06X", get() & 0xFFFFFF);
    }
}
