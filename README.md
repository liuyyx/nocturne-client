# nocturne-client

一个 **JVM 注入式 Minecraft 客户端**：单个 jar 既是注入器，也是被注入进游戏的 agent。
不产出 `fabric.mod.json` / `mods.toml`，不放进 `mods/`，只有 attach 一条路径。

**覆盖范围（两件事，别混）**

* **映射表**：`1.8.9 – 26.3` 的**全部 66 个 release**，每版本带 `vanilla` / `fabric` / `forge` / `neoforge`
  四套运行期名（`client/src/main/resources/mappings-*.json`，由 `tools/mapping/` 生成）。
* **真机跑过**：`1.8.9`（官方 + LWJGL2）、`1.16.5`（LWJGL3）、`26.3`（真实 26.3 + Fabric）——
  逐条证据见 `docs/VERSION-MATRIX.md`。

## 三条硬规则

1. **只有注入一条路径**。不做模组形态，不做 `-javaagent` 之外的入口花样。
2. **客户端代码不按版本分支**。模块一律按 **Mojmap 规范名**写（`net.minecraft.client.Minecraft`），
   版本差异全部下沉到每版本一份映射表 JSON。
3. **运行时零探测**。目标版本由注入器判定并随 agent 参数传入（`mcVersion=1.8.9`）；
   加载器（原版 / Fabric / Forge / NeoForge）也不探测——表里带齐四套名字，按候选顺序试。

## 模块

| 模块 | 职责 |
|---|---|
| `core/` | 进程发现、attach（自实现 Windows/POSIX 通道 + JDK attach 兜底）、载荷解密装载、agent 选项组装（`AgentOptions`）；另有免 GUI 的 CLI 入口 `dev.nocturne.core.Nocturne` |
| `agent/` | 目标 JVM 内的入口（`agentmain`/`premain`）、ASM 子加载器、两种注入点、按版本选表 |
| `client/` | 事件总线、模块与值框架、映射层（`Mapping` / `ObfuscatedMapping` / `IdentityMapping`）、游戏桥（`GameBridge`） |
| `ui/` | **Setsuna 风格界面**（Skija 直绘）：三栏 ClickGUI、常显 HUD、HUD 编辑器、绘制原语与纹理桥；另有面向 `Renderer` 抽象的组件树，作为非 Skija 后端的回落 |
| `injector/` | Swing 注入器 GUI（扫描进程 → 选版本 → 注入），并提供 jar 的 Main-Class——GUI 与 `--list-json` / `--pid=` 是同一个入口 |
| `dist/` | 把上述模块合并成单个多入口 `nocturne-*.jar` |
| `launcher/` | 独立的 WPF(.NET 8) 启动器壳：只把 jar 当子进程驱动（`--list-json` / `--pid=`），不含业务逻辑，故客户端升级无需重编译它。不在 Gradle 构建里 |

## 两个注入点（`agent/transform/`）

| 转换器 | 插入什么 | 用在哪种入口 |
|---|---|---|
| `FrameHookTransformer` | 方法开头一条**无参**静态调用 | 缓冲区交换点：`Display.update()` / `glfwSwapBuffers(J)` / `SDL_GL_SwapWindow(J)` |
| `CallbackHookTransformer` | 方法开头**把首个引用形参**交给钩子 | 绘制上下文是**回调形参**的那代 API（`DrawContext` / `GuiGraphics` / `GuiGraphicsExtractor`） |

两者都只动指定类、指定方法的第一条指令；不命中或异常一律返回 `null`（沿用原字节码），内部错误打印一次。
保留原始 `StackMapTable`（丢帧会让含分支的方法 `VerifyError`）。

## 版本差异怎么处理

| 关注点 | 做法 |
|---|---|
| 类 / 字段 / 方法名 | 映射表（每版本一份 JSON，打进 jar 资源）。每类/成员带 `vanilla` / `fabric` / `forge` / `neoforge` 四套运行期名与**配套的 JNI 描述符**，运行期按 `vanilla → fabric → forge → neoforge → 规范名` 依次尝试 |
| 目标版本 | 注入器传入 `mcVersion`，运行时**不探测**。实例名里没有版本号时（`fpsmaster`、`TLauncher` 这类自定义实例），读该实例 json 的 `clientVersion` / `inheritsFrom`——启动器自己写的权威字段；两者都拿不到才退化为未知（恒等映射 + 日志） |
| 界面绘制与字体 | **Skija 直绘**：对着当前 GL 上下文把界面画进游戏帧缓冲，自带字体栈（含中文回退），不依赖游戏的绘制 API——一份代码管所有版本。Skija 不可用时回落到按代际的自绘 GL 后端（成员名查表） |
| 输入 | 用游戏自己的键鼠状态（成员名查表） |
| 帧信号 | LWJGL 的交换函数（3 个签名，属于 LWJGL 而非 MC，MC 改版不影响） |

## 映射表

同一版本在不同加载器下**运行期名字不同**，所以一张表里存四套：

| 命名空间 | 谁这样跑 | 类名 / 成员名 |
|---|---|---|
| `vanilla` | 不带加载器 | 混淆名（`enn` / `N` / `f_90977_`） |
| `fabric` | Fabric / Quilt | intermediary（`net.minecraft.class_1657` / `method_1551`） |
| `forge` | Forge | 可读类名 + SRG（`func_`/`field_` ≤1.15.2，`m_`/`f_` ≥1.16.5） |
| `neoforge` | NeoForge | 1.20.1 同 Forge；**1.20.2+ 即 Mojmap 名**（未混淆） |

数据源：Mojang 官方 `client_mappings`（1.14.4+）、`FabricMC/intermediary`（1.14+）、
`Legacy-Fabric/Legacy-Intermediaries`（1.8.2–1.13.2）、`MinecraftForge/MCPConfig`（1.12.2+）、
MCP 自己的 `joined.srg`（1.9–1.12.1），以及 1.8.9 / 1.12.2 的别名桥。
`1.9 – 1.14.3` 没有官方映射也没有 MCP 人类名，走 **intermediary 锚点**（intermediary 名跨版本稳定）。

* 重新生成：`python tools/mapping/generate.py`（细节、镜像与离线用法见 `tools/mapping/README.md`）
* 一致性门禁：`python tools/mapping/generate.py --check`（表是字节稳定的）
* 逐版本核对：`python tools/mapping/generate.py --javap`（对每个版本的真实 client jar 校验混淆名与描述符）
* 已知缺口：`1.9.1 / 1.9.3 / 1.10.1` 这三个点版本 MCP 从未发布 SRG，表里只有 `vanilla` / `fabric`

## 构建与运行

```bash
# 构建（Gradle wrapper 在本机不可用，用已安装的 Gradle；路径按本机情况替换）
JAVA_HOME="C:\Program Files\Microsoft\jdk-21.0.12.8-hotspot" \
  sh "$HOME/.gradle/wrapper/dists/gradle-9.2.1-bin/*/gradle-9.2.1/bin/gradle" \
  build --console=plain --no-daemon

# 运行：双击 nocturne.bat 走 GUI；命令行两参数走 CLI
java -jar dist/build/libs/nocturne-<version>.jar --list-json    # 列出可注入的进程
java -jar dist/build/libs/nocturne-<version>.jar --pid=<pid>   # 注入指定进程
```

游戏里按**右 Shift** 唤出界面（`guiKey` 由注入器传入，AWT VK 码）。

> **已知按键冲突**：FPSMaster Edge（Forge 1.8.9）的 ClickGUI 默认也绑在右 Shift 上
> （其 `ClientSettings.keyBind`）。目标装了它时，按右 Shift 会同时唤起它的面板——看起来像我们
> 没生效。注入器检测到这种情况会在日志里提示；请在设置里把我们的 GUI 键改成别的（如 `INSERT`、右 Ctrl）。

## 状态（诚实版）

只写实测结论，不写"应该能行"。

| 项 | 状态 |
|---|---|
| 注入链路（attach → agentmain → 帧钩子 live → 叠加层装载） | **已实测**：官方 1.8.9 真机；另有 LWJGL2 实验靶（Java 8 + 真实 LWJGL2 + 真实 OpenGL，靶内无 Minecraft）单独验证过 Java 8 栈上的链路 |
| 自实现 attach（免 `jdk.attach` / `tools.jar`） | **Windows 已实测**：`nocturne-attach.dll` 在官方 1.8.9 真机与 JDK 8 靶上均完成 attach → `agentmain` → 帧钩子 live → 叠加层 attach；**裁剪 JRE 验收通过**——在无 `tools.jar`、无 `jdk.attach` 的 JRE 上 CLI 打印 `attach strategy: windows-native` 并完成注入。**Linux/macOS 通道已实现**（`PosixAttachStrategy` + `attach_unix.c`，域套接字），但只做过 WSL 编译校验与单测，**未实机验证** |
| 1.8.9 真机（注入 + 界面） | **已实测可用**：attach → `agentmain` → 帧钩子 live → 叠加层 attach，右 Shift 唤出 ClickGUI，四个分类面板与模块名正常显示（`backend=gl-fixed`、`screen=ClickGui`），游戏稳定不崩 |
| 1.16.5 真机（注入 + 界面） | **当时实测**：注入、帧钩子、输入层、叠加层装载全部正常（`backend=skija`、`input=glfw`、`screen=SetsunaClickGui`），面板可见、字号正常。**注**：其后 Skija 路径因"直写外部帧缓冲会盖黑游戏"被禁用（1.8.9 与 1.16.5 真机实锤），当前 1.13+ 一律走 `gl-core`，**未在 1.16.5 上按当前代码重新实测** |
| 26.3 真机（注入 + 界面） | **已实测可用**：真实 26.3 + Fabric 上 attach → `agentmain` → 界面可见可点（`backend=gui-extractor`、`input=sdl`、`screen=ClickGui`；三列面板 + 模块名正常，点击 `FullBright` 状态翻转）。SDL 栈下**不注册帧钩子、也不碰 GL**，绘制走游戏自己的 `GuiGraphicsExtractor`，详见 `docs/VERSION-MATRIX.md` |
| 映射表生成器（66 个 release × 四套命名空间） | **已完成**：`--check` 全绿；`--javap` 对**每个版本**的真实 client jar 逐成员校验通过；`forge` / `neoforge` 另用真实 Forge 1.20.1 运行产物与 NeoForge 1.21.11 patched jar 逐成员核对（166/166）。仅 `1.9.1 / 1.9.3 / 1.10.1` 缺 `forge` |
| 版本号传递、两种注入点 | 已完成并有测试 |
| 界面：Setsuna 风格三栏 ClickGUI（分类导航 / 模块列表 / 设置详情，含颜色选择器与右键恢复默认） | **已完成并离屏验证**（Java 8 + LWJGL2 + Skija 真实 GL）。方案、证据与截图见 `docs/research/setsuna-gui-port.md` |
| HUD（常显）+ HUD 编辑器（拖动摆放） | **已完成并离屏验证**；顺带补齐了此前缺失的 `HudSink` 实现（此前模块发布的文本行全部落到空处） |
| 纹理桥（GL 纹理借用 / 帧快照 / 背景模糊） | **已完成并验证**：真 GL 上下文下借用 4×4 纹理、棋盘模糊均有像素级判定；从 MC 取纹理 id 与快照时机待真机 |
| Skija 通道 | 已实现（Skija 绑定是 Multi-Release JAR，Java 8 可用且已实测）；但当前**不在任何路径上被 probe**——直写外部帧缓冲会盖黑游戏（1.8.9 与 1.16.5 真机实锤），修法是纹理中转，未做。1.13–26.2 走 `gl-core`，26.3 走 `gui-extractor` |
| ⚠️ HUD / 纹理桥**未在真实 Minecraft 内验证** | ClickGUI 已在 1.8.9 / 1.16.5（当时）/ 26.3 真机可见可点；HUD 与纹理桥的验证跑在无 MC 的 GL 靶与离屏光栅上。待真机项（取纹理 id、快照时机、屏幕壳层与输入适配、HUD 真实数据源）逐条列在 `docs/research/setsuna-gui-port.md` |
| 世界覆盖层（ESP 等） | **基础设施已落地并有测试**：`WorldProjection` **自己算投影**（只读眼位/朝向/FOV，不依赖游戏的投影矩阵——所以 1.8.9 / 1.16.5 / 26.x 一份代码通用），`OverlayDraw` / `WorldOverlay` 接口，叠加层在 `beginFrame()` 之后同帧回调（四个后端都能画）。**已画出来**：ESP（2D 框 + 名字）、Tracers（底部连线，并修掉了恒 -1 的距离桩）、NameTags（头顶名字 + 距离）、ItemEsp（掉落物标签）、Trajectories（真实弹道步进 + 落点十字）、StorageEsp（遍历已加载区块的方块实体，八类容器各一色；区块表跨代际三种形态：`AtomicReferenceArray`/`List`/`Long2ObjectMap` 都认）。**待铺**：Search 需要方块扫描 + 渲染拦截，Chams / Xray 需要实体渲染 pass 或方块渲染拦截（2D 覆盖层做不了）。**已知版本缺口**：`1.14–1.14.3` 的区块表字段面在表里缺失（锚点从 1.12.2 学，而 1.14 换了结构），StorageEsp 在这四版只打一次门日志、不画；`1.9–1.14.3` 的 `ClientLevel#getBlockState` 面缺失，陷阱箱按普通箱画。**进世界后的画面待真人验收**（自动化环境无法点击菜单进入世界） |
| 自销毁（Panic） | **已实现并有测试**：GUI 里打开即停用全部模块、关闭驱动闸门、清空 bootstrap 层分发器——之后 GUI 打不开、开关键不响应、没有任何每帧工作。**不可逆**（agent 无法卸载自己，只能重启游戏）。真机上该模块在 MISC 列可见；点击触发待真人验收（自动化环境里 Windows 前台锁不允许抢焦点，SDL 鼠标坐标随之失效） |
| 模组形态 | 已移除（不再支持放进 `mods/`） |

## 文档索引

| 文档 | 内容 |
|---|---|
| `docs/ARCHITECTURE.md` | 模块与数据流 |
| `docs/VERSION-MATRIX.md` | 每版本的映射来源、绘制代际、帧钩子目标、验收状态；已知不确定项 |
| `docs/PLAN.md` | 开发计划与工作纪律（每个大项做完即 commit + push） |
| `docs/research/mapping-sources.md` | 映射数据源与反查路径调研 |
| `docs/research/setsuna-gui-port.md` | 界面移植方案、证据与截图 |
| `docs/research/draw-api-per-version.md` | 各版本绘制 API 差异 |
| `docs/research/vape-port-assessment.md` | Vape 模块移植评估 |
| `tools/mapping/README.md` | 映射表生成器：schema、数据源、镜像/离线用法 |
| `THIRD-PARTY-NOTICES.md` | 第三方组件与来源、许可 |

## 许可

本项目以 **GPL-3.0-or-later** 分发，全文见根目录 `LICENSE`。

之所以是 GPL：`ui/` 中的 Skija 绘制层与部分控件视觉移植自 **Setsuna**（上游 commit `e4915ae`，
作者 ShiYi，声明许可 `GPL-3.0-or-later`）。上游源码快照保存在 `vendor/setsuna/`
（含其 `LICENSE`、`LICENSE-APACHE` 与来源说明 `UPSTREAM.txt`）。

分发要求：保留版权与许可声明、注明来源，并以 GPL-3.0-or-later 提供完整对应源码；
**不得**再附加"禁止转售/禁止商用"之类的额外限制。第三方组件清单见 `THIRD-PARTY-NOTICES.md`。
