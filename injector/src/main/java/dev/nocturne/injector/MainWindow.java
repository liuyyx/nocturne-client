package dev.nocturne.injector;

import dev.nocturne.core.attach.AttachException;
import dev.nocturne.core.attach.Attacher;
import dev.nocturne.core.attach.CurrentProcess;
import dev.nocturne.core.attach.ProcessScanner;
import dev.nocturne.core.attach.TargetHints;
import net.miginfocom.swing.MigLayout;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Rectangle;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

/**
 * 主窗口：单栏指挥台构图——工具栏、全宽进程列表、操作坞、日志条，四段共用边缘。
 *
 * <p>视觉为「夜航信标」：暖石墨底盘 + 钠灯琥珀强调。刻意不用任何内嵌卡片：窗口底色就是唯一的
 * 面板色，进程表格直接坐在上面成为视觉主角，分段只靠 1px 发丝线。所有布局都用 MigLayout；
 * 所有尺寸都由 {@link WindowGeometry} 以「逻辑像素」推导。扫描与注入都跑在 {@link SwingWorker}
 * 上，因此整个过程界面保持可响应。
 *
 * <p>状态机：「扫描游戏」在扫描或注入进行中禁用；「注入」需要「有扫描结果」且「有选中行」且
 * 「既不在扫描也不在注入中」才可用，由 {@link #updateButtons()} 统一推导。列表区域在
 * {@link CardLayout} 上于空态（{@link #CARD_EMPTY}）、骨架屏（{@link #CARD_SKELETON}）与
 * 表格（{@link #CARD_TABLE}）之间切换。
 */
public final class MainWindow extends JFrame {


    /** 显示在标题栏与版本章的版本号。 */
    public static final String VERSION = "v0.1.0";


    /** {@link CardLayout} 中骨架屏卡片的名称。 */
    private static final String CARD_SKELETON = "skeleton";

    /** {@link CardLayout} 中进程表格卡片的名称。 */
    private static final String CARD_TABLE = "table";

    /** {@link CardLayout} 中空状态卡片的名称（未扫描/扫描无结果）。 */
    private static final String CARD_EMPTY = "empty";

    /** 注入成功后自动最小化窗口的延迟（毫秒），给游戏留出切前台的时间。 */
    private static final int AUTO_MINIMIZE_DELAY_MS = 5000;


    /** 用户偏好；设置对话框直接原地修改它，关闭时落盘。 */
    private final AppConfig config;

    /** 底部日志条，仅追加。 */
    private final LogPane log = new LogPane();

    /** 进程表格的数据模型，持有当前扫描结果。 */
    private final ProcessTableModel model = new ProcessTableModel();

    /** 进程表格本体，整个窗口的视觉主角。 */
    private final JTable table = new JTable(model);

    /** 列表区域的卡片布局，用于在空态/骨架屏/表格之间切换。 */
    private final CardLayout listCards = new CardLayout();

    /** 承载上面三个卡片的容器；直接坐在窗口底色上，无内嵌卡片。 */
    private final JPanel listArea = new JPanel(listCards);

    /** 「扫描游戏」按钮（操作坞）。 */
    private final JButton scanButton = new JButton("扫描游戏");

    /** 「注入」按钮，初始禁用。 */
    private final JButton injectButton = new JButton("注入");

    /** 空状态里的主操作按钮：零结果/首次进入时把下一步动作直接摆到眼前。 */
    private final JButton emptyScanButton = new JButton("扫描游戏");

    /** 操作坞左侧的错误提示行；用单个空格占位，避免切换文字时布局跳动。 */
    private final JLabel errorLabel = new JLabel(" ");

    /** 工具栏右侧的状态指示点。 */
    private final StatusDot statusDot = new StatusDot();

    /** 工具栏里状态点旁的文字说明（空闲/扫描中…/注入成功 等）。 */
    private final JLabel statusText = new JLabel("空闲");

    /** 列表标题行右侧的结果计数（「3 个进程」），无结果时为空。 */
    private final JLabel countLabel = new JLabel(" ");

    /** 空状态的主标题。 */
    private final JLabel emptyTitle = new JLabel("尚未扫描");

    /** 空状态的提示行。 */
    private final JLabel emptyHint = new JLabel("点击「扫描游戏」查找正在运行的 Minecraft");

    /** 扫描/注入进行中的不定态进度条；全宽贴在工具栏下沿，空闲时收起高度。 */
    private final javax.swing.JProgressBar progress = new javax.swing.JProgressBar();

    /** 鼠标当前悬停的表格行；{@code -1} 表示没有。只影响绘制，不影响选中态。 */
    private int hoveredRow = -1;

    /** 是否正在扫描；扫描期间注入按钮必须禁用，避免对陈旧行/并发 attach 操作。 */
    private boolean scanning;
    /** 是否正在注入；注入期间禁用重扫，避免重复/并发 attach。 */
    private boolean injecting;
    /** 注入成功后的自动最小化定时器；用户一旦有新操作就取消，避免最小化正在用的窗口。 */
    private Timer autoMinimizeTimer;

    /**
     * 构建主窗口。
     *
     * <p>必须在 EDT 上调用（所有组件都在构造期创建）。构造函数只负责装配与初始布局，
     * 不会做任何 I/O。
     *
     * @param config 已加载的用户偏好，本窗口持有同一实例供设置对话框修改
     */
    public MainWindow(AppConfig config) {
        super("Nocturne \u00b7 诺克特恩");
        this.config = config;
        // 退出策略用 EXIT_ON_CLOSE：没有托盘图标，关闭即结束进程。
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        // 尺寸与位置由逻辑分辨率推导，跨缩放比例表现一致。
        setBounds(WindowGeometry.initialBounds());
        setMinimumSize(new Dimension(640, 460));

        // 单栏四段：工具栏 / 进度条 / 列表 / 操作坞 / 日志条，全部共用窗口边缘。
        // gap 0 0：段与段之间不允许出现默认间隙，发丝线必须互相贴合。
        setLayout(new MigLayout("insets 0, fill, wrap 1, gap 0 0",
                "[grow,fill]", "[56!][3!][grow,fill][64!][118!]"));
        add(buildToolbar(), "grow");
        add(buildProgressTrack(), "grow");
        add(listArea, "grow");
        add(buildDock(), "grow");
        add(log, "grow");

        // 添加顺序即默认卡片顺序；实际显示哪个由 listCards.show 决定。
        listArea.setOpaque(false);
        listArea.add(buildEmptyState(), CARD_EMPTY);
        listArea.add(buildTableCard(), CARD_TABLE);
        listArea.add(buildSkeletonCard(), CARD_SKELETON);

        // 初始进入空状态而不是空表格：零行表格会让用户误以为程序坏了。
        listCards.show(listArea, CARD_EMPTY);
        setStatus(AppTheme.TEXT_MUTED, "空闲");

        log.info("Nocturne " + VERSION + " 就绪");

        // 用户一旦重新激活窗口（开始操作），就取消待执行的自动最小化。
        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowActivated(java.awt.event.WindowEvent e) {
                cancelAutoMinimize();
            }
        });
        // 初始状态：无选中行、无结果，注入按钮禁用。
        updateButtons();

        // 这里只打日志，方便把「逻辑分辨率 vs 窗口尺寸 vs 配置缩放」写进控制台便于排查。
        System.out.println("[nocturne] logical screen=" + WindowGeometry.activeScreenSize()
                + " window=" + getBounds().width + "x" + getBounds().height
                + " configuredZoom=" + config.uiScale + "%");
    }

    // ------------------------------------------------------------------ toolbar

    /**
     * 构建进度条轨道：恒定 3px 高的空槽，进度条藏在槽内。
     *
     * <p>必须保持轨道常驻可见而不是隐藏进度条本身：MigLayout 对 hidemode 隐藏的组件会连同其行
     * 约束一起从网格里抽掉，后续组件顺次吃掉前面的行规格（列表吃掉进度行的 3px、操作坞吃掉
     * grow 行），整版布局随即错位。常驻轨道让「可见组件数 == 行规格数」恒成立。
     */
    private JPanel buildProgressTrack() {
        JPanel track = new JPanel(new BorderLayout());
        track.setOpaque(false);
        progress.setIndeterminate(true);
        progress.setVisible(false);
        progress.setBorder(BorderFactory.createEmptyBorder());
        track.add(progress, BorderLayout.CENTER);
        return track;
    }

    /**
     * 构建顶部工具栏：左侧品牌（新月 + 字标 + 版本章），右侧状态读数与设置。
     *
     * <p>底缘一条发丝线把四段中的第一段剥出来。
     */
    private JPanel buildToolbar() {
        JPanel bar = new JPanel(new MigLayout(
                "insets 0 " + AppTheme.SPACE_XL + " 0 " + AppTheme.SPACE_XL + ", fillx",
                "[]10[]12[]push[]8[]12[]", "[center]"));
        bar.setBackground(AppTheme.BACKGROUND);
        bar.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, AppTheme.BORDER_SOFT));

        bar.add(new CrescentMark(), "h 20!, w 20!");
        JLabel title = new JLabel("Nocturne");
        AppTheme.bindFont(title, Font.BOLD, AppTheme.TYPE_HERO);
        title.setForeground(AppTheme.TEXT);
        bar.add(title);

        JPanel chip = new JPanel(new MigLayout("insets 3 10 3 10", "[]", "[]"));
        AppTheme.styleChip(chip);
        JLabel chipText = new JLabel(VERSION);
        AppTheme.bindFont(chipText, Font.BOLD, AppTheme.TYPE_CAPTION);
        chipText.setForeground(AppTheme.ACCENT);
        chip.add(chipText);
        bar.add(chip);

        AppTheme.bindFont(statusText, Font.PLAIN, AppTheme.TYPE_CAPTION);
        statusText.setForeground(AppTheme.TEXT_MUTED);
        bar.add(statusText);

        bar.add(statusDot, "h 14!, w 14!");

        JButton settings = new JButton("设置");
        settings.addActionListener(e -> openSettings());
        AppTheme.styleSecondaryButton(settings);
        bar.add(settings, "h 30!, w 76!");
        return bar;
    }

    /**
     * 品牌标记：一弯钠灯琥珀色的新月。
     *
     * <p>先画正圆，再用工具栏底色切掉右上一块——切口的圆心与外圆偏移量决定月牙的粗细。
     * 「夜曲」的「夜」收在这一弯月里。
     */
    private static final class CrescentMark extends javax.swing.JComponent {
        /** 绘制新月。 */
        @Override
        protected void paintComponent(java.awt.Graphics g) {
            java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
            g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                    java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            int size = Math.min(getWidth(), getHeight());
            g2.setColor(AppTheme.ACCENT);
            g2.fillOval(0, 0, size, size);
            // 切口圆略大于外圆、向右上偏移：月牙下缘厚、上缘尖。
            g2.setColor(AppTheme.BACKGROUND);
            g2.fillOval(size * 9 / 16, -size / 6, size + 2, size + 2);
            g2.dispose();
        }
    }

    // ------------------------------------------------------------------ list

    /**
     * 构建进程表格段：列表标题行 + 全宽表格，直接坐在窗口底色上。
     *
     * <p>没有内嵌卡片：表格区与窗口融为一体，行与行之间靠渲染器的发丝线分隔。
     */
    private JPanel buildTableCard() {
        JPanel card = new JPanel(new MigLayout(
                "insets 0 " + AppTheme.SPACE_XL + " 0 " + AppTheme.SPACE_XL + ", fill, wrap 1",
                "[grow,fill]", "[44!]0[grow,fill]"));
        card.setOpaque(false);

        JPanel header = new JPanel(new MigLayout("insets 0 4 0 4, fillx", "[]push[]", "[]"));
        header.setOpaque(false);
        JLabel heading = new JLabel("进程列表");
        AppTheme.bindFont(heading, Font.BOLD, AppTheme.TYPE_TITLE);
        heading.setForeground(AppTheme.TEXT);
        AppTheme.bindFont(countLabel, Font.PLAIN, AppTheme.TYPE_CAPTION);
        countLabel.setForeground(AppTheme.TEXT_FAINT);
        header.add(heading);
        header.add(countLabel, "aligny center");

        card.add(header, "growx");
        card.add(buildTable(), "grow");
        return card;
    }

    /**
     * 构建进程表格本体。
     *
     * <p>只被 {@link #buildTableCard()} 调用；列宽偏好值是
     * {@link JTable#AUTO_RESIZE_ALL_COLUMNS} 的初始分配比例。
     */
    private JScrollPane buildTable() {
        table.setRowHeight(40);
        table.setFillsViewportHeight(true);
        // 网格线整体关掉：水平分隔交给渲染器的发丝线，垂直方向只靠留白。
        table.setShowVerticalLines(false);
        table.setShowHorizontalLines(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        table.setBorder(BorderFactory.createEmptyBorder());
        AppTheme.bindFont(table, Font.PLAIN, AppTheme.TYPE_BODY);
        table.setSelectionBackground(AppTheme.ACCENT_SOFT);
        table.setSelectionForeground(AppTheme.TEXT);
        table.setDefaultRenderer(Object.class, new RowRenderer());
        table.getTableHeader().setReorderingAllowed(false);
        AppTheme.bindFont(table.getTableHeader(), Font.PLAIN, AppTheme.TYPE_CAPTION);
        // 表头与窗口同色：栏目名只是列表的注脚，不是另一块面板。
        table.getTableHeader().setBackground(AppTheme.BACKGROUND);
        table.getTableHeader().setForeground(AppTheme.TEXT_MUTED);
        table.getTableHeader().setPreferredSize(new Dimension(0, 36));
        // 表头左对齐，内垫与单元格文字对齐（FlatLaf 默认居中）。
        javax.swing.table.DefaultTableCellRenderer headerRenderer =
                (javax.swing.table.DefaultTableCellRenderer) table.getTableHeader().getDefaultRenderer();
        headerRenderer.setHorizontalAlignment(javax.swing.SwingConstants.LEFT);
        headerRenderer.setBorder(BorderFactory.createEmptyBorder(0, 11, 0, 0));
        table.getColumnModel().getColumn(0).setPreferredWidth(320);
        table.getColumnModel().getColumn(1).setPreferredWidth(80);
        table.getColumnModel().getColumn(2).setPreferredWidth(120);
        table.getSelectionModel().addListSelectionListener(e -> {
            // 只在拖动结束时响应：拖动过程中会连续触发，频繁刷新按钮状态没有意义。
            if (!e.getValueIsAdjusting()) {
                updateInjectButton();
            }
        });
        // 悬停行高亮：只记录行号并触发重绘，真正的着色在行渲染器里完成。
        table.addMouseMotionListener(new java.awt.event.MouseMotionAdapter() {
            @Override
            public void mouseMoved(java.awt.event.MouseEvent e) {
                int row = table.rowAtPoint(e.getPoint());
                if (row != hoveredRow) {
                    hoveredRow = row;
                    table.repaint();
                }
            }
        });
        table.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseExited(java.awt.event.MouseEvent e) {
                hoveredRow = -1;
                table.repaint();
            }
        });

        JScrollPane scroll = new JScrollPane(table);
        scroll.setOpaque(false);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setOpaque(false);
        table.setOpaque(false);
        return scroll;
    }

    /**
     * 构建空态：雷达标记 + 标题 + 提示 + 覆盖范围 + 主按钮，整体垂直居中。
     *
     * <p>空态同样不套卡片：提示文字直接坐在窗口底色上，由上下两段弹性空白推到视口正中。
     */
    private JPanel buildEmptyState() {
        JPanel empty = new JPanel(new MigLayout(
                "insets 0 " + AppTheme.SPACE_XL + " 0 " + AppTheme.SPACE_XL
                        + ", fill, wrap 1", "[center]", "[push][]14[]6[]10[][push]"));
        empty.setOpaque(false);

        AppTheme.bindFont(emptyTitle, Font.BOLD, AppTheme.TYPE_TITLE);
        emptyTitle.setForeground(AppTheme.TEXT);
        AppTheme.bindFont(emptyHint, Font.PLAIN, AppTheme.TYPE_CAPTION);
        emptyHint.setForeground(AppTheme.TEXT_FAINT);

        JLabel coverage = new JLabel("支持 1.8.9 \u2013 26.3 \u00b7 66 个版本");
        AppTheme.bindFont(coverage, Font.PLAIN, AppTheme.TYPE_CAPTION);
        coverage.setForeground(AppTheme.TEXT_FAINT);

        emptyScanButton.addActionListener(e -> startScan());
        AppTheme.stylePrimaryButton(emptyScanButton);

        empty.add(new RadarMark(), "h 48!, w 48!");
        empty.add(emptyTitle);
        empty.add(emptyHint);
        empty.add(coverage);
        empty.add(emptyScanButton, "h 36!, w 136!");
        return empty;
    }

    /**
     * 构建骨架屏卡片：扫描进行中替代列表的三条呼吸条，同样无卡片。
     */
    private JPanel buildSkeletonCard() {
        JPanel card = new JPanel(new MigLayout(
                "insets " + AppTheme.SPACE_XL + " " + AppTheme.SPACE_XL + " 0 " + AppTheme.SPACE_XL
                        + ", fillx, wrap 1", "[grow,fill]", "[]20[]20[]"));
        card.setOpaque(false);
        card.add(new SkeletonPanel(), "growx");
        return card;
    }

    /**
     * 空状态/骨架屏的雷达标记：双环 + 60° 扫描扇面 + 中心信标点。
     *
     * <p>不带动画：空态出现的时间很短，动效反而显得焦躁；琥珀信标点与全局状态点呼应。
     */
    private static final class RadarMark extends javax.swing.JComponent {
        /** 固定 48×48，让扇面与圆环的相对比例不受布局压缩影响。 */
        private RadarMark() {
            Dimension size = new Dimension(48, 48);
            setPreferredSize(size);
            setMinimumSize(size);
        }

        /** 绘制圆环、扫描扇面与中心点。 */
        @Override
        protected void paintComponent(java.awt.Graphics g) {
            java.awt.Graphics2D g2 = (java.awt.Graphics2D) g.create();
            g2.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
                    java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
            int size = Math.min(getWidth(), getHeight());
            int x = (getWidth() - size) / 2;
            int y = (getHeight() - size) / 2;
            // 扫描扇面：低透明度琥珀，压在圆环下层。
            g2.setColor(new Color(AppTheme.ACCENT.getRed(), AppTheme.ACCENT.getGreen(),
                    AppTheme.ACCENT.getBlue(), 34));
            g2.fillArc(x, y, size, size, 30, 60);
            // 外环用常规描边色，内环用更暗的发丝线，形成远近两层。
            g2.setColor(AppTheme.BORDER);
            g2.drawOval(x, y, size - 1, size - 1);
            int inset = size / 4;
            g2.setColor(AppTheme.BORDER_SOFT);
            g2.drawOval(x + inset, y + inset, size - inset * 2 - 1, size - inset * 2 - 1);
            // 中心信标点：唯一的实心琥珀。
            int dot = Math.max(5, size / 7);
            g2.setColor(AppTheme.ACCENT);
            g2.fillOval(x + (size - dot) / 2, y + (size - dot) / 2, dot, dot);
            g2.dispose();
        }
    }

    /**
     * 行渲染器：按列分配字体层级与语义色，并负责选中/悬停背景、选中强调条与行底发丝线。
     *
     * <p>内部类，直接访问外层的 {@link AppTheme} 常量、{@code table} 与 {@code hoveredRow}。
     */
    private final class RowRenderer extends DefaultTableCellRenderer {
        /**
         * 渲染单个单元格。
         *
         * @param selected 该行是否被选中，决定强调色竖条与选中底色
         * @param focused  固定传 {@code false}：单元格自己画焦点态，避免与行选中态打架
         * @param column   列索引：进程名用正文样式，PID/版本用等宽弱化，版本未知时用占位样式
         * @return 已配置好的 {@link JLabel}
         */
        @Override
        public Component getTableCellRendererComponent(JTable source, Object value, boolean selected,
                                                       boolean focused, int row, int column) {
            String text = String.valueOf(value);
            // 版本未知时显示可读占位而不是一个孤零零的破折号。
            boolean unknownVersion = column == 2 && "\u2014".equals(text);
            if (unknownVersion) {
                text = "未知";
            }
            JLabel label = (JLabel) super.getTableCellRendererComponent(
                    source, text, selected, false, row, column);
            if (column == 1 || column == 2) {
                // PID 与版本是数据列：等宽字体让上下行按列对齐，读数感更强。
                label.setFont(new Font(Font.MONOSPACED, Font.PLAIN,
                        Math.max(1, Math.round(AppTheme.baseFont().getSize() * AppTheme.TYPE_MONO))));
            } else {
                label.setFont(AppTheme.scaled(Font.PLAIN, AppTheme.TYPE_BODY));
            }
            // 选中指示条只画在**进程列**的左边缘，作为"整行选中"的视觉锚点。
            // MatteBorder 四个方向都会上色，内垫必须放在内层 EmptyBorder，否则列内缘会多出一条琥珀边。
            boolean indicator = selected && column == 0;
            label.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 1, 0, AppTheme.BORDER_SOFT),
                    indicator
                            ? BorderFactory.createCompoundBorder(
                                    BorderFactory.createMatteBorder(0, 2, 0, 0, AppTheme.ACCENT),
                                    BorderFactory.createEmptyBorder(0, 8, 0, 8))
                            : BorderFactory.createEmptyBorder(0, 10, 0, 8)));
            if (unknownVersion) {
                label.setForeground(AppTheme.TEXT_FAINT);
            } else if (column == 1) {
                label.setForeground(AppTheme.TEXT_MUTED);
            } else if (column == 2) {
                // 识别到的版本是整行唯一「读数」：用琥珀点出，扫一眼就能核对目标版本。
                label.setForeground(AppTheme.ACCENT);
            } else {
                label.setForeground(AppTheme.TEXT);
            }
            label.setOpaque(true);
            // 未选中行就是窗口底色：列表与窗口融为一体，「色块」在这里必须无缝。
            label.setBackground(selected ? AppTheme.ACCENT_SOFT
                    : (row == hoveredRow ? AppTheme.PANEL_HOVER : AppTheme.BACKGROUND));
            return label;
        }
    }

    // ------------------------------------------------------------------ dock & log

    /**
     * 构建操作坞：左侧错误提示行，右侧扫描/注入按钮（次/主分明）。
     *
     * <p>顶缘一条发丝线把它从列表上剥出来；按钮垂直居中，整段高 64。
     */
    private JPanel buildDock() {
        JPanel dock = new JPanel(new MigLayout(
                "insets 0 " + AppTheme.SPACE_XL + " 0 " + AppTheme.SPACE_XL + ", fillx",
                "[grow,fill]12[]8[]", "[64!]"));
        dock.setOpaque(false);
        dock.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, AppTheme.BORDER_SOFT));

        errorLabel.setForeground(AppTheme.DANGER);
        AppTheme.bindFont(errorLabel, Font.PLAIN, AppTheme.TYPE_CAPTION);
        errorLabel.setText(" ");

        AppTheme.styleSecondaryButton(scanButton);
        AppTheme.stylePrimaryButton(injectButton);
        scanButton.addActionListener(e -> startScan());
        injectButton.addActionListener(e -> startInject());

        dock.add(errorLabel, "growx, aligny center");
        dock.add(scanButton, "h 38!, w 128!, aligny center");
        dock.add(injectButton, "h 38!, w 128!, aligny center");
        return dock;
    }

    // --------------------------------------------------------------- behaviour

    /**
     * 打开设置对话框。
     *
     * <p>{@code setVisible(true)} 会阻塞在模态对话框的模态循环里，因此此方法返回即代表用户已
     * 关闭对话框；缩放在回调里即时生效，落盘由对话框的 {@code dispose()} 完成。
     */
    private void openSettings() {
        // 用户开始操作了，取消待执行的自动最小化。
        cancelAutoMinimize();
        SettingsDialog dialog = new SettingsDialog(this, config, updated -> {
            AppTheme.setZoom(updated.uiScale / 100f);
            // 快捷键在 attach 时写死传给游戏，改完必须重新注入才生效——这里也留一行保存日志。
            log.info("设置已保存（界面缩放 " + updated.uiScale + "%）；快捷键改动需重新注入后生效");
        });
        dialog.setVisible(true);
    }

    /**
     * 同时更新状态点颜色与状态文字。
     *
     * <p>必须在 EDT 上调用。
     *
     * @param color 状态点颜色（取自 {@link AppTheme} 的语义色）
     * @param text  状态点旁的中文说明
     */
    private void setStatus(Color color, String text) {
        statusDot.setState(color);
        statusText.setText(text);
        statusText.setForeground(color);
    }

    /**
     * 依据「是否扫描/注入中、是否有结果、是否有选中行」推导两个按钮的可用状态。
     *
     * <p>把推导集中在一处，避免像旧实现那样把 {@code isEnabled()} 当成与条件——那样一旦置为
     * 禁用就再也无法恢复（例如注入成功后按钮永久锁死）。
     */
    private void updateButtons() {
        boolean idle = !scanning && !injecting;
        scanButton.setEnabled(idle);
        // 空态里的扫描按钮与操作坞的扫描按钮同生共死，避免一处可点一处灰着的割裂感。
        emptyScanButton.setEnabled(idle);
        // 进行中反馈：不定态进度条随扫描/注入显隐（布局用 hidemode 3 收掉隐藏时的高度）。
        progress.setVisible(scanning || injecting);
        updateInjectButton();
    }

    /** 单独刷新注入按钮：需要「有扫描结果 + 有选中行 + 不在扫描/注入中」。 */
    private void updateInjectButton() {
        injectButton.setEnabled(!scanning && !injecting
                && model.getRowCount() > 0 && table.getSelectedRow() >= 0);
    }

    /** 取消并清空注入成功后的自动最小化定时器。 */
    private void cancelAutoMinimize() {
        if (autoMinimizeTimer != null) {
            autoMinimizeTimer.stop();
            autoMinimizeTimer = null;
        }
    }

    /**
     * 扫描候选 JVM 进程并填充表格。
     *
     * <p>必须在 EDT 上调用（按钮监听器）。耗时部分放在 {@link SwingWorker#doInBackground()}，
     * UI 更新只在 {@code done()} 里做——{@code done()} 同样运行在 EDT 上。
     */
    private void startScan() {
        cancelAutoMinimize();
        errorLabel.setText(" ");
        scanning = true;
        // 立刻清空上一轮结果：扫描失败时不能留下过期行供误注入。
        model.clear();
        countLabel.setText(" ");
        updateButtons();
        setStatus(AppTheme.INFO, "扫描中…");
        // 先切到骨架屏，让用户立刻看到「在忙」而不是一个空列表。
        listCards.show(listArea, CARD_SKELETON);

        new SwingWorker<List<ProcessTableModel.Row>, Void>() {
            @Override
            protected List<ProcessTableModel.Row> doInBackground() {
                List<ProcessScanner.ProcessInfo> candidates = ProcessScanner.minecraftProcesses();
                if (candidates.isEmpty()) {
                    // 退化路径：宁可多列几个候选，也不让用户以为没找到游戏。
                    candidates = ProcessScanner.javaProcesses();
                }
                int self = CurrentProcess.pid();
                // 排除自身：把自己注进去没有任何意义，而且容易误点。
                List<ProcessTableModel.Row> rows = new ArrayList<ProcessTableModel.Row>();
                for (ProcessScanner.ProcessInfo info : candidates) {
                    if (info.pid != self) {
                        rows.add(new ProcessTableModel.Row(info.pid, info.displayName(), "\u2014"));
                    }
                }
                return rows;
            }

            /** 回到 EDT：无论是正常完成还是抛异常，都必须恢复按钮与卡片的可用状态。 */
            @Override
            protected void done() {
                scanning = false;
                try {
                    List<ProcessTableModel.Row> rows = get();
                    model.setRows(rows);
                    if (rows.isEmpty()) {
                        // 无结果时给可读空状态，而不是一张零行的表格。
                        emptyTitle.setText("未发现 Minecraft 进程");
                        emptyHint.setText("启动游戏后重新扫描，或确认游戏正在运行");
                        listCards.show(listArea, CARD_EMPTY);
                        countLabel.setText(" ");
                        setStatus(AppTheme.DANGER, "未找到进程");
                        log.info("未找到 Minecraft 进程");
                    } else {
                        listCards.show(listArea, CARD_TABLE);
                        countLabel.setText(rows.size() + " 个进程");
                        setStatus(AppTheme.SUCCESS, "发现 " + rows.size() + " 个进程");
                        log.info("发现 " + rows.size() + " 个进程");
                        table.setRowSelectionInterval(0, 0);
                        // 默认选中第一行：多数场景用户就是要注入第一个进程。
                        loadVersions();
                    }
                    // 扫过一次之后，空态里的主按钮就改称「重新扫描」——文案与后果一致。
                    emptyScanButton.setText("重新扫描");
                } catch (Exception e) {
                    // 失败时列表保持清空状态，不能展示上一轮的过期行。
                    model.clear();
                    listCards.show(listArea, CARD_TABLE);
                    countLabel.setText(" ");
                    setStatus(AppTheme.DANGER, "扫描失败");
                    log.error("扫描失败：" + firstLine(e));
                }
                updateButtons();
            }
        }.execute();
    }

    /**
     * 异步补齐版本号。
     *
     * <p>版本信息来自 WMI，速度很慢，所以等列表已经可见之后才去取。这里刻意<em>不</em>因为版本未就绪
     * 而禁用注入：按契约 K1，传给 agent 的键码只与录制的 AWT VK 有关、与目标版本无关，版本缺失不再
     * 影响正确性；而非 Windows 上 {@code javaProcessCommandLines()} 直接返回空 map，禁用会让注入永远
     * 不可用。{@code Row.version} 声明为 volatile 只为跨线程可见性的防御。
     */
    private void loadVersions() {
        new SwingWorker<Map<Integer, String>, Void>() {
            @Override
            protected Map<Integer, String> doInBackground() {
                // WMI 查询可能耗时数秒，绝不能在 EDT 上做。
                return ProcessScanner.javaProcessCommandLines();
            }

            @Override
            protected void done() {
                try {
                    Map<Integer, String> commandLines = get();
                    for (Integer pid : commandLines.keySet()) {
                        model.setVersion(pid, GameVersion.fromCommandLine(commandLines.get(pid)));
                    }
                } catch (Exception ignored) {
                    // 取不到就保持未知：列表本身已经可用，不值得为版本号中断流程。
                }
            }
        }.execute();
    }

    /**
     * 向选中的 JVM 注入 agent。
     *
     * <p>必须在 EDT 上调用。目标进程通过 {@code model.rowAt(table.getSelectedRow())} 在提交
     * 后台任务前解析成局部变量——表格选中行随时可能被用户改掉。
     */
    private void startInject() {
        int viewRow = table.getSelectedRow();
        // 表格将来若启用排序，视图行与模型行会错位，必须显式转换。
        ProcessTableModel.Row row = model.rowAt(
                viewRow >= 0 ? table.convertRowIndexToModel(viewRow) : viewRow);
        if (row == null) {
            errorLabel.setText("请先选择一个进程");
            return;
        }

        cancelAutoMinimize();
        injecting = true;
        updateButtons();
        injectButton.setText("注入中…");
        errorLabel.setText(" ");
        setStatus(AppTheme.INFO, "注入中…");
        // 记下实际换算出的键码：绑定不生效时，这行日志能立刻区分「传错了」还是「传对了但游戏侧没响应」。
        log.info("注入 → pid " + row.pid + "，版本 " + row.version + "，GUI 键 " + config.guiBind
                + "（VK " + KeyCodes.codeFor(config.guiBind) + "）");

        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                // 自身 jar 路径作为 agent jar。用 URL.toURI 而非 URLDecoder：后者会把路径里字面量的
                // '+' 解成空格，得到错误的 agent jar 路径。
                File self;
                try {
                    self = new File(MainWindow.class.getProtectionDomain()
                            .getCodeSource().getLocation().toURI());
                } catch (URISyntaxException malformed) {
                    throw new IllegalStateException("无法解析 agent jar 路径", malformed);
                }
                // guiKey 传 AWT VK 码；mcVersion 传目标版本族（运行时据此选映射表，零探测）。
                String options = KeyCodes.attachOptions(config.guiBind, row.version);
                // 已知目标客户端占用同一个 GUI 键时提前说明：否则按下去看到的是对方的面板，
                // 玩家会以为我们的注入没生效（FPSMaster 默认就是右 Shift）。
                String commandLine = ProcessScanner.javaProcessCommandLines()
                        .get(Integer.valueOf(row.pid));
                String keyHint = TargetHints.rightShiftConflict(commandLine,
                        KeyCodes.codeFor(config.guiBind));
                if (keyHint != null) {
                    log.info("按键提示：" + keyHint);
                }
                Attacher.attach(row.pid, self, options);
                return null;
            }

            /** 回到 EDT：恢复按钮文案，并按结果更新状态点、日志与错误行。 */
            @Override
            protected void done() {
                injecting = false;
                injectButton.setText("注入");
                updateButtons();
                try {
                    get();
                    setStatus(AppTheme.SUCCESS, "注入成功");
                    log.info("注入成功 → pid " + row.pid);
                    errorLabel.setText(" ");
                    // 延迟最小化：注入后 agent 可能还要几秒才真正生效。把定时器存下来以便取消。
                    cancelAutoMinimize();
                    autoMinimizeTimer = new Timer(AUTO_MINIMIZE_DELAY_MS, e -> setState(JFrame.ICONIFIED));
                    autoMinimizeTimer.setRepeats(false);
                    autoMinimizeTimer.start();
                } catch (Exception e) {
                    String reason = describeFailure(e);
                    setStatus(AppTheme.DANGER, "注入失败");
                    log.error("注入失败：" + reason);
                    errorLabel.setText(reason);
                }
            }
        }.execute();
    }

    /**
     * 把 attach 失败映射为一句用户可读的说明。
     *
     * <p>先剥掉 {@code SwingWorker.get()} 的 {@link ExecutionException}、反射包装
     * {@link InvocationTargetException} 与 {@link AttachException} 的内层异常，再对消息做关键字匹配；
     * 匹配不到就回落到截断后的原始消息。
     *
     * @param throwable {@code done()} 里 {@code get()} 抛出的异常
     * @return 中文原因说明，永不为 {@code null}
     */
    static String describeFailure(Throwable throwable) {
        Throwable cause = throwable;
        // SwingWorker.get() 抛的是 ExecutionException，真实原因在 cause 里；不剥掉的话所有友好
        // 映射都不可达，用户只会看到笼统的「游戏拒绝 attach」。
        while ((cause instanceof ExecutionException || cause instanceof InvocationTargetException)
                && cause.getCause() != null) {
            // AttachException 的 cause 才是底层原因（如 ClassNotFoundException）。
            cause = cause.getCause();
        }
        if (cause instanceof AttachException && cause.getCause() != null) {
            if (cause.getCause() instanceof ClassNotFoundException) {
                return "未找到 attach API（请用完整 JDK 启动）";
            }
            cause = cause.getCause();
        }
        // message 可能为 null，退回 toString() 至少还能给出异常类型。
        String message = String.valueOf(cause.getMessage() == null ? cause.toString() : cause.getMessage());
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        // 用 Locale.ROOT 统一小写：土耳其语等 locale 下 "I" 的小写形式会让匹配失效。
        if (lower.contains("no such process") || lower.contains("process not found")) {
            return "没找到 JVM（进程可能已退出）";
        }
        if (lower.contains("agent jar not found")) {
            // 路径本身要留着：它是判断「注入器与目标 JVM 是否看到同一个文件系统」的唯一线索。
            return "找不到 agent jar（该路径对目标 JVM 不可见）：" + firstLine(message);
        }
        if (lower.contains("unsupported") || lower.contains("class version")) {
            return "版本不支持";
        }
        // 「already」必须排在笼统的 agent/attach 分支之前：否则 "agent already loaded" 这类消息
        // 会先命中下面那条，永远走不到这里。
        if (lower.contains("already")) {
            return "该进程已经注入过";
        }
        if (lower.contains("agent") || lower.contains("attach")) {
            // 笼统的「拒绝」曾让排查绕远路：附上原始消息，至少能看出是哪个机制在拒绝。
            return "游戏拒绝 attach（" + firstLine(message) + "）";
        }
        return firstLine(message);
    }

    /** 取异常 {@code toString()} 的首行，用于日志输出。 */
    private static String firstLine(Throwable throwable) {
        return firstLine(String.valueOf(throwable));
    }

    /**
     * 取消息首行并截断到 160 字符。
     *
     * <p>异常栈信息往往带换行，日志行只取首行才不会撑破日志面板。
     */
    private static String firstLine(String message) {
        // 多行栈信息对用户没有价值，截断即可。
        int newline = message.indexOf('\n');
        String line = newline < 0 ? message : message.substring(0, newline);
        return line.length() > 160 ? line.substring(0, 159) + "\u2026" : line;
    }
}
