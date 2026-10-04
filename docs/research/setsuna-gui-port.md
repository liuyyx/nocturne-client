# 照抄 Setsuna GUI 到 noturne-client：可行性与最小集（实测）

对象：`vendor/setsuna/`（Setsuna 客户端源码快照，GPL-3.0，330 个 `.java`）
方法：静态依赖闭包 + 真实客户端 jar 编译（隔离工程 `vendor/setsuna-gui/`）+ 字节码版本核对。
所有数字均为本机实测，命令附在文末。

---

## 结论速览

| 问题 | 结论 | 证据 |
|---|---|---|
| 最少要带多少文件 | **265 类 / 58,782 行**（全量 330 类 / 62,222 行）→ 只能排掉 65 类 | 闭包脚本，见 §1 |
| 哪些能逐行不动 | 只有 `render/**` 的纯 Skia 调用 + 主题/几何/控件绘制逻辑 | §2 编译结果 |
| 哪些必须改写 | 所有带 `net.minecraft.*` 类型的屏幕/模块、5 个 mixin 类、全部 Java 9+ 语法 | §2 计数表 |
| Skija 能否跨版本 | **能**：`skija-shared` 是 Multi-Release JAR，根目录类是 Java 8 字节码（版本 52），1.8.9 可用 | §3 |

**一句话**：GUI 抄不动"一点点"——它的界面层直接引用模块与设置对象，闭包≈整个客户端；
而卡住 1.8.9 的不是 Skija（它的核心 jar 是 Java 8 字节码），而是 Setsuna 源码里的
**Java 16+ 语法**（模式匹配/record）与 **MC 26.x 专属 API**。

---

## 1. 最小集：GUI 闭包 = 265 类 / 58,782 行

起点 = `com.setsuna.ui.*` + `com.setsuna.render.*`（43 类），沿 `import com.setsuna.*` 与同包简单名引用做传递闭包：

| 包 | 类 | 行 |
|---|---:|---:|
| `com.setsuna.module` | 94 | 27,654 |
| `com.setsuna.ui` | 40 | 15,258 |
| `com.setsuna.util` | 43 | 5,035 |
| `com.setsuna.script` | 14 | 2,623 |
| `com.setsuna.command` | 9 | 2,284 |
| `com.setsuna.render` | 3 | 1,434 |
| `com.setsuna.event` | 32 | 1,160 |
| `com.setsuna.manager` | 6 | 1,056 |
| `com.setsuna.config` | 1 | 963 |
| `com.setsuna.setting` | 10 | 594 |
| `com.setsuna.integration` | 1 | 299 |
| `com.setsuna.notification` | 2 | 155 |
| `com.setsuna.i18n` | 2 | 132 |
| `com.setsuna.mixin` | 5 | 89 |
| `com.setsuna` / `accessor` | 3 | 46 |
| **合计** | **265** | **58,782** |

可排除的 65 类：其余模块与未被界面引用的 util（不在闭包内）。

**为什么"只抄 GUI"不成立**：ClickGUI 面板遍历 `ModuleManager`、每个控件绑定 `Setting` 实例、
HUD 注册表在 `ui/hud/HUD.java` 持有全部 HUD 模块单例——界面与模块是双向引用，切不开。

---

## 2. 逐行不动 vs 必须改写

### 编译实测（真实 jar、真实 javac）

隔离工程 `vendor/setsuna-gui/`：JDK 25 编译、`options.release = 8`、源集指向 `../setsuna`，
依赖 `analysis/client-26.1.2.jar`（38.1 MB，含 `net/minecraft/world/phys/AABB.class`、
`net/minecraft/client/gui/GuiGraphicsExtractor.class`；**无** `net/minecraft/client/gui/Hud.class`
——26.1.2 的渲染入口仍是 `Gui.extractRenderState(GuiGraphicsExtractor, DeltaTracker)`）。

| 阶段 | 错误数 | 说明 |
|---|---:|---|
| 首次编译 | **3,048** | 工程里 MC jar 的相对路径少一层（`../../analysis/` → 实际在 `../../../analysis/`），整个 MC classpath 缺失 |
| 修好路径后 | **64** | 全部落在这 4 个文件，且全是第三方集成，与我们的目标无关 |

剩余 64 条的分布：

| 文件 | 行数 | 缺的第三方 |
|---|---:|---|
| `ui/screen/MusicScreen.java` | 2,308 | `tritium.ncm.*`（网易云音乐库） |
| `ui/hud/MusicLyricsHUD.java` | 404 | `tritium.ncm.*` |
| `module/modules/combat/KillAura.java` | 1,977 | `com.viaversion.setsunavia.*`（ViaVersion 分支） |
| `module/FeatureRuntime.java` | 97 | 两者都缺 |

⇒ **除音乐播放器两个界面外，`ui/**` 与 `render/**` 对 26.1.2 零改动即可编译。**
（`MusicLyricsHUD` 被 `HUD`/`ConfigManager`/`ModuleManager` 通过 `INSTANCE` 单例引用，
`MusicScreen` 被 `NetEaseMusicModule` 引用，剔除时要一并处理。）

### 必须改写的原因（GUI 闭包内计数）

| 原因 | 处数 | 主要位置 |
|---|---:|---|
| MC 版本敏感调用（`Minecraft.getInstance` / `mc.screen|player|level|options|font`） | 1,657 | module 1,381 / util 121 / ui 66 |
| `instanceof` 模式匹配（Java 16+ 语法） | 219 | module 97 / ui 69 / script 27 |
| MC 版本敏感绘制（`GuiGraphicsExtractor` / `DeltaTracker` / `GuiGraphics` / `RenderSystem` / blaze3d） | 162 | module 54 / ui 46 / util 43 |
| `List.of` / `Map.of` / `Set.of`（Java 9+） | 116 | ui 41 / module 41 / command 18 |
| `record` 声明（Java 16+） | 35 | module 15 / ui 6 / util 5 |
| `var` 局部变量（Java 10+） | 17 | module 14 / script 2 / ui 1 |
| `Files.readAllBytes` | 8 | ui 5 / render 2 / module 1 |
| 文本块 `"""`（Java 15+） | 2 | script 2 |
| Mixin 注解/导入 | 18（5 个类） | `mixin/`：`DeltaTrackerTimerAccessor`、`KeyMappingAccessor`、`LivingEntityAccessor`、`LocalPlayerAccessor`、`MultiPlayerGameModeAccessor` |

**能逐行不动的部分**：`render/SkijaUi.java`、`render/SkijaRenderer.java`（除字体路径取自
`gameDirectory` 外）以及各类控件的几何/绘制算法——它们只调 Skia 与自身类型。
**必须改写的部分**：任何出现 `Minecraft`/`GuiGraphics`/`KeyMapping` 的类（`ui/screen/*`、
`ui/clickgui/*`、`ui/hud/*`）、5 个 mixin，以及全部 Java 9+ 语法（我们要 `release = 8`）。

我们既没有 Mixin 框架（走 JVMTI/ASM），也没有 Fabric 运行时——所以 `mixin/` 的 5 个类必须重写为
ASM transformer 或直接放弃（它们只提供 accessor，用途可替代）。

---

## 3. Skija 能否成为跨版本 GUI 层：能，包括 1.8.9

> **修正记录**：本节初版判为"Skija 是 Java 9 字节码（版本 53），1.8.9 用不了"——那是读到了
> jar 里第一个 `.class`（`META-INF/versions/9/module-info.class`）。实测该 jar 是 Multi-Release JAR，
> 结论要反过来。

```
skija-shared-0.143.17.jar        META-INF/MANIFEST.MF → Multi-Release: true
                                 根目录 271 个类 → class 版本 52 (Java 8)   ← Java 8 加载这一份
                                 META-INF/versions/9/ → 10 项，版本 53（module-info 等，Java 8 忽略）
types-0.2.0.jar                  同上：根目录 7 类 = 版本 52
skija-windows-x64-0.143.17.jar   仅 module-info.class (53) + 原生库资源 → 与字节码版本无关
```

Java 8 的 JVM 加载根目录的 52 版本类、忽略 `versions/9`，因此 **Skija 可用于 1.8.9**
（与 `ui/build.gradle.kts` 的注释"skija-shared/types 是 Java 8 字节码，能进 1.8.9 的 JVM（已实测）"一致）。
**不需要按 JVM 版本分流**：一份 Skija 绘制代码可覆盖全部目标版本。

真正的障碍不在 Skija，而在 Setsuna 源码自身：

| 障碍 | 规模 | 处理 |
|---|---:|---|
| Java 16+ 语法（`instanceof` 模式匹配） | 219 处 | 降级为 `instanceof` + 强转 |
| `record` 声明 | 35 处 | 改写为普通类 |
| `List.of` / `Map.of` / `Set.of` | 116 处 | 改写为 `Arrays.asList` / `Collections` |
| `var` / 文本块 | 17 / 2 处 | 显式类型 / 字符串拼接 |
| MC 26.x 专属 API（`GuiGraphicsExtractor`、`DeltaTracker`、`Minecraft.screen`、`Hud`） | 1,657 + 162 处 | 写适配层，映射到目标版本的 `GuiScreen`/`FontRenderer`/`ScaledResolution` 等 |
| Mixin accessor 类 | 5 个 | 改为 ASM/JVMTI 或绕开 |

**为什么值得**：Skija 把"绘制"与 MC 版本解耦（对着当前 GL 上下文建 `DirectContext`、直接画进帧缓冲，
不用游戏的绘制 API 与字体）。只要消化掉上表，同一套视觉代码即可跑在 1.8.9 – 26.3。

---

## 4. 许可与风险

- `vendor/setsuna` 与 `SetsunaClient/` 为 **GPL-3.0**，且上游含"禁止转售/改名"类条款。
- 照抄进 `noturne-client` ⇒ 整个项目按 GPL-3.0 分发（我们的注入器+agent 会被一并传染）。
- 建议：`vendor/` 仅作**研究与编译基线**，正式实现只借鉴视觉/结构（配色、圆角、动画曲线、
  控件布局），代码独立实现；`render/**` 里的 Skija 绘制思路可以重写，不复制源码。

---

## 5. 复现命令

```bash
# 1) 闭包统计：起点 ui.*/render.*，沿 import 传递闭包 → 265 类 / 58,782 行（见 §1）
# 2) 隔离编译（JDK 25 + release 8 + 真实 26.1.2 客户端 jar）
JAVA_HOME="C:\Program Files\Eclipse Adoptium\jdk-25.0.4.7-hotspot" \
  gradle -p noturne-client/vendor/setsuna-gui compileJava --console=plain --no-daemon
#    修好 build.gradle.kts 里 mcClientJar 的相对路径（../../../analysis/client-26.1.2.jar）后：
#    BUILD FAILED，64 个错误，全部落在 MusicScreen / MusicLyricsHUD / KillAura / FeatureRuntime
# 3) Skija 字节码版本（注意：要看根目录类，不是 META-INF/versions/9/module-info.class）
unzip -p skija-shared-0.143.17.jar io/github/humbleui/skija/Canvas.class | xxd -s 6 -l 2   # → 00 34 = 52 = Java 8
unzip -p skija-shared-0.143.17.jar META-INF/MANIFEST.MF | grep -i multi-release               # → Multi-Release: true
```

## 6. 待办（本次未做）

- 未给隔离工程补第三方 stub（`tritium.ncm.*` / `com.viaversion.setsunavia.*`），因此 64 条错误仍在；
  它们只影响音乐播放器界面与 KillAura 的协议版本判断，对 GUI 移植无影响。
- 未实测 Setsuna 的 `SkijaRenderer` 在 1.8.9（LWJGL2 的 GL 上下文）上挂载：字节码层面已确认可用，
  但 `DirectContext.makeGL` / `BackendRenderTarget.makeGL` 对老 GL 上下文的兼容性要在真机验证。
- 未评估 Java 16+ 语法的降级改写成本（219 处模式匹配 + 35 个 record + 116 处 `List.of`），
  可半自动改写，但必须逐处校验语义。

---

## 7. 移植方案与进度（决定：全抄 + 保 1.8.9，项目许可切 GPL-3.0-or-later）

### 分层：抄什么、不抄什么

| 层 | 处理 | 代表文件 |
|---|---|---|
| **视觉原语层**（Skija 调用、色板、几何、动画） | **整类照抄**，只做 Java 8 降级与字符集替换 | `render/SkijaUi`、`ui/UiTheme`、`ui/clickgui/ClickGuiLayout`、`ui/screen/PageTransition`、`ui/screen/UiControls`、`ui/screen/ScreenBackdrop`、`ui/hud/HudRenderUtil`、`ui/CategoryGlyphs` |
| **屏幕壳层**（MC 的 `Screen` 生命周期 / 输入分发） | 抄结构，MC 交互全部下沉到本项目已有的跨版本层（`OverlayBootstrap` / `ReflectiveInput` / 映射表） | `ui/SkijaScreen`、`ui/screen/AbstractSkijaScreen`、`ui/clickgui/*Screen` |
| **数据层**（玩家/世界/物品的读取） | 抄视觉算法，数据来源换成本项目的映射层 | `ui/hud/*HUD`、`module/modules/render/*` |
| **不抄** | 上游的 module/setting/event/config/notification 框架（用本项目 `client/` 的 `Module`/`Value`/`EventBus`）、5 个 Mixin accessor、网易云音乐、ViaVersion | — |

**为什么这样切**：上游 UI 与它的模块框架双向耦合（§1 的 265 类闭包），而它的模块又直接引用 26.x 专属
MC API（`GuiGraphicsExtractor`/`DeltaTracker`/`ClientLevel`）。要让同一套视觉跑在 1.8.9 上，唯一可行的
是把"绘制"与"MC 交互/数据"分开：绘制层纯 Skija（Skija 本身是 Java 8 字节码，§3），MC 相关的部分由本
项目的跨版本层提供。

### 包与命名对照

全部落在 `ui/src/main/java/dev/noturne/ui/skija/`（新包），与既有 `ui/gl`、`ui/theme`、`ui/clickgui`
（本项目自研、Epsilon 风格）并存，互不覆盖。

| 上游 | 本项目 | 主要改动 |
|---|---|---|
| `render/SkijaUi` | `SkijaUi` | 去 `Setsuna` 依赖、Java 8 降级、字体资源改 `/assets/noturne/fonts/` |
| `ui/UiTheme` | `SkijaTheme` | `accent()` 改为可注入静态值 |
| `ui/SkijaScreen` | `SkijaScreen` | 仅包名 |
| `ui/screen/PageTransition` | `PageTransition` | 可见性 public |
| `ui/CategoryGlyphs` | `CategoryGlyphs` | 换成本项目 `Category` 枚举 |
| `ui/clickgui/ClickGuiLayout` | `ClickGuiLayout` | record → final 类 |
| `ui/screen/UiControls` | `SkijaControls` | 去 MC/GLFW 输入类型，改原始 `char`/`int` |
| `ui/screen/ScreenBackdrop` | `SkijaBackdrop` | 去 MC，配置改静态 setter |
| `ui/hud/HudRenderUtil` | `SkijaHudPrimitives` | `IntSetting` → `int`，内联 `Edges` |

### 依赖替换规则（一致约定）

| 上游用法 | 本项目做法 |
|---|---|
| `Setsuna.mc().gameDirectory` | `System.getProperty("user.dir")`（MC 的工作目录即此） |
| `Setsuna.MOD_ID` | 字面量 `"noturne"` |
| `Setsuna.LOGGER.warn(...)` | `System.out.println("[noturne] ...")`（与项目其它处一致） |
| MC 输入事件类（`KeyEvent`/`CharacterEvent`、GLFW 键码） | 原始 `char` / `int` + `java.awt.event.KeyEvent.VK_*`（跨版本，不依赖 LWJGL3） |
| MC 剪贴板 | 可注入接口（`SkijaControls.Clipboard`） |
| MC 纹理（`Identifier` → Skia `Image`） | 待建"纹理桥"（本项目映射层 + `SkijaCanvas`），此部分方法暂不搬 |
| 上游 `Setting` 对象 | 本项目 `client/value/*` 或原始参数 |

### 进度

| 文件 | 状态 |
|---|---|
| `SkijaUi`（840 行） | ✅ 完成：class 版本 52；字体资源 6 个中只保留 4 个图标字体（苹方不打包，见 §7 字体取舍） |
| `SkijaScreen` / `PageTransition` / `CategoryGlyphs` / `SkijaTheme` / `ClickGuiLayout` | ✅ 完成 |
| `SkijaControls`（725 行）/ `SkijaBackdrop`（548 行）/ `SkijaHudPrimitives`（484 行） | ✅ 完成 |
| 接入本项目渲染路径 | ✅ `ui/gl/SkijaRenderer` 已改为委托 `SkijaUi`（原语 + 自带字体栈 + CJK 回退），并新增 `SkijaUi.lineHeight` 供组件树算行距 |
| 编译/测试验证（release 8） | ✅ `gradle test` 全模块通过 |
| **纹理桥（MC 纹理/帧缓冲 → Skia）** | ⛔ 未建，阻塞 3 处：`SkijaHudPrimitives.blur(...)`（上游唯一实现是 `render/SkijaRenderer.drawBlurredBackdrop`，依赖 MC 帧快照）、`SkijaRenderer.borrowTexture`（皮肤/旗帜/物品图标）、HUD 图标绘制 |
| 屏幕壳层（`AbstractSkijaScreen` 等）与数据层（HUD/ClickGUI 屏） | 待做：先建纹理桥与渲染回调对接 |

### 字体取舍（本批的刻意偏离）

上游打包了 6 个字体，本批只搬 4 个图标字体（`icomoon` / `music` / `icon` / `lucide`，合计 ~880 KB）。
`pf_normal.ttf` 与 `pf_middleblack.ttf`（各约 10 MB）**未搬**：它们的内部 family 名是 `.PingFang SC`，
即苹果苹方字体，苹果的字体许可不允许在非苹果设备上再分发。缺失时 `SkijaUi.loadTypeface()` 按设计
回落到系统字体（`Microsoft YaHei UI` 等），中文显示正常；需要原字形可用 `SkijaUi.importFont` 自行导入。
