package dev.nocturne.client.runtime;

/**
 * 滚轮增量的中转站（SDL 世代）。
 *
 * <p><b>为什么需要中转</b>：SDL 的滚轮是**事件驱动**的，没有轮询接口——而叠加层的输入是每帧轮询的，
 * 于是 SDL 世代一直"列表滚不动"。事件钩子在游戏自己的事件入口上把它累加起来，界面每帧取走即可。
 *
 * <p><b>为什么放在 bootstrap 层</b>（与 {@link FrameDispatcher}、{@link InputBlock} 同样的理由）：
 * 写方是织入游戏类的钩子、读方是叠加层，两者分属不同类加载器各自的拷贝，类放在别处会各持一份状态。
 *
 * <p>线程：事件在游戏主线程产生，叠加层在渲染线程读取，因此用 {@code synchronized} 保护；
 * 每帧最多几次锁竞争，可以忽略。
 */
public final class ScrollState {

    /** 累加器与读取之间的锁。 */
    private static final Object LOCK = new Object();

    /** 自上次取走以来累积的滚轮增量（正数 = 向上/远离，与 SDL 的约定一致）。 */
    private static double pending;

    private ScrollState() {
    }

    /**
     * 累加一次滚轮增量（由事件钩子调用）。
     *
     * @param delta 增量；{@code 0}、NaN、无穷会被忽略
     */
    public static void accumulate(double delta) {
        if (delta == 0d || Double.isNaN(delta) || Double.isInfinite(delta)) {
            return;
        }
        synchronized (LOCK) {
            pending += delta;
        }
    }

    /**
     * 取走并清零累积的增量（界面每帧调用；界面关闭期间也必须调用，否则打开瞬间会把积压一次滚出来）。
     *
     * @return 自上次取走以来的增量；没有时返回 {@code 0}
     */
    public static double take() {
        synchronized (LOCK) {
            double value = pending;
            pending = 0d;
            return value;
        }
    }

    /** 清空（自销毁等场景用）。 */
    public static void reset() {
        synchronized (LOCK) {
            pending = 0d;
        }
    }
}
