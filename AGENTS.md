# AGENTS.md

面向在本仓库工作的 AI 编码代理。人类入口：`README.md`；架构：`docs/ARCHITECTURE.md`；
版本矩阵与验收状态：`docs/VERSION-MATRIX.md`；计划与工作纪律：`docs/PLAN.md`。
**本文件只写规则与陷阱；状态与验收结论一律以 `docs/VERSION-MATRIX.md` 为准，不要在别处复述。**

## 0. 这是什么

JVM **注入式** Minecraft 客户端：同一个 jar 既是注入器（Swing GUI / CLI），也是被注入进游戏的 agent。
Fabric 形态由同一个 jar 里的 `fabric.mod.json` + `nocturne.mixins.json` 提供（1.21.1 已真机验收）。

- 覆盖 `1.8.9 – 26.3` 共 **66 个 release**，但那是**映射表覆盖**。真机跑过的版本与逐版本验收状态
  **只**看 `docs/VERSION-MATRIX.md`（历史上跑过 1.8.9 / 1.12.2 / 1.16.5 / 1.20.1 / 1.21.1 / 1.21.4 /
  1.21.10 / 1.21.11 / 26.2 / 26.3；当前构建的验收格子以该文件的表为准）。
- 交付 jar 同时带 `fabric.mod.json`（Fabric 通道，1.21.1 真机验收）；**不产出** Forge/NeoForge 元数据。
- 代码全部中文注释；新代码保持同样风格。

## 1. 硬规则（违反即错）

| # | 规则 | 为什么 |
|---|---|---|
| 1 | 主路径是注入（attach / `-javaagent`）；Fabric 模组入口是第二条通道，**只**靠 Mixin 插件手写 ASM 拿钩子。不做 Forge/NeoForge 入口 | 模组路径拿不到 `Instrumentation`；Knot 用 `defineClass` 定义游戏类，`retransform/redefine` 不可靠，Mixin 是唯一能在类定义前拿到字节码的通道 |
| 2 | 客户端代码只写 **Mojmap 规范名**，**禁止版本分支**（`if (version >= …)`、`instanceof` 分代） | 版本差异全部下沉到每版本一份映射表 JSON |
| 3 | **运行时零探测**：目标版本由注入器判定并随 attach 参数传入（`mcVersion=`），加载器靠候选名字顺序试 | 探测在隔离类加载器下极不可靠 |
| 4 | `core/ agent/ client/ ui/ injector/` 一律 **Java 8 字节码**（根脚本 `options.release = 8`），且只用 Java 8 API | 要注入 JVM 8 的 1.8.9；高版本字节码会被 `UnsupportedClassVersionError` 拒 |
| 5 | ASM 变换**不得** `COMPUTE_FRAMES`；Mixin 只用于 Fabric 通道，且走 `IMixinConfigPlugin#postApply` 手写 ASM（不用 `@Inject`） | 隔离加载器下 `getCommonSuperClass` 会崩；`@Inject` 必须绑定版本专属形参类型（`GuiGraphics` / `class_332`），`postApply` 只按类名+方法名+描述符匹配 |
| 6 | 注入进游戏方法的代码**绝不把异常外泄**：转换器失败 `return null`（沿用原字节码），钩子内部 `catch(Throwable)` | 未捕获异常会让游戏崩在任意位置 |
| 7 | 反射解析游戏类必须走 `GameBridge` / `gameLoader()`，绝不用本模块的类加载器 | Fabric Knot / Forge 用隔离加载器重新加载每份 jar |

## 2. 构建与验证（下列命令已在本机实测通过）

```bash
export JAVA_HOME="C:\Program Files\Microsoft\jdk-21.0.12.8-hotspot"   # 必须 JDK 21
gradlew.bat build --console=plain --no-daemon    # 编译 + 单测 + 打包（实测 BUILD SUCCESSFUL）
gradlew.bat test  --console=plain --no-daemon    # 仅单测（实测 658 项 = agent 88 /
                                                 # client 278 / ui 176 / core 70 / injector 46，0 失败）
python tools/mapping/generate.py --check         # 映射表门禁：陈旧 **或** 描述符与 jar 不符 → exit 1
python tools/mapping/generate.py --check --fast  # 只比文本的快速路径（没装 javap 的机器用）
python tools/mapping/generate.py --javap         # 只做逐版本 jar 交叉校验
```

- **有 gradle wrapper**：`gradlew` / `gradlew.bat` / `gradle/wrapper/`（Gradle 9.2.1）。但本机 wrapper 缓存
  只有一个残缺的 `.part`（`~/.gradle/wrapper/dists/gradle-9.2.1-bin/2t0n5ozl…`），`gradlew.bat` 会先联网下载；
  离线时直接用已解包的发行版：
  - cmd：`"C:\Users\liuyyx\.gradle\wrapper\dists\gradle-9.2.1-bin\9fgc9dy84duywbml6zqd0l4mo\gradle-9.2.1\bin\gradle.bat" <task>`
  - Git-Bash：`java -classpath "…/gradle-9.2.1/lib/gradle-launcher-9.2.1.jar" org.gradle.launcher.GradleMain <task>`
  `gradle` 不在 PATH。
- 必须先设 `JAVA_HOME=JDK 21`：Gradle 9 自身要求 17+，而本机默认 `JAVA_HOME` 指向
  `C:\Java\jdk8u432-b06`。toolchain 固定 21，靠 `release = 8` 输出旧字节码。
- 产物是**两个** jar（`dist/build/libs/`）：
  - `nocturne-<version>.jar`：agent/注入面，全部类 `major ≤ 52`（约 9.4 MB）。
  - `nocturne-launcher-<version>.jar`：同一份内容 + 37 个 `net/miginfocom/**`（MigLayout，major 55），
    双击 GUI 用这个；`build` 会把两者拷到根 `build/libs/`。
  双击 `nocturne.bat` 时**优先取最新 launcher jar**，找不到才退回 agent jar（`dir /o-d`），
  所以别在源码树里散落旧 jar。
- 官方二进制：`gradlew.bat :dist:randomizedJar` 调 `tools/randomize/randomize.py` 随机化类名，
  产出 `nocturne-<version>-randomized.jar`（`-PclassSalt=<seed>` 可复现）。该任务不在默认 `build` 里。
- 打包闸门：`distJar` 的 `doLast` 硬校验「有 `dev/nocturne/agent/asm.pack`、无展开 ASM」；
  `verifyJarBytecode`（挂在 `check`，`distJar` 完成后自动跑）再校验「无 major > 52」和
  「五个模块编译出的每个类都在 jar 里」。任何一条失败都直接让构建红。
- 运行：`java -jar dist/build/libs/nocturne-launcher-*.jar --list-json`（列进程）/
  `java -jar … --pid=<n>`（注入）。GUI 与 CLI 是同一个入口。
- 映射表生成器（`tools/mapping/`）：`generate.py` 重写全部表；`--check` 陈旧即 exit 1；
  `--check` 默认已包含 jar 交叉校验（`--fast` 退回只比文本）；`--only <ver>` + `--report` 打 absent 清单。
  absent 条目带 `absentReason`（`verified` / `ambiguous` / `unverified`）：只有 `verified` 才算
  "本版本确实没有"，`ambiguous`（生成器择优失败）会让 `--check --javap` 直接失败。
  `member-aliases.txt` 处理「改名」而不写 absent；`requirements.txt` 的 `#成员!field` 强制按字段登记。
  只用 Python 3.12 stdlib；`tools/mapping/cache/` 已含全部 66 个 client jar，**离线可跑**。
- **不在 Gradle 构建内**：`launcher/`（.NET 8 WPF，`dotnet build`）、`tools/mapping/`、
  `tools/randomize/`（Python）、`vendor/`（参考快照）。改它们不影响 `gradlew build`。

## 3. 模块地图

| 模块 | 职责 | 关键类 | 依赖 |
|---|---|---|---|
| `core/` | 进程发现、三策略 attach 链（`jdk-api` → `windows-native` / `unix-domain`）、载荷加解密、attach 参数组装、CLI 入口 | `Nocturne`（`main`）、`Attacher`、`AgentOptions`、`PayloadPack`/`PayloadTool` | gson |
| `agent/` | 被注入侧：`premain`/`agentmain`/`startAsMod`、ASM 子加载器、12 个转换器、Mixin 插件、bootstrap 桥 | `NocturneAgent`、`EmbeddedAsmLoader`、`transform/*Transformer`、`mixin/NocturneMixinPlugin`、`OverlayBootstrap` | client, ui, core, ASM 9.8（Mixin / Fabric loader / MC intermediary 仅 compileOnly） |
| `client/` | 事件总线、模块/值框架、映射层、反射桥、渲染契约、runtime | `NocturneClient`、`EventBus`、`Module`/`ModuleRegistry`、`Mapping`/`ObfuscatedMapping`、`GameBridge`、`runtime/*` | gson |
| `ui/` | 自绘 ClickGUI + 绘制后端（`GlRenderer`/`ModernRenderer`/`ExtractorRenderer` 三形态）+ 字形图集 + 输入 | `Renderer`/`UiBackend`、`gl/{GlRenderer,ModernRenderer,ExtractorRenderer}`、`fatality/FaClickGui`、`text/FontRegistry` | client |
| `injector/` | 注入器 Swing GUI，提供 jar 的 `Main-Class` | `InjectorApp`、`MainWindow`、`KeyCodes` | core, FlatLaf, MigLayout, gson |
| `dist/` | 无源码的聚合模块：`distJar` + `launcherJar` + `randomizedJar` + `verifyJarBytecode` | `dist/build.gradle.kts` | 全部 |

依赖是**森林**：`injector → core`；`agent → client, ui, core`；`ui → client`；`dist → 全部`。
方向恒为 `ui → client`，**client 不得 import ui 的类型**（UI 通过 `OverlayDraw`/`HudSink`/`FrameListener` 反向实现）。

入口矩阵：

| 环境 | 入口 |
|---|---|
| attach / `-javaagent` | `MANIFEST: Premain-Class` = `Agent-Class` = `dev.nocturne.agent.NocturneAgent` |
| 双击 / `java -jar` | `Main-Class: dev.nocturne.injector.InjectorApp`；参数含 `--list-json` / `--pid=` 时**在启动 Swing 之前**转发给 `dev.nocturne.core.Nocturne` |
| Fabric mod | `fabric.mod.json` 的 `client` entrypoint = `dev.nocturne.agent.mod.NocturneModEntry`；`mixins` = `nocturne.mixins.json`（`NocturneMixinPlugin#postApply` 手写 ASM）。版本从 Fabric 的 Minecraft 元数据取，转交 `NocturneAgent.startAsMod` |
| WPF 壳 | `launcher/`，只把 jar 当子进程驱动（`--list-json` / `--pid=`），不链接 jar 内任何类型 |

绘制后端按栈选择（逻辑在 `agent/OverlayBootstrap`）：

| 后端 | 类 | 代际 | 版本 |
|---|---|---|---|
| `gl-fixed` | `GlRenderer` | LWJGL2 固定管线 | 1.8.9 – 1.12.2 |
| `gl-core` | `ModernRenderer` | GL 3.2 core + GLSL 150 | 1.13 – 1.19（1.16–1.19 默认；`-Dnocturne.ui.backend=gl` 与 1.21.1 attach 兼容豁免也走它） |
| `gui-stack` | `ExtractorRenderer`（`poseArg` 分支） | 静态 `GuiComponent.fill`/`drawString` + 游戏 `PoseStack` | 仅 1.16 – 1.19 的**兜底**（gl-core 绑不上核心 profile 时；`-Dnocturne.ui.backend=game` 可强制；scissor 1.19.1 起才有） |
| `gui-graphics` | `ExtractorRenderer` | 游戏自己的 `GuiGraphics`，**零 GL**（日志 `gui-graphics stack`；`backendName()` 仍报 `gui-extractor`） | 1.20 – 1.21（1.21.1 attach 强制 gl-core） |
| `gui-extractor` | `ExtractorRenderer` | 游戏自己的 `GuiGraphicsExtractor`，**零 GL** | 26.1 – 26.3 |

## 4. 必须知道的陷阱

### 4.1 bootstrap 层（本仓库最容易踩、症状最迷惑的坑）

- bootstrap 搜索路径里的类由 `NocturneAgent.installBootstrapBridge` 写临时 jar +
  `appendToBootstrapClassLoaderSearch` 导出，共 10 个条目：
  - `client/.../runtime/{FrameDispatcher,FrameListener,InputBlock,ScrollState}`；
  - `dev/nocturne/agent/GuiDrawHook`（普通类，绘制钩子）；
  - `dev/nocturne/bootstrap/{KeySink,GameplaySink,LegacyMouse}` 与
    `dev/nocturne/client/bootstrap/GameplayHook`（含内部 `Sink`）——这 5 个以**资源**形式随 jar
    （`nocturne-bootstrap/`，由 dist 的 `bridgeKeySink` 生成），交付 jar 里同名类被显式排除。
  任何一份被 Knot 再定义一次，静态字段就分裂。
- 桥 jar 内嵌 `nocturne-bootstrap/bridge.version`：安装前比对**已生效**的桥版本，不一致直接拒绝安装
  （bootstrap 是 first-wins，旧类会留着，混装两份构建必炸），并提示重启游戏。
- 它们**必须只依赖 JDK**（一旦 import gson/ASM 就会和游戏自带版本打架），且**只能**用
  `Class.forName(name, true, null)` 取用。
- 症状：Fabric 的 `KnotClassLoader` 会把每份 jar 各加载一遍，静态字段各存一份——
  **帧回调连续触发，但监听器列表恒为 0，界面永远不出现**。任何跨加载器的静态状态都必须放这里。
- 关联步骤：`exposeAgentJarToGameLoader` 用反射调 Knot 自己的 `addUrlFwd(URL)` 把 agent jar 暴露给游戏加载器。

### 4.2 反射与映射

- `Reflect.load` 会执行 `<clinit>`，可能把类标记为 erroneous 并拖垮游戏；**探测一律用 `loadWithoutInit`**。
- `MethodCandidate.descriptor == null` 含义是「按实参推断」，**不是无参**；先查 `hasMethodDescriptor()`。
- 候选顺序恒为 `vanilla → fabric → forge → neoforge → canonical`；名字与描述符必须**同命名空间配对**使用，
  跨命名空间混用会让参数解析失效。
- 表里 `"absent": true` 表示该版本**真的没有**对应物：运行期打一次性日志并回退规范名。
  生成器会再附一个 `absentReason`：`verified`（映射源/jar 确认不存在）、`ambiguous`（自身择优失败，
  生成期就该失败）、`unverified`（该版本的来源不足以判定，例如 anchor 路径）。别把后两者当"确认没有"。
- 跨版本 arity 漂移是常态（例：`Entity#getEyePosition` 在 ≤1.16.5 是 `(F)`，1.17+ 是无参），
  `callMapped` 会在 arity 不匹配时退回按实参类型推断。
- 反射句柄有缓存，且**只缓存成功**；失败会重试，别在外面再套一层缓存。

### 4.3 字节码变换

- 目标类/方法名**硬编码在 `NocturneAgent` 常量里**，不是运行期查表——加注入点要改那里。
  帧钩子：`Display.update()V` / `GLFW.glfwSwapBuffers(J)V` / `SDLVideo.SDL_GL_SwapWindow(J)Z`；
  绘制钩子：`Hud.extractRenderState` / `Screen.extractRenderStateWithTooltipAndSubtitles`；
  输入短路：`KeyboardHandler#keyPress`、`KeyMapping#setAll|set|click`、`ContainerEventHandler#mouseClicked`、
  `MouseHandler#grabMouse|turnPlayer|onScroll`、1.8.9/1.12.2 的 `MouseHelper#mouseXYChange` 与
  `Inventory#changeCurrentItem`、`Minecraft#startAttack|startUseItem`；
  世界钩子：`EntityRenderDispatcher#shouldRender|render`、`BlockRenderDispatcher#renderBatched`、
  `Camera#getMaxZoom`、`LocalPlayer`/`Connection` 的 tick/发包入口；
  SDL 滚轮：`SDLEventHandler#handleMouseWheelEvent`。
- **Fabric 通道**走 Mixin：`nocturne.mixins.json` 声明 4 个空壳 mixin（Screen/Hud/KeyboardInput/MouseInput），
  真正改写由 `NocturneMixinPlugin#postApply` 用 ASM 完成；agent 形态下由 `Mixins.addConfiguration`
  （`MixinEnvironment.addConfiguration` 作兜底）注册，mod 形态下由 `fabric.mod.json` 在 `DEFAULT` 阶段前交给 loader。
  Mixin **不处理已加载的类**：agent 形态只在 `-javaagent` 启动期生效，运行中 attach 会静默跳过。
  `@Inject` 不要用——它的方法签名绑定版本专属形参类型。
- `ClassWriter(reader, COMPUTE_MAXS)` + `reader.accept(visitor, 0)` 保留原 `StackMapTable`；
  插入**分支**的转换器（`InputBlockTransformer`）必须手写一条 `F_NEW` 完整 frame。
- 末尾钩子**不得读取局部变量**：带 `StackMapTable` 的 `RETURN` 可能把 `this`/形参槽裁成 `top`
  （1.16.5 `KeyboardInput#tick` 实测）。需要 `this` 或形参时一律改成「方法头捕获 + 末尾无参消费」，
  参考 `InputTickHookTransformer`、`GameplayHook.chamsPre/chamsPost`。
- **SDL 栈（26.x）不注册帧钩子、不碰 GL**：LWJGL GL 绑定在 SDL 下不可用，native abort 接不住。
  绘制由绘制钩子驱动。
- 输入短路**不要**拦 `MouseHandler#onButton`——那是我们自己点击的来源。

### 4.4 打包与运行时不变式

- `dist` jar **不得**出现展开的 `org/objectweb/asm/**`（Knot 会加载出第二份 ASM → `VerifyError`）；
  ASM 只以加密载荷 `dev/nocturne/agent/asm.pack` 存在，由 `dist` 的 `packAsmPayload` 调用
  `dev.nocturne.core.pack.PayloadTool asm-pack` 生成，`distJar.doLast` 会**硬失败**校验这两条。
- `module-info.class` 与 `META-INF/versions/**` 必须排除，否则以该 jar 为 classpath 的编译会报
  「程序包不存在」，且 Java 8 目标会撞上高版本类。
- `net/miginfocom/**`（MigLayout，major 55）只进 `nocturne-launcher-*.jar`，**不进** agent jar：
  注入进 1.8.9 的类必须全部 ≤ 52。
- `fabric.mod.json` 与 `nocturne.mixins.json` **随交付 jar**（Fabric 通道）；`verifyJarBytecode`
  另外校验「五个模块编译出的每个类都在 jar 里」，漏包会在构建期红。
- 载荷密钥（`PayloadKey` 的种子）写在代码里，只提高零成本静态扫描门槛，**不是**对抗定向逆向的防护。

### 4.5 每帧执行的东西

- 零分配是硬要求：投影用 `project(..., out)` 重载，`ScreenPoint` 实例复用，别在 `onTick`/`onFrame` 里 new。
- `EventBus.post` 同步、先按优先级再按注册顺序、逐订阅者 `catch(Throwable)`（5s 节流日志）；
  `Cancellable` 默认跳过后续投递，`skipCancelled=true` 的订阅者仍接收。`FrameListener` 不得阻塞。
- 订阅必须留 handle：`Subscription s = eventBus.subscribe(RenderEvent.class, this::onRender);`——
  lambda 是不同对象，只能靠这个 handle `unsubscribe`。
- 渲染模块**不走 EventBus 画图**：在 `GuiOverlay.drawFrame` 的 `beginFrame()`/`endFrame()` 之间调用
  `WorldOverlay.drawWorldOverlay(OverlayDraw, WorldProjection)`（`RenderEvent` 时后端还没准备好）。
- **每帧每实体禁止手写 `Class.forName` 候选循环**：一律走 `GameBridge.type(ClassType)`（内部有
  `typeCache`，只缓存成功结果）。未命中的候选每次都会抛异常，几百个实体 × 每帧就是上千次类加载
  查询——1.16.5 真机表现为「一开透视框，服务端 tick 掉队、随后追赶」，像游戏加速。
- **帧驱动有两入口**：`NocturneRuntime.onFrame()`（帧交换点，权威）与 `onDrawFrame()`（GUI 绘制钩子）。
  游戏绘制 API 后端（`gui-graphics`/`gui-stack`/`gui-extractor`）的上下文只在绘制钩子那一次调用内
  有效，所以 `OverlayBootstrap` 会置 `setContextDriven(true)`；自绘 GL 后端在帧钩子已驱动时
  `onDrawFrame()` 直接返回，避免同帧两遍。诊断开关 `-Dnocturne.debug.frames` 打印每秒驱动次数与来源。

### 4.6 自销毁

`NocturneClient.panic()` **不可逆**：停用全部模块、关闭驱动闸门、清空 `FrameDispatcher`、
`InputBlock.disable()`——之后 GUI 打不开、开关键不响应。恢复只能重启游戏。

## 5. 常见改动怎么做

### 5.1 新增模块

1. 在 `client/src/main/java/dev/nocturne/client/module/modules/<分类小写>/` 建类，游戏功能优先继承
   `GameModule`（统一名称/分类与启停退订），HUD 行继承 `HudModule`。
2. 继承 `GameModule` 时在构造器传入名称/分类；其它基类按各自契约实现 `name()` + `category()`。
3. 值用字段初始化器 `private final BooleanValue x = add(new BooleanValue("X", true));`
   （`BooleanValue`/`NumberValue`/`ModeValue`/`ColorValue`）；子类构造器末尾必须 `normaliseDefault()`。
4. 实现 `onTick()` 与/或 `onEnable()`/`onDisable()`；需要跨模块协作时订阅 `TickEvent`。
5. 在 `NocturneClient.registerModules()` 里按分类连续注册。
6. GUI 卡片自动生成：`BooleanValue→FaCheckRow`、`NumberValue→FaSliderRow`、`ModeValue→FaComboRow`、
   `ColorValue→FaColorRow`，其它 Value 类型会被跳过。
   空分类的 Tab 会被隐藏。

### 5.2 新增渲染模块

实现 `WorldOverlay`，在 `onEnable` 订阅 `RenderEvent`（并在 `onTick` 里自愈重订阅），
用 `OverlayDraw` 画、用 `WorldProjection` 把世界坐标投影成屏幕逻辑像素。
Chams 使用 `EntityRenderDispatcher` 的实体首参回调；Xray 使用 `BlockRenderDispatcher` 的
`BlockState + BlockPos` 双参门控。新增需要 render pass 的效果也应走真实钩子，**没接入的模块不要注册**：
注册出去的「开了没反应」开关比不提供更糟。

`ModernRenderer`（gl-core）的两条硬约束：
1. 同色几何走合并缓冲（`batchBuffer`），任何会改 GL 状态或改变层级的调用前必须 `flushBatch()`——
   `text` / `texturedQuad` / `drawPlayer` / `pushClip` / `popClip` / `uploadTexture` / `endFrame`。
   漏一个的后果是纯色几何被裁进新矩形、或压在文字下面。
2. **不要试图把 program/VAO/纹理「恢复成进入前的原值」**：在游戏自己的 VAO 上 `glBindBuffer` 会改写
   它自己的 ARRAY_BUFFER 绑定，1.16.5 实测直接刷 `GL_INVALID_OPERATION: Invalid VAO/VBO/pointer usage`
   并让服务端掉 100+ tick。只解绑到 VAO 0（VAO 0 是独立的默认状态，不触碰游戏 VAO 内容）。

### 5.3 新增映射需求

1. 直接把规范名用在调用点（`scan.py` 会扫到），或写进 `tools/mapping/requirements.txt`。
   **`scan.py` 只认得直接传进桥的字符串字面量**（`readField`/`writeField`/`fieldName`/`callMapped` 那类）；
   一旦成员名经过自己的包装方法（例：`FullBrightModule.readStaticField(bridge, ClassType.MOB_EFFECTS, "NIGHT_VISION")`）
   就扫不到，必须手工补 `requirements.txt`——漏了的表现是表里**根本没有那条**（注意不是 `absent`），
   运行期读不到、模块按「缺成员」降级（只打一次日志）。
2. `python tools/mapping/generate.py --report` 看 absent 清单。
3. legacy 版本（1.8.9 / 1.12.2）改 `aliases-<version>.toml` 的桥接规则；
   modern 版本以官方映射为准——真改名就保持 absent，只有**移动了命名空间**才改 `namespaces.py`。
   已知的改名链写 `member-aliases.txt`；同名方法/字段并存时在 `requirements.txt` 用 `#成员!field`
   强制按字段登记。
4. 重跑 `--check` / `--javap`，并确认 `--report` 里你关心的成员干净。
   `--check` 现在会连 jar 校验一起跑（`--fast` 才是纯文本比较）；只比文本时它**不保证名字真的存在**，
   改过别名或需求后不要用 `--fast` 当门禁。
5. 静态常量必须映射到真实持有类：效果常量使用 `MOB_EFFECTS`，不能挂到 `MOB_EFFECT` 上；
   旧映射可以把两种规范类映射到同一运行时类，但不能把别的类的字段名写进效果类型。

### 5.4 新增/修改注入点

目标方法名在 `NocturneAgent` 里硬编码；同时要考虑：描述符、插入位置（开头/末尾）、
失败语义（未命中返回 `null`）、以及是否需要新的 bootstrap 层状态。
Fabric 通道的钩子还要在 `nocturne.mixins.json` / `NocturneMixinPlugin` 里加目标（或复用 `autoApply`），
并确认 `shouldApplyMixin` 不会把它在 agent 形态下误启用。

### 5.5 新增绘制能力

只能经 `Renderer` / `UiBackend` 抽象添加，并**各后端同时实现**（`GlRenderer` / `ModernRenderer` /
`ExtractorRenderer` 的 `gui-graphics`、`gui-stack`、`gui-extractor` 三形态）；纹理只走 `uploadTexture` / `texturedQuad` / `deleteTexture`。
`ExtractorRenderer` 的能力是游戏 API 的子集（没有圆角与逐顶点色），缺啥就近似。
后端不支持纹理或 `java.desktop` 被裁剪时整体回落到游戏字体 + ASCII 图标。

### 5.6 新增键位/输入

`KeyMap` 的规范空间是 AWT `KeyEvent.VK_*`（注入器存的就是它），默认 GUI 键是右 Shift 的哨兵
`VK_RIGHT_SHIFT = 0x1F01`（AWT 无该常量；**不能**借用 `VK_6` 的 54，否则按数字 6 会开关界面）。
`GuiOverlay` 同时接受 **INSERT**（AWT 155）作为兼容入口，第三方客户端占用右 Shift 时仍可打开。
SDL 世代**鼠标取游戏自己的事件态**（`MouseHandler.xpos/ypos` + `activeButton`），
`SDL_GetMouseState` 在无焦点时静默给 `0,0`。

## 6. 测试与验收纪律

- 单测：JUnit 5，位置 `<module>/src/test/java/…`；`ui` 用手写假后端（`FakeBackend` / `FakeGl`）跑，不需要真 GL。
- 结论只有两种状态：**实测通过** / **未验证**。禁止写「应该能行」「大概率可用」。
- 真机验收的版本、结论与证据记进 `docs/VERSION-MATRIX.md`（每格要求：注入成功 → 日志行 + 截图），
  涉及的日志放 `tmp/`（已 gitignore）。
- **每做完一个大项立刻 `git commit` + `git push`**：真机验证的结论只认已 push 的提交。
- `tmp/` 是草稿区（launch 脚本、探针、现场日志），不是产品代码。

## 7. 许可红线

- 本仓库以 **GPL-3.0-or-later** 分发（`LICENSE`）。分发时要保留声明并以同许可提供完整对应源码，
  不得附加「禁止转售」之类的额外限制。第三方清单见 `THIRD-PARTY-NOTICES.md`。
- `vendor/vape/`（OpenVape 4.21.36，CC0）**只作参考资料**与 `tools/mapping` 的 legacy 映射数据源。
  移植模块**只做行为对齐**：参数名、默认值、触发逻辑可以对齐；**禁止**拷实现文本、`gg.vape.*` 包路径或注释原文。
- `vendor/setsuna/` 是参考快照，不参与构建；`ui/` 中曾移植自它的代码已全部删除，不要再引回来。
- `ui/fatality/**` 移植自 FatalityClickGui，**上游没有 LICENSE**：移植与再分发以仓库使用者的授权为准，
  无授权时不得对外分发。字体各自独立授权（Manrope/MiSans/Lucide/FA Free）。
- 映射表 JSON 是**衍生事实**，可以入库；`tools/mapping/cache/` 与 Mojang 的 `client.txt` **只落地不到仓库、不进 jar**。

## 8. 当前缺口（不要假装已实现）

- Linux/macOS 的 attach 通道（`PosixAttachStrategy` + `attach_unix.c`）只做过编译校验与单测，**未实机**。
- 模组形态只有 Fabric 一条（`fabric.mod.json` + Mixin）；Forge/NeoForge 入口不存在。
- 3D 玩家预览已接游戏原生库存人物渲染入口（1.8.9–1.14.4 固定管线、1.15–1.19.3 PoseStack、1.20+ GuiGraphics），
  但**未逐版本真机验收**；SDL 仍只走游戏绘制队列。
- 1.14–1.14.3 的 StorageEsp、1.9–1.14.3 的 `ClientLevel#getBlockState` 面缺失（对应功能降级）。
- 1.21.5–26.3 的 Chams / 部分 Xray：`EntityRenderDispatcher#render`、`BlockRenderDispatcher#renderBatched`
  找不到可织入的描述符时显示 `unavailable`，Xray 只画矿框（日志显式打印，不静默假装生效）。
- 新 Fatality 界面已删除旧 UI 库；HUD/HUD 编辑器不再存在（`GuiOverlay` 提供新的文本/多行 HUD，
  不包含旧编辑器）。逐版本真机验收格子见 `docs/VERSION-MATRIX.md`。
- 世界覆盖层已铺：ESP / Tracers / NameTags / ItemEsp / Trajectories / StorageEsp / Search；
  Chams 已接实体渲染回调，Xray 已接方块渲染门控（26.1+ 见上）。
