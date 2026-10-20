# AGENTS.md

面向在本仓库工作的 AI 编码代理。人类入口：`README.md`；架构：`docs/ARCHITECTURE.md`；
版本矩阵与验收状态：`docs/VERSION-MATRIX.md`；计划与工作纪律：`docs/PLAN.md`。
**本文件只写规则与陷阱；状态与验收结论一律以 `docs/VERSION-MATRIX.md` 为准，不要在别处复述。**

## 0. 这是什么

JVM **注入式** Minecraft 客户端：同一个 jar 既是注入器（Swing GUI / CLI），也是被注入进游戏的 agent。

- 覆盖 `1.8.9 – 26.3` 共 **66 个 release**，但那是**映射表覆盖**；真机跑过的只有 **1.8.9 / 1.16.5 / 26.3**。
- 没有模组形态：不产出 `fabric.mod.json` / `mods.toml`，不支持放进 `mods/`。
- 代码全部中文注释；新代码保持同样风格。

## 1. 硬规则（违反即错）

| # | 规则 | 为什么 |
|---|---|---|
| 1 | 只有注入一条路径，不做模组入口 | 模组路径拿不到 `Instrumentation` 就没有帧钩子，而帧回调是整个客户端的驱动源 |
| 2 | 客户端代码只写 **Mojmap 规范名**，**禁止版本分支**（`if (version >= …)`、`instanceof` 分代） | 版本差异全部下沉到每版本一份映射表 JSON |
| 3 | **运行时零探测**：目标版本由注入器判定并随 attach 参数传入（`mcVersion=`），加载器靠候选名字顺序试 | 探测在隔离类加载器下极不可靠 |
| 4 | `core/ agent/ client/ ui/ injector/` 一律 **Java 8 字节码**（根脚本 `options.release = 8`），且只用 Java 8 API | 要注入 JVM 8 的 1.8.9；高版本字节码会被 `UnsupportedClassVersionError` 拒 |
| 5 | 不用 Mixin；ASM 变换**不得** `COMPUTE_FRAMES` | 隔离加载器下 `getCommonSuperClass` 会崩 |
| 6 | 注入进游戏方法的代码**绝不把异常外泄**：转换器失败 `return null`（沿用原字节码），钩子内部 `catch(Throwable)` | 未捕获异常会让游戏崩在任意位置 |
| 7 | 反射解析游戏类必须走 `GameBridge` / `gameLoader()`，绝不用本模块的类加载器 | Fabric Knot / Forge 用隔离加载器重新加载每份 jar |

## 2. 构建与验证（下列命令已在本机实测通过）

```bash
export JAVA_HOME="C:\Program Files\Microsoft\jdk-21.0.12.8-hotspot"        # 必须 JDK 21
GRADLE="C:/Users/liuyyx/.gradle/wrapper/dists/gradle-9.2.1-bin/9fgc9dy84duywbml6zqd0l4mo/gradle-9.2.1/bin/gradle.bat"

"$GRADLE" build --console=plain --no-daemon    # 编译 + 单测 + 打包 dist（实测 BUILD SUCCESSFUL）
"$GRADLE" test  --console=plain --no-daemon    # 仅单测（实测 298 项，0 失败 0 跳过）
python tools/mapping/generate.py --check       # 映射表门禁（实测 66 个版本，exit 0，离线可跑）
python tools/mapping/generate.py --javap       # 逐版本对真实 client jar 校验（⚠ 当前 exit 1，见 §8）
```

- **没有 gradle wrapper**：`gradlew` / `gradlew.bat` / `gradle/wrapper/` 都不存在，`gradle` 也不在 PATH。
  上面是本机已解包的 Gradle 9.2.1 发行版；注意 `~/.gradle/wrapper/dists/gradle-9.2.1-bin/` 下
  **另有一个只有 `.part` 的残缺目录**（`2t0n5ozl…`），用 `*` 通配时可能命中它——写成具体路径最稳。
- 必须先设 `JAVA_HOME=JDK 21`：Gradle 9 自身要求 17+，而本机默认 `JAVA_HOME` 指向
  `C:\Java\jdk8u432-b06`。toolchain 固定 21，靠 `release = 8` 输出旧字节码。
- 产物：`dist/build/libs/nocturne-<version>.jar`（约 8.4 MB）；`build` 会另拷一份到根 `build/libs/`。
  双击 `nocturne.bat` 从 `dist/build/libs/` 取**最新** jar（`dir /o-d`），所以别在源码树里散落旧 jar。
- 运行：`java -jar dist/build/libs/nocturne-*.jar --list-json`（列进程）/
  `java -jar … --pid=<n>`（注入）。GUI 与 CLI 是同一个入口。
- 映射表生成器（`tools/mapping/`）：`generate.py` 重写全部表；`--check` 陈旧即 exit 1；
  `--javap` 对本地 client jar 逐版本校验；`--only <ver>` + `--report` 打 absent 清单。
  只用 Python 3.12 stdlib；`tools/mapping/cache/` 已含 66 个 client jar，**离线可跑**。
- **不在 Gradle 构建内**：`launcher/`（.NET 8 WPF，`dotnet build`）、`tools/mapping/`（Python）、
  `vendor/`（参考快照）。改它们不影响 `gradle build`。

## 3. 模块地图

| 模块 | 职责 | 关键类 | 依赖 |
|---|---|---|---|
| `core/` | 进程发现、三策略 attach 链（`jdk-api` → `windows-native` / `unix-domain`）、载荷加解密、attach 参数组装、CLI 入口 | `Nocturne`（`main`）、`Attacher`、`AgentOptions`、`PayloadPack`/`PayloadTool` | gson |
| `agent/` | 被注入侧：`premain`/`agentmain`、ASM 子加载器、3 个转换器、bootstrap 桥 | `NocturneAgent`、`EmbeddedAsmLoader`、`transform/{FrameHook,CallbackHook,InputBlock}Transformer`、`OverlayBootstrap` | client, ui, core, ASM 9.8 |
| `client/` | 事件总线、模块/值框架、映射层、反射桥、渲染契约、runtime | `NocturneClient`、`EventBus`、`Module`/`ModuleRegistry`、`Mapping`/`ObfuscatedMapping`、`GameBridge`、`runtime/*` | gson |
| `ui/` | 自绘 ClickGUI + 三个绘制后端 + 字形图集 + 输入 | `Renderer`/`UiBackend`、`gl/{GlRenderer,ModernRenderer,ExtractorRenderer}`、`fatality/FaClickGui`、`text/FontRegistry` | client |
| `injector/` | 注入器 Swing GUI，提供 jar 的 `Main-Class` | `InjectorApp`、`MainWindow`、`KeyCodes` | core, FlatLaf, MigLayout, gson |
| `dist/` | 无源码的聚合模块：自定义 `distJar` 合并五者 | `dist/build.gradle.kts` | 全部 |

依赖是**森林**：`injector → core`；`agent → client, ui, core`；`ui → client`；`dist → 全部`。
方向恒为 `ui → client`，**client 不得 import ui 的类型**（UI 通过 `OverlayDraw`/`HudSink`/`FrameListener` 反向实现）。

入口矩阵：

| 环境 | 入口 |
|---|---|
| attach / `-javaagent` | `MANIFEST: Premain-Class` = `Agent-Class` = `dev.nocturne.agent.NocturneAgent` |
| 双击 / `java -jar` | `Main-Class: dev.nocturne.injector.InjectorApp`；参数含 `--list-json` / `--pid=` 时**在启动 Swing 之前**转发给 `dev.nocturne.core.Nocturne` |
| WPF 壳 | `launcher/`，只把 jar 当子进程驱动（`--list-json` / `--pid=`），不链接 jar 内任何类型 |

绘制后端按栈选择（逻辑在 `agent/OverlayBootstrap`）：

| 后端 | 类 | 代际 | 版本 |
|---|---|---|---|
| `gl-fixed` | `GlRenderer` | LWJGL2 固定管线 | 1.8.9 – 1.12.2 |
| `gl-core` | `ModernRenderer` | GL 3.2 core + GLSL 150 | 1.13 – 1.21.11 |
| `gui-extractor` | `ExtractorRenderer` | 游戏自己的 `GuiGraphicsExtractor`，**零 GL** | 26.1 – 26.3 |

## 4. 必须知道的陷阱

### 4.1 bootstrap 层（本仓库最容易踩、症状最迷惑的坑）

- 只有 4 个类被放进 **bootstrap** 搜索路径：`client/.../runtime/{FrameDispatcher,FrameListener,InputBlock,ScrollState}`，
  由 `NocturneAgent.installBootstrapBridge` 写临时 jar + `appendToBootstrapClassLoaderSearch` 导出。
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
- 跨版本 arity 漂移是常态（例：`Entity#getEyePosition` 在 ≤1.16.5 是 `(F)`，1.17+ 是无参），
  `callMapped` 会在 arity 不匹配时退回按实参类型推断。
- 反射句柄有缓存，且**只缓存成功**；失败会重试，别在外面再套一层缓存。

### 4.3 字节码变换

- 目标类/方法名**硬编码在 `NocturneAgent` 常量里**，不是运行期查表——加注入点要改那里。
  帧钩子：`Display.update()V` / `GLFW.glfwSwapBuffers(J)V` / `SDLVideo.SDL_GL_SwapWindow(J)Z`；
  绘制钩子：`Hud.extractRenderState` / `Screen.extractRenderStateWithTooltipAndSubtitles`；
  输入短路：`KeyboardHandler#keyPress`、`KeyMapping#setAll|set|click`、`ContainerEventHandler#mouseClicked`；
  SDL 滚轮：`SDLEventHandler#handleMouseWheelEvent`。
- `ClassWriter(reader, COMPUTE_MAXS)` + `reader.accept(visitor, 0)` 保留原 `StackMapTable`；
  插入**分支**的转换器（`InputBlockTransformer`）必须手写一条 `F_NEW` 完整 frame。
- **SDL 栈（26.x）不注册帧钩子、不碰 GL**：LWJGL GL 绑定在 SDL 下不可用，native abort 接不住。
  绘制由绘制钩子驱动。
- 输入短路**不要**拦 `MouseHandler#onButton`——那是我们自己点击的来源。

### 4.4 打包与运行时不变式

- `dist` jar **不得**出现展开的 `org/objectweb/asm/**`（Knot 会加载出第二份 ASM → `VerifyError`）；
  ASM 只以加密载荷 `dev/nocturne/agent/asm.pack` 存在，由 `dist` 的 `packAsmPayload` 调用
  `dev.nocturne.core.pack.PayloadTool asm-pack` 生成，`distJar.doLast` 会**硬失败**校验这两条。
- `module-info.class` 必须排除，否则以该 jar 为 classpath 的编译会报「程序包不存在」。
- 载荷密钥（`PayloadKey` 的种子）写在代码里，只提高零成本静态扫描门槛，**不是**对抗定向逆向的防护。

### 4.5 每帧执行的东西

- 零分配是硬要求：投影用 `project(..., out)` 重载，`ScreenPoint` 实例复用，别在 `onTick`/`onFrame` 里 new。
- `EventBus.post` 同步、按注册顺序、逐订阅者 `catch(Throwable)`（5s 节流日志）；
  `Cancellable` 会中止后续投递。`FrameListener` 不得阻塞。
- 订阅必须留 handle：`Subscription s = eventBus.subscribe(RenderEvent.class, this::onRender);`——
  lambda 是不同对象，只能靠这个 handle `unsubscribe`。
- 渲染模块**不走 EventBus 画图**：在 `GuiOverlay.drawFrame` 的 `beginFrame()`/`endFrame()` 之间调用
  `WorldOverlay.drawWorldOverlay(OverlayDraw, WorldProjection)`（`RenderEvent` 时后端还没准备好）。

### 4.6 自销毁

`NocturneClient.panic()` **不可逆**：停用全部模块、关闭驱动闸门、清空 `FrameDispatcher`、
`InputBlock.disable()`——之后 GUI 打不开、开关键不响应。恢复只能重启游戏。

## 5. 常见改动怎么做

### 5.1 新增模块

1. 在 `client/src/main/java/dev/nocturne/client/module/modules/` 建类，继承 `Module`（HUD 行继承 `HudModule`）。
2. 实现 `name()` + `category()`；分类只有 `MOVEMENT / RENDER / PLAYER / MISC` 四个。
3. 值用字段初始化器 `private final BooleanValue x = add(new BooleanValue("X", true));`
   （`BooleanValue`/`NumberValue`/`ModeValue`/`ColorValue`）；子类构造器末尾必须 `normaliseDefault()`。
4. 实现 `onTick()` 与/或 `onEnable()`/`onDisable()`；需要跨模块协作时订阅 `TickEvent`。
5. 在 `NocturneClient.registerModules()` 里注册。
6. GUI 卡片自动生成：`BooleanValue→FaCheckRow`、`NumberValue→FaSliderRow`、`ModeValue→FaComboRow`、
   `ColorValue→FaColorRow`，其它 Value 类型会被跳过。
   空分类的 Tab 会被隐藏；`XrayModule` 目前**故意未注册**（渲染 pass 未接，别顺手打开）。

### 5.2 新增渲染模块

实现 `WorldOverlay`，在 `onEnable` 订阅 `RenderEvent`（并在 `onTick` 里自愈重订阅），
用 `OverlayDraw` 画、用 `WorldProjection` 把世界坐标投影成屏幕逻辑像素。
需要实体渲染 pass / 方块渲染拦截的效果（Chams、Xray）在 2D 覆盖层下**做不了**——这是能力边界，不是没做。

### 5.3 新增映射需求

1. 直接把规范名用在调用点（`scan.py` 会扫到），或写进 `tools/mapping/requirements.txt`。
   **`scan.py` 只认得直接传进桥的字符串字面量**（`readField`/`writeField`/`fieldName`/`callMapped` 那类）；
   一旦成员名经过自己的包装方法（例：`FullBrightModule.readStaticField(bridge, ClassType.MOB_EFFECT, "NIGHT_VISION")`）
   就扫不到，必须手工补 `requirements.txt`——漏了的表现是表里**根本没有那条**（注意不是 `absent`），
   运行期读不到、模块按「缺成员」降级（只打一次日志）。
2. `python tools/mapping/generate.py --report` 看 absent 清单。
3. legacy 版本（1.8.9 / 1.12.2）改 `aliases-<version>.toml` 的桥接规则；
   modern 版本以官方映射为准——真改名就保持 absent，只有**移动了命名空间**才改 `namespaces.py`。
4. 重跑 `--check` / `--javap`，并确认 `--report` 里你关心的成员干净。
   **`--check` 只保证表的字节稳定，不保证名字真的存在**——改过别名或需求后必须 `--javap` 双绿再提交。

### 5.4 新增/修改注入点

目标方法名在 `NocturneAgent` 里硬编码；同时要考虑：描述符、插入位置（开头/末尾）、
失败语义（未命中返回 `null`）、以及是否需要新的 bootstrap 层状态。

### 5.5 新增绘制能力

只能经 `Renderer` / `UiBackend` 抽象添加，并**三个后端同时实现**（`GlRenderer` / `ModernRenderer` /
`ExtractorRenderer`）；纹理只走 `uploadTexture` / `texturedQuad` / `deleteTexture`。
`ExtractorRenderer` 的能力是游戏 API 的子集（没有圆角与逐顶点色），缺啥就近似。
后端不支持纹理或 `java.desktop` 被裁剪时整体回落到游戏字体 + ASCII 图标。

### 5.6 新增键位/输入

`KeyMap` 的规范空间是 AWT `KeyEvent.VK_*`（注入器存的就是它），默认 GUI 键是右 Shift 的哨兵
`VK_RIGHT_SHIFT = 0x1F01`（AWT 无该常量；**不能**借用 `VK_6` 的 54，否则按数字 6 会开关界面）。
SDL 世代**鼠标取游戏自己的事件态**（`MouseHandler.xpos/ypos` + `activeButton`），
`SDL_GetMouseState` 在无焦点时静默给 `0,0`。

## 6. 测试与验收纪律

- 单测：JUnit 5，位置 `<module>/src/test/java/…`；`ui` 用手写假后端（`FakeBackend` / `FakeGl`）跑，不需要真 GL。
- 结论只有两种状态：**实测通过** / **未验证**。禁止写「应该能行」「大概率可用」。
- 真机验收（1.8.9 / 1.16.5 / 26.3）的结论记进 `docs/VERSION-MATRIX.md`，涉及的日志放 `tmp/`（已 gitignore）。
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

- **`generate.py --javap` 当前红：21 个版本各 1 个问题**（1.9、1.9.1–1.9.4、1.10–1.10.2、1.11–1.11.2、
  1.12–1.12.2、1.13–1.13.2、1.14–1.14.3）：
  `net/minecraft/world/effect/MobEffect.NIGHT_VISION -> <name> not found on <obf class>`。
  表里给它写的 `vanilla` 名是 `p`，但实测 1.9 的 `rk`（fabric `class_1291` = `MobEffect`）只有 8 个字段、没有 `p`；
  `git show HEAD:client/src/main/resources/mappings-1.9.json` 里该类的 `fields` 是**空的**——
  这条是工作区未提交的 `tools/mapping/aliases-1.12.2.toml` 新增 `[fields.NIGHT_VISION]`（scope
  `net/minecraft/init/MobEffects`，即常量持有类，**不是** `MobEffect`）后重算表时凭空补进来的。
  根因是需求面缺口：`scan.py` 只看得到 `readField`/`fieldName` 这类直接调用，
  而 `FullBrightModule.readStaticField(bridge, ClassType.MOB_EFFECT, "NIGHT_VISION")` 包了一层，扫不到，
  该成员从未进过 `requirements.txt`。修的方向：给 `net/minecraft/world/effect/MobEffects` 建 `ClassType`、
  改 `FullBrightModule` 去读它，并把需求补进 `requirements.txt`——而不是在 `MobEffect` 上造别名。
- Linux/macOS 的 attach 通道（`PosixAttachStrategy` + `attach_unix.c`）只做过编译校验与单测，**未实机**。
- 3D 玩家预览只有占位卡片（映射表缺实体渲染面，26.x 又不能自建离屏 framebuffer）。
- 1.14–1.14.3 的 StorageEsp、1.9–1.14.3 的 `ClientLevel#getBlockState` 面缺失（对应功能降级）。
- 新 Fatality 界面只有**离屏光栅**验证，真机复验未做；旧界面已整体删除，HUD/HUD 编辑器不再存在
  （`HudSink` 接口保留、当前无实现）。
- 世界覆盖层已铺：ESP / Tracers / NameTags / ItemEsp / Trajectories / StorageEsp / Search；
  Chams、Xray 需要渲染 pass 钩子，属未实现。
