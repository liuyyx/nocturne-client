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

/// <summary>Dark, card-based front-end for the noturne injector.</summary>
public sealed class LauncherWindow : Window
{
    private static readonly Brush BackgroundBrush = Freeze(Color.FromRgb(0x0E, 0x0E, 0x12));
    private static readonly Brush CardBrush = Freeze(Color.FromRgb(0x17, 0x17, 0x1E));
    private static readonly Brush RowBrush = Freeze(Color.FromRgb(0x1C, 0x1C, 0x24));
    private static readonly Brush TextBrush = Freeze(Color.FromRgb(0xEC, 0xEC, 0xF4));
    private static readonly Brush MutedBrush = Freeze(Color.FromRgb(0x8E, 0x8E, 0xA6));
    private static readonly Brush AccentBrush = Freeze(Color.FromRgb(0x7A, 0x5C, 0xFF));
    private static readonly Brush OkBrush = Freeze(Color.FromRgb(0x6E, 0xDB, 0xA0));
    private static readonly Brush ErrorBrush = Freeze(Color.FromRgb(0xE0, 0x64, 0x5C));

    private readonly ListBox _processList = new();
    private readonly TextBox _log = new();
    private readonly TextBlock _status = new();
    private readonly Button _injectButton = new();
    private readonly Button _refreshButton = new();
    private readonly List<ProcessEntry> _entries = new();

    public LauncherWindow(string[] args)
    {
        Title = "noturne \u2014 launcher";
        Width = 980;
        Height = 680;
        MinWidth = 780;
        MinHeight = 540;
        WindowStyle = WindowStyle.None;
        AllowsTransparency = true;
        Background = Brushes.Transparent;
        WindowStartupLocation = WindowStartupLocation.CenterScreen;
        FontFamily = new FontFamily("Microsoft YaHei UI, Segoe UI");

        var root = new DockPanel { LastChildFill = true, Margin = new Thickness(26, 12, 26, 26) };
        var titleBar = BuildTitleBar();
        DockPanel.SetDock(titleBar, Dock.Top);
        root.Children.Add(titleBar);
        var footer = BuildFooter();
        DockPanel.SetDock(footer, Dock.Bottom);
        root.Children.Add(footer);
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
                // released before the drag started
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

    private void RefreshProcesses()
    {
        _status.Text = "Scanning\u2026";
        _refreshButton.IsEnabled = false;
        string? jar = FindJar();
        string? java = FindJava();

        if (jar == null || java == null)
        {
            _status.Text = "noturne jar or java not found";
            Log(jar == null ? "jar not found next to the launcher." : "java not found (set JAVA_HOME).");
            _refreshButton.IsEnabled = true;
            return;
        }

        System.Threading.Tasks.Task.Run(() =>
        {
            string output = Run(java, $"-jar \"{jar}\" --list-json");
            List<ProcessEntry> entries = ParseEntries(output);
            Dispatcher.Invoke(() =>
            {
                _entries.Clear();
                _entries.AddRange(entries);
                _processList.Items.Clear();
                foreach (ProcessEntry entry in entries)
                {
                    _processList.Items.Add(entry.ToString());
                }
                _refreshButton.IsEnabled = true;
                _status.Text = entries.Count == 0
                    ? "No Minecraft process found \u2014 start the game first"
                    : $"{entries.Count} process(es)";
                Log($"Found {entries.Count} process(es).");
            });
        });
    }

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

        _injectButton.IsEnabled = false;
        Log($"Injecting into pid {entry.Pid}\u2026");
        System.Threading.Tasks.Task.Run(() =>
        {
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
                bool ok = output.Contains("agent loaded", StringComparison.OrdinalIgnoreCase);
                _status.Text = ok ? $"Injected into pid {entry.Pid}" : "Injection failed";
                _injectButton.IsEnabled = true;
            });
        });
    }

    private void Log(string message)
    {
        _log.AppendText($"[{DateTime.Now:HH:mm:ss}]  {message}{Environment.NewLine}");
        _log.ScrollToEnd();
    }

    // ------------------------------------------------------------------ helpers

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
            // fall through with whatever parsed
        }
        return list;
    }

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
                StandardErrorEncoding = Encoding.UTF8,
            };
            using Process? process = Process.Start(info);
            if (process == null)
            {
                return "";
            }
            string stdout = process.StandardOutput.ReadToEnd();
            string stderr = process.StandardError.ReadToEnd();
            if (!process.WaitForExit(20000))
            {
                try
                {
                    process.Kill(true);
                }
                catch (Exception)
                {
                    // already gone
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
        // Vendors install JDKs side by side under these roots. Pick the highest version by name
        // rather than hard-coding one machine's exact build directory.
        string[] roots =
        {
            @"C:\Program Files\Microsoft",
            @"C:\Program Files\Eclipse Adoptium",
            @"C:\Program Files\Java",
            @"C:\Program Files\Amazon Corretto",
            @"C:\Program Files\Zulu",
        };
        string? best = null;
        foreach (string root in roots)
        {
            if (!Directory.Exists(root))
            {
                continue;
            }
            foreach (string dir in Directory.GetDirectories(root, "jdk*"))
            {
                string candidate = Path.Combine(dir, "bin", "java.exe");
                if (File.Exists(candidate)
                    && (best == null || string.CompareOrdinal(candidate, best) > 0))
                {
                    best = candidate;
                }
            }
        }
        return best ?? "java";
    }

    private static string? FindJar()
    {
        string baseDir = AppContext.BaseDirectory;
        string[] candidates = Directory.Exists(baseDir)
            ? Directory.GetFiles(baseDir, "noturne*.jar")
            : Array.Empty<string>();
        if (candidates.Length > 0)
        {
            return candidates.OrderByDescending(File.GetLastWriteTimeUtc).First();
        }
        return null;
    }

    private static Brush Freeze(Color color)
    {
        var brush = new SolidColorBrush(color);
        brush.Freeze();
        return brush;
    }

    private sealed record ProcessEntry(int Pid, string Title, string Command)
    {
        public override string ToString()
        {
            string title = string.IsNullOrWhiteSpace(Title) ? "(no window)" : Title;
            return $"{Pid,-8}  {title}";
        }
    }
}
