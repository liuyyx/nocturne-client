package dev.nocturne.injector;

import dev.nocturne.core.attach.AttachException;
import dev.nocturne.core.attach.Attacher;
import dev.nocturne.core.attach.CurrentProcess;
import dev.nocturne.core.attach.ProcessScanner;
import dev.nocturne.core.attach.TargetHints;
import net.miginfocom.swing.MigLayout;

import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JScrollPane;
import javax.swing.JTable;
import javax.swing.ListSelectionModel;
import javax.swing.SwingConstants;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.table.DefaultTableCellRenderer;
import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;

/**
 * Nocturne 注入工作台。
 *
 * <p>左侧只负责产品定位、操作流程和设置；右侧按页头、进程工作区、目标确认、日志排列。
 * 扫描、版本识别与 attach 均在后台执行，所有控件状态由本类统一推导。
 */
public final class MainWindow extends JFrame {

    public static final String VERSION = "v0.1.0";
    private static final String CARD_SKELETON = "skeleton";
    private static final String CARD_TABLE = "table";
    private static final String CARD_EMPTY = "empty";
    private static final int AUTO_MINIMIZE_DELAY_MS = 5000;

    private final AppConfig config;
    private final LogPane log = new LogPane();
    private final ProcessTableModel model = new ProcessTableModel();
    private final JTable table = new JTable(model);
    private final CardLayout listCards = new CardLayout();
    private final JPanel listArea = new JPanel(listCards);
    private final JButton scanButton = new JButton("扫描游戏");
    private final JButton injectButton = new JButton("注入到游戏");
    private final JButton emptyScanButton = new JButton("扫描运行中的游戏");
    private final JLabel errorLabel = new JLabel(" ");
    private final StatusDot statusDot = new StatusDot();
    private final SkeletonPanel skeletonPanel = new SkeletonPanel();
    private final JLabel statusText = new JLabel("等待扫描");
    private final JLabel countLabel = new JLabel(" ");
    private final JLabel emptyTitle = new JLabel("从正在运行的游戏开始");
    private final JLabel emptyHint = new JLabel("启动 Minecraft，然后扫描本机 JVM 进程");
    private final JLabel selectedName = new JLabel("尚未选择目标");
    private final JLabel selectedMeta = new JLabel("扫描并选择一个游戏进程后即可注入");
    private final JProgressBar progress = new JProgressBar();
    private final JLabel[] stepNumbers = new JLabel[3];
    private final JLabel[] stepTitles = new JLabel[3];

    private int hoveredRow = -1;
    private int workflowStage;
    private boolean scanning;
    private boolean injecting;
    private Timer autoMinimizeTimer;

    public MainWindow(AppConfig config) {
        super("Nocturne · 注入工作台");
        this.config = config;
        statusDot.setSpeedPercent(config.animationSpeed);
        skeletonPanel.setSpeedPercent(config.animationSpeed);
        setDefaultCloseOperation(EXIT_ON_CLOSE);
        setBounds(WindowGeometry.initialBounds());
        setMinimumSize(new Dimension(900, 640));
        setBackground(AppTheme.BACKGROUND);

        setLayout(new MigLayout("insets 0, fill, gap 0", "[224!]0[grow,fill]", "[grow,fill]"));
        add(buildSidebar(), "grow");
        add(buildWorkspace(), "grow");

        listArea.setOpaque(false);
        listArea.add(buildEmptyState(), CARD_EMPTY);
        listArea.add(buildTableCard(), CARD_TABLE);
        listArea.add(buildSkeletonCard(), CARD_SKELETON);
        listCards.show(listArea, CARD_EMPTY);

        configureButtons();
        configureTableSelection();
        setStatus(AppTheme.TEXT_MUTED, "等待扫描");
        updateSelectionSummary();
        updateButtons();
        log.info("Nocturne " + VERSION + " 就绪，等待扫描游戏进程");

        addWindowListener(new java.awt.event.WindowAdapter() {
            @Override
            public void windowActivated(java.awt.event.WindowEvent e) {
                cancelAutoMinimize();
            }
        });
        System.out.println("[nocturne] logical screen=" + WindowGeometry.activeScreenSize()
                + " window=" + getBounds().width + "x" + getBounds().height
                + " configuredZoom=" + config.uiScale + "%");
    }

    private JPanel buildSidebar() {
        JPanel sidebar = new JPanel(new MigLayout(
                "insets 28 22 22 22, fill, wrap 1", "[grow,fill]",
                "[]44[]14[]12[]12[grow,fill]18[]14[]"));
        sidebar.setBackground(AppTheme.SIDEBAR);
        sidebar.setBorder(BorderFactory.createMatteBorder(0, 0, 0, 1, AppTheme.BORDER_SOFT));

        sidebar.add(buildBrand(), "growx");
        sidebar.add(overline("INJECTION WORKFLOW"), "growx");
        sidebar.add(flowStep(0, "01", "扫描", "发现本机游戏进程"), "growx");
        sidebar.add(flowStep(1, "02", "选择", "确认 PID 与游戏版本"), "growx");
        sidebar.add(flowStep(2, "03", "注入", "加载 Nocturne 客户端"), "growx");

        JPanel spacer = new JPanel();
        spacer.setOpaque(false);
        sidebar.add(spacer, "growy");
        sidebar.add(buildCoverage(), "growx");

        JButton settings = new JButton("偏好设置");
        settings.setIcon(new AppIcons(AppIcons.Kind.SETTINGS, AppTheme.TEXT_MUTED, 16));
        settings.setHorizontalAlignment(SwingConstants.LEFT);
        settings.setIconTextGap(10);
        settings.addActionListener(e -> openSettings());
        AppTheme.styleQuietButton(settings);
        sidebar.add(settings, "growx, h 40!");
        return sidebar;
    }

    private JPanel buildBrand() {
        JPanel brand = new JPanel(new MigLayout("insets 0, fillx", "[38!]12[grow,fill]", "[38!]"));
        brand.setOpaque(false);
        brand.add(new BrandMark(), "w 38!, h 38!");

        JPanel words = new JPanel(new MigLayout("insets 0, wrap 1", "[grow]", "[]2[]"));
        words.setOpaque(false);
        JLabel name = new JLabel("NOCTURNE");
        AppTheme.bindFont(name, Font.BOLD, AppTheme.TYPE_TITLE);
        name.setForeground(AppTheme.TEXT);
        JLabel edition = overline("CLIENT INJECTOR");
        words.add(name, "aligny bottom");
        words.add(edition);
        brand.add(words, "aligny center");
        return brand;
    }

    private JPanel flowStep(int step, String number, String title, String subtitle) {
        JPanel row = new JPanel(new MigLayout("insets 0, fillx", "[30!]10[grow,fill]", "[36!]"));
        row.setOpaque(false);
        JLabel index = new JLabel(number);
        stepNumbers[step] = index;
        AppTheme.bindMonospacedFont(index, AppTheme.TYPE_CAPTION);
        row.add(index, "aligny top, gaptop 2");

        JPanel copy = new JPanel(new MigLayout("insets 0, wrap 1", "[grow]", "[]1[]"));
        copy.setOpaque(false);
        JLabel heading = new JLabel(title);
        stepTitles[step] = heading;
        AppTheme.bindFont(heading, Font.BOLD, AppTheme.TYPE_BODY);
        JLabel detail = new JLabel(subtitle);
        AppTheme.bindFont(detail, Font.PLAIN, AppTheme.TYPE_OVERLINE);
        detail.setForeground(AppTheme.TEXT_FAINT);
        copy.add(heading);
        copy.add(detail);
        row.add(copy, "aligny center");
        return row;
    }

    private JPanel buildCoverage() {
        JPanel coverage = new JPanel(new MigLayout("insets 16 0 0 0, wrap 1", "[grow]", "[]4[]4[]"));
        coverage.setOpaque(false);
        coverage.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, AppTheme.BORDER_SOFT));
        JLabel number = new JLabel("66");
        AppTheme.bindFont(number, Font.BOLD, AppTheme.TYPE_HERO);
        number.setForeground(AppTheme.TEXT);
        JLabel caption = overline("SUPPORTED RELEASES");
        JLabel range = new JLabel("Minecraft 1.8.9 — 26.3");
        AppTheme.bindFont(range, Font.PLAIN, AppTheme.TYPE_CAPTION);
        range.setForeground(AppTheme.TEXT_MUTED);
        coverage.add(number);
        coverage.add(caption);
        coverage.add(range);
        return coverage;
    }

    private JPanel buildWorkspace() {
        JPanel workspace = new JPanel(new MigLayout(
                "insets 0, fill, wrap 1, gap 0 0", "[grow,fill]",
                "[112!][3!][grow,fill][108!][138!]"));
        workspace.setBackground(AppTheme.BACKGROUND);
        workspace.add(buildHeader(), "grow");
        workspace.add(buildProgressTrack(), "growx");
        workspace.add(listArea, "grow");
        workspace.add(buildActionDock(), "grow");
        workspace.add(buildLogSection(), "grow");
        return workspace;
    }

    private JPanel buildHeader() {
        JPanel header = new JPanel(new MigLayout(
                "insets 24 32 20 32, fillx", "[grow,fill]push[]", "[center]"));
        header.setOpaque(false);

        JPanel copy = new JPanel(new MigLayout("insets 0, wrap 1", "[grow]", "[]5[]"));
        copy.setOpaque(false);
        copy.add(overline("NOCTURNE / INJECTION"));
        JLabel title = new JLabel("游戏注入");
        AppTheme.bindFont(title, Font.BOLD, AppTheme.TYPE_HERO);
        title.setForeground(AppTheme.TEXT);
        copy.add(title);
        header.add(copy, "aligny center");

        JPanel status = new JPanel(new MigLayout("insets 8 12 8 12", "[]8[]", "[center]"));
        status.setBackground(AppTheme.PANEL);
        AppTheme.styleCard(status);
        AppTheme.bindFont(statusText, Font.PLAIN, AppTheme.TYPE_CAPTION);
        status.add(statusDot, "w 14!, h 14!");
        status.add(statusText);
        header.add(status, "aligny center");
        return header;
    }

    private JPanel buildProgressTrack() {
        JPanel track = new JPanel(new BorderLayout());
        track.setOpaque(false);
        progress.setIndeterminate(true);
        progress.setVisible(false);
        progress.setBorder(BorderFactory.createEmptyBorder());
        track.add(progress, BorderLayout.CENTER);
        return track;
    }

    private JPanel buildTableCard() {
        JPanel card = new JPanel(new MigLayout("insets 0, fill, wrap 1", "[grow,fill]", "[54!]0[grow,fill]"));
        card.setBackground(AppTheme.PANEL);
        AppTheme.styleCard(card);

        JPanel header = new JPanel(new MigLayout("insets 0 20 0 20, fillx", "[]push[]", "[center]"));
        header.setOpaque(false);
        JLabel heading = new JLabel("运行中的游戏");
        AppTheme.bindFont(heading, Font.BOLD, AppTheme.TYPE_TITLE);
        heading.setForeground(AppTheme.TEXT);
        AppTheme.bindFont(countLabel, Font.PLAIN, AppTheme.TYPE_CAPTION);
        countLabel.setForeground(AppTheme.TEXT_MUTED);
        header.add(heading);
        header.add(countLabel);
        card.add(header, "growx");
        card.add(buildTable(), "grow");
        return card;
    }

    private JScrollPane buildTable() {
        table.setRowHeight(50);
        table.setFillsViewportHeight(true);
        table.setShowVerticalLines(false);
        table.setShowHorizontalLines(false);
        table.setIntercellSpacing(new Dimension(0, 0));
        table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        table.setAutoResizeMode(JTable.AUTO_RESIZE_ALL_COLUMNS);
        table.setBorder(BorderFactory.createEmptyBorder());
        table.setBackground(AppTheme.PANEL);
        table.setSelectionBackground(AppTheme.ACCENT_SOFT);
        table.setSelectionForeground(AppTheme.TEXT);
        table.setDefaultRenderer(Object.class, new RowRenderer());
        table.getTableHeader().setReorderingAllowed(false);
        table.getTableHeader().setBackground(AppTheme.PANEL_RAISED);
        table.getTableHeader().setForeground(AppTheme.TEXT_MUTED);
        table.getTableHeader().setPreferredSize(new Dimension(0, 34));
        AppTheme.bindFont(table.getTableHeader(), Font.PLAIN, AppTheme.TYPE_OVERLINE);
        DefaultTableCellRenderer headerRenderer = new DefaultTableCellRenderer();
        headerRenderer.setHorizontalAlignment(SwingConstants.LEFT);
        headerRenderer.setBorder(BorderFactory.createEmptyBorder(0, 18, 0, 8));
        headerRenderer.setOpaque(true);
        headerRenderer.setBackground(AppTheme.PANEL_RAISED);
        headerRenderer.setForeground(AppTheme.TEXT_MUTED);
        table.getTableHeader().setDefaultRenderer(headerRenderer);
        table.getColumnModel().getColumn(0).setPreferredWidth(330);
        table.getColumnModel().getColumn(1).setPreferredWidth(100);
        table.getColumnModel().getColumn(2).setPreferredWidth(120);

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
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setOpaque(false);
        scroll.getViewport().setOpaque(false);
        return scroll;
    }

    private void configureTableSelection() {
        table.getSelectionModel().addListSelectionListener(e -> {
            if (!e.getValueIsAdjusting()) {
                showError("");
                updateSelectionSummary();
                updateInjectButton();
            }
        });
        table.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (e.getClickCount() == 2 && table.getSelectedRow() >= 0 && injectButton.isEnabled()) {
                    startInject();
                }
            }
        });
    }

    private JPanel buildEmptyState() {
        JPanel empty = new JPanel(new MigLayout(
                "insets 24 32 24 32, fill, wrap 1", "[center]",
                "[push][]16[]8[]22[][push]"));
        empty.setBackground(AppTheme.PANEL);
        AppTheme.styleCard(empty);
        AppTheme.bindFont(emptyTitle, Font.BOLD, AppTheme.TYPE_TITLE);
        emptyTitle.setForeground(AppTheme.TEXT);
        AppTheme.bindFont(emptyHint, Font.PLAIN, AppTheme.TYPE_CAPTION);
        emptyHint.setForeground(AppTheme.TEXT_MUTED);
        empty.add(new RadarMark(), "w 64!, h 64!");
        empty.add(emptyTitle);
        empty.add(emptyHint);
        empty.add(emptyScanButton, "h 40!, w 190!");
        return empty;
    }

    private JPanel buildSkeletonCard() {
        JPanel card = new JPanel(new MigLayout("insets 24 24, fill, wrap 1", "[grow,fill]", "[]18[grow,fill]"));
        card.setBackground(AppTheme.PANEL);
        AppTheme.styleCard(card);
        JLabel title = new JLabel("正在扫描本机 JVM");
        AppTheme.bindFont(title, Font.BOLD, AppTheme.TYPE_TITLE);
        title.setForeground(AppTheme.TEXT);
        card.add(title);
        card.add(skeletonPanel, "grow");
        return card;
    }

    private JPanel buildActionDock() {
        JPanel dock = new JPanel(new MigLayout(
                "insets 14 32 14 32, fillx", "[grow,fill]16[]10[]", "[center]"));
        dock.setOpaque(false);
        dock.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, AppTheme.BORDER_SOFT));

        JPanel target = new JPanel(new MigLayout("insets 0, fillx, wrap 1", "[grow,fill]", "[]2[]2[]"));
        target.setOpaque(false);
        JLabel caption = overline("SELECTED TARGET");
        AppTheme.bindFont(selectedName, Font.BOLD, AppTheme.TYPE_BODY);
        selectedName.setForeground(AppTheme.TEXT);
        AppTheme.bindFont(selectedMeta, Font.PLAIN, AppTheme.TYPE_OVERLINE);
        selectedMeta.setForeground(AppTheme.TEXT_MUTED);
        AppTheme.bindFont(errorLabel, Font.PLAIN, AppTheme.TYPE_OVERLINE);
        errorLabel.setForeground(AppTheme.DANGER);
        target.add(caption);
        target.add(selectedName);
        target.add(selectedMeta);
        target.add(errorLabel);
        dock.add(target, "growx");
        dock.add(scanButton, "h 42!, w 122!");
        dock.add(injectButton, "h 42!, w 158!");
        return dock;
    }

    private JPanel buildLogSection() {
        JPanel section = new JPanel(new MigLayout(
                "insets 0 32 20 32, fill, wrap 1", "[grow,fill]", "[30!][grow,fill]"));
        section.setOpaque(false);
        JPanel heading = new JPanel(new MigLayout("insets 0 2, fillx", "[]push[]", "[center]"));
        heading.setOpaque(false);
        JLabel title = new JLabel("活动日志");
        AppTheme.bindFont(title, Font.BOLD, AppTheme.TYPE_CAPTION);
        title.setForeground(AppTheme.TEXT_MUTED);
        JLabel hint = new JLabel("右键复制 / 清空");
        AppTheme.bindFont(hint, Font.PLAIN, AppTheme.TYPE_CAPTION);
        hint.setForeground(AppTheme.TEXT_FAINT);
        heading.add(title);
        heading.add(hint);
        section.add(heading, "growx");
        section.add(log, "grow");
        return section;
    }

    private void configureButtons() {
        AppTheme.styleSecondaryButton(scanButton);
        AppTheme.stylePrimaryButton(injectButton);
        AppTheme.stylePrimaryButton(emptyScanButton);
        scanButton.setIcon(new AppIcons(AppIcons.Kind.SCAN, AppTheme.TEXT, 16));
        injectButton.setIcon(new AppIcons(AppIcons.Kind.INJECT, AppTheme.ON_ACCENT, 17));
        emptyScanButton.setIcon(new AppIcons(AppIcons.Kind.SCAN, AppTheme.ON_ACCENT, 16));
        scanButton.setIconTextGap(8);
        injectButton.setIconTextGap(9);
        emptyScanButton.setIconTextGap(8);
        scanButton.addActionListener(e -> startScan());
        injectButton.addActionListener(e -> startInject());
        emptyScanButton.addActionListener(e -> startScan());
        injectButton.setEnabled(false);
        getRootPane().setDefaultButton(injectButton);
    }

    private JLabel overline(String text) {
        JLabel label = new JLabel(text);
        AppTheme.bindMonospacedFont(label, AppTheme.TYPE_OVERLINE);
        label.setForeground(AppTheme.TEXT_FAINT);
        return label;
    }

    private void openSettings() {
        cancelAutoMinimize();
        SettingsDialog dialog = new SettingsDialog(this, config, updated -> {
            AppTheme.setZoom(updated.uiScale / 100f);
            log.info("设置已保存（界面缩放 " + updated.uiScale + "%）；快捷键改动需重新注入后生效");
        }, speed -> {
            statusDot.setSpeedPercent(speed);
            skeletonPanel.setSpeedPercent(speed);
        });
        dialog.setVisible(true);
    }

    private void setStatus(Color color, String text) {
        statusDot.setState(color);
        statusText.setText(text);
        statusText.setForeground(color);
    }

    private void updateButtons() {
        boolean idle = !scanning && !injecting;
        scanButton.setEnabled(idle);
        emptyScanButton.setEnabled(idle);
        progress.setVisible(scanning || injecting);
        updateWorkflow();
        updateInjectButton();
    }

    /** 当前步骤用琥珀色，已完成步骤用成功色，未到达步骤保持弱化。 */
    private void updateWorkflow() {
        for (int i = 0; i < stepNumbers.length; i++) {
            boolean complete = i < workflowStage;
            boolean current = i == workflowStage;
            stepNumbers[i].setForeground(complete ? AppTheme.SUCCESS
                    : current ? AppTheme.ACCENT : AppTheme.TEXT_FAINT);
            stepTitles[i].setForeground(current ? AppTheme.TEXT : AppTheme.TEXT_MUTED);
        }
    }

    private void updateInjectButton() {
        injectButton.setEnabled(!scanning && !injecting
                && model.getRowCount() > 0 && table.getSelectedRow() >= 0);
    }

    /** 将表格选中行同步为操作栏里的明确目标，避免注入前还要回看列表。 */
    private void updateSelectionSummary() {
        int viewRow = table.getSelectedRow();
        ProcessTableModel.Row row = model.rowAt(
                viewRow >= 0 ? table.convertRowIndexToModel(viewRow) : viewRow);
        if (row == null) {
            selectedName.setText("尚未选择目标");
            selectedMeta.setText("扫描并选择一个游戏进程后即可注入");
        } else {
            selectedName.setText(row.name);
            String version = "—".equals(row.version) ? "版本识别中" : "Minecraft " + row.version;
            selectedMeta.setText("PID " + row.pid + "  ·  " + version);
        }
    }

    private void showError(String message) {
        errorLabel.setText(message == null || message.isEmpty() ? " " : ellipsize(message, 58));
        errorLabel.setToolTipText(message);
        selectedMeta.setVisible(message == null || message.isEmpty());
    }

    private void cancelAutoMinimize() {
        if (autoMinimizeTimer != null) {
            autoMinimizeTimer.stop();
            autoMinimizeTimer = null;
        }
    }

    private void startScan() {
        cancelAutoMinimize();
        showError("");
        scanning = true;
        workflowStage = 0;
        scanButton.setText("重新扫描");
        model.clear();
        table.clearSelection();
        updateSelectionSummary();
        countLabel.setText(" ");
        updateButtons();
        setStatus(AppTheme.INFO, "正在扫描");
        listCards.show(listArea, CARD_SKELETON);

        new SwingWorker<List<ProcessTableModel.Row>, Void>() {
            @Override
            protected List<ProcessTableModel.Row> doInBackground() {
                List<ProcessScanner.ProcessInfo> candidates = ProcessScanner.minecraftProcesses();
                if (candidates.isEmpty()) {
                    candidates = ProcessScanner.javaProcesses();
                }
                int self = CurrentProcess.pid();
                List<ProcessTableModel.Row> rows = new ArrayList<ProcessTableModel.Row>();
                for (ProcessScanner.ProcessInfo info : candidates) {
                    if (info.pid != self) {
                        rows.add(new ProcessTableModel.Row(info.pid, info.displayName(), "—"));
                    }
                }
                return rows;
            }

            @Override
            protected void done() {
                scanning = false;
                try {
                    List<ProcessTableModel.Row> rows = get();
                    model.setRows(rows);
                    if (rows.isEmpty()) {
                        emptyTitle.setText("没有发现游戏进程");
                        emptyHint.setText("先启动 Minecraft，再点击重新扫描");
                        emptyScanButton.setText("重新扫描");
                        listCards.show(listArea, CARD_EMPTY);
                        setStatus(AppTheme.DANGER, "未发现进程");
                        log.info("未找到 Minecraft 进程");
                    } else {
                        workflowStage = 1;
                        listCards.show(listArea, CARD_TABLE);
                        countLabel.setText(rows.size() + " 个可用进程");
                        setStatus(AppTheme.SUCCESS, "发现 " + rows.size() + " 个进程");
                        log.info("发现 " + rows.size() + " 个候选 JVM 进程");
                        table.setRowSelectionInterval(0, 0);
                        loadVersions();
                    }
                } catch (Exception e) {
                    model.clear();
                    emptyTitle.setText("扫描没有完成");
                    emptyHint.setText("无法读取本机进程，请查看活动日志后重试");
                    emptyScanButton.setText("重新扫描");
                    listCards.show(listArea, CARD_EMPTY);
                    setStatus(AppTheme.DANGER, "扫描失败");
                    showError("扫描失败：" + firstLine(e));
                    log.error("扫描失败：" + firstLine(e));
                }
                updateSelectionSummary();
                updateButtons();
            }
        }.execute();
    }

    private void loadVersions() {
        new SwingWorker<Map<Integer, String>, Void>() {
            @Override
            protected Map<Integer, String> doInBackground() {
                return ProcessScanner.javaProcessCommandLines();
            }

            @Override
            protected void done() {
                try {
                    Map<Integer, String> commandLines = get();
                    for (Integer pid : commandLines.keySet()) {
                        model.setVersion(pid, GameVersion.fromCommandLine(commandLines.get(pid)));
                    }
                    updateSelectionSummary();
                } catch (Exception ignored) {
                    // 版本只是辅助信息；识别失败不阻塞 attach。
                }
            }
        }.execute();
    }

    private void startInject() {
        int viewRow = table.getSelectedRow();
        ProcessTableModel.Row row = model.rowAt(
                viewRow >= 0 ? table.convertRowIndexToModel(viewRow) : viewRow);
        if (row == null) {
            showError("请先选择一个游戏进程");
            return;
        }

        cancelAutoMinimize();
        injecting = true;
        workflowStage = 2;
        updateButtons();
        injectButton.setText("正在注入…");
        showError("");
        setStatus(AppTheme.INFO, "正在注入");
        log.info("注入 → pid " + row.pid + "，版本 " + row.version + "，GUI 键 " + config.guiBind
                + "（VK " + KeyCodes.codeFor(config.guiBind) + "）");

        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() throws Exception {
                File self;
                try {
                    self = new File(MainWindow.class.getProtectionDomain()
                            .getCodeSource().getLocation().toURI());
                } catch (URISyntaxException malformed) {
                    throw new IllegalStateException("无法解析 agent jar 路径", malformed);
                }
                String options = KeyCodes.attachOptions(config.guiBind, row.version);
                String commandLine = ProcessScanner.javaProcessCommandLines().get(Integer.valueOf(row.pid));
                String keyHint = TargetHints.rightShiftConflict(commandLine, KeyCodes.codeFor(config.guiBind));
                if (keyHint != null) {
                    log.info("按键提示：" + keyHint);
                }
                Attacher.attach(row.pid, self, options);
                return null;
            }

            @Override
            protected void done() {
                injecting = false;
                injectButton.setText("注入到游戏");
                updateButtons();
                try {
                    get();
                    workflowStage = 3;
                    updateWorkflow();
                    setStatus(AppTheme.SUCCESS, "注入成功");
                    log.info("注入成功 → pid " + row.pid);
                    showError("");
                    selectedMeta.setText("PID " + row.pid + "  ·  Nocturne 已加载");
                    cancelAutoMinimize();
                    autoMinimizeTimer = new Timer(AUTO_MINIMIZE_DELAY_MS, e -> setState(JFrame.ICONIFIED));
                    autoMinimizeTimer.setRepeats(false);
                    autoMinimizeTimer.start();
                } catch (Exception e) {
                    String reason = describeFailure(e);
                    setStatus(AppTheme.DANGER, "注入失败");
                    log.error("注入失败：" + reason);
                    showError(reason);
                }
            }
        }.execute();
    }

    static String describeFailure(Throwable throwable) {
        Throwable cause = throwable;
        while ((cause instanceof ExecutionException || cause instanceof InvocationTargetException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        if (cause instanceof AttachException && cause.getCause() != null) {
            if (cause.getCause() instanceof ClassNotFoundException) {
                return "未找到 attach API（请用完整 JDK 启动）";
            }
            cause = cause.getCause();
        }
        String message = String.valueOf(cause.getMessage() == null ? cause.toString() : cause.getMessage());
        String lower = message.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("no such process") || lower.contains("process not found")) {
            return "没找到 JVM（进程可能已退出）";
        }
        if (lower.contains("agent jar not found")) {
            return "找不到 agent jar（该路径对目标 JVM 不可见）：" + firstLine(message);
        }
        if (lower.contains("unsupported") || lower.contains("class version")) {
            return "版本不支持";
        }
        if (lower.contains("already")) {
            return "该进程已经注入过";
        }
        if (lower.contains("agent") || lower.contains("attach")) {
            return "游戏拒绝 attach（" + firstLine(message) + "）";
        }
        return firstLine(message);
    }

    private static String firstLine(Throwable throwable) {
        return firstLine(String.valueOf(throwable));
    }

    private static String firstLine(String message) {
        int newline = message.indexOf('\n');
        String line = newline < 0 ? message : message.substring(0, newline);
        return ellipsize(line, 160);
    }

    private static String ellipsize(String text, int limit) {
        return text.length() > limit ? text.substring(0, limit - 1) + "…" : text;
    }

    private final class RowRenderer extends DefaultTableCellRenderer {
        @Override
        public Component getTableCellRendererComponent(JTable source, Object value, boolean selected,
                                                       boolean focused, int row, int column) {
            String text = String.valueOf(value);
            boolean unknownVersion = column == 2 && "—".equals(text);
            if (unknownVersion) {
                text = "识别中";
            }
            JLabel label = (JLabel) super.getTableCellRendererComponent(source, text, selected, false, row, column);
            label.setFont(column == 0
                    ? AppTheme.scaled(Font.BOLD, AppTheme.TYPE_BODY)
                    : new Font(Font.MONOSPACED, Font.PLAIN, 1).deriveFont(
                            AppTheme.baseFont().getSize2D() * AppTheme.TYPE_MONO));
            label.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 1, 0, AppTheme.BORDER_SOFT),
                    BorderFactory.createCompoundBorder(
                            BorderFactory.createMatteBorder(0, selected && column == 0 ? 3 : 0, 0, 0,
                                    AppTheme.ACCENT),
                            BorderFactory.createEmptyBorder(0, 17, 0, 8))));
            label.setForeground(unknownVersion ? AppTheme.TEXT_FAINT
                    : column == 0 ? AppTheme.TEXT
                    : column == 2 ? AppTheme.ACCENT : AppTheme.TEXT_MUTED);
            label.setOpaque(true);
            label.setBackground(selected ? AppTheme.ACCENT_SOFT
                    : row == hoveredRow ? AppTheme.PANEL_HOVER : AppTheme.PANEL);
            return label;
        }
    }

    /** 由两条折线和小信号点组成的 N 标记，避免依赖外部图片资源。 */
    private static final class BrandMark extends JComponent {
        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int size = Math.min(getWidth(), getHeight());
            g.setColor(AppTheme.PANEL_RAISED);
            g.fillRoundRect(0, 0, size, size, 11, 11);
            g.setColor(AppTheme.BORDER);
            g.drawRoundRect(0, 0, size - 1, size - 1, 11, 11);
            g.setStroke(new BasicStroke(2.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setColor(AppTheme.TEXT);
            int left = size * 10 / 38;
            int right = size * 27 / 38;
            int top = size * 10 / 38;
            int bottom = size * 28 / 38;
            g.drawLine(left, bottom, left, top);
            g.drawLine(left, top, right, bottom);
            g.drawLine(right, bottom, right, top);
            g.setColor(AppTheme.ACCENT);
            g.fillRoundRect(size - 11, 5, 6, 6, 3, 3);
            g.dispose();
        }
    }

    /** 空态扫描标记：静态同心轨道与 JVM 字样，清晰表达下一步而不制造无用动画。 */
    private static final class RadarMark extends JComponent {
        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int size = Math.min(getWidth(), getHeight());
            int x = (getWidth() - size) / 2;
            int y = (getHeight() - size) / 2;
            g.setColor(AppTheme.PANEL_RAISED);
            g.fillOval(x, y, size, size);
            g.setColor(AppTheme.BORDER);
            g.setStroke(new BasicStroke(1.2f));
            g.drawOval(x + 1, y + 1, size - 3, size - 3);
            g.setColor(AppTheme.ACCENT);
            g.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.drawArc(x + 7, y + 7, size - 14, size - 14, 35, 105);
            g.fillOval(x + size - 15, y + 14, 6, 6);
            g.setFont(AppTheme.scaled(Font.BOLD, AppTheme.TYPE_OVERLINE));
            g.setColor(AppTheme.TEXT_MUTED);
            String text = "JVM";
            int textWidth = g.getFontMetrics().stringWidth(text);
            g.drawString(text, x + (size - textWidth) / 2, y + size / 2 + 4);
            g.dispose();
        }
    }
}
