# 第三方组件与来源

本项目（noturne-client）以 **GPL-3.0-or-later** 分发（见根目录 `LICENSE`）。
下列第三方代码/资源的版权归其各自作者，来源与许可如实记录。

---

## 1. Setsuna（UI 绘制层与控件视觉的来源）

| 项 | 值 |
|---|---|
| 来源 | 用户提供的上游工程 `SetsunaClient/`（快照另存于 `vendor/setsuna/`） |
| 上游 commit | `e4915ae093748d48d92ee47c38cdb8b2746a4730`（Sun Aug 30 20:46:25 2026 +0800，"Restore original README"） |
| 作者 | ShiYi（QQ 1782605215 / @FS_Oracle） |
| 声明许可 | `GPL-3.0-or-later`（上游 `gradle.properties`: `license=GPL-3.0-or-later`） |
| 随附许可文件 | 上游 `LICENSE`（GPL-3.0 全文）、`LICENSE-APACHE`（Apache-2.0 全文），两者均随 `vendor/setsuna/` 保留 |
| 上游备注 | README 称"允许你进行任何操作 甚至魔改后售卖"；目录内虽附 Apache-2.0 全文，但**没有**"GPL-3.0 或 Apache-2.0 二选一"的文字声明，故本项目按 `GPL-3.0-or-later` 处理 |

### 已移植的文件

| 本项目路径 | 上游路径 | 改动 |
|---|---|---|
| `ui/src/main/java/dev/noturne/ui/skija/SkijaUi.java` | `render/SkijaUi.java` | 改包名、去除 `com.setsuna.Setsuna` 依赖、Java 16+ 语法降级为 Java 8、字体资源路径改为 `/assets/noturne/fonts/` |
| `ui/src/main/java/dev/noturne/ui/skija/SkijaScreen.java` | `ui/SkijaScreen.java` | 仅改包名 |
| `ui/src/main/java/dev/noturne/ui/skija/PageTransition.java` | `ui/screen/PageTransition.java` | 改包名；可见性提升为 public |
| `ui/src/main/java/dev/noturne/ui/skija/CategoryGlyphs.java` | `ui/CategoryGlyphs.java` | 改包名；`Category` 换成本项目枚举；switch 表达式降级；加 `default` 回落 |
| `ui/src/main/java/dev/noturne/ui/skija/SkijaTheme.java` | `ui/UiTheme.java` | 改名（UiTheme → SkijaTheme）；`accent()` 改为可注入静态值，去掉对上游模块设置对象的依赖 |
| `ui/src/main/java/dev/noturne/ui/skija/SkijaControls.java` | `ui/screen/UiControls.java` | 去 MC/GLFW 输入类型（改原始 `int keyCode`/`int codePoint` + AWT `VK_*`）；剪贴板改为可注入 `Clipboard` 接口；`record Box` → Java 8 类；`Objects.requireNonNullElse`/`String.repeat`/`StringBuilder.isEmpty` 降级 |
| `ui/src/main/java/dev/noturne/ui/skija/SkijaBackdrop.java` | `ui/screen/ScreenBackdrop.java` | 去 `Minecraft`（背景目录与网格模式改为可注入静态状态）、去 `Setsuna` 日志、`ColorListener`→`println`；`record TraceLine` → Java 8 类；`readAllBytes` 自实现 |
| `ui/src/main/java/dev/noturne/ui/skija/SkijaHudPrimitives.java` | `ui/hud/HudRenderUtil.java` | `IntSetting` 参数改原始 `int`；内联 `HudFusionManager.Edges`（16 组合 enum + `of` 工厂）；**未搬** `blur(...)`（依赖 MC 帧缓冲快照，待纹理桥）；`BorderMode` 常量名保持不变以免改动 HUD 设置文案 |

### 已移植的资源

上游 `assets/setsuna/**` 下的**图标字体**按其原许可（同上）复制到
`ui/src/main/resources/assets/noturne/fonts/`：

| 文件 | 上游路径 | 用途 | 体积 |
|---|---|---|---|
| `icomoon.ttf` | `tritium/fonts/icomoon.ttf` | 控件字形 | 9.9 KB |
| `music.ttf` | `tritium/fonts/music.ttf` | 音乐字形 | 2.8 KB |
| `icon.ttf` | `textures/Font/icon.ttf` | 客户端图标 | 15 KB |
| `lucide.ttf` | `fonts/lucide/lucide.ttf` | Lucide 图标集（`CategoryGlyphs` 用） | 848 KB |
| `mainmenu/background.png` | `textures/mainmenu/background.png` | 主菜单背景 | — |

**未复制**：上游的 `pf_normal.ttf` / `pf_middleblack.ttf`（各约 10 MB）。这两个文件的内部
family 名是 `.PingFang SC`，即**苹果苹方字体**——苹果的字体许可不允许在非苹果设备上再分发，
本项目因此不打包它们（也顺带省下约 21 MB）。

后果与替代：`SkijaUi.loadTypeface()` 在资源缺失时会**回落到系统字体**
（`Microsoft YaHei UI` / `Microsoft YaHei` / `Segoe UI`），中文显示正常，仅字形风格与上游不同。
需要上游那套字形时，用 `SkijaUi.importFont(...)` 把自己合法持有的字体导入用户目录即可
（导入的字体不进仓库、不再分发）。

---

## 2. Skija / Skiko 之外的其他依赖

| 组件 | 许可 | 用途 |
|---|---|---|
| [Skija](https://github.com/HumbleUI/Skija) `io.github.humbleui:skija-shared` / `types` / `skija-windows-x64` | Apache-2.0 | Skia 的 JNI 绑定，用于自绘 GUI |
| JUnit 5（`org.junit.jupiter`、`org.junit.platform`） | EPL-2.0 | 测试 |

> GPL-3.0 与 Apache-2.0 / EPL-2.0 兼容；分发时保留各自的许可与版权声明即可。
