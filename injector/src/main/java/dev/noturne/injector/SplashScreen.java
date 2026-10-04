package dev.noturne.injector;

import net.miginfocom.swing.MigLayout;

import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JProgressBar;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsDevice;
import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

/**
 * 无边框启动画面：淡入，进度条走 {@value #HOLD_MS} 毫秒，淡出，然后交棒给主窗口。
 * 在任意位置点击一下即可跳过整段动画。
 */
public final class SplashScreen {

    /** 淡入时长（毫秒）。 */
    private static final int FADE_IN_MS = 350;
    /** 进度条匀速推进的时长（毫秒）。 */
    private static final int HOLD_MS = 1200;
    /** 淡出时长（毫秒）。 */
    private static final int FADE_OUT_MS = 200;
    /** 动画定时器间隔（毫秒）；20ms 约合 60fps。 */
    private static final int FRAME_MS = 20;
    /** 启动画面固定宽度（逻辑像素）。 */
    private static final int WIDTH = 360;
    /** 启动画面固定高度（逻辑像素）。 */
    private static final int HEIGHT = 170;

    /** 无边框窗口本体；显示后由本类负责销毁。 */
    private final JWindow window = new JWindow();
    /** 进度条，取值 0–100。 */
    private final JProgressBar progress = new JProgressBar(0, 100);

    /**
     * 显示启动画面，并且恰好调用一次 {@code onFinished}——无论动画正常播完还是用户点击跳过。
     *
     * <p>必须在 EDT 上调用。回调被投递到 EDT，因此回调里可以安全地创建主窗口。
     *
     * @param onFinished 动画结束时的回调，恰好执行一次
     */
    public void show(Runnable onFinished) {
        window.setBackground(AppTheme.BACKGROUND);
        // JWindow 没有装饰边框，且必须置顶，否则会被主窗口盖住。
        window.setLayout(new MigLayout("insets 26, fillx, wrap 1", "[grow,center]", "[]10[]18[]"));
        // 置顶：启动画面比主窗口先出现，必须压在所有窗口之上。
        window.setAlwaysOnTop(true);

        JLabel title = new JLabel("Noturne \u00b7 诺克特恩");
        title.setFont(AppTheme.scaled(Font.BOLD, 1.6f));
        title.setForeground(AppTheme.TEXT);

        JLabel subtitle = new JLabel("正在启动…");
        subtitle.setFont(AppTheme.scaled(Font.PLAIN, 0.9f));
        subtitle.setForeground(AppTheme.TEXT_MUTED);

        progress.setValue(0);
        progress.setBorderPainted(false);
        progress.setForeground(AppTheme.ACCENT);
        progress.setBackground(AppTheme.PANEL);
        // 进度条高度按固定尺寸给，宽度扣掉两侧内边距，避免换算成逻辑分辨率比例后变形。
        progress.setPreferredSize(new Dimension(WIDTH - 52, 6));

        JPanel content = new JPanel(new MigLayout("insets 0, fillx, wrap 1", "[grow,center]", "[]6[]16[]"));
        content.setBackground(AppTheme.BACKGROUND);
        content.add(title, "center");
        content.add(subtitle, "center");
        content.add(progress, "growx, h 6!");

        window.setContentPane(content);
        // 显式设定尺寸而不是 pack()：MigLayout 的首选尺寸在缩放下不稳定。
        window.setSize(WIDTH, HEIGHT);
        // 位置必须相对「鼠标所在屏」而不是主屏，多显示器下才不会跳屏。
        centreOnActiveScreen();
        // 显式从 0 透明度开始：某些平台会缓存上一次窗口的透明度。
        window.setOpacity(0f);
        window.setVisible(true);

        final long start = System.currentTimeMillis();
        // 用数组包装：Timer 监听器是 lambda，必须借助 effectively-final 的可变容器。
        final boolean[] handedOver = {false};
        final Runnable finish = () -> {
            if (handedOver[0]) {
                return;
            }
            handedOver[0] = true;
            // 跳过与自然结束可能同帧发生，用这个标志保证回调只跑一次。
            SwingUtilities.invokeLater(onFinished);
        };

        window.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                window.setVisible(false);
                // 点击跳过：先隐藏再回调，避免主窗口出现时两个窗口同时可见造成闪烁。
                finish.run();
            }
        });

        // Timer(FRAME_MS, null) 只是为了拿到实例，监听器随后单独注册。
        Timer timer = new Timer(FRAME_MS, null);
        timer.addActionListener(e -> {
            // 用墙钟时间而非帧计数推进：掉帧时动画时长不会漂移。
            long elapsed = System.currentTimeMillis() - start;
            try {
                if (elapsed < FADE_IN_MS) {
                    window.setOpacity(Math.min(1f, elapsed / (float) FADE_IN_MS));
                } else if (elapsed < FADE_IN_MS + HOLD_MS) {
                    window.setOpacity(1f);
                    progress.setValue((int) ((elapsed - FADE_IN_MS) * 100 / HOLD_MS));
                } else if (elapsed < FADE_IN_MS + HOLD_MS + FADE_OUT_MS) {
                    // 淡出段进度条保持 100%，只把不透明度往下压。
                    progress.setValue(100);
                    window.setOpacity(1f - (elapsed - FADE_IN_MS - HOLD_MS) / (float) FADE_OUT_MS);
                } else {
                    timer.stop();
                    window.setVisible(false);
                    window.dispose();
                    finish.run();
                }
            } catch (Throwable ignored) {
                // 部分平台不支持透明度设置：立即收场而不是让启动画面卡住不动。
                timer.stop();
                window.setVisible(false);
                window.dispose();
                finish.run();
            }
        });
        timer.start();
    }

    /** 把启动画面居中到鼠标所在的那块屏幕，与主窗口的定位规则保持一致。 */
    private void centreOnActiveScreen() {
        Rectangle screen = WindowGeometry.activeScreenBounds();
        window.setLocation(screen.x + (screen.width - window.getWidth()) / 2,
                screen.y + (screen.height - window.getHeight()) / 2);
    }
}
