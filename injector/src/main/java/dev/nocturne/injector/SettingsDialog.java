package dev.nocturne.injector;

import net.miginfocom.swing.MigLayout;

import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JSlider;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.Dialog;
import java.awt.Font;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.function.Consumer;

/**
 * 设置对话框：GUI 快捷键录制 + 三个滑块。
 *
 * <p>拖动过程中改动实时生效，关闭对话框时才落盘（见 {@link #dispose()}）。
 */
public final class SettingsDialog extends JDialog {

    /** 被直接原地修改的偏好对象；对话框不持有副本。 */
    private final AppConfig config;
    /** 关闭时的回调，参数即已修改并保存过的 {@link #config}。 */
    private final Consumer<AppConfig> onApply;

    /** 快捷键录制输入框；不可编辑，只用于显示与接收按键事件。 */
    private final JTextField bindField = new JTextField();
    /** 改键后的提示行：提醒用户新快捷键要等重新注入才生效。 */
    private final JLabel bindHint = new JLabel(" ");
    /** 是否处于录制状态；为 {@code false} 时按键事件被忽略。 */
    private boolean recording;
    /** 是否已经处理过关闭；保证 {@link #dispose()} 的保存与回调只执行一次。 */
    private boolean disposed;

    /**
     * 构建设置对话框。
     *
     * <p>必须在 EDT 上调用。构造完成后仍需由调用方 {@code setVisible(true)} 展示。
     *
     * @param owner 父窗口，用于模态绑定与相对定位
     * @param config 被修改的偏好对象，通常与主窗口共享同一实例
     * @param onApply 关闭对话框后触发的回调
     */
    public SettingsDialog(java.awt.Window owner, AppConfig config, Consumer<AppConfig> onApply) {
        super(owner, "设置", Dialog.ModalityType.APPLICATION_MODAL);
        this.config = config;
        this.onApply = onApply;

        // 右上角 X 走的是窗口关闭事件：默认 HIDE_ON_CLOSE 会绕过 dispose() 导致设置不落盘、
        // 对话框也永不回收。改为 DISPOSE_ON_CLOSE，让 X 与「完成」走同一条保存路径。
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);

        // 依次堆叠：4 个分区 + 完成按钮，每个分区由标题与控件两行组成。
        setLayout(new MigLayout("insets 22, fillx, wrap 1", "[grow,fill]", "[]12[]12[]12[]"));
        setBackground(AppTheme.BACKGROUND);

        add(sectionLabel("GUI 快捷键"));
        add(buildBindRow(), "growx");

        add(sectionLabel("动画速度"));
        add(slider(0, 200, config.animationSpeed, value -> config.animationSpeed = value), "growx");
        // 动画速度与模糊强度目前只写入配置，供注入端读取；这里不改变界面表现。

        add(sectionLabel("模糊强度"));
        add(slider(0, 100, config.blurStrength, value -> config.blurStrength = value), "growx");

        add(sectionLabel("界面缩放"));
        add(slider(80, 150, config.uiScale, value -> {
        // 界面缩放立刻调用 setZoom，让用户拖动时就能看到字号变化。
            config.uiScale = value;
            AppTheme.setZoom(value / 100f);
        }), "growx");

        JButton close = new JButton("完成");
        close.addActionListener(e -> dispose());
        add(close, "right, gaptop 12");

        // pack() 让窗口贴合控件尺寸，避免固定尺寸在 150% 缩放下溢出。
        pack();
        setLocationRelativeTo(owner);
        // 建好后立刻把焦点交给录制框：用户点开设置就是为了改键，省掉一次多余的点击。
        // 必须延后到窗口可见之后，否则这一次 requestFocusInWindow 会被随后的显示流程覆盖。
        SwingUtilities.invokeLater(bindField::requestFocusInWindow);
    }

    /**
     * 创建一个分区小标题。
     *
     * @param text 标题文本
     * @return 已套用副标题样式的标签
     */
    private JLabel sectionLabel(String text) {
        JLabel label = new JLabel(text);
        AppTheme.bindFont(label, Font.PLAIN, 0.95f);
        label.setForeground(AppTheme.TEXT_MUTED);
        return label;
    }

    /**
     * 构建快捷键录制行：单击进入录制，按下任意键即绑定。
     *
     * @return 只含一个不可编辑输入框的透明面板
     */
    private JPanel buildBindRow() {
        JPanel row = new JPanel(new MigLayout("insets 0, fillx, wrap 1", "[grow,fill]", "[]2[]"));
        row.setOpaque(false);

        // 不可编辑但可聚焦：既防止用户直接输入文本，又能稳定接收到按键事件。
        bindField.setEditable(false);
        bindField.setFocusable(true);
        bindField.setText(config.guiBind);
        AppTheme.bindFont(bindField, Font.BOLD, 1.0f);
        bindField.setToolTipText("点击后按下要绑定的按键");

        // 改键提示：快捷键由注入器在 attach 时写死，改完必须重新注入才会传到游戏。
        bindHint.setForeground(AppTheme.ACCENT);
        AppTheme.bindFont(bindHint, Font.PLAIN, 0.8f);

        bindField.addFocusListener(new FocusAdapter() {
            @Override
            // 获得焦点即进入录制态，此时字段内容变为提示文案而不是真实绑定值。
            public void focusGained(FocusEvent e) {
                recording = true;
                bindField.setText("按下按键…");
            }

            @Override
            // 失焦时退出录制并还原为当前绑定值，避免提示文案被当成配置保存。
            public void focusLost(FocusEvent e) {
                recording = false;
                bindField.setText(config.guiBind);
            }
        });
        bindField.addKeyListener(new KeyAdapter() {
            @Override
            // 非录制态的按键（如 Tab 切换焦点）必须放行，否则用户无法离开该字段。
            public void keyPressed(KeyEvent e) {
                if (!recording) {
                    return;
                }
                e.consume();
                if (e.getKeyCode() == KeyEvent.VK_ESCAPE) {
                    // Esc 取消录制而不是绑定「Esc」本身。
                    recording = false;
                    bindField.setText(config.guiBind);
                    return;
                }
                // 记录一次即退出录制：快捷键是单键/单组合，不支持后续修饰。
                String recorded = describe(e);
                if (!recorded.equals(config.guiBind)) {
                    config.guiBind = recorded;
                    // 快捷键在 attach 时写死传给游戏，改完必须重新注入才生效。
                    bindHint.setText("快捷键已修改，需重新注入后生效");
                }
                recording = false;
                bindField.setText(config.guiBind);
            }
        });

        // 输入框已有焦点时点击不会再次触发 focusGained，
        // 因此监听器无条件进入录制态；再延迟一个事件循环请求焦点，
        // 因为此刻鼠标仍按在输入框上，立即请求会被这次点击吃掉。
        bindField.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mousePressed(java.awt.event.MouseEvent e) {
                recording = true;
                SwingUtilities.invokeLater(bindField::requestFocusInWindow);
            }
        });

        row.add(bindField, "growx");
        row.add(bindHint, "growx");
        return row;
    }

    /**
     * 按游戏命名快捷键的方式渲染一个按键事件，例如 {@code RSHIFT} 或 {@code CTRL+F}。
     *
     * @param event 待渲染的按键事件
     * @return 形如 {@code SHIFT+RSHIFT} 的组合键字符串
     */
    static String describe(KeyEvent event) {
        String key = baseKeyName(event);
        // 依次拼接修饰键；本身正在按下的修饰键不在此处重复，由 baseKeyName 负责。
        StringBuilder combo = new StringBuilder();
        if (event.isControlDown() && event.getKeyCode() != KeyEvent.VK_CONTROL) {
            combo.append("CTRL+");
        }
        // 排除「当前按下的就是该修饰键」本身：否则按住 RSHIFT 会得到 SHIFT+RSHIFT。
        if (event.isShiftDown() && event.getKeyCode() != KeyEvent.VK_SHIFT) {
            combo.append("SHIFT+");
        }
        if (event.isAltDown() && event.getKeyCode() != KeyEvent.VK_ALT) {
            combo.append("ALT+");
        }
        return combo.append(key).toString();
    }

    /**
     * 返回按键本身的名字，不含任何修饰键前缀。
     *
     * @param event 待取名的按键事件
     * @return 例如 {@code LSHIFT}、{@code RALT}、{@code A}；无法识别时退回 {@code KEY<code>}
     */
    private static String baseKeyName(KeyEvent event) {
        int code = event.getKeyCode();
        boolean right = event.getKeyLocation() == KeyEvent.KEY_LOCATION_RIGHT;
        boolean numpad = event.getKeyLocation() == KeyEvent.KEY_LOCATION_NUMPAD;
        // 左右 Shift/Control/Alt/Windows 在游戏里有独立绑定，AWT 无法从 keyCode 区分，靠 location 判断。
        switch (code) {
            case KeyEvent.VK_SHIFT:
                return right ? "RSHIFT" : "LSHIFT";
            case KeyEvent.VK_CONTROL:
                return right ? "RCTRL" : "LCTRL";
            case KeyEvent.VK_ALT:
                return right ? "RALT" : "LALT";
            case KeyEvent.VK_WINDOWS:
                return right ? "RWIN" : "LWIN";
            case KeyEvent.VK_ENTER:
                // 小键盘回车与主回车共用 VK_ENTER，靠 location 区分名字。
                return numpad ? "NUMPADENTER" : "ENTER";
            default:
                // 其余键统一走 KeyCodes 的规范表：录出来的名字必定能原样解析回 VK。
                return KeyCodes.nameForVk(code);
        }
    }

    /**
     * 构建一行「滑块 + 数值标签」。
     *
     * @param min 最小值
     * @param max 最大值
     * @param initial 初始值，同时用于初次显示的百分比文本
     * @param onChange 值变化回调（拖动中即触发）
     * @return 透明的两列面板
     */
    private JPanel slider(int min, int max, int initial, Consumer<Integer> onChange) {
        JPanel wrapper = new JPanel(new MigLayout("insets 0", "[grow,fill]8[]", "[]"));
        wrapper.setOpaque(false);

        JLabel value = new JLabel(initial + "%");
        value.setForeground(AppTheme.TEXT);
        AppTheme.bindFont(value, Font.PLAIN, 1.0f);

        JSlider slider = new JSlider(min, max, initial);
        slider.setOpaque(false);
        // 变化回调在拖动过程中就会触发，缩放因此是「实时」的。
        slider.addChangeListener(e -> {
            int current = slider.getValue();
            value.setText(current + "%");
            onChange.accept(current);
        });

        wrapper.add(slider, "growx");
        wrapper.add(value, "w 52!");
        return wrapper;
    }

    /**
     * {@inheritDoc}
     *
     * <p>覆写以保证无论用户点「完成」还是直接点右上角 X（{@code DISPOSE_ON_CLOSE}），偏好都会保存
     * 且回调只触发一次。重复调用是安全的：{@link #disposed} 保证保存与回调不会跑第二遍。
     */
    @Override
    public void dispose() {
        if (disposed) {
            return;
        }
        disposed = true;
        config.save();
        onApply.accept(config);
        super.dispose();
    }
}
