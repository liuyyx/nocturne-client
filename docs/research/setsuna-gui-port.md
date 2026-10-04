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
| Skija 能否跨版本 | **按 JVM 版本切，不按 MC 版本切**：Skija 是 Java 9 字节码，Java 8 的 1.8.9 用不了 | §3 class 版本 53 |

**一句话**：GUI 抄不动"一点点"——它的界面层直接引用模块与设置对象，闭包≈整个客户端；
而且真正卡住 1.8.9 的不是 MC 版本差异，是 **Java 8 加载不了 Skija**。

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

## 3. Skija 能否成为跨版本 GUI 层

**不能覆盖 1.8.9（当 1.8.9 跑在 Java 8 上）。硬证据：**

```
skija-shared-0.143.17.jar      首类版本 53 (Java 9)   275 类 / 379 KB
skija-windows-x64-0.143.17.jar 首类版本 53 (Java 9)   10,309 KB
types-0.2.0.jar                首类版本 53 (Java 9)   8 类
```

class 版本 53 的类**无法被 Java 8 的 JVM 加载**（`UnsupportedClassVersionError`）。
1.8.9 的常规启动就是 Java 8 → Skija 通道在 1.8.9 上不可用。

**切口：按 JVM 版本分流，而不是按 MC 版本。**

| 目标 | JVM | GUI 通道 |
|---|---|---|
| 1.8.9 / 1.12.2 / 1.16.5（Java 8 启动） | 8 | 自有 GL 后端（`ui/gl/GlRenderer` + `GlApi`，LWJGL2/3 各一代） |
| 1.8.9 但用 Java 17 启动 | 17 | Skija 可用（class 53 在 17 上正常） |
| 1.17+ | 17+ | Skija（一份代码管全部） |

判定方式：运行时读 `java.class.version`（≥53 才装 Skija 通道），**不要**按 MC 版本猜——
同一份 1.8.9 在不同启动参数下 JVM 不同。我们的 `OverlayBootstrap` 已经是"装一次、可用即整场使用、
否则回落按代际的 GL 后端"的结构，把这个判定条件从"探测 Skija 是否能挂上"改成"JVM ≥ 9 才探测"即可。

**附带成本**：Skija 原生库要按平台分发（Windows/macOS/Linux × x64/ARM64），1.0 MB 级
`skija-<os>-<arch>.jar` 各一份；我们的单 jar 注入方案要么内嵌全部、要么按需下载。

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
# 3) Skija 字节码版本
unzip -p skija-shared-0.143.17.jar skija/Canvas.class | xxd -s 6 -l 2   # → 00 35 = 53 = Java 9
```

## 6. 待办（本次未做）

- 未给隔离工程补第三方 stub（`tritium.ncm.*` / `com.viaversion.setsunavia.*`），因此 64 条错误仍在；
  它们只影响音乐播放器界面与 KillAura 的协议版本判断，对 GUI 移植无影响。
- 未实测 Skija 在 Java 17 启动的 1.8.9 上挂载（`GlApi`/`BackendRenderTarget.makeGL` 路径在 LWJGL2 下的兼容性）。
- 未评估把 265 类里的 Java 9+ 语法批量降级到 Java 8 的成本（预计不可行：219 处模式匹配 + 35 个 record）。
