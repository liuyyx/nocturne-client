using System;
using System.Windows;

namespace NoturneLauncher;

/// <summary>
/// Entry point. The shell is deliberately a thin front-end: it never links against the jar, it just
/// drives it as a subprocess, so the client can be updated without rebuilding this.
/// </summary>
public sealed class LauncherApp : Application
{
    [STAThread]
    public static void Main(string[] args)
    {
        var app = new LauncherApp { ShutdownMode = ShutdownMode.OnMainWindowClose };
        app.Run(new LauncherWindow(args));
    }
}
