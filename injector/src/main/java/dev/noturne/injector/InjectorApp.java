package dev.noturne.injector;

import dev.noturne.core.attach.AttachException;
import dev.noturne.core.attach.Attacher;
import dev.noturne.core.attach.CurrentProcess;
import dev.noturne.core.attach.ProcessScanner;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.JTextArea;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.plaf.basic.BasicScrollBarUI;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.LayoutManager;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.net.URLDecoder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Desktop injector: a borderless dark window listing the JVMs on this machine.
 *
 * <p>Runs on the operator's JVM (never the target's), so plain Swing is used. The window is
 * undecorated with a hand-drawn title bar, rounded body and flat controls — system chrome would
 * break the look and add a light strip to an otherwise dark UI.
 */
public final class InjectorApp {

    private InjectorApp() {
    }

    public static void main(String[] args) {
        if (dev.noturne.core.attach.ToolsJarBootstrap.relaunchIfNeeded(
                "dev.noturne.injector.InjectorApp", args)) {
            return;
        }
        configureRendering();
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                new InjectorFrame().setVisible(true);
            }
        });
    }

    private static void configureRendering() {
        System.setProperty("awt.useSystemAAFontSettings", "on");
        System.setProperty("swing.aatext", "true");
        try {
            UIManager.put("ToolTip.background", Palette.CARD);
            UIManager.put("ToolTip.foreground", Palette.TEXT);
        } catch (Throwable ignored) {
            // cosmetic only
        }
    }

    // ================================================================= palette

    static final class Palette {
        static final Color BG = new Color(0x0B, 0x0B, 0x0F);
        static final Color CARD = new Color(0x14, 0x14, 0x1A);
        static final Color ROW_ALT = new Color(0x18, 0x18, 0x20);
        static final Color ROW_HOVER = new Color(0x1F, 0x1F, 0x2A);
        static final Color STROKE = new Color(0xFF, 0xFF, 0xFF, 0x12);
        static final Color STROKE_SOFT = new Color(0xFF, 0xFF, 0xFF, 0x08);
        static final Color TEXT = new Color(0xF2, 0xF2, 0xF7);
        static final Color TEXT_MUTED = new Color(0x8A, 0x8A, 0xA0);
        static final Color TEXT_FAINT = new Color(0x5A, 0x5A, 0x6E);
        static final Color ACCENT = new Color(0x7C, 0x5C, 0xFF);
        static final Color ACCENT_HOVER = new Color(0x93, 0x78, 0xFF);
        static final Color ACCENT_GLOW = new Color(0x7C, 0x5C, 0xFF, 0x40);
        static final Color OK = new Color(0x5B, 0xD8, 0x9A);
        static final Color WARN = new Color(0xE8, 0xB0, 0x5A);
        static final Color ERR = new Color(0xE8, 0x6A, 0x62);
    }

    // =================================================================== fonts

    private static final String FAMILY = pickFamily("Microsoft YaHei UI", "Segoe UI", "Microsoft YaHei");

    private static String pickFamily(String... candidates) {
        try {
            Set<String> available = new HashSet<String>(Arrays.asList(
                    GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
            for (String candidate : candidates) {
                if (available.contains(candidate)) {
                    return candidate;
                }
            }
        } catch (Throwable ignored) {
            // headless
        }
        return Font.SANS_SERIF;
    }

    static Font ui(int style, float size) {
        return new Font(FAMILY, style, 12).deriveFont(size);
    }

    // ==================================================================== frame

    static final class InjectorFrame extends JFrame {

        private final ProcessTableModel model = new ProcessTableModel();
        private final JTable table = new JTable(model);
        private final JTextArea log = new JTextArea();
        private final StatusBadge status = new StatusBadge();
        private final AccentButton inject = new AccentButton("Inject", Palette.ACCENT, true);
        private final AccentButton refresh = new AccentButton("Refresh", Palette.CARD, false);

        InjectorFrame() {
            setUndecorated(true);
            setBackground(new Color(0, 0, 0, 0));
            setIconImage(buildIcon());
            setPreferredSize(new Dimension(980, 660));
            setMinimumSize(new Dimension(820, 560));

            JPanel root = new RoundedPanel(20, Palette.BG);
            root.setLayout(new BorderLayout());
            root.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(Palette.STROKE, 1, true),
                    BorderFactory.createEmptyBorder(0, 0, 0, 0)));
            setContentPane(root);

            root.add(new TitleBar(), BorderLayout.NORTH);
            root.add(buildBody(), BorderLayout.CENTER);

            pack();
            setLocationRelativeTo(null);
            setShape(new RoundRectangle2D.Double(0, 0, getWidth(), getHeight(), 20, 20));

            refresh.addActionListener(e -> refreshProcesses());
            inject.addActionListener(e -> injectSelected());
            log("noturne injector ready.");
            refreshProcesses();
        }

        private static Image buildIcon() {
            BufferedImage image = new BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = image.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setPaint(new java.awt.GradientPaint(0, 0, Palette.ACCENT_HOVER, 128, 128, Palette.ACCENT));
            g.fillRoundRect(8, 8, 112, 112, 30, 30);
            g.setColor(Color.WHITE);
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 76));
            FontMetrics metrics = g.getFontMetrics();
            String mark = "n";
            g.drawString(mark, (128 - metrics.stringWidth(mark)) / 2, 96);
            g.dispose();
            return image;
        }

        private JPanel buildBody() {
            JPanel body = new JPanel(new BorderLayout());
            body.setOpaque(false);
            body.setBorder(BorderFactory.createEmptyBorder(6, 26, 24, 26));

            body.add(buildHeader(), BorderLayout.NORTH);
            body.add(buildTable(), BorderLayout.CENTER);
            body.add(buildFooter(), BorderLayout.SOUTH);
            return body;
        }

        private JPanel buildHeader() {
            JPanel header = new JPanel(new BorderLayout());
            header.setOpaque(false);
            header.setBorder(BorderFactory.createEmptyBorder(2, 0, 18, 0));

            JLabel title = new JLabel("noturne");
            title.setFont(ui(Font.BOLD, 27f));
            title.setForeground(Palette.TEXT);

            JLabel subtitle = new JLabel("cross-version injection client");
            subtitle.setFont(ui(Font.PLAIN, 13f));
            subtitle.setForeground(Palette.TEXT_MUTED);

            JPanel column = new JPanel();
            column.setOpaque(false);
            column.setLayout(new BoxLayout(column, BoxLayout.Y_AXIS));
            title.setAlignmentX(Component.LEFT_ALIGNMENT);
            subtitle.setAlignmentX(Component.LEFT_ALIGNMENT);
            column.add(title);
            column.add(Box.createVerticalStrut(3));
            column.add(subtitle);

            header.add(column, BorderLayout.WEST);
            return header;
        }

        private JPanel buildTable() {
            table.setOpaque(false);
            table.setForeground(Palette.TEXT);
            table.setSelectionBackground(new Color(0, 0, 0, 0));
            table.setSelectionForeground(Palette.TEXT);
            table.setFont(ui(Font.PLAIN, 13.5f));
            table.setRowHeight(44);
            table.setShowGrid(false);
            table.setIntercellSpacing(new Dimension(0, 0));
            table.setFillsViewportHeight(true);
            table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            table.setAutoResizeMode(JTable.AUTO_RESIZE_LAST_COLUMN);
            table.setDefaultRenderer(Object.class, new CellRenderer(table));
            table.getTableHeader().setDefaultRenderer(new HeaderRenderer());
            table.getTableHeader().setBorder(BorderFactory.createEmptyBorder());
            table.getTableHeader().setReorderingAllowed(false);
            table.getTableHeader().setPreferredSize(new Dimension(0, 40));
            table.getColumnModel().getColumn(0).setPreferredWidth(110);
            table.getColumnModel().getColumn(0).setMaxWidth(130);
            table.getColumnModel().getColumn(1).setPreferredWidth(800);

            JScrollPane scroll = new JScrollPane(table);
            scroll.setOpaque(false);
            scroll.getViewport().setOpaque(false);
            scroll.setBorder(BorderFactory.createEmptyBorder());
            scroll.getVerticalScrollBar().setUI(new ThinScrollBarUI());
            scroll.getVerticalScrollBar().setUnitIncrement(20);
            scroll.getVerticalScrollBar().setPreferredSize(new Dimension(8, 0));

            RoundedPanel card = new RoundedPanel(16, Palette.CARD);
            card.setLayout(new BorderLayout());
            card.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
            card.add(scroll, BorderLayout.CENTER);

            JPanel wrapper = new JPanel(new BorderLayout());
            wrapper.setOpaque(false);
            wrapper.add(card, BorderLayout.CENTER);
            return wrapper;
        }

        private JPanel buildFooter() {
            JPanel footer = new JPanel(new BorderLayout());
            footer.setOpaque(false);
            footer.setBorder(BorderFactory.createEmptyBorder(18, 0, 0, 0));

            log.setEditable(false);
            log.setOpaque(false);
            log.setForeground(new Color(0xA8, 0xA8, 0xC0));
            log.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
            log.setBorder(BorderFactory.createEmptyBorder(14, 16, 14, 16));
            log.setLineWrap(true);

            JScrollPane logScroll = new JScrollPane(log);
            logScroll.setOpaque(false);
            logScroll.getViewport().setOpaque(false);
            logScroll.setBorder(BorderFactory.createEmptyBorder());
            logScroll.getVerticalScrollBar().setUI(new ThinScrollBarUI());
            logScroll.getVerticalScrollBar().setUnitIncrement(20);
            logScroll.getVerticalScrollBar().setPreferredSize(new Dimension(8, 0));

            RoundedPanel logCard = new RoundedPanel(14, Palette.CARD);
            logCard.setLayout(new BorderLayout());
            logCard.setBorder(BorderFactory.createEmptyBorder(2, 2, 2, 2));
            logCard.add(logScroll, BorderLayout.CENTER);
            logCard.setPreferredSize(new Dimension(0, 150));

            JPanel row = new JPanel(new BorderLayout());
            row.setOpaque(false);
            row.setBorder(BorderFactory.createEmptyBorder(16, 2, 0, 2));
            row.add(status, BorderLayout.WEST);

            JPanel buttons = new JPanel();
            buttons.setOpaque(false);
            buttons.setLayout(new BoxLayout(buttons, BoxLayout.X_AXIS));
            buttons.add(refresh);
            buttons.add(Box.createHorizontalStrut(10));
            buttons.add(inject);
            row.add(buttons, BorderLayout.EAST);

            footer.add(logCard, BorderLayout.CENTER);
            footer.add(row, BorderLayout.SOUTH);
            return footer;
        }

        // ------------------------------------------------------------ behaviour

        private void refreshProcesses() {
            status.set("Scanning\u2026", Palette.WARN);
            refresh.setEnabled(false);
            new Thread(new Runnable() {
                @Override
                public void run() {
                    final List<ProcessScanner.ProcessInfo> minecraft = ProcessScanner.minecraftProcesses();
                    final List<ProcessScanner.ProcessInfo> all = minecraft.isEmpty()
                            ? ProcessScanner.javaProcesses() : minecraft;
                    SwingUtilities.invokeLater(new Runnable() {
                        @Override
                        public void run() {
                            int self = CurrentProcess.pid();
                            List<ProcessScanner.ProcessInfo> visible =
                                    new ArrayList<ProcessScanner.ProcessInfo>();
                            for (ProcessScanner.ProcessInfo info : all) {
                                if (info.pid != self) {
                                    visible.add(info);
                                }
                            }
                            model.set(visible);
                            refresh.setEnabled(true);
                            if (minecraft.isEmpty()) {
                                status.set(visible.isEmpty()
                                        ? "No JVM found \u2014 start Minecraft first"
                                        : "No Minecraft detected \u00b7 " + visible.size() + " other JVMs",
                                        Palette.ERR);
                                log("No Minecraft process detected.");
                            } else {
                                status.set(visible.size() + " Minecraft process(es)", Palette.OK);
                                log("Found " + visible.size() + " Minecraft process(es).");
                                table.setRowSelectionInterval(0, 0);
                            }
                        }
                    });
                }
            }, "noturne-scan").start();
        }

        private void injectSelected() {
            final int row = table.getSelectedRow();
            if (row < 0) {
                log("Select a process first.");
                return;
            }
            final ProcessScanner.ProcessInfo target = model.get(row);
            inject.setEnabled(false);
            status.set("Injecting\u2026", Palette.WARN);
            log("Injecting into pid " + target.pid + "\u2026");
            new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        File self = new File(URLDecoder.decode(
                                InjectorApp.class.getProtectionDomain().getCodeSource().getLocation().getPath(),
                                "UTF-8"));
                        Attacher.attach(target.pid, self, "");
                        SwingUtilities.invokeLater(new Runnable() {
                            @Override
                            public void run() {
                                status.set("Injected into " + target.pid, Palette.OK);
                                log("Injected successfully.");
                                inject.setEnabled(true);
                            }
                        });
                    } catch (final Throwable t) {
                        SwingUtilities.invokeLater(new Runnable() {
                            @Override
                            public void run() {
                                status.set("Injection failed", Palette.ERR);
                                log("Failed: " + describe(t));
                                inject.setEnabled(true);
                            }
                        });
                    }
                }
            }, "noturne-inject").start();
        }

        private static String describe(Throwable t) {
            Throwable cause = t;
            while (cause instanceof java.lang.reflect.InvocationTargetException && cause.getCause() != null) {
                cause = cause.getCause();
            }
            if (cause instanceof AttachException) {
                Throwable root = cause.getCause();
                if (root instanceof ClassNotFoundException) {
                    return "attach API unavailable \u2014 run the injector with a full JDK";
                }
                if (root != null) {
                    return root.toString();
                }
            }
            String message = cause.toString();
            Throwable nested = cause.getCause();
            return nested == null ? message : message + " \u2190 " + nested;
        }

        private void log(String message) {
            String stamp = new SimpleDateFormat("HH:mm:ss").format(new Date());
            log.append("[" + stamp + "]  " + message + "\n");
            log.setCaretPosition(log.getDocument().getLength());
        }

        // ------------------------------------------------------------ title bar

        final class TitleBar extends JPanel {
            private Point dragOrigin;

            TitleBar() {
                setOpaque(false);
                setLayout(new BorderLayout());
                setBorder(BorderFactory.createEmptyBorder(14, 20, 8, 14));
                setPreferredSize(new Dimension(0, 52));

                JLabel mark = new JLabel("noturne");
                mark.setFont(ui(Font.BOLD, 12.5f));
                mark.setForeground(Palette.TEXT_FAINT);
                add(mark, BorderLayout.WEST);

                JPanel controls = new JPanel();
                controls.setOpaque(false);
                controls.setLayout(new BoxLayout(controls, BoxLayout.X_AXIS));
                controls.add(new WindowButton(WindowButton.Kind.MINIMIZE, InjectorFrame.this));
                controls.add(Box.createHorizontalStrut(8));
                controls.add(new WindowButton(WindowButton.Kind.CLOSE, InjectorFrame.this));
                add(controls, BorderLayout.EAST);

                // Dragging must move the *window*. On a JPanel, setLocation/getX address the panel
                // inside its parent — which is why the title bar used to slide out of the frame.
                addMouseListener(new MouseAdapter() {
                    @Override
                    public void mousePressed(MouseEvent e) {
                        dragOrigin = e.getPoint();
                    }
                });
                addMouseMotionListener(new MouseMotionAdapter() {
                    @Override
                    public void mouseDragged(MouseEvent e) {
                        if (dragOrigin == null) {
                            return;
                        }
                        Point onScreen = e.getLocationOnScreen();
                        InjectorFrame.this.setLocation(onScreen.x - dragOrigin.x,
                                onScreen.y - dragOrigin.y);
                    }
                });
            }
        }

        static final class WindowButton extends JButton {
            enum Kind { MINIMIZE, CLOSE }

            private final Kind kind;
            private boolean hovered;

            WindowButton(Kind kind, JFrame frame) {
                this.kind = kind;
                setContentAreaFilled(false);
                setBorderPainted(false);
                setFocusPainted(false);
                setOpaque(false);
                setPreferredSize(new Dimension(26, 26));
                setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                addActionListener(e -> {
                    if (kind == Kind.CLOSE) {
                        System.exit(0);
                    } else {
                        frame.setState(JFrame.ICONIFIED);
                    }
                });
                addMouseListener(new MouseAdapter() {
                    @Override
                    public void mouseEntered(MouseEvent e) {
                        hovered = true;
                        repaint();
                    }

                    @Override
                    public void mouseExited(MouseEvent e) {
                        hovered = false;
                        repaint();
                    }
                });
            }

            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                if (hovered) {
                    g2.setColor(kind == Kind.CLOSE ? new Color(0xE8, 0x6A, 0x62, 0x55) : Palette.STROKE);
                    g2.fillRoundRect(0, 0, getWidth(), getHeight(), 8, 8);
                }
                g2.setColor(hovered && kind == Kind.CLOSE ? Color.WHITE : Palette.TEXT_MUTED);
                g2.setStroke(new BasicStroke(1.4f));
                int cx = getWidth() / 2;
                int cy = getHeight() / 2;
                if (kind == Kind.CLOSE) {
                    g2.drawLine(cx - 5, cy - 5, cx + 5, cy + 5);
                    g2.drawLine(cx + 5, cy - 5, cx - 5, cy + 5);
                } else {
                    g2.drawLine(cx - 5, cy + 3, cx + 5, cy + 3);
                }
                g2.dispose();
            }
        }
    }

    // ================================================================ widgets

    /** Filled rounded container. */
    static final class RoundedPanel extends JPanel {
        private final int radius;
        private final Color fill;

        RoundedPanel(int radius, Color fill) {
            this(radius, fill, null);
        }

        RoundedPanel(int radius, Color fill, LayoutManager layout) {
            super(layout);
            this.radius = radius;
            this.fill = fill;
            setOpaque(false);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(fill);
            g2.fillRoundRect(0, 0, getWidth(), getHeight(), radius, radius);
            g2.setColor(Palette.STROKE_SOFT);
            g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, radius, radius);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /** Pill-shaped status indicator with a coloured dot. */
    static final class StatusBadge extends JLabel {
        private Color dot = Palette.TEXT_MUTED;

        StatusBadge() {
            setFont(ui(Font.PLAIN, 12.5f));
            setForeground(Palette.TEXT_MUTED);
            setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 0));
            setText("Ready");
        }

        void set(String text, Color dotColor) {
            this.dot = dotColor;
            setText(text);
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int cy = getHeight() / 2;
            g2.setColor(new Color(dot.getRed(), dot.getGreen(), dot.getBlue(), 60));
            g2.fillOval(0, cy - 6, 12, 12);
            g2.setColor(dot);
            g2.fillOval(3, cy - 3, 6, 6);
            g2.dispose();
            super.paintComponent(g);
        }

        @Override
        public Dimension getPreferredSize() {
            Dimension size = super.getPreferredSize();
            return new Dimension(size.width + 18, Math.max(22, size.height));
        }
    }

    static final class AccentButton extends JButton {
        private final Color base;
        private final boolean primary;
        private boolean hovered;

        AccentButton(String text, Color base, boolean primary) {
            super(text);
            this.base = base;
            this.primary = primary;
            setContentAreaFilled(false);
            setFocusPainted(false);
            setBorderPainted(false);
            setOpaque(false);
            setForeground(primary ? Color.WHITE : Palette.TEXT);
            setFont(ui(Font.BOLD, 13.5f));
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setPreferredSize(new Dimension(primary ? 132 : 118, 40));
            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    hovered = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hovered = false;
                    repaint();
                }
            });
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            if (primary) {
                Color top = !isEnabled() ? new Color(0x33, 0x33, 0x3D)
                        : (hovered ? Palette.ACCENT_HOVER : Palette.ACCENT);
                g2.setPaint(new java.awt.GradientPaint(0, 0, top, 0, getHeight(),
                        top.darker()));
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 11, 11);
            } else {
                g2.setColor(hovered ? Palette.ROW_HOVER : Palette.CARD);
                g2.fillRoundRect(0, 0, getWidth(), getHeight(), 11, 11);
                g2.setColor(Palette.STROKE);
                g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 11, 11);
            }
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /** Slim, arrow-less scrollbar. */
    static final class ThinScrollBarUI extends BasicScrollBarUI {
        @Override
        protected void configureScrollBarColors() {
            thumbColor = new Color(0xFF, 0xFF, 0xFF, 0x22);
            trackColor = new Color(0, 0, 0, 0);
        }

        @Override
        protected JButton createDecreaseButton(int orientation) {
            return invisibleButton();
        }

        @Override
        protected JButton createIncreaseButton(int orientation) {
            return invisibleButton();
        }

        private static JButton invisibleButton() {
            JButton button = new JButton();
            button.setPreferredSize(new Dimension(0, 0));
            button.setMinimumSize(new Dimension(0, 0));
            button.setMaximumSize(new Dimension(0, 0));
            return button;
        }

        @Override
        protected void paintThumb(Graphics g, JComponent c, java.awt.Rectangle bounds) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(thumbColor);
            g2.fillRoundRect(bounds.x + 2, bounds.y + 2, bounds.width - 4, bounds.height - 4, 6, 6);
            g2.dispose();
        }

        @Override
        protected void paintTrack(Graphics g, JComponent c, java.awt.Rectangle bounds) {
            // deliberately empty
        }
    }

    /** Table header with the column captions the design calls for. */
    static final class HeaderRenderer extends DefaultTableCellRenderer {
        HeaderRenderer() {
            setOpaque(false);
        }

        @Override
        public Component getTableCellRendererComponent(JTable table, Object value, boolean selected,
                                                       boolean focused, int row, int column) {
            JLabel label = (JLabel) super.getTableCellRendererComponent(
                    table, column == 0 ? "PID" : "Process", selected, focused, row, column);
            label.setFont(ui(Font.BOLD, 11.5f));
            label.setForeground(Palette.TEXT_FAINT);
            label.setBorder(BorderFactory.createEmptyBorder(0, 16, 0, 16));
            return label;
        }
    }

    /** Row renderer: accent bar + hover/selection highlight, no grid lines. */
    static final class CellRenderer extends DefaultTableCellRenderer {
        private final JTable table;

        CellRenderer(JTable table) {
            this.table = table;
            setOpaque(false);
        }

        @Override
        public Component getTableCellRendererComponent(JTable t, Object value, boolean selected,
                                                       boolean focused, int row, int column) {
            JLabel label = (JLabel) super.getTableCellRendererComponent(t, value, false, false, row, column);
            label.setOpaque(false);
            label.setFont(column == 0 ? ui(Font.PLAIN, 13f) : ui(Font.PLAIN, 13.5f));
            boolean isSelected = table.isRowSelected(row);
            label.setForeground(column == 0
                    ? (isSelected ? Palette.ACCENT_HOVER : Palette.TEXT_FAINT)
                    : (isSelected ? Palette.TEXT : Palette.TEXT_MUTED));
            label.setBorder(BorderFactory.createEmptyBorder(0, 16, 0, 16));
            return label;
        }
    }

    static final class ProcessTableModel extends AbstractTableModel {
        private final String[] columns = {"PID", "Process"};
        private List<ProcessScanner.ProcessInfo> rows = new ArrayList<ProcessScanner.ProcessInfo>();

        void set(List<ProcessScanner.ProcessInfo> newRows) {
            this.rows = new ArrayList<ProcessScanner.ProcessInfo>(newRows);
            fireTableDataChanged();
        }

        ProcessScanner.ProcessInfo get(int index) {
            return rows.get(index);
        }

        @Override
        public int getRowCount() {
            return rows.size();
        }

        @Override
        public int getColumnCount() {
            return columns.length;
        }

        @Override
        public String getColumnName(int column) {
            return columns[column];
        }

        @Override
        public Object getValueAt(int rowIndex, int columnIndex) {
            ProcessScanner.ProcessInfo info = rows.get(rowIndex);
            return columnIndex == 0 ? Integer.toString(info.pid) : info.display();
        }
    }
}
