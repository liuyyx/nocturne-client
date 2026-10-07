# nocturne-client

一个 **JVM 注入式 Minecraft 客户端**：单 jar 既是注入器 GUI，也是被注入进游戏的 agent。
覆盖 **1.8.9 / 1.12.2 / 1.16.5 / 1.20.1 / 1.21.x / 26.2 / 26.3**（Windows / macOS / Linux）。

## 三条硬规则

1. **只有注入一条路径**。不做模组形态（不产出 `fabric.mod.json` / `mods.toml`），不做 `-javaagent` 之外的入口花样。
2. **客户端代码不按版本分支**。模块一律按 **Mojmap 规范名**写（`net.minecraft.client.Minecraft`），
   版本差异全部下沉到 **每版本一份映射表 JSON**（自动生成，见 `tools/mapping/`）。
3. **运行时零探测**。目标版本由注入器判定并随 agent 参数传入（`mcVersion=1.8.9`），
   agent 只做一件事：读 `/mappings-<版本>.json`。

## 模块

| 模块 | 职责 |
|---|---|
| `core/` | 进程发现、attach（JDK attach API + JDK 8 的 tools.jar 自举）、agent 选项组装（`AgentOptions`） |
| `agent/` | 目标 JVM 内的入口（`agentmain`/`premain`）、ASM 子加载器、两种注入点（帧交换钩子 / 绘制上下文钩子）、按版本选表 |
| `client/` | 事件总线、模块与值框架、映射层（`Mapping` / `ObfuscatedMapping` / `IdentityMapping`）、游戏桥（`GameBridge`） |
| `ui/` | **Setsuna 风格界面**（Canvas 直绘）：三栏 ClickGUI、常显 HUD、HUD 编辑器、Skija 绘制原语与纹理桥；另有面向 `Renderer` 抽象的组件树（四列 GUI，作为非 Skija 后端的回落） |
| `injector/` | Swing 注入器 GUI（扫描进程 → 选版本 → 注入） |
| `tools/mapping/` | 映射表生成器（见 `docs/research/mapping-sources.md` 调研报告） |

## 两个注入点（都在 `agent/transform/`）

| 转换器 | 插入什么 | 用在哪种入口 |
|---|---|---|
| `FrameHookTransformer` | 方法开头一条**无参**静态调用 | 缓冲区交换点：`Display.update()` / `glfwSwapBuffers(J)` / `SDL_GL_SwapWindow(J)` |
| `CallbackHookTransformer` | 方法开头**把首个引用形参**交给钩子 | 绘制上下文是**回调形参**的那代 API（`DrawContext` / `GuiGraphics` / `GuiGraphicsExtractor`） |

两者都只动指定类、指定方法、方法第一条指令；不命中或异常一律返回 `null`（沿用原字节码），
内部错误打印一次。保留原始 `StackMapTable`（丢帧会让含分支的方法 `VerifyError`）。

## 版本差异怎么处理

| 关注点 | 做法 |
|---|---|
| 类/字段/方法名 | 映射表（每版本一份 JSON，打进 jar 资源）。每类/成员带 `vanilla`/`fabric`/`forge`/`neoforge` 四套运行期名与配套 JNI 描述符，运行期按 `vanilla → fabric → forge → neoforge → 规范名` 依次尝试——**因此不需要探测加载器**，同一份表同时服务原版、Fabric、Forge、NeoForge |
| 目标版本 | 注入器传入 `mcVersion`，运行时**不探测**。实例名里没有版本号时（`fpsmaster`、`TLauncher` 这类自定义实例），读该实例 json 的 `clientVersion`/`inheritsFrom`——这是启动器自己写的权威字段；两者都拿不到才退化为未知（恒等映射 + 日志） |
| 界面绘制与字体 | **Skija 直绘**：对着当前 GL 上下文把界面画进游戏帧缓冲，自带字体栈（含中文回退），完全不依赖游戏的绘制 API——一份代码管所有版本。Skija 不可用时回落到按代际的自绘 GL 后端（成员名查表） |
| 输入 | 用游戏自己的键鼠状态（成员名查表） |
| 帧信号 | LWJGL 的交换函数（3 个签名，属于 LWJGL 而非 MC，MC 改版不影响） |

## 构建与运行

```bash
# 构建（Gradle wrapper 在本机不可用，用已安装的 Gradle）
JAVA_HOME="C:\Program Files\Microsoft\jdk-21.0.12.8-hotspot" \
  sh "$HOME/.gradle/wrapper/dists/gradle-9.2.1-bin/*/gradle-9.2.1/bin/gradle" \
  build --console=plain --no-daemon

# 注入（双击 nocturne.bat 走 GUI；命令行两参数走 CLI）
java -jar dist/build/libs/nocturne-<version>.jar --list-json      # 列出可注入的进程
java -jar dist/build/libs/nocturne-<version>.jar --pid=<pid>     # 注入指定进程
```

游戏里按**右 Shift** 唤出界面（`guiKey` 由注入器传入，AWT VK 码）。

> **已知按键冲突**：FPSMaster Edge（Forge 1.8.9）的 ClickGUI 默认也绑在右 Shift 上
> （其 `ClientSettings.keyBind`）。目标装了它时，按右 Shift 会同时唤起它的面板——看起来像我们
> 没生效。注入器检测到这种情况会在日志里提示，请在设置里把我们的 GUI 键改成别的
> （例如 `INSERT`、右 Ctrl）。

## 状态（诚实版）

| 项 | 状态 |
|---|---|
| 注入链路（attach → agentmain → 帧钩子生效 → 叠加层装载） | **已在两处实测**：① 官方 1.8.9 真机（见下两行）；② LWJGL2 实验靶（`tmp/lab189/targetH.log`：帧钩子 live、叠加层 attach、`backend=gl-fixed`）。真机之前先用实验靶验证了 Java 8 栈上的链路（靶内无 Minecraft） |
| 自实现 attach（免 `jdk.attach` / `tools.jar`） | **Windows 已实测**：`nocturne-attach.dll` 在官方 1.8.9 真机与 JDK 8 靶上均完成 attach → `agentmain` → 帧钩子 live → 叠加层 attach（`tmp/mc189-native.log`、`tmp/selftest-target.log`）；**裁剪 JRE 验收已通过**——在无 `tools.jar`、无 `jdk.attach` 的 JRE 上，CLI 打印 `attach strategy: windows-native` 并完成注入（`tmp/selftest-trimmed.log`）。**Linux/macOS 通道已实现**（`PosixAttachStrategy` + `attach_unix.c`，域套接字），但只在 WSL 里通过 `gcc -Werror` 编译校验与单测，**未实机验证**；`PayloadPack` 接入生产待做 |
| 26.3 真机（注入 + 稳定性） | **已实测**：真实 26.3 + Fabric 上 attach → `agentmain` → `client installed (modules=4)`，游戏存活、无崩溃（`tmp/mc263-*.log`）。SDL 栈下按设计**不注册帧钩子、不安装叠加层**（LWJGL 的 GL 绑定在 SDL 进程里不可用），原因与后续方案见 `docs/VERSION-MATRIX.md` |
| 1.8.9 真机（注入 + 界面） | **已实测可用**：官方 1.8.9 + LWJGL2 上 attach → `agentmain` → 帧钩子 live → 叠加层 attach，右 Shift 唤出 ClickGUI，四个分类面板与模块名正常显示（`backend=gl-fixed`、`screen=ClickGui`），游戏稳定不崩。细节见 `docs/VERSION-MATRIX.md` |
| 映射表生成器（1.8.9–26.3 全部 46 个 release × 四加载器命名空间） | **已完成**：`tools/mapping/` 从 Mojang 官方映射 + FabricMC/intermediary + Legacy-Fabric + MinecraftForge/MCPConfig 联表生成；`--check` 全绿，`--javap` 对本地 client jar 校验通过，forge/neoforge 命名空间另用真实 Forge 1.20.1 / NeoForge 1.21.11 产物逐成员核对（149/149、166/166）。1.9–1.12.1、1.13–1.14.3 无官方映射且无人工别名桥，无表 |
| 版本号传递、两种注入点 | 已完成并有测试 |
| 界面：Setsuna 风格三栏 ClickGUI（分类导航 / 模块列表 / 设置详情，含颜色选择器与右键恢复默认） | **已完成并离屏验证**（Java 8 + LWJGL2 + Skija 真实 GL）。方案、证据与截图见 `docs/research/setsuna-gui-port.md` |
| HUD（常显）+ HUD 编辑器（拖动摆放） | **已完成并离屏验证**。顺带补齐了一直缺失的 `HudSink` 实现——此前模块发布的文本行全部落到空处 |
| 纹理桥（GL 纹理借用 / 帧快照 / 背景模糊） | **已完成并验证**：真 GL 上下文下借用 4×4 纹理、棋盘模糊均有像素级判定；从 MC 取纹理 id 与快照时机待真机 |
| Skija 通道 | 首选后端（Skija 绑定是 Multi-Release JAR，Java 8 可用且已实测）；不可用时回落按代际的自绘 GL 后端 |
| ⚠️ 上述界面 / HUD / 纹理桥**均未在真实 Minecraft 内验证** | 验证跑在无 MC 的 GL 靶与离屏光栅上；待真机项（取纹理 id、快照时机、屏幕壳层与输入适配、HUD 真实数据源）逐条列在 `docs/research/setsuna-gui-port.md` |
| 模组形态 | 已移除（不再支持放进 `mods/`） |

## 许可

本项目以 **GPL-3.0-or-later** 分发，全文见根目录 `LICENSE`。

之所以是 GPL：`ui/` 中的 Skija 绘制层与部分控件视觉移植自 **Setsuna**（上游 commit
`e4915ae`，作者 ShiYi，声明许可 `GPL-3.0-or-later`）。上游源码快照保存在 `vendor/setsuna/`
（含其 `LICENSE`、`LICENSE-APACHE` 与来源说明 `UPSTREAM.txt`）。

分发要求：保留版权与许可声明、注明来源，并以 GPL-3.0-or-later 提供完整对应源码；
**不得**再附加"禁止转售/禁止商用"之类的额外限制。第三方组件清单见 `THIRD-PARTY-NOTICES.md`。
