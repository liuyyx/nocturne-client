package dev.nocturne.ui.fatality;

/**
 * 系统剪贴板接缝。
 *
 * <p>文本框与颜色浮层需要复制/粘贴，而「怎么读写剪贴板」在两代 LWJGL（与 26.x 的 SDL 栈）上完全没有
 * 共同入口。这里把它收成一个静态可注入的实现：agent 侧在装好 {@code InputSource} 后注入
 * （见 {@code OverlayBootstrap}），UI 侧只调 {@link #get()}/{@link #set(String)}。
 *
 * <p>未注入时读写都退化为空操作——文本框仍可编辑，只是复制粘贴不生效。
 */
public final class Clipboard {

    /** 剪贴板读写实现。 */
    public interface Source {
        /** @return 剪贴板文本；不可用或为空时返回 {@code null} 或空串 */
        String get();

        /** 写入剪贴板文本。 */
        void set(String value);
    }

    /** 默认实现：什么都不做。 */
    private static final Source NONE = new Source() {
        @Override
        public String get() {
            return "";
        }

        @Override
        public void set(String value) {
        }
    };

    /** 当前实现；由 {@link #install} 替换。 */
    private static volatile Source source = NONE;

    private Clipboard() {
    }

    /** 安装剪贴板实现；传 {@code null} 恢复为空操作。 */
    public static void install(Source installed) {
        source = installed == null ? NONE : installed;
    }

    /** @return 剪贴板文本；不可用时返回空串 */
    public static String get() {
        String value = source.get();
        return value == null ? "" : value;
    }

    /** 写入剪贴板。 */
    public static void set(String value) {
        source.set(value == null ? "" : value);
    }
}
