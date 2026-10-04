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
     * <p>发布 jar 的 {@code Main-Class} 是本类（双击必须出 GUI）；但 C# 启动器会用
     * {@code --list-json} / {@code --pid=<n>} 调用同一个 jar 并期望拿到命令行行为。
     * 为此，这里在触发任何 Swing/relaunch 之前先做 CLI 分流：命中则直接转发给
     * {@link dev.noturne.core.Noturne#main(String[])} 并返回，绝不初始化 GUI
     * （否则 GUI 进程常驻不退出，启动器必然超时）。
     *
     * <p>GUI 路径下，整个启动序列都被包在 {@link SwingUtilities#invokeLater(Runnable)} 里；
     * 顺序是固定的：attach 自举 → {@link AppTheme#install()} → {@link AppConfig#load()} →
     * 应用缩放 → 启动画面 → 主窗口。
     *
     * @param args 命令行参数；{@code --list-json} / {@code --pid=<n>} 走 CLI，其余原样传给
     *             {@link ToolsJarBootstrap#relaunchIfNeeded(String, String[])}
     */
    public static void main(String[] args) {
        // CLI 分流必须先于 relaunch 与 Swing：命令行调用不需要 GUI，也不该被 GUI 自举拖住。
        if (isCliInvocation(args)) {
            try {
                // 直接转发（而非再起进程）：Noturne.main 自身会处理 JDK 8 的 tools.jar 自举。
                dev.noturne.core.Noturne.main(args);
            } catch (Throwable t) {
                System.err.println("[noturne] injector CLI failed: " + t);
                t.printStackTrace();
                System.exit(1);
            }
            return;
        }
        // JDK 8 把 attach API 放在 lib/tools.jar 里；若不在 classpath 上，就带它重启一次。
        if (ToolsJarBootstrap.relaunchIfNeeded("dev.noturne.injector.InjectorApp", args)) {
            return;
        }
        // 之后所有 Swing 组件的创建都必须回到 EDT 上执行。
        SwingUtilities.invokeLater(new Runnable() {
            @Override
            public void run() {
                // 最外层兜底：headless / javaw 环境下初始化异常若逃逸，将完全无窗口且无输出。
                try {
                    AppTheme.install();
                    // 主题必须先于任何组件创建，否则 FlatLaf 的默认值不会作用到已建好的控件上。
                    AppConfig config = AppConfig.load();
                    // 缩放要在读配置之后应用：config.json 里的 uiScale 是用户偏好，优先级最高。
                    AppTheme.setZoom(config.uiScale / 100f);
                    // 直接显示主窗口：不做过场动画，双击到可用窗口的路径越短越好。
                    new MainWindow(config).setVisible(true);
                } catch (Throwable t) {
                    System.err.println("[noturne] injector GUI failed to start: " + t);
                    t.printStackTrace();
                }
            }
        });
    }

    /** 判断本次调用是否属于命令行分流（{@code --list-json} 或任一 {@code --pid=<n>} 参数）。 */
    private static boolean isCliInvocation(String[] args) {
        if (args == null) {
            return false;
        }
        for (String arg : args) {
            if ("--list-json".equals(arg) || (arg != null && arg.startsWith("--pid="))) {
                return true;
            }
        }
        return false;
    }
}
