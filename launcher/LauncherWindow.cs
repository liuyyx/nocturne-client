using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Text;
using System.Text.Json;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;

namespace NoturneLauncher;

/// <summary>
/// noturne 注入器的深色卡片式图形前端。
/// <para>
/// 该窗口是“壳”而非内核：它不引用 jar 中的任何类型，只把 <c>noturne-*.jar</c> 当作
/// 外部进程驱动（<c>--list-json</c> 枚举目标进程、<c>--pid=</c> 执行注入），因此客户端升级
/// 后无需重新编译本启动器。窗口本身负责扫描/展示 Minecraft 进程、选择目标并触发注入，
/// 并把子进程输出回显到日志区。
/// </para>
/// </summary>
public sealed class LauncherWindow : Window
{
    // 调色板：全部在类型初始化时 Freeze，避免每次渲染都产生新的可冻结对象。
    private static readonly Brush BackgroundBrush = Freeze(Color.FromRgb(0x0E, 0x0E, 0x12));
    private static readonly Brush CardBrush = Freeze(Color.FromRgb(0x17, 0x17, 0x1E));
    private static readonly Brush TextBrush = Freeze(Color.FromRgb(0xEC, 0xEC, 0xF4));
    private static readonly Brush MutedBrush = Freeze(Color.FromRgb(0x8E, 0x8E, 0xA6));
    private static readonly Brush AccentBrush = Freeze(Color.FromRgb(0x7A, 0x5C, 0xFF));
    private static readonly Brush OkBrush = Freeze(Color.FromRgb(0x6E, 0xDB, 0xA0));
    private static readonly Brush ErrorBrush = Freeze(Color.FromRgb(0xE0, 0x64, 0x5C));

    // 控件在构造期创建并保存为字段，以便 refresh/inject 回调在异步返回后仍能更新它们。
    private readonly ListBox _processList = new();
    private readonly TextBox _log = new();
    private readonly TextBlock _status = new();
    private readonly Button _injectButton = new();
    private readonly Button _refreshButton = new();
    // 与 _processList 中显示项一一对应；索引用于把选中行映射回进程 pid。
    private readonly List<ProcessEntry> _entries = new();

    /// <summary>
    /// 构建窗口并装配完整 UI。窗口无系统边框（自定义标题栏负责拖动/关闭），
    /// 采用居中启动；<paramref name="args"/> 目前仅用于未来扩展，尚未参与解析。
    /// 布局为 DockPanel：顶部标题栏、底部页脚（日志 + 操作按钮）、中间填满进程列表。
    /// 构造完成后注册 Loaded 回调首轮扫描进程。
    /// </summary>
    /// <param name="args">命令行参数（当前保留，未使用）。</param>
    public LauncherWindow(string[] args)
    {
        Title = "noturne \u2014 launcher";
        Width = 980;
        Height = 680;
        MinWidth = 780;
        MinHeight = 540;
        // 无边框 + 透明背景是自绘圆角卡片的必要条件：系统边框会强制方形不透明窗口。
        WindowStyle = WindowStyle.None;
        AllowsTransparency = true;
        Background = Brushes.Transparent;
        WindowStartupLocation = WindowStartupLocation.CenterScreen;
        FontFamily = new FontFamily("Microsoft YaHei UI, Segoe UI");

        // 优先使用微软雅黑以保证中文界面观感，回退到 Segoe UI 覆盖拉丁字符。
        var root = new DockPanel { LastChildFill = true, Margin = new Thickness(26, 12, 26, 26) };
        var titleBar = BuildTitleBar();
        DockPanel.SetDock(titleBar, Dock.Top);
        root.Children.Add(titleBar);
        // 内容区品牌头（大标题 + 副标题）：此前写好却从未挂进布局，等于白写。
        var header = BuildHeader();
        DockPanel.SetDock(header, Dock.Top);
        root.Children.Add(header);
        var footer = BuildFooter();
        DockPanel.SetDock(footer, Dock.Bottom);
        root.Children.Add(footer);
        // 最后添加者填满剩余空间（LastChildFill），因此进程列表必须排在最后。
        root.Children.Add(BuildProcessCard());

        Content = new Border
        {
            Background = BackgroundBrush,
            CornerRadius = new CornerRadius(20),
            BorderBrush = Freeze(Color.FromArgb(0x18, 0xFF, 0xFF, 0xFF)),
            BorderThickness = new Thickness(1),
            Child = root,
        };
        Loaded += (_, _) => RefreshProcesses();
        Log("noturne launcher ready.");
    }

    // ------------------------------------------------------------------ layout

    /// <summary>
    /// 构建自定义标题栏：左侧品牌文字、右侧关闭按钮，整条可拖动窗口。
    /// 因为窗口样式为 <see cref="WindowStyle.None"/>，系统边框与拖动行为全部由这里模拟。
    /// </summary>
    /// <returns>作为 DockPanel 顶部子元素使用的标题栏。</returns>
    private UIElement BuildTitleBar()
    {
        var bar = new Grid { Margin = new Thickness(0, 6, 0, 0) };
        bar.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        bar.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        bar.Background = Brushes.Transparent;
        bar.MouseLeftButtonDown += (_, _) =>
        {
            try
            {
                DragMove();
            }
            catch (InvalidOperationException)
            {
                // 鼠标在拖动真正开始前就已释放，WPF 会抛此异常；忽略即可。
            }
        };

        var brand = new TextBlock
        {
            Text = "noturne",
            FontSize = 12.5,
            Foreground = MutedBrush,
            VerticalAlignment = VerticalAlignment.Center,
        };
        Grid.SetColumn(brand, 0);
        bar.Children.Add(brand);

        var close = new Button
        {
            Content = "\u2715",
            Width = 30,
            Height = 26,
            Background = Brushes.Transparent,
            Foreground = MutedBrush,
            BorderThickness = new Thickness(0),
            FontSize = 12,
            Cursor = System.Windows.Input.Cursors.Hand,
        };
        close.Click += (_, _) => Close();
        Grid.SetColumn(close, 1);
        bar.Children.Add(close);

        bar.Height = 34;
        return bar;
    }

    /// <summary>
    /// 构建内容区顶部的品牌头（大标题 + 副标题）。
    /// 与窗口标题栏上的小字品牌标记分工不同：这里承担内容区的视觉入口。
    /// </summary>
    /// <returns>竖直排列的两行文字面板。</returns>
    private UIElement BuildHeader()
    {
        var panel = new StackPanel { Margin = new Thickness(0, 0, 0, 18) };

        panel.Children.Add(new TextBlock
        {
            Text = "noturne",
            FontSize = 30,
            FontWeight = FontWeights.Bold,
            Foreground = TextBrush,
        });
        panel.Children.Add(new TextBlock
        {
            Text = "cross-version injection client",
            FontSize = 13,
            Foreground = MutedBrush,
            Margin = new Thickness(0, 2, 0, 0),
        });
        return panel;
    }

    /// <summary>
    /// 构建进程列表卡片，并在此挂载列表样式与选中事件。
    /// 选中项变化时同步刷新注入按钮的可用性：无选中即不可注入。
    /// </summary>
    /// <returns>带圆角边框的列表容器。</returns>
    private UIElement BuildProcessCard()
    {
        _processList.Background = Brushes.Transparent;
        _processList.BorderThickness = new Thickness(0);
        _processList.Foreground = TextBrush;
        _processList.FontSize = 14;
        _processList.ItemContainerStyle = BuildRowStyle();
        _processList.SelectionChanged += (_, _) =>
            _injectButton.IsEnabled = _processList.SelectedIndex >= 0;

        var border = new Border
        {
            Background = CardBrush,
            CornerRadius = new CornerRadius(14),
            BorderBrush = Freeze(Color.FromArgb(0x20, 0xFF, 0xFF, 0xFF)),
            BorderThickness = new Thickness(1),
            Padding = new Thickness(6),
            Child = _processList,
        };
        return border;
    }

    /// <summary>
    /// 更新状态文字并按语义着色：成功绿、失败红、进行中灰。
    /// </summary>
    /// <remarks>
    /// 原先只设置文字、颜色恒为灰，于是「注入成功」与「注入失败」看起来一模一样；
    /// 统一走这里可以让颜色与语义始终一致。
    /// </remarks>
    /// <param name="text">状态文字。</param>
    /// <param name="brush">文字颜色。</param>
    private void SetStatus(string text, Brush brush)
    {
        _status.Text = text;
        _status.Foreground = brush;
    }

    /// <summary>
    /// 构建页脚：上方日志框、下方状态文字与「Refresh」/「Inject」两个按钮。
    /// 日志框只读并自动滚动到底部，便于观察子进程输出。
    /// </summary>
    /// <returns>作为 DockPanel 底部子元素的页脚面板。</returns>
    private UIElement BuildFooter()
    {
        var footer = new StackPanel { Margin = new Thickness(0, 18, 0, 0) };

        _log.IsReadOnly = true;
        _log.AcceptsReturn = true;
        _log.TextWrapping = TextWrapping.Wrap;
        _log.Background = CardBrush;
        _log.Foreground = Freeze(Color.FromRgb(0xB9, 0xB9, 0xCC));
        _log.FontFamily = new FontFamily("Consolas, Cascadia Mono");
        _log.FontSize = 12.5;
        _log.Height = 160;
        _log.Padding = new Thickness(12);
        _log.VerticalScrollBarVisibility = ScrollBarVisibility.Auto;

        footer.Children.Add(new Border
        {
            Background = CardBrush,
            CornerRadius = new CornerRadius(14),
            BorderBrush = Freeze(Color.FromArgb(0x20, 0xFF, 0xFF, 0xFF)),
            BorderThickness = new Thickness(1),
            Padding = new Thickness(2),
            Child = _log,
        });

        var row = new Grid { Margin = new Thickness(0, 14, 0, 0) };
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = new GridLength(1, GridUnitType.Star) });
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });
        row.ColumnDefinitions.Add(new ColumnDefinition { Width = GridLength.Auto });

        _status.Foreground = MutedBrush;
        _status.FontSize = 12.5;
        _status.VerticalAlignment = VerticalAlignment.Center;
        Grid.SetColumn(_status, 0);
        row.Children.Add(_status);

        StyleButton(_refreshButton, "Refresh", Freeze(Color.FromRgb(0x2A, 0x2A, 0x36)));
        _refreshButton.Margin = new Thickness(0, 0, 10, 0);
        _refreshButton.Click += (_, _) => RefreshProcesses();
        Grid.SetColumn(_refreshButton, 1);
        row.Children.Add(_refreshButton);

        StyleButton(_injectButton, "Inject", AccentBrush);
        _injectButton.IsEnabled = false;
        _injectButton.Click += (_, _) => InjectSelected();
        Grid.SetColumn(_injectButton, 2);
        row.Children.Add(_injectButton);

        footer.Children.Add(row);
        return footer;
    }

    /// <summary>
    /// 为按钮套用统一的深色扁平外观：去掉系统边框、用圆角 Border 作模板，
    /// 保证与卡片刻意保持一致（WPF 默认按钮模板在无边框窗口中观感突兀）。
    /// </summary>
    /// <param name="button">被就地改造的按钮实例。</param>
    /// <param name="text">按钮显示文本。</param>
    /// <param name="background">背景填充画刷。</param>
    private static void StyleButton(Button button, string text, Brush background)
    {
        button.Content = text;
        button.Background = background;
        button.Foreground = Brushes.White;
        button.BorderThickness = new Thickness(0);
        button.FontWeight = FontWeights.SemiBold;
        button.FontSize = 14;
        button.Padding = new Thickness(22, 10, 22, 10);
        button.MinWidth = 120;
        button.Cursor = System.Windows.Input.Cursors.Hand;

        var template = new ControlTemplate(typeof(Button));
        var border = new FrameworkElementFactory(typeof(Border));
        border.SetValue(Border.CornerRadiusProperty, new CornerRadius(10));
        border.SetValue(Border.BackgroundProperty, background);
        border.SetValue(Border.PaddingProperty, new Thickness(22, 10, 22, 10));
        var content = new FrameworkElementFactory(typeof(ContentPresenter));
        content.SetValue(FrameworkElement.HorizontalAlignmentProperty, HorizontalAlignment.Center);
        content.SetValue(FrameworkElement.VerticalAlignmentProperty, VerticalAlignment.Center);
        border.AppendChild(content);
        template.VisualTree = border;
        button.Template = template;
    }

    /// <summary>
    /// 构造列表项样式：透明背景让行背景透出卡片色，Padding/Margin 提供行间距。
    /// </summary>
    /// <returns>应用于 <c>ListBoxItem</c> 的隐式样式。</returns>
    private static Style BuildRowStyle()
    {
        var style = new Style(typeof(ListBoxItem));
        style.Setters.Add(new Setter(Control.BackgroundProperty, Brushes.Transparent));
        style.Setters.Add(new Setter(Control.ForegroundProperty, TextBrush));
        style.Setters.Add(new Setter(Control.PaddingProperty, new Thickness(14, 10, 14, 10)));
        style.Setters.Add(new Setter(FrameworkElement.MarginProperty, new Thickness(0, 1, 0, 1)));
        return style;
    }

    // --------------------------------------------------------------- behaviour

    /// <summary>
    /// 扫描可注入的 Minecraft 进程：定位 jar 与 java，随后在后台线程执行
    /// <c>--list-json</c> 子进程，并把结果回填到 UI 线程。
    /// 扫描期间禁用刷新按钮防止重入。
    /// </summary>
    private void RefreshProcesses()
    {
        SetStatus("Scanning\u2026", MutedBrush);
        _refreshButton.IsEnabled = false;
        string? jar = FindJar();
        string? java = FindJava();

        if (jar == null || java == null)
        {
            SetStatus("noturne jar or java not found", ErrorBrush);
            Log(jar == null ? "jar not found next to the launcher." : "java not found (set JAVA_HOME).");
            _refreshButton.IsEnabled = true;
            return;
        }

        // 子进程调用为阻塞 IO，放到线程池避免冻结 UI；所有控件写入都回到 Dispatcher。
        System.Threading.Tasks.Task.Run(() =>
        {
            string output = Run(java, $"-jar \"{jar}\" --list-json");
            List<ProcessEntry> entries = ParseEntries(output);
            Dispatcher.Invoke(() =>
            {
                _entries.Clear();
                _entries.AddRange(entries);
                _processList.Items.Clear();
                // 列表显示字符串，_entries 与显示项保持同序，用索引回映射 pid。
                foreach (ProcessEntry entry in entries)
                {
                    _processList.Items.Add(entry.ToString());
                }
                _refreshButton.IsEnabled = true;
                SetStatus(
                    entries.Count == 0
                        ? "No Minecraft process found \u2014 start the game first"
                        : $"{entries.Count} process(es)",
                    entries.Count == 0 ? ErrorBrush : OkBrush);
                Log($"Found {entries.Count} process(es).");
            });
        });
    }

    /// <summary>
    /// 对当前选中的进程执行注入：校验选择有效性与运行环境后，在后台线程以
    /// <c>--pid=</c> 启动子进程，逐行回显输出并据输出判定成败。
    /// 执行期间禁用注入按钮，结束后恢复。
    /// </summary>
    private void InjectSelected()
    {
        int index = _processList.SelectedIndex;
        if (index < 0 || index >= _entries.Count)
        {
            Log("Select a process first.");
            return;
        }
        ProcessEntry entry = _entries[index];
        string? jar = FindJar();
        string? java = FindJava();
        if (jar == null || java == null)
        {
            return;
        }
        // 环境缺失时静默返回：启动器不做模态弹窗，缺失原因已在扫描时记录过日志。

        _injectButton.IsEnabled = false;
        Log($"Injecting into pid {entry.Pid}\u2026");
        System.Threading.Tasks.Task.Run(() =>
        {
            // --pid= 必须紧贴等号无空格；结果同样通过 Dispatcher 回调回到 UI 线程更新。
            string output = Run(java, $"-jar \"{jar}\" --pid={entry.Pid}");
            Dispatcher.Invoke(() =>
            {
                foreach (string line in output.Split('\n'))
                {
                    if (line.Trim().Length > 0)
                    {
                        Log(line.Trim());
                    }
                }
                // jar 侧唯一的成功信号是输出中出现 "agent loaded"，
                // 用序数忽略大小写匹配以容忍日志风格差异。
                bool ok = output.Contains("agent loaded", StringComparison.OrdinalIgnoreCase);
                SetStatus(ok ? $"Injected into pid {entry.Pid}" : "Injection failed",
                    ok ? OkBrush : ErrorBrush);
                _injectButton.IsEnabled = true;
            });
        });
    }

    /// <summary>
    /// 向日志框追加一条带本地时间戳的记录并滚动到末尾。只能在 UI 线程调用。
    /// </summary>
    /// <param name="message">日志正文。</param>
    private void Log(string message)
    {
        _log.AppendText($"[{DateTime.Now:HH:mm:ss}]  {message}{Environment.NewLine}");
        // 始终滚动到末尾，保证最新一行可见（子进程输出可能瞬间刷出几十行）。
        _log.ScrollToEnd();
    }

    // ------------------------------------------------------------------ helpers

    /// <summary>
    /// 从子进程 stdout 中截取首个 '[' 到末个 ']' 的 JSON 数组并反序列化为进程列表。
    /// 采用「截取数组片段」而非整段解析，是为了容忍 jar 打印的日志噪声；
    /// 解析失败时保留已成功解析的部分，不抛异常。
    /// </summary>
    /// <param name="json">子进程原始输出。</param>
    /// <returns>解析得到的条目列表；无法解析时为空列表。</returns>
    private static List<ProcessEntry> ParseEntries(string json)
    {
        var list = new List<ProcessEntry>();
        int start = json.IndexOf('[');
        int end = json.LastIndexOf(']');
        if (start < 0 || end <= start)
        {
            return list;
        }
        try
        {
            using JsonDocument document = JsonDocument.Parse(json.Substring(start, end - start + 1));
            foreach (JsonElement element in document.RootElement.EnumerateArray())
            {
                list.Add(new ProcessEntry(
                    element.GetProperty("pid").GetInt32(),
                    element.TryGetProperty("title", out JsonElement title) ? title.GetString() ?? "" : "",
                    element.TryGetProperty("command", out JsonElement command) ? command.GetString() ?? "" : ""));
            }
        }
        catch (JsonException)
        {
            // 解析失败时静默降级：把已经成功解析出的条目交回调用方。
        }
        return list;
    }

    /// <summary>
    /// 以子进程方式运行 java，捕获 stdout 与 stderr 并拼接返回。
    /// 20 秒超时后强制杀死进程树；所有异常都被折叠成 <c>error: ...</c> 文本，
    /// 以便调用方统一按字符串处理而无需 try/catch。
    /// </summary>
    /// <param name="fileName">可执行文件路径。</param>
    /// <param name="arguments">命令行参数（含引号）。</param>
    /// <returns>stdout + stderr 文本，或以 "error: " 开头的错误描述。</returns>
    private static string Run(string fileName, string arguments)
    {
        try
        {
            var info = new ProcessStartInfo(fileName, arguments)
            {
                RedirectStandardOutput = true,
                RedirectStandardError = true,
                UseShellExecute = false,
                CreateNoWindow = true,
                StandardOutputEncoding = Encoding.UTF8,
                // 显式指定 UTF-8：jar 输出的进程标题含中文，缺省会按系统代码页解码成乱码。
                StandardErrorEncoding = Encoding.UTF8,
            };
            using Process? process = Process.Start(info);
            if (process == null)
            {
                return "";
            }
            string stdout = process.StandardOutput.ReadToEnd();
            string stderr = process.StandardError.ReadToEnd();
            // 20 秒足够完成 attach；超时多半是目标 JVM 卡死或权限不足，杀掉进程树避免留下孤儿。
            if (!process.WaitForExit(20000))
            {
                try
                {
                    process.Kill(true);
                }
                catch (Exception)
                {
                    // 进程已自行退出，无需处理。
                }
                return stdout + stderr + Environment.NewLine + "error: timed out";
            }
            return stdout + stderr;
        }
        catch (Exception ex)
        {
            return "error: " + ex.Message;
        }
    }

    /// <summary>
    /// 定位 java 可执行文件：优先 <c>JAVA_HOME\bin\java.exe</c>，
    /// 否则在常见 JDK 安装根目录下挑版本号最大的 <c>jdk*</c>，都找不到则回退到 PATH 中的 <c>java</c>。
    /// </summary>
    /// <returns>java 可执行文件路径；从未判为 null。</returns>
    private static string? FindJava()
    {
        string? home = Environment.GetEnvironmentVariable("JAVA_HOME");
        if (!string.IsNullOrEmpty(home))
        {
            string candidate = Path.Combine(home, "bin", "java.exe");
            if (File.Exists(candidate))
            {
                return candidate;
            }
        }
        // 各厂商把 JDK 并排安装在上述根目录下；按名称取最大版本，
        // 而不是硬编码某一台机器的具体构建目录。
        string[] roots =
        {
            @"C:\Program Files\Microsoft",
            @"C:\Program Files\Eclipse Adoptium",
            @"C:\Program Files\Java",
            @"C:\Program Files\Amazon Corretto",
            @"C:\Program Files\Zulu",
        };
        string? best = null;
        int bestVersion = -1;
        foreach (string root in roots)
        {
            if (!Directory.Exists(root))
            {
                continue;
            }
            foreach (string dir in Directory.GetDirectories(root, "jdk*"))
            {
                string candidate = Path.Combine(dir, "bin", "java.exe");
                if (!File.Exists(candidate))
                {
                    continue;
                }
                // 按版本号比较，而不是路径字典序：字典序下 "jdk-9" 会排在 "jdk-21" 之后，
                // 装了新旧两个 JDK 的机器上就会选中旧的那个。
                int version = ParseJdkVersion(Path.GetFileName(dir));
                if (best == null || version > bestVersion)
                {
                    best = candidate;
                    bestVersion = version;
                }
            }
        }
        return best ?? "java";
    }

    /// <summary>
    /// 从 JDK 目录名解析主版本号，覆盖 <c>jdk-21.0.12.8-hotspot</c>、<c>jdk1.8.0_411</c>、<c>jdk-17</c> 等形式。
    /// </summary>
    /// <param name="directoryName">目录名（不含路径）。</param>
    /// <returns>名字中第一段连续数字所表示的版本号；解析不出时返回 -1，该目录会被排在所有可解析目录之后。</returns>
    private static int ParseJdkVersion(string directoryName)
    {
        var digits = new System.Text.StringBuilder();
        foreach (char c in directoryName)
        {
            if (char.IsDigit(c))
            {
                digits.Append(c);
            }
            else if (digits.Length > 0)
            {
                // 第一段数字结束即停止：后续的 "0.12.8" 属于补丁号，不参与主版本比较。
                break;
            }
        }
        return digits.Length > 0 && int.TryParse(digits.ToString(), out int version) ? version : -1;
    }

    /// <summary>
    /// 在启动器所在目录查找 <c>noturne*.jar</c>，取最后修改时间最新者。
    /// 取最新是为了让多版本 jar 并存时优先使用刚更新的客户端。
    /// </summary>
    /// <returns>jar 路径；未找到时为 null。</returns>
    private static string? FindJar()
    {
        string baseDir = AppContext.BaseDirectory;
        // 防御性分支：程序目录理论上总存在，但卸载竞态下可能被移除。
        string[] candidates = Directory.Exists(baseDir)
            ? Directory.GetFiles(baseDir, "noturne*.jar")
            : Array.Empty<string>();
        if (candidates.Length > 0)
        {
            // 最新修改的 jar 优先，保证换客户端后无需手动清理旧版本。
            return candidates.OrderByDescending(File.GetLastWriteTimeUtc).First();
        }
        return null;
    }

    /// <summary>
    /// 创建并冻结纯色画刷。冻结后可在任意线程使用，避免 WPF 跨线程访问限制。
    /// </summary>
    /// <param name="color">画刷颜色。</param>
    /// <returns>已冻结的 <see cref="SolidColorBrush"/>。</returns>
    private static Brush Freeze(Color color)
    {
        var brush = new SolidColorBrush(color);
        brush.Freeze();
        return brush;
    }

    /// <summary>
    /// 一条被扫描到的目标进程记录：pid + 窗口标题 + 命令行。
    /// 标题或命令行可能缺失（无窗口的 java 进程），此时为空字符串。
    /// </summary>
    private sealed record ProcessEntry(int Pid, string Title, string Command)
    {
        /// <summary>
        /// 生成列表显示文本：左对齐 pid + 窗口标题，标题为空时回退为 "(no window)"。
        /// </summary>
        /// <returns>形如 "12345     Minecraft* 1.19"。</returns>
        public override string ToString()
        {
            string title = string.IsNullOrWhiteSpace(Title) ? "(no window)" : Title;
            return $"{Pid,-8}  {title}";
        }
    }
}
