package dev.nocturne.injector;

import dev.nocturne.core.attach.AttachException;
import dev.nocturne.core.attach.Attacher;
import dev.nocturne.core.attach.CurrentProcess;
import dev.nocturne.core.attach.ProcessScanner;
import com.formdev.flatlaf.FlatClientProperties;
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
 * 主窗口：左侧边栏、进程列表、操作按钮与底部状态栏。
 *
 * <p>所有布局都用 MigLayout；所有尺寸都由 {@link WindowGeometry} 以「逻辑像素」推导。扫描与
 * 注入都跑在 {@link SwingWorker} 上，因此整个过程界面保持可响应。
 *
 * <p>状态机：{@code scanButton} 在扫描或注入进行中禁用；{@code injectButton} 需要「有扫描结果」且
 * 「有选中行」且「既不在扫描也不在注入中」才可用，由 {@link #updateButtons()} 统一推导。列表区域在
 * {@link CardLayout} 上于骨架屏（{@link #CARD_SKELETON}）与表格（{@link #CARD_TABLE}）之间切换。
 */
public final class MainWindow extends JFrame {


    /** 显示在标题栏与状态栏的版本号。 */
    public static final String VERSION = "v0.1.0";


    /** {@link CardLayout} 中骨架屏卡片的名称。 */
    private static final String CARD_SKELETON = "skeleton";

    /** {@link CardLayout} 中进程表格卡片的名称。 */
    private static final String CARD_TABLE = "table";

    /** 注入成功后自动最小化窗口的延迟（毫秒），给游戏留出切前台的时间。 */
    private static final int AUTO_MINIMIZE_DELAY_MS = 5000;


    /** 用户偏好；设置对话框直接原地修改它，关闭时落盘。 */
    private final AppConfig config;

    /** 底部日志面板，仅追加。 */
    private final LogPane log = new LogPane();

    /** 进程表格的数据模型，持有当前扫描结果。 */
    private final ProcessTableModel model = new ProcessTableModel();

    /** 进程表格本体。 */
    private final JTable table = new JTable(model);

    /** 列表区域的卡片布局，用于在骨架屏与表格之间切换。 */
    private final CardLayout listCards = new CardLayout();

    /** 承载上面两个卡片的容器。 */
    private final JPanel listArea = new JPanel(listCards);

    /** 「扫描游戏」按钮。 */
    private final JButton scanButton = new JButton("扫描游戏");

    /** 「注入」按钮，初始禁用。 */
    private final JButton injectButton = new JButton("注入");

    /** 错误提示行；用单个空格占位，避免切换文字时布局跳动。 */
    private final JLabel errorLabel = new JLabel(" ");

    /** 状态栏右侧的状态指示点。 */
    private final StatusDot statusDot = new StatusDot();

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
        setMinimumSize(new Dimension(640, 440));

        // 布局分两列两行：左列侧边栏 + 内容区，底部状态栏横跨两列。
        setLayout(new MigLayout("insets 0, fill", "[220!][grow,fill]", "[grow,fill][34!]"));
        add(buildSidebar(), "growy");
        add(buildContent(), "grow");
        add(buildStatusBar(), "newline, span 2, growx");

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

    // ------------------------------------------------------------------ sidebar

    /** 构建左侧边栏：品牌标题、副标题与设置按钮。 */
    private JPanel buildSidebar() {
        JPanel sidebar = new JPanel(new MigLayout("insets 20, fillx, wrap 1", "[grow,fill]", "[]6[]push[]"));
        sidebar.setBackground(AppTheme.PANEL);
        // 右侧一条 1px 竖线作为与内容区的分界，避免用额外面板撑开。
        sidebar.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, new Color(0x1E, 0x2B, 0x45)));

        JLabel title = new JLabel("Nocturne \u00b7 诺克特恩");
        AppTheme.bindFont(title, Font.BOLD, 1.25f);
        title.setForeground(AppTheme.TEXT);

        JLabel subtitle = new JLabel("<html>开源的多版本<br>注入式客户端</html>");
        AppTheme.bindFont(subtitle, Font.PLAIN, 0.85f);
        subtitle.setForeground(AppTheme.TEXT_MUTED);

        JButton settings = new JButton("设置");
        settings.addActionListener(e -> openSettings());

        // 按钮样式通过 FlatLaf 的客户端属性声明，而不是自绘。
        String buttonStyle = "arc: 8; focusWidth: 0";
        scanButton.putClientProperty(FlatClientProperties.STYLE, buttonStyle);
        injectButton.putClientProperty(FlatClientProperties.STYLE, buttonStyle + "; background: #3D7BFF; foreground: #FFFFFF");
        settings.putClientProperty(FlatClientProperties.STYLE, buttonStyle + "; borderWidth: 1; background: #111A2E");

        sidebar.add(title);
        sidebar.add(subtitle);
        sidebar.add(settings, "growx, h 34!");
        return sidebar;
    }

    // ------------------------------------------------------------------ content

    /**
     * 构建右侧内容区：上方是列表卡片（骨架屏/表格），下方是错误行 + 按钮 + 日志。
     *
     * @return 内容面板；只被构造函数调用一次
     */
    private JPanel buildContent() {
        JPanel content = new JPanel(new MigLayout("insets 18 18 14 18, fill", "[grow,fill]", "[grow,fill][pref!]"));
        content.setBackground(AppTheme.BACKGROUND);

        // 添加顺序即默认卡片顺序；实际显示哪个由 listCards.show 决定。
        listArea.setOpaque(false);
        listArea.add(buildTable(), CARD_TABLE);
        listArea.add(new SkeletonPanel(), CARD_SKELETON);

        content.add(listArea, "grow");
        content.add(buildBottom(), "newline, growx");
        return content;
    }

    /**
     * 构建进程表格及其外层圆角卡片。
     *
     * <p>只被 {@link #buildContent()} 调用；列宽偏好值是 {@link JTable#AUTO_RESIZE_ALL_COLUMNS}
     * 的初始分配比例。
     */
    private JPanel buildTable() {
        table.setRowHeight(30);
        table.setFillsViewportHeight(true);
        table.setShowVerticalLines(false);
        table.setShowHorizontalLines(true);
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        table.setBorder(BorderFactory.createEmptyBorder());
        AppTheme.bindFont(table, Font.PLAIN, 1.0f);
        table.setSelectionBackground(AppTheme.PANEL_HOVER);
        table.setSelectionForeground(AppTheme.TEXT);
        table.setDefaultRenderer(Object.class, new RowRenderer());
        table.getTableHeader().setReorderingAllowed(false);
        AppTheme.bindFont(table.getTableHeader(), Font.PLAIN, 0.9f);
        table.getTableHeader().setBackground(AppTheme.PANEL);
        table.getTableHeader().setForeground(AppTheme.TEXT_MUTED);
        table.getColumnModel().getColumn(0).setPreferredWidth(320);
        table.getColumnModel().getColumn(1).setPreferredWidth(80);
        table.getColumnModel().getColumn(2).setPreferredWidth(120);
        table.getSelectionModel().addListSelectionListener(e -> {
            // 只在拖动结束时响应：拖动过程中会连续触发，频繁刷新按钮状态没有意义。
            if (!e.getValueIsAdjusting()) {
                updateInjectButton();
            }
        });

        JScrollPane scroll = new JScrollPane(table);
        scroll.setOpaque(false);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setOpaque(false);
        table.setOpaque(false);

        // 圆角画在卡片上而不是滚动面板上：
        // viewport 是矩形且不透明的，圆角画在滚动面板上会被内部内容盖住。
        JPanel card = new JPanel(new BorderLayout());
        card.putClientProperty(FlatClientProperties.STYLE, "arc: 12; border: 1,1,1,1,#1E2B45");
        card.setBackground(AppTheme.PANEL);
        card.add(scroll, BorderLayout.CENTER);
        return card;
    }

    /**
     * 行渲染器：在选中行左侧画一条强调色竖条。
     *
     * <p>内部类，直接访问外层的 {@link AppTheme} 常量与 {@code table}。
     */
    private final class RowRenderer extends DefaultTableCellRenderer {
        /**
         * 渲染单个单元格。
         *
         * @param selected 该行是否被选中，决定强调色竖条与背景色
         * @param focused  固定传 {@code false}：单元格自己画焦点态，避免与行选中态打架
         * @param column   列索引：第 2 列（版本）用弱化色，其余用主文字色
         * @return 已配置好的 {@link JLabel}
         */
        @Override
        public Component getTableCellRendererComponent(JTable source, Object value, boolean selected,
                                                       boolean focused, int row, int column) {
            JLabel label = (JLabel) super.getTableCellRendererComponent(
                    source, value, selected, false, row, column);
            // PID 列用略小的字号拉开层次；原写法两个分支同为 PLAIN，等于没写。
            label.setFont(AppTheme.scaled(Font.PLAIN, column == 0 ? 0.93f : 1.0f));
            // 选中指示器只画在**进程列**的左边缘，作为"整行选中"的视觉锚点。
            // 此前每一列都画，一行会被切成一堆色块（右侧那条蓝边也是这么来的）。
            boolean indicator = selected && column == 0;
            label.setBorder(indicator
                    ? BorderFactory.createMatteBorder(0, 3, 0, 8, AppTheme.ACCENT)
                    : BorderFactory.createEmptyBorder(0, 11, 0, 8));
            label.setForeground(column == 2 ? AppTheme.TEXT_MUTED : AppTheme.TEXT);
            label.setOpaque(true);
            label.setBackground(selected ? AppTheme.PANEL_HOVER : AppTheme.BACKGROUND);
            return label;
        }
    }

    /**
     * 构建底部区域：错误提示行 + 扫描/注入按钮 + 横跨整宽的日志面板。
     *
     * <p>只被 {@link #buildContent()} 调用。
     */
    private JPanel buildBottom() {
        JPanel bottom = new JPanel(new MigLayout("insets 12 0 0 0, fillx", "[grow,fill][]10[]", "[]10[]"));
        bottom.setOpaque(false);

        errorLabel.setForeground(AppTheme.DANGER);
        AppTheme.bindFont(errorLabel, Font.PLAIN, 0.85f);
        errorLabel.setText(" ");

        scanButton.addActionListener(e -> startScan());
        injectButton.addActionListener(e -> startInject());

        bottom.add(errorLabel, "growx, aligny center");
        bottom.add(scanButton, "h 34!, w 120!");
        bottom.add(injectButton, "h 34!, w 120!, wrap");
        bottom.add(log, "span 3, growx, h 140!");
        return bottom;
    }

    /**
     * 构建底部状态栏：左侧版本号，右侧状态指示点。
     *
     * <p>只被构造函数调用一次。
     */
    private JPanel buildStatusBar() {
        JPanel bar = new JPanel(new MigLayout("insets 0 18 0 18, fillx", "[]push[]", "[]"));
        bar.setBackground(AppTheme.PANEL);

        JLabel version = new JLabel(VERSION);
        AppTheme.bindFont(version, Font.PLAIN, 0.85f);
        version.setForeground(AppTheme.TEXT_MUTED);

        bar.add(version, "growx");
        bar.add(statusDot, "h 16!, w 16!");
        return bar;
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
     * 依据「是否扫描/注入中、是否有结果、是否有选中行」推导两个按钮的可用状态。
     *
     * <p>把推导集中在一处，避免像旧实现那样把 {@code isEnabled()} 当成与条件——那样一旦置为
     * 禁用就再也无法恢复（例如注入成功后按钮永久锁死）。
     */
    private void updateButtons() {
        scanButton.setEnabled(!scanning && !injecting);
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
        updateButtons();
        statusDot.setState(AppTheme.TEXT_MUTED);
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
                    listCards.show(listArea, CARD_TABLE);
                    if (rows.isEmpty()) {
                        statusDot.setState(AppTheme.DANGER);
                        log.info("未找到 Minecraft 进程");
                    } else {
                        statusDot.setState(AppTheme.SUCCESS);
                        log.info("发现 " + rows.size() + " 个进程");
                        table.setRowSelectionInterval(0, 0);
                        // 默认选中第一行：多数场景用户就是要注入第一个进程。
                        loadVersions();
                    }
                } catch (Exception e) {
                    // 失败时列表保持清空状态，不能展示上一轮的过期行。
                    model.clear();
                    listCards.show(listArea, CARD_TABLE);
                    statusDot.setState(AppTheme.DANGER);
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
                    statusDot.setState(AppTheme.SUCCESS);
                    log.info("注入成功 → pid " + row.pid);
                    errorLabel.setText(" ");
                    // 延迟最小化：注入后 agent 可能还要几秒才真正生效。把定时器存下来以便取消。
                    cancelAutoMinimize();
                    autoMinimizeTimer = new Timer(AUTO_MINIMIZE_DELAY_MS, e -> setState(JFrame.ICONIFIED));
                    autoMinimizeTimer.setRepeats(false);
                    autoMinimizeTimer.start();
                } catch (Exception e) {
                    String reason = describeFailure(e);
                    statusDot.setState(AppTheme.DANGER);
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
