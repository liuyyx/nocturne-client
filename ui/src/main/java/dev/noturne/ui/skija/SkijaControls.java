/*
 * 移植自 Setsuna 的 ui/screen/UiControls.java（上游 commit e4915ae093748d48d92ee47c38cdb8b2746a4730，
 * 作者 ShiYi，许可 GPL-3.0-or-later，见 THIRD-PARTY-NOTICES.md）。本文件相对上游的改动：
 * 1. 包名 dev.noturne.ui.skija、类名 SkijaControls，顶层类改为 public final（上游为包内可见）。
 * 2. 删除 com.setsuna.render.SkijaUi / com.setsuna.ui.UiTheme 的 import：前者已移植到本包，
 *    UiTheme.* 一律改名为 SkijaTheme.*（同包，无需 import）。
 * 3. 彻底移除 Minecraft / GLFW 依赖：
 *    - 剪贴板：不再从 Minecraft.keyboardHandler 读写，改为本类内的 Clipboard 接口 +
 *      SkijaControls.setClipboard 注入点；未注入时复制/剪切/粘贴为静默空操作。
 *    - 键盘事件：keyPressed(net.minecraft.client.input.KeyEvent, Minecraft) 改为
 *      keyPressed(int keyCode, boolean ctrl, boolean shift)；上游 isSelectAll/isCopy/isCut/isPaste
 *      都是"Ctrl + A/C/X/V"，这里以 ctrl 参数对应；isEscape 对应 VK_ESCAPE；hasShiftDown 对应 shift。
 *    - 字符输入：charTyped(net.minecraft.client.input.CharacterEvent) 改为 charTyped(int codePoint)，
 *      并复刻 CharacterEvent.isAllowedChatCharacter()/codepointAsString()（见下方私有辅助方法）。
 *    - GLFW 键码改为 java.awt.event.KeyEvent.VK_*（AWT 的 VK_* 是编译期常量，会被 javac 内联，
 *      运行时不会加载 AWT 类；数值与本项目注入器使用的 LWJGL2 码一致）。对照表见 keyPressed 的 javadoc。
 * 4. 上游 record Box 降级为 Java 8 静态值类（字段 + 构造器 + x()/y()/width()/height() 访问器）。
 * 5. Java 9+ API 降级：Objects.requireNonNullElse → 私有辅助 orEmpty(String)，
 *    "*".repeat(n) → 逐字符拼接，StringBuilder.isEmpty() → length() == 0。
 * 6. 注释与 javadoc 中文化；绘制逻辑、颜色、间距、圆角数值逐行与上游一致。
 */
package dev.noturne.ui.skija;

import io.github.humbleui.skija.Canvas;

import java.awt.event.KeyEvent;
import java.util.function.IntPredicate;

/** Noturne 独立屏幕（Setsuna 视觉语言）共享的一小组保留态控件。 */
public final class SkijaControls {

    /** 控件的语义色调。 */
    enum Tone {
        NORMAL,
        PRIMARY,
        DANGER
    }

    /**
     * 上游 {@code record Box} 的 Java 8 等价物：不可变矩形值对象。
     *
     * <p>可见性保持包级：同包的上游屏幕（AltManagerScreen / MusicScreen 等）会直接
     * {@code new Box(...)} 构造它，改成 private 会让这些调用方无法编译。
     * 字段名沿用 record 组件名，因此上游的 {@code box.x} 直读写法也仍然成立。
     */
    static final class Box {

        final float x;
        final float y;
        final float width;
        final float height;

        Box(float x, float y, float width, float height) {
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }

        float x() {
            return x;
        }

        float y() {
            return y;
        }

        float width() {
            return width;
        }

        float height() {
            return height;
        }

        boolean contains(double mouseX, double mouseY) {
            return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
        }

        Box inset(float amount) {
            return new Box(x + amount, y + amount, Math.max(0, width - amount * 2),
                    Math.max(0, height - amount * 2));
        }
    }

    // 聚焦（Spotlight）视觉语言。刻意做成屏幕级令牌：SkijaTheme 由 HUD 与 ClickGUI 拥有，
    // 这里的常量只给独立的 Setsuna 屏幕套皮。
    static final float RADIUS = 9.0F;
    static final float RADIUS_SMALL = 6.0F;

    static final int TEXT = 0xFFF2F4F8;
    static final int TEXT_MUTED = 0xFF9AA2B0;
    static final int TEXT_FAINT = 0xFF5C6472;

    static final int PANEL = 0xE60C0F16;
    static final int CARD = 0x99141826;
    static final int CARD_HOVER = 0xC81C2231;
    static final int STROKE = 0x16FFFFFF;
    static final int STROKE_STRONG = 0x2CFFFFFF;
    static final int HIGHLIGHT = 0x1EFFFFFF;
    static final int SHADOW = 0x63000000;

    /** 剪贴板访问的最小抽象：由客户端引导代码接上 Minecraft 的 keyboardHandler 或系统剪贴板。 */
    public interface Clipboard {

        /** 读取剪贴板文本；无内容时应返回空串（可为空串语义，不要返回 null）。 */
        String get();

        /** 写入剪贴板文本。 */
        void set(String value);
    }

    private static volatile Clipboard clipboard;

    private SkijaControls() {
    }

    /**
     * 注入剪贴板实现。
     *
     * <p>未注入时，文本输入框的复制/剪切/粘贴分支仍然消费按键（返回值与上游一致），
     * 但不产生任何剪贴板副作用。
     *
     * @param clipboardImpl 实现；可为 null 以断开注入
     */
    public static void setClipboard(Clipboard clipboardImpl) {
        clipboard = clipboardImpl;
    }

    /** 柔和投影：{@code box} 背后偏移的一块更暗的圆角板。 */
    static void shadow(Canvas canvas, Box box, float radius) {
        SkijaUi.rounded(canvas, box.x() - 1.0F, box.y() + 3.0F, box.width() + 2.0F,
                box.height() + 4.0F, radius + 1.0F, SHADOW);
    }

    /** 玻璃质感表面：1px 描边 + 顶部一条微弱的高光。 */
    static void surface(Canvas canvas, Box box, int fill, int stroke, float radius) {
        SkijaUi.rounded(canvas, box.x(), box.y(), box.width(), box.height(), radius, stroke);
        SkijaUi.rounded(canvas, box.x() + 1, box.y() + 1, Math.max(0, box.width() - 2),
                Math.max(0, box.height() - 2), Math.max(0, radius - 1), fill);
        SkijaUi.rounded(canvas, box.x() + 2, box.y() + 1, Math.max(0, box.width() - 4), 1.0F,
                0.5F, HIGHLIGHT);
    }

    /** 列表项（账号等）使用的可交互卡片行。 */
    static void card(Canvas canvas, Box box, boolean hovered, boolean selected) {
        int fill = selected ? SkijaTheme.withAlpha(SkijaTheme.accent(), 40)
                : hovered ? CARD_HOVER : CARD;
        int stroke = selected ? SkijaTheme.withAlpha(SkijaTheme.accent(), 150)
                : hovered ? STROKE_STRONG : STROKE;
        surface(canvas, box, fill, stroke, RADIUS_SMALL);
    }

    /** 用最大圆角的圆角矩形近似实心圆。 */
    static void disc(Canvas canvas, float x, float y, float size, int color) {
        SkijaUi.rounded(canvas, x, y, size, size, size * 0.5F, color);
    }

    /** 绘制在强调色填充之上的可读文字颜色。 */
    static int onAccent(int accent) {
        int red = (accent >> 16) & 0xFF;
        int green = (accent >> 8) & 0xFF;
        int blue = accent & 0xFF;
        double luminance = (0.299 * red + 0.587 * green + 0.114 * blue) / 255.0;
        return luminance > 0.6 ? 0xFF0B0E14 : TEXT;
    }

    /** 以固定字距逐字绘制粗体 {@code text}。 */
    static void brand(Canvas canvas, String text, float x, float top, float height, int color,
                      float size, float tracking) {
        float cursor = x;
        for (int index = 0; index < text.length(); index++) {
            String glyph = text.substring(index, index + 1);
            SkijaUi.boldText(canvas, glyph, cursor, top, height, color, size);
            cursor += SkijaUi.boldTextWidth(glyph, size) + tracking;
        }
    }

    static float brandWidth(String text, float size, float tracking) {
        if (text == null || text.isEmpty()) {
            return 0.0F;
        }
        float width = -tracking;
        for (int index = 0; index < text.length(); index++) {
            width += SkijaUi.boldTextWidth(text.substring(index, index + 1), size) + tracking;
        }
        return width;
    }

    static void panel(Canvas canvas, Box box) {
        shadow(canvas, box, RADIUS);
        surface(canvas, box, PANEL, STROKE_STRONG, RADIUS);
    }

    static void section(Canvas canvas, Box box) {
        surface(canvas, box, CARD, STROKE, RADIUS_SMALL);
    }

    static void button(
            Canvas canvas,
            Box box,
            String label,
            boolean hovered,
            boolean enabled,
            Tone tone
    ) {
        int accent = SkijaTheme.accent();
        int fill;
        int stroke;
        int foreground;
        if (!enabled) {
            fill = 0x120E1420;
            stroke = STROKE;
            foreground = TEXT_FAINT;
        } else if (tone == Tone.PRIMARY) {
            fill = hovered ? accent : SkijaTheme.withAlpha(accent, 205);
            stroke = accent;
            foreground = onAccent(accent);
        } else if (tone == Tone.DANGER) {
            fill = hovered ? 0xE6E0504C : 0x33E0504C;
            stroke = hovered ? 0xFFE0504C : 0x66E0504C;
            foreground = hovered ? TEXT : 0xFFF0A5A2;
        } else {
            fill = hovered ? CARD_HOVER : CARD;
            stroke = hovered ? SkijaTheme.withAlpha(accent, 130) : STROKE_STRONG;
            foreground = TEXT;
        }

        SkijaUi.rounded(canvas, box.x, box.y, box.width, box.height, RADIUS_SMALL, stroke);
        SkijaUi.rounded(
                canvas,
                box.x + 1,
                box.y + 1,
                Math.max(0, box.width - 2),
                Math.max(0, box.height - 2),
                Math.max(0, RADIUS_SMALL - 1),
                fill
        );
        if (enabled && tone != Tone.PRIMARY) {
            SkijaUi.rounded(canvas, box.x + 2, box.y + 1, Math.max(0, box.width - 4), 1.0F, 0.5F, HIGHLIGHT);
        }
        centeredText(canvas, label, box, foreground, true);
    }

    /** 整宽聚焦菜单行：强调色侧条 + 标签，悬停时更亮。 */
    static void menuItem(Canvas canvas, Box box, String label, boolean hovered, Tone tone) {
        int accent = SkijaTheme.accent();
        boolean danger = tone == Tone.DANGER;
        int fill = hovered ? CARD_HOVER : CARD;
        int stroke = hovered ? (danger ? 0xFFE0504C : SkijaTheme.withAlpha(accent, 150)) : STROKE;
        surface(canvas, box, fill, stroke, RADIUS_SMALL);
        if (hovered) {
            SkijaUi.rounded(canvas, box.x() + 2, box.y() + 4, 3.0F, Math.max(0, box.height() - 8),
                    1.5F, danger ? 0xFFE0504C : accent);
        }
        int color = danger ? (hovered ? 0xFFF3A6A3 : 0xFFCF8683) : (hovered ? TEXT : TEXT_MUTED);
        Box textBox = new Box(box.x() + 16, box.y(), box.width() - 20, box.height());
        String value = ellipsize(label, Math.max(0, textBox.width()), true);
        SkijaUi.boldText(canvas, value, textBox.x(), textBox.y(), textBox.height(), color, 8.5F);
    }

    static void centeredText(Canvas canvas, String text, Box box, int color, boolean bold) {
        String value = ellipsize(text, Math.max(0, box.width - 8), bold);
        float textWidth = bold ? SkijaUi.boldTextWidth(value) : SkijaUi.textWidth(value);
        float x = box.x + Math.max(0, (box.width - textWidth) * 0.5F);
        if (bold) {
            SkijaUi.boldText(canvas, value, x, box.y, box.height, color);
        } else {
            SkijaUi.text(canvas, value, x, box.y, box.height, color);
        }
    }

    static String ellipsize(String text, float maxWidth) {
        return ellipsize(text, maxWidth, false);
    }

    static String ellipsize(String text, float maxWidth, boolean bold) {
        String value = orEmpty(text);
        if (maxWidth <= 0) {
            return "";
        }
        if (measure(value, bold) <= maxWidth) {
            return value;
        }

        String suffix = "...";
        int end = value.length();
        while (end > 0 && measure(value.substring(0, end) + suffix, bold) > maxWidth) {
            end = value.offsetByCodePoints(end, -1);
        }
        return end == 0 && measure(suffix, bold) > maxWidth ? "" : value.substring(0, end) + suffix;
    }

    static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static float measure(String text, boolean bold) {
        return bold ? SkijaUi.boldTextWidth(text) : SkijaUi.textWidth(text);
    }

    /** 上游 Java 9 的 {@code Objects.requireNonNullElse(text, "")} 的 Java 8 等价物。 */
    private static String orEmpty(String text) {
        return text == null ? "" : text;
    }

    /**
     * 复刻上游 {@code CharacterEvent.isAllowedChatCharacter()}（其实现等于原版
     * {@code StringUtil.isAllowedChatCharacter(int)}）：拒绝 § 颜色控制符、C0 控制符与 DEL。
     */
    private static boolean isAllowedChatCharacter(int codePoint) {
        return codePoint != 167 && codePoint >= 32 && codePoint != 127;
    }

    /** 复刻上游 {@code CharacterEvent.codepointAsString()}。 */
    private static String codepointAsString(int codePoint) {
        return new String(Character.toChars(codePoint));
    }

    static final class TextInput {

        private final int maxLength;
        private final boolean password;
        private final IntPredicate characterFilter;
        private final StringBuilder value = new StringBuilder();

        private Box bounds = new Box(0, 0, 0, 0);
        private String placeholder = "";
        private int cursor;
        private int anchor;
        private int viewStart;
        private boolean focused;

        TextInput(int maxLength) {
            this(maxLength, false, codePoint -> true);
        }

        TextInput(int maxLength, boolean password) {
            this(maxLength, password, codePoint -> true);
        }

        TextInput(int maxLength, boolean password, IntPredicate characterFilter) {
            this.maxLength = Math.max(0, maxLength);
            this.password = password;
            this.characterFilter = characterFilter == null ? codePoint -> true : characterFilter;
        }

        void setBounds(Box bounds) {
            this.bounds = bounds;
        }

        Box bounds() {
            return bounds;
        }

        void setPlaceholder(String placeholder) {
            this.placeholder = orEmpty(placeholder);
        }

        String text() {
            return value.toString();
        }

        void setText(String text) {
            value.setLength(0);
            cursor = 0;
            anchor = 0;
            viewStart = 0;
            insertFiltered(orEmpty(text));
            cursor = value.length();
            anchor = cursor;
        }

        void clear() {
            value.setLength(0);
            cursor = 0;
            anchor = 0;
            viewStart = 0;
        }

        boolean isFocused() {
            return focused;
        }

        void focus() {
            focused = true;
        }

        void blur() {
            focused = false;
            anchor = cursor;
        }

        boolean click(double mouseX, double mouseY, boolean doubleClick) {
            if (!bounds.contains(mouseX, mouseY)) {
                blur();
                return false;
            }
            focused = true;
            if (doubleClick) {
                cursor = value.length();
                anchor = 0;
                return true;
            }

            String shown = displayValue();
            float relativeX = (float) mouseX - bounds.x - 5;
            int closest = viewStart;
            float closestDistance = Math.abs(relativeX);
            for (int index = viewStart; index <= shown.length();) {
                float candidateX = inputTextWidth(shown.substring(viewStart, index));
                float distance = Math.abs(relativeX - candidateX);
                if (distance < closestDistance) {
                    closest = index;
                    closestDistance = distance;
                }
                if (candidateX > bounds.width - 2 && candidateX > relativeX) {
                    break;
                }
                if (index == shown.length()) {
                    break;
                }
                index = nextIndex(shown, index);
            }
            cursor = Math.min(closest, value.length());
            anchor = cursor;
            return true;
        }

        /**
         * 处理一次按键。
         *
         * <p>键码用 {@link java.awt.event.KeyEvent#VK_*} 常量（编译期常量，javac 会内联，
         * 运行时不会加载 AWT）。上游 GLFW → AWT 对照表：
         * GLFW_KEY_ESCAPE→VK_ESCAPE、GLFW_KEY_LEFT→VK_LEFT、GLFW_KEY_RIGHT→VK_RIGHT、
         * GLFW_KEY_HOME→VK_HOME、GLFW_KEY_END→VK_END、GLFW_KEY_BACKSPACE→VK_BACK、
         * GLFW_KEY_DELETE→VK_DELETE，快捷键 GLFW_KEY_A/C/X/V→VK_A/VK_C/VK_X/VK_V。
         *
         * @param ctrl  是否按下控制键。上游 {@code isSelectAll()/isCopy()/isCut()/isPaste()}
         *              的前置条件都是 Control 按下；macOS 上若要复刻原版
         *              {@code hasControlDownWithQuirks()} 的"Command 视作 Control"行为，
         *              调用方应传 {@code ctrl || command}
         * @param shift 是否按下 Shift（决定光标移动是扩展选区还是收起选区）
         */
        boolean keyPressed(int keyCode, boolean ctrl, boolean shift) {
            if (!focused) {
                return false;
            }
            if (keyCode == KeyEvent.VK_ESCAPE) {
                blur();
                return true;
            }
            if (ctrl && keyCode == KeyEvent.VK_A) {
                anchor = 0;
                cursor = value.length();
                return true;
            }
            if (ctrl && keyCode == KeyEvent.VK_C) {
                if (hasSelection()) {
                    Clipboard target = clipboard;
                    if (target != null) {
                        target.set(selectedText());
                    }
                }
                return true;
            }
            if (ctrl && keyCode == KeyEvent.VK_X) {
                if (hasSelection()) {
                    Clipboard target = clipboard;
                    if (target != null) {
                        target.set(selectedText());
                    }
                    deleteSelection();
                }
                return true;
            }
            if (ctrl && keyCode == KeyEvent.VK_V) {
                Clipboard target = clipboard;
                if (target != null) {
                    replaceSelection(target.get());
                }
                return true;
            }

            boolean selecting = shift;
            if (keyCode == KeyEvent.VK_LEFT) {
                moveCursor(previousIndex(value, cursor), selecting);
                return true;
            }
            if (keyCode == KeyEvent.VK_RIGHT) {
                moveCursor(nextIndex(value, cursor), selecting);
                return true;
            }
            if (keyCode == KeyEvent.VK_HOME) {
                moveCursor(0, selecting);
                return true;
            }
            if (keyCode == KeyEvent.VK_END) {
                moveCursor(value.length(), selecting);
                return true;
            }
            if (keyCode == KeyEvent.VK_BACK_SPACE) {
                if (!deleteSelection() && cursor > 0) {
                    int previous = previousIndex(value, cursor);
                    value.delete(previous, cursor);
                    cursor = previous;
                    anchor = cursor;
                }
                return true;
            }
            if (keyCode == KeyEvent.VK_DELETE) {
                if (!deleteSelection() && cursor < value.length()) {
                    value.delete(cursor, nextIndex(value, cursor));
                    anchor = cursor;
                }
                return true;
            }
            return false;
        }

        /**
         * 处理一次字符输入。
         *
         * @param codePoint Unicode 码点（对应上游 {@code CharacterEvent.codepoint()}；
         *                  用 int 而非 char 是为了支持增补平面字符）
         */
        boolean charTyped(int codePoint) {
            if (!focused || !isAllowedChatCharacter(codePoint) || !characterFilter.test(codePoint)) {
                return false;
            }
            replaceSelection(codepointAsString(codePoint));
            return true;
        }

        void draw(Canvas canvas, int mouseX, int mouseY) {
            draw(canvas, mouseX, mouseY, SkijaTheme.accent(), CARD, STROKE_STRONG);
        }

        void draw(Canvas canvas, int mouseX, int mouseY, int accent, int fill, int border) {
            boolean hovered = bounds.contains(mouseX, mouseY);
            int outline = focused ? accent : hovered ? SkijaTheme.TEXT_FAINT : border;
            SkijaUi.rounded(canvas, bounds.x, bounds.y, bounds.width, bounds.height, SkijaTheme.RADIUS_SMALL, outline);
            SkijaUi.rounded(
                    canvas,
                    bounds.x + 1,
                    bounds.y + 1,
                    Math.max(0, bounds.width - 2),
                    Math.max(0, bounds.height - 2),
                    Math.max(0, SkijaTheme.RADIUS_SMALL - 1),
                    fill
            );

            if (value.length() == 0 && !focused) {
                SkijaUi.textWithFallback(
                        canvas,
                        ellipsize(placeholder, Math.max(0, bounds.width - 10)),
                        bounds.x + 5,
                        bounds.y,
                        bounds.height,
                        SkijaTheme.TEXT_FAINT,
                        9.0F
                );
                return;
            }

            String shown = displayValue();
            float available = Math.max(0, bounds.width - 10);
            keepCursorVisible(shown, available);
            int visibleEnd = viewStart;
            while (visibleEnd < shown.length()) {
                int next = nextIndex(shown, visibleEnd);
                if (inputTextWidth(shown.substring(viewStart, next)) > available) {
                    break;
                }
                visibleEnd = next;
            }

            if (hasSelection()) {
                int selectionStart = Math.max(Math.min(cursor, anchor), viewStart);
                int selectionEnd = Math.min(Math.max(cursor, anchor), visibleEnd);
                if (selectionStart < selectionEnd) {
                    float selectionX = bounds.x + 5 + inputTextWidth(shown.substring(viewStart, selectionStart));
                    float selectionWidth = inputTextWidth(shown.substring(selectionStart, selectionEnd));
                    SkijaUi.fill(
                            canvas,
                            selectionX,
                            bounds.y + 4,
                            selectionWidth,
                            Math.max(1, bounds.height - 8),
                            SkijaTheme.withAlpha(accent, 48)
                    );
                }
            }

            SkijaUi.textWithFallback(
                    canvas,
                    shown.substring(viewStart, visibleEnd),
                    bounds.x + 5,
                    bounds.y,
                    bounds.height,
                    SkijaTheme.TEXT,
                    9.0F
            );
            if (focused && (System.currentTimeMillis() / 500L) % 2L == 0L) {
                int visibleCursor = Math.max(viewStart, Math.min(cursor, visibleEnd));
                float cursorX = bounds.x + 5 + inputTextWidth(shown.substring(viewStart, visibleCursor));
                SkijaUi.fill(canvas, cursorX, bounds.y + 4, 1, Math.max(1, bounds.height - 8), SkijaTheme.TEXT);
            }
        }

        private void keepCursorVisible(String shown, float available) {
            viewStart = Math.max(0, Math.min(viewStart, Math.min(cursor, shown.length())));
            if (inputTextWidth(shown.substring(viewStart, cursor)) > available) {
                int candidate = cursor;
                while (candidate > 0) {
                    int previous = previousIndex(shown, candidate);
                    if (inputTextWidth(shown.substring(previous, cursor)) > available) {
                        break;
                    }
                    candidate = previous;
                }
                viewStart = candidate;
            }
            while (cursor == shown.length() && viewStart > 0) {
                int previous = previousIndex(shown, viewStart);
                if (inputTextWidth(shown.substring(previous, cursor)) > available) {
                    break;
                }
                viewStart = previous;
            }
        }

        private String displayValue() {
            if (!password) {
                return value.toString();
            }
            // 上游用 Java 11 的 "*".repeat(value.length())，这里逐字符拼接。
            StringBuilder masked = new StringBuilder(value.length());
            for (int index = 0; index < value.length(); index++) {
                masked.append('*');
            }
            return masked.toString();
        }

        private static float inputTextWidth(String text) {
            return SkijaUi.textWidthWithFallback(text, 9.0F);
        }

        private void replaceSelection(String text) {
            deleteSelection();
            insertFiltered(orEmpty(text));
            anchor = cursor;
        }

        private void insertFiltered(String text) {
            int remaining = maxLength - value.length();
            if (remaining <= 0 || text.isEmpty()) {
                return;
            }
            StringBuilder accepted = new StringBuilder(Math.min(remaining, text.length()));
            text.codePoints().forEach(codePoint -> {
                if (accepted.length() >= remaining || Character.isISOControl(codePoint) || !characterFilter.test(codePoint)) {
                    return;
                }
                String candidate = new String(Character.toChars(codePoint));
                if (accepted.length() + candidate.length() <= remaining) {
                    accepted.append(candidate);
                }
            });
            value.insert(cursor, accepted);
            cursor += accepted.length();
        }

        private boolean hasSelection() {
            return cursor != anchor;
        }

        private String selectedText() {
            return value.substring(Math.min(cursor, anchor), Math.max(cursor, anchor));
        }

        private boolean deleteSelection() {
            if (!hasSelection()) {
                return false;
            }
            int start = Math.min(cursor, anchor);
            int end = Math.max(cursor, anchor);
            value.delete(start, end);
            cursor = start;
            anchor = start;
            return true;
        }

        private void moveCursor(int newCursor, boolean selecting) {
            cursor = Math.max(0, Math.min(value.length(), newCursor));
            if (!selecting) {
                anchor = cursor;
            }
        }

        private static int previousIndex(CharSequence text, int index) {
            if (index <= 0) {
                return 0;
            }
            char previous = text.charAt(index - 1);
            return Character.isLowSurrogate(previous)
                    && index > 1
                    && Character.isHighSurrogate(text.charAt(index - 2))
                    ? index - 2
                    : index - 1;
        }

        private static int nextIndex(CharSequence text, int index) {
            if (index >= text.length()) {
                return text.length();
            }
            char current = text.charAt(index);
            return Character.isHighSurrogate(current)
                    && index + 1 < text.length()
                    && Character.isLowSurrogate(text.charAt(index + 1))
                    ? index + 2
                    : index + 1;
        }
    }
}
