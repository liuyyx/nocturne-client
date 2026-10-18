package dev.nocturne.ui.fatality;

import java.awt.event.KeyEvent;
import java.util.function.Consumer;

/** 单行文本编辑：搜索框、数值直接输入与 HEX 编辑共用。 */
final class FaTextInput {
    /** 最大长度。 */
    private final int maxLength;
    /** 当前文本。 */
    private String text = "";
    /** 文本变化回调。 */
    private Consumer<String> onChange = new Consumer<String>() {
        @Override
        public void accept(String value) {
        }
    };
    /** 回车回调。 */
    private Consumer<String> onSubmit = new Consumer<String>() {
        @Override
        public void accept(String value) {
        }
    };
    /** Esc 回调。 */
    private Runnable onCancel = new Runnable() {
        @Override
        public void run() {
        }
    };
    /** 获得焦点的时间戳（毫秒），用于光标闪烁。 */
    private long focusedAt;

    FaTextInput(int maxLength) {
        this.maxLength = maxLength;
    }

    FaTextInput onChange(Consumer<String> listener) {
        onChange = listener;
        return this;
    }

    FaTextInput onSubmit(Consumer<String> listener) {
        onSubmit = listener;
        return this;
    }

    FaTextInput onCancel(Runnable listener) {
        onCancel = listener;
        return this;
    }

    /** @return 当前文本 */
    String text() {
        return text;
    }

    /** 置文本（超长截断）。 */
    void set(String value) {
        text = value == null ? "" : value.length() > maxLength ? value.substring(0, maxLength) : value;
    }

    /** 标记获得焦点（重置光标闪烁相位）。 */
    void focused() {
        focusedAt = System.currentTimeMillis();
    }

    /**
     * 处理一次按键。
     *
     * @param key       AWT VK 码
     * @param modifiers AWT 修饰位（SHIFT=1、CTRL=2、ALT=8）
     * @return 消费返回 {@code true}；回车提交、Esc 取消
     */
    boolean keyPressed(int key, int modifiers) {
        boolean control = (modifiers & 2) != 0;
        switch (key) {
            case KeyEvent.VK_BACK_SPACE:
                if (!text.isEmpty()) {
                    text = control ? "" : text.substring(0, text.offsetByCodePoints(text.length(), -1));
                    onChange.accept(text);
                }
                return true;
            case KeyEvent.VK_ENTER:
                onSubmit.accept(text);
                return true;
            case KeyEvent.VK_ESCAPE:
                onCancel.run();
                return true;
            case KeyEvent.VK_V:
                if (!control) {
                    return false;
                }
                append(Clipboard.get().replace("\r", "").replace("\n", ""));
                return true;
            case KeyEvent.VK_C:
                if (!control) {
                    return false;
                }
                Clipboard.set(text);
                return true;
            default:
                return false;
        }
    }

    /** 输入一个字符（控制字符被忽略）。 */
    boolean charTyped(char character) {
        if (character < 32 || character == 127) {
            return false;
        }
        append(String.valueOf(character));
        return true;
    }

    /** 追加文本，超出最大长度时截断。 */
    private void append(String value) {
        int room = maxLength - text.length();
        if (room <= 0 || value.isEmpty()) {
            return;
        }
        text += value.length() > room ? value.substring(0, room) : value;
        onChange.accept(text);
    }

    /**
     * 绘制文本（从左侧裁剪以获得更长的可见尾部）与闪烁光标。
     *
     * @param placeholder      空文本且未聚焦时的占位文本；为 {@code null} 时不画
     * @param placeholderColor 占位文本颜色
     */
    void render(FaDraw draw, float x, float centerY, float width, FaFont font, int color, boolean focused,
                String placeholder, int placeholderColor) {
        if (text.isEmpty() && !focused) {
            if (placeholder != null) {
                draw.textMid(placeholder, x, centerY, font, placeholderColor);
            }
            return;
        }
        String shown = text;
        while (!shown.isEmpty() && font.width(shown) > width - 3.0f) {
            shown = shown.substring(shown.offsetByCodePoints(0, 1));
        }
        if (shown.isEmpty() && focused && text.isEmpty() && placeholder != null) {
            draw.textMid(placeholder, x, centerY, font, placeholderColor);
        }
        draw.textMid(shown, x, centerY, font, color);
        if (focused && ((System.currentTimeMillis() - focusedAt) / 530L) % 2L == 0L) {
            float caretX = x + font.width(shown) + 1.0f;
            draw.rect(caretX, centerY - font.height() * 0.42f, 1.0f, font.height() * 0.84f, color);
        }
    }
}
