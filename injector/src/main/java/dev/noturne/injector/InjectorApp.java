package dev.noturne.injector;

import dev.noturne.core.attach.ToolsJarBootstrap;

import javax.swing.SwingUtilities;

/**
 * 应用入口：先安装主题，再读取偏好设置，播放启动画面，最后显示主窗口。
 *
 * <p>本类刻意保持极薄——其余职责都在各自的类里，且任何 I/O 或 attach 操作都不会发生在事件
 * 分发线程（EDT）上。
 */
public final class InjectorApp {

    /** 工具类，不允许实例化。 */
    private InjectorApp() {
    }

    /**
     * 程序入口。
     *
     * <p>必须在 EDT 上创建任何 Swing 组件，因此整个启动序列都被包在
     * {@link SwingUtilities#invokeLater(Runnable)} 里；顺序是固定的：attach 自举 →
     * {@link AppTheme#install()} → {@link AppConfig#load()} → 应用缩放 → 启动画面 → 主窗口。
     *
     * @param args 命令行参数，原样传给 {@link ToolsJarBootstrap#relaunchIfNeeded(String, String[])}
     */
    public static void main(String[] args) {
        // JDK 8 把 attach API 放在 lib/tools.jar 里；若不在 classpath 上，就带它重启一次。
        if (ToolsJarBootstrap.relaunchIfNeeded("dev.noturne.injector.InjectorApp", args)) {
            return;
        }
        // 之后所有 Swing 组件的创建都必须回到 EDT 上执行。
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                AppTheme.install();
                // 主题必须先于任何组件创建，否则 FlatLaf 的默认值不会作用到已建好的控件上。
                AppConfig config = AppConfig.load();
                // 缩放要在读配置之后应用：config.json 里的 uiScale 是用户偏好，优先级最高。
                AppTheme.setZoom(config.uiScale / 100f);
                // 启动画面只是观感层，真正的重活（扫描进程、attach）都在主窗口的后台线程里。
                new SplashScreen().show(new Runnable() {
                    @Override
                    public void run() {
                        // 启动画面结束（或被点击跳过）后才显示主窗口，避免两者同时抢焦点。
                        new MainWindow(config).setVisible(true);
                    }
                });
            }
        });
    }
}
