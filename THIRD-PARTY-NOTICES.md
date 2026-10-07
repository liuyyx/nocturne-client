# 第三方组件与来源

本项目（nocturne-client）以 **GPL-3.0-or-later** 分发（见根目录 `LICENSE`）。
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
| `ui/src/main/java/dev/nocturne/ui/skija/SkijaUi.java` | `render/SkijaUi.java` | 改包名、去除 `com.setsuna.Setsuna` 依赖、Java 16+ 语法降级为 Java 8、字体资源路径改为 `/assets/nocturne/fonts/` |
| `ui/src/main/java/dev/nocturne/ui/skija/SkijaScreen.java` | `ui/SkijaScreen.java` | 仅改包名 |
| `ui/src/main/java/dev/nocturne/ui/skija/PageTransition.java` | `ui/screen/PageTransition.java` | 改包名；可见性提升为 public |
| `ui/src/main/java/dev/nocturne/ui/skija/CategoryGlyphs.java` | `ui/CategoryGlyphs.java` | 改包名；`Category` 换成本项目枚举；switch 表达式降级；加 `default` 回落 |
| `ui/src/main/java/dev/nocturne/ui/skija/SkijaTheme.java` | `ui/UiTheme.java` | 改名（UiTheme → SkijaTheme）；`accent()` 改为可注入静态值，去掉对上游模块设置对象的依赖 |
| `ui/src/main/java/dev/nocturne/ui/skija/SkijaControls.java` | `ui/screen/UiControls.java` | 去 MC/GLFW 输入类型（改原始 `int keyCode`/`int codePoint` + AWT `VK_*`）；剪贴板改为可注入 `Clipboard` 接口；`record Box` → Java 8 类；`Objects.requireNonNullElse`/`String.repeat`/`StringBuilder.isEmpty` 降级 |
| `ui/src/main/java/dev/nocturne/ui/skija/SkijaBackdrop.java` | `ui/screen/ScreenBackdrop.java` | 去 `Minecraft`（背景目录与网格模式改为可注入静态状态）、去 `Setsuna` 日志、`ColorListener`→`println`；`record TraceLine` → Java 8 类；`readAllBytes` 自实现 |
| `ui/src/main/java/dev/nocturne/ui/skija/SkijaHudPrimitives.java` | `ui/hud/HudRenderUtil.java` | `IntSetting` 参数改原始 `int`；内联 `HudFusionManager.Edges`（16 组合 enum + `of` 工厂）；`BorderMode` 常量名保持不变以免改动 HUD 设置文案。其中 `blur(...)` 为后续补齐，来自 `render/SkijaRenderer.drawBlurredBackdrop`：只搬「裁切 → 采样 → 模糊」，快照改由调用方传入，并省掉上游按 `window.getGuiScaledWidth()` 推的换算（我们的画布是像素坐标 1:1） |
| `ui/src/main/java/dev/nocturne/ui/skija/SkijaTextureBridge.java` | `render/SkijaRenderer.java`（纹理借用段） | 抽成独立类；去掉 Minecraft 依赖——只接受 GL 纹理 id 与尺寸，取 id 的事留给调用方（映射层），于是本类在没有游戏的进程里也能被完整验证；快照改用 Skija 自身的 `Surface.makeImageSnapshot`（上游走 MC 帧缓冲读数） |

### 本项目原创（不属于上游代码）

以下文件是用上述移植件**搭建**的界面实现，由本项目独立编写，不包含上游代码：

| 文件 | 说明 |
|---|---|
| `ui/src/main/java/dev/nocturne/ui/skija/SetsunaClickGui.java` | 三栏 ClickGUI（分类导航 / 模块列表 / 设置详情），含颜色选择器与右键恢复默认 |
| `ui/src/main/java/dev/nocturne/ui/skija/SetsunaHud.java` | 常显 HUD（品牌 / 模块文本 / 帧率 / 已启用模块列表），元素化并受 `HudLayout` 支配 |
| `ui/src/main/java/dev/nocturne/ui/skija/SetsunaHudEditor.java` | HUD 编辑器（拖动摆放、重置、完成），功能对应上游 `HudEditorScreen` 但为本项目重写 |
| `ui/src/main/java/dev/nocturne/ui/skija/HudLayout.java` | HUD 元素位置表（懒初始化默认值） |
| `ui/src/main/java/dev/nocturne/ui/skija/SkijaHudSink.java` | `HudSink` 的 UI 侧实现（接住模块发布的文本行） |
| `ui/src/main/java/dev/nocturne/ui/gl/OverlayGui.java` · `SkijaBackend` 的画布暴露 · `GuiOverlay` 的三层调度 | 界面契约与叠加层接线 |

### 已移植的资源

上游 `assets/setsuna/**` 下的**图标字体**按其原许可（同上）复制到
`ui/src/main/resources/assets/nocturne/fonts/`：

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

---

## 3. 映射数据来源（只在生成期使用；产物是衍生事实）

`client/src/main/resources/mappings-<版本>.json` 由 `tools/mapping/` 离线生成，
输入来自下列上游（下载物只落在 gitignored 的 `tools/mapping/cache/`，**不进仓库、不进 jar**）。

| 上游 | 用途 | 许可 |
|---|---|---|
| Mojang 官方 `client_mappings`（`piston-meta.mojang.com`） | 1.14.4+ 的 canonical ↔ 混淆名 | Minecraft EULA；版权头明确**不得原样再分发完整映射文件**，故只分发由它派生的 JSON（类名/成员名/描述符） |
| [MinecraftForge/MCPConfig](https://github.com/MinecraftForge/MCPConfig) | 1.12.2+ 的 SRG（Forge 运行期成员名） | 修改版 zlib（© 2018 Forge Development LLC）；允许创建与发布衍生作品，但须以**不同的 group 与名称**发布——本项目只发布自己命名的衍生 JSON，并在此致谢 |
| [FabricMC/intermediary](https://github.com/FabricMC/intermediary) | 1.14–1.21.11 的 intermediary（Fabric 运行期名） | CC0-1.0 |
| [Legacy-Fabric/Legacy-Intermediaries](https://github.com/Legacy-Fabric/Legacy-Intermediaries) | 1.8.2–1.13.2 的遗留 intermediary | CC0-1.0 |
| 本机 MCP 资产（`OpenVape4.21/.../mappings/vanilla189`、`forge189` 等） | 1.8.9 / 1.12.2 的 SRG 与 MCP 人类名 | 随该工程一并提供，仅本地读取 |

生成期还读取目标版本的官方 client jar（`javap` 取类继承链），同样只落在 `cache/`。
