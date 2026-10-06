package dev.nocturne.injector;

import javax.swing.BorderFactory;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTextPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;
import java.awt.BorderLayout;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.Deque;

/**
 * 只追加的日志视图：等宽字体、按级别着色（信息/失败）、自动滚动，总行数上限 {@value #MAX_LINES}。
 *
 * <p>设上限是为了防止长时间运行的会话把文本组件撑到无限大；新行到来时最老的一行被丢弃。
 * 着色用 {@link JTextPane} 的样式文档实现：时间戳用弱文字色、信息行用主文字色、失败行用
 * 危险语义色；复制到剪贴板的仍是纯文本，着色不影响导出内容。
 */
public final class LogPane extends JPanel {

    /** 保留的最大行数。 */
    private static final int MAX_LINES = 200;

    /** 实际显示的只读文本区；每次追加都会整体重设样式文档。 */
    private final JTextPane area = new JTextPane();
    /** 行缓冲，限制长度并作为 {@link #area} 内容的唯一来源。 */
    private final Deque<Entry> lines = new ArrayDeque<Entry>();
    /** 承载 {@link #area} 的滚动面板；用于在追加时保持用户的滚动位置。 */
    private final JScrollPane scroll;
    /** 时间戳格式；{@link DateTimeFormatter} 线程安全，可在任意线程格式化。 */
    private static final DateTimeFormatter CLOCK = DateTimeFormatter.ofPattern("HH:mm:ss");

    /** 一条日志行：级别决定正文着色。 */
    private static final class Entry {
        /** {@code true} 表示失败行（危险色），否则为信息行（主文字色）。 */
        private final boolean error;
        /** 含时间戳前缀的完整行文本。 */
        private final String text;

        Entry(boolean error, String text) {
            this.error = error;
            this.text = text;
        }
    }

    /**
     * 构造日志面板并装配滚动区、圆角卡片与右键菜单。
     *
     * <p>必须在 EDT 上调用。
     */
    public LogPane() {
        super(new BorderLayout());
        setOpaque(false);

        area.setEditable(false);
        area.setOpaque(true);
        area.setBackground(AppTheme.PANEL);
        // 等宽族，字号继承自 L&F 基础字体（绝不用绝对磅值），并随运行时缩放更新。
        AppTheme.bindMonospacedFont(area, AppTheme.TYPE_MONO);
        area.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
        area.setDragEnabled(false);

        // 禁止横向滚动：卡片宽度有限，横向滚动条会挤掉边框。JTextPane 默认按宽度折行。
        scroll = new JScrollPane(area);
        scroll.setOpaque(false);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setOpaque(false);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);

        // 圆角画在卡片上：viewport 是矩形且不透明的，画在滚动面板上会被内容盖住。
        JPanel card = new JPanel(new BorderLayout());
        card.putClientProperty(com.formdev.flatlaf.FlatClientProperties.STYLE,
                "arc: " + AppTheme.RADIUS_CARD + "; border: 1,1,1,1,#1E2B45");
        card.setBackground(AppTheme.PANEL);
        card.add(scroll, BorderLayout.CENTER);

        attachContextMenu();
        add(card, BorderLayout.CENTER);
    }

    /**
     * 为日志区装配右键菜单（复制全部 / 清空）。
     *
     * <p>只在构造期调用一次。
     */
    private void attachContextMenu() {
        JPopupMenu menu = new JPopupMenu();
        JMenuItem copy = new JMenuItem("复制全部");
        // 复制整个文档而不是选区：右键未必带选区。剪贴板可能被其它进程占用，失败时给出提示而不是抛异常。
        copy.addActionListener(e -> {
            try {
                Toolkit.getDefaultToolkit().getSystemClipboard()
                        .setContents(new StringSelection(area.getText()), null);
            } catch (RuntimeException busy) {
                // 剪贴板争用/无头环境：记一行日志，不让异常打破 EDT 事件循环。
                append(false, "复制失败：系统剪贴板暂不可用");
            }
        });
        JMenuItem clear = new JMenuItem("清空");
        clear.addActionListener(e -> clear());
        menu.add(copy);
        menu.add(clear);

        area.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                maybeShow(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                // 按下与松开都判断：Windows 靠 press，其他平台靠 release 触发弹出菜单。
                maybeShow(e);
            }

            private void maybeShow(MouseEvent e) {
                // isPopupTrigger 平台相关，因此两个事件都要走同一条判断路径。
                if (e.isPopupTrigger()) {
                    menu.show(area, e.getX(), e.getY());
                }
            }
        });
    }

    /** 追加一条普通信息行。 */
    public void info(String message) {
        append(false, message);
    }

    /**
     * 追加一条失败行。文案由调用方决定；与 info 的差别是整行着危险色并带 `!` 前缀，
     * 纯文本导出（复制到剪贴板）时也能检索到。
     */
    public void error(String message) {
        append(true, "! " + message);
    }

    /** 清空全部日志行。与 append 一样可从任意线程调：正文重设投递到 EDT。 */
    public void clear() {
        Runnable task = () -> {
            lines.clear();
            area.setText("");
        };
        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
        } else {
            SwingUtilities.invokeLater(task);
        }
    }

    /**
     * 追加一行带时间戳的日志，受 {@link #MAX_LINES} 限制。
     *
     * <p>可在任意线程调用：时间戳在调用线程即时生成（而不是等 EDT 排到队时），正文重设则投递到
     * {@code invokeLater}。若用户此前已上滚查看历史，追加不会把他拽回底部。
     *
     * @param error   是否失败行（决定正文颜色）
     * @param message 不含时间戳的正文
     */
    private void append(boolean error, String message) {
        // 时间戳取调用时刻：如果等到 EDT 执行时才取，繁忙时日志时间会整体后移。
        final Entry entry = new Entry(error, "[" + LocalTime.now().format(CLOCK) + "]  " + message);
        Runnable task = () -> {
            boolean wasAtBottom = isScrolledToBottom();
            int previousScroll = scroll.getVerticalScrollBar().getValue();
            int previousCaret = area.getCaretPosition();

            lines.addLast(entry);
            while (lines.size() > MAX_LINES) {
                // 超出上限就丢最老的一行，保持恒定内存占用。
                lines.removeFirst();
            }
            rebuildDocument();
            int length = area.getDocument().getLength();
            if (wasAtBottom) {
                // 用户本就在看最新一行：保持自动滚动到底。
                area.setCaretPosition(length);
            } else {
                // 用户正在上滚查看历史：恢复其滚动位置与光标，不要把他拽回底部。
                area.setCaretPosition(Math.min(previousCaret, length));
                JScrollBar bar = scroll.getVerticalScrollBar();
                bar.setValue(Math.min(previousScroll, bar.getMaximum()));
            }
        };
        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
        } else {
            SwingUtilities.invokeLater(task);
        }
    }

    /**
     * 按行缓冲整体重建样式文档：时间戳段弱色，正文按级别着色。
     *
     * <p>整篇重设而非增量插入：JTextPane 的增量插入要手动维护偏移量，且这里只需几百行。
     * 必须在 EDT 上调用。
     */
    private void rebuildDocument() {
        StyledDocument doc = area.getStyledDocument();
        try {
            doc.remove(0, doc.getLength());
            SimpleAttributeSet timestamp = new SimpleAttributeSet();
            StyleConstants.setForeground(timestamp, AppTheme.TEXT_FAINT);
            for (Entry entry : lines) {
                // 时间戳段长 10 个字符（"[HH:mm:ss]"），正文随后；前缀两个空格归到正文段。
                int stampEnd = Math.min(10, entry.text.length());
                SimpleAttributeSet body = new SimpleAttributeSet();
                StyleConstants.setForeground(body, entry.error ? AppTheme.DANGER : AppTheme.TEXT);
                doc.insertString(doc.getLength(), entry.text.substring(0, stampEnd), timestamp);
                doc.insertString(doc.getLength(), entry.text.substring(stampEnd) + "\n", body);
            }
        } catch (javax.swing.text.BadLocationException impossible) {
            // 插入位置始终取文档末尾，不会越界；防御性吞掉以免打破 EDT。
            throw new IllegalStateException(impossible);
        }
    }

    /** 判断滚动条当前是否停在最底部（留 1px 容差）。 */
    private boolean isScrolledToBottom() {
        JScrollBar bar = scroll.getVerticalScrollBar();
        return bar.getValue() + bar.getVisibleAmount() >= bar.getMaximum() - 1;
    }
}
