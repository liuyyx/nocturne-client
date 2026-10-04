using System;
using System.Windows;

namespace NoturneLauncher;

/// <summary>
/// 启动器进程入口。这里刻意保持极薄：既不链接 jar 中的任何类型，也不做任何业务逻辑，
/// 只是把 jar 当子进程驱动（<c>--list-json</c> / <c>--pid=</c>），
/// 因此客户端升级后无需重新编译本壳。
/// 关闭模式设为 <see cref="ShutdownMode.OnMainWindowClose"/>，主窗口关闭即进程退出。
/// </summary>
public sealed class LauncherApp : Application
{
    /// <summary>
    /// 程序入口：在 STA 线程上创建 <see cref="Application"/> 并以 <see cref="LauncherWindow"/>
    /// 作为主窗口启动消息循环。
    /// </summary>
    /// <param name="args">命令行参数，原样透传给 <see cref="LauncherWindow"/> 构造函数。</param>
    [STAThread]
    public static void Main(string[] args)
    {
        // 窗口作为 Run 的参数传入，由 WPF 在此时设置 Application.MainWindow，
        // 从而使 OnMainWindowClose 关闭模式生效。
        var app = new LauncherApp { ShutdownMode = ShutdownMode.OnMainWindowClose };
        app.Run(new LauncherWindow(args));
    }
}
