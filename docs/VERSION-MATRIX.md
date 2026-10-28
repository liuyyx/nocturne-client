# 版本矩阵

映射表现已覆盖 **1.8.9 – 26.3 的全部 66 个 release**（含 vanilla/Fabric/Forge/NeoForge 四套命名空间）；
>下面标注"真机"的行指**实际在机器上跑过**的版本：**1.8.9 / 1.12.2 / 1.16.5 / 1.20.1 / 1.21.1 / 1.21.4 / 1.21.10 / 1.21.11 / 26.2 / 26.3**。

## 1. 映射表：每版本一张，四个加载器命名空间

表的规模固定为 **61 个类**（客户端实际解析的规范面）。每个类/成员带
`vanilla` / `fabric` / `forge` / `neoforge` 四套运行期名 + **配套的 JNI 描述符**；
运行时按 `vanilla → fabric → forge → neoforge → 规范名` 依次尝试，因此
**同一份表同时服务原版、Fabric、Forge、NeoForge 四种安装**（不做加载器探测）。

| 命名空间 | 运行期类名 | 运行期成员名 | 数据源 |
|---|---|---|---|
| `vanilla` | 混淆（`enn`） | 混淆（`N`、`f_90977_`） | Mojang 官方 `client_mappings`（1.14.4+）；1.8.9/1.12.2 用本机 SRG |
| `fabric` | intermediary（`net.minecraft.class_1657`） | intermediary（`method_1551`） | FabricMC/intermediary（1.14+）、Legacy-Fabric/Legacy-Intermediaries（1.8.2–1.13.2） |
| `forge` | 可读名（`net.minecraft.client.Minecraft`） | SRG：`func_`/`field_` ≤1.15.2，`m_`/`f_` ≥1.16.5 | MinecraftForge/MCPConfig `joined.tsrg` |
| `neoforge` | 同 forge | 1.20.1 同 forge；**1.20.2+ 即 Mojmap 名**（未混淆） | 同 forge / 恒等 |

**覆盖 66 张表**：`1.8.9 – 26.3` 的**每一个 release** 都有表。
`python tools/mapping/generate.py --check` 全绿；生成器与联表细节见 `tools/mapping/README.md`。

**1.9 – 1.14.3 怎么来的**：这些版本早于 Mojang 官方映射（1.14.4 首发），且 1.13+ 没有 MCP 人类名，
两条老路都到不了。改走 **intermediary 锚点**：intermediary 名跨版本稳定（`class_310`/`field_1724`
在每个版本都指向同一个成员），于是先在 1.12.2 学到 `规范名 → intermediary`，再到目标版本的 tiny
文件里反查出混淆名，Forge 命名空间再由混淆名接该版本的 SRG（1.9–1.12.1 用 MCP 自己的
`joined.srg`，1.13–1.14.3 用 MCPConfig）。

**唯一的不完整**：`1.9.1 / 1.9.3 / 1.10.1` 三个点版本 MCP 从未发布 SRG，因此它们的表只有
`vanilla` / `fabric` 两套名（`forge` 缺省）；其余 63 个版本四套齐全。

以下是**真机验证过**的版本的绘制/帧钩子差异：

| 版本 | 混淆 | 映射表来源 | 绘制代际 | 帧钩子目标 | 表状态 |
|---|---|---|---|---|---|
| 1.8.9 | 混淆 | SRG+MCP CSV+人工别名 → +fabric/forge 命名空间 | A | `Display.update()V` | ✅ 已产出（156 具名成员；vanilla 名与旧表逐项一致） |
| 1.12.2 | 混淆 | 同上（`vanilla1122` / `forge1122`） | A | `Display.update()V` | ✅ 已产出（150） |
| 1.16.5 | 混淆 | ProGuard + MCPConfig + intermediary | B | `glfwSwapBuffers(J)V` | ✅ 已产出（162，`--javap` 通过） |
| 1.21.4 | ✅ **真机已实测**（BMWClient 整合包 + Fabric：`backend=gui-extractor`、`Screen.render` 钩子织入；我方 UI 渲染正常，被整合包自带 LiquidBounce 菜单覆盖） | ✅ | ✅ 已产出 | ✅ 可见（三列面板 + 中文标签） |
| 1.21.10 | ✅ **真机已实测**（`1.21.10-opal` + Fabric：`class_437.method_25394` 织入 → `backend=gui-extractor` → `first frame 427x240, font=class_327`；`overlay active`） | ✅ | ✅ 已产出 | ✅ 可见（三列 + 文字） |
| 1.21.11 | ✅ **真机已实测**（`class_437.method_25394` 织入 → `backend=gui-extractor` → `first frame 427x240, font=class_327`；`FaClickGui` 完整显示，文字按设计字号缩放） | ✅ | ✅ 已产出 | ✅ 可见（三列 + 中文标签，无截断） |
| 1.20.1 | 混淆 | 同上 | B | `glfwSwapBuffers(J)V` | ✅ 已产出（166，`--javap` 通过；forge 名用真实 Forge jar 校验 149/149） |
| 1.21.4 | 混淆 | 同上 | B | `glfwSwapBuffers(J)V` | ✅ 已产出（166，`--javap` 通过） |
| 1.21.10 | 混淆 | 同上 | B | `glfwSwapBuffers(J)V` | ✅ 已产出（166） |
| 1.21.11 | 混淆 | 同上 | B | `glfwSwapBuffers(J)V` | ✅ 已产出（166；neoforge 名用真实 NeoForge patched jar 校验 166/166） |
| 26.2 | 未混淆 | 恒等（+ 逐成员存在性校验） | C | `SDL_GL_SwapWindow(J)Z` | ✅ 已产出（167） |
| 26.3 | 未混淆 | 恒等（+ 逐成员存在性校验） | C | `SDL_GL_SwapWindow(J)Z` | ✅ 已产出（167，`--javap` 通过） |

> 各版本行内的成员数差异来自 `absent`：该版本运行时**没有**对应类/成员时显式标注，
> 由运行期打一次性日志并回退规范名，而不是把规范名当混淆名静默使用。

> **已知不确定项（1.16.5 的 Forge 成员名）**：本世界 MCPConfig 的 1.16.5 `joined.tsrg` 给的是
> `m_`/`f_`（与它 1.14.4–1.15.2 的 `func_`、1.17+ 的 `m_` 自洽），而本机 OpenVape 的
> `forge1165/methods.csv` 给的是 `func_71410_x`。两种说法不能同时成立，且本机**没有**可裁决的
> Forge 1.16.5 运行产物（`libraries/net/minecraftforge/forge` 仅有 1.8.9 与 1.20.1）。当前按
> MCPConfig 生成；装一台 Forge 1.16.5 后用 `--javap`-等价的方式核对即可定案。

> 26.x 的映射表不是「不必要」：未混淆只意味着**名字相同**，成员**存不存在**仍逐版本不同
> （例如 26.2 的 HUD 入口与 26.1 不兼容）。表里的 `"absent": true` 就是给这种情况用的。

## 2. 运行时选表

- 注入器判定版本族（命令行里的 `--version <id>` 或 `versions/<实例名>/`），随 agent 参数传 `mcVersion=`。
- agent 读 `/mappings-<版本族>.json`；没有表或版本未知 → 恒等映射 + **日志**（不静默）。
- 版本族归一化：`1.8.9优化` → `1.8.9`；`26.3-Fabric 0.19.5` → `26.3`。

## 3. 绘制/输入能力矩阵

| 能力 | 代际 A（1.8.9/1.12.2） | 代际 B（1.16.5–1.21.x） | 代际 C（26.1–26.3） |
|---|---|---|---|
| 矩形 | `Gui.drawRect` | `DrawContext/GuiGraphics.fill` | `GuiGraphicsExtractor.fill` |
| 文字 | `FontRenderer.drawString`（自绘） | `drawString(Font,…)` | `extractor.text(Font,…)`（字体不自绘） |
| 字体度量 | `getStringWidth` / 行高 9 | `Font.width` | `Font.width` / `lineHeight` |
| 裁剪 | 直接 GL `glScissor` | `enableScissor/disableScissor` | `enableScissor/disableScissor` |
| 按键 | `KeyBinding` + LWJGL2 `Keyboard` | `KeyMapping` / `InputConstants.Type.KEYSYM.getOrCreate(vk)` | `InputConstants.isKeyDown` |
| 鼠标 | LWJGL2 `Mouse` | `MouseHandler.xpos()/ypos()` | 同左 |

## 4. 验收状态

| 版本 | 注入 | 帧钩子 | 表 | 界面可见（真机） |
|---|---|---|---|---|
| 1.8.9 | ✅ **真机已实测**（官方 1.8.9 + LWJGL2：attach → `agentmain` → 帧钩子 live → 叠加层 attach → 右 Shift 开/关，四个分类面板与模块名正常显示；`tmp/mc189*.log`） | ✅ | ✅ 已有表 | ✅ 可见（`backend=gl-fixed` 固定管线、`screen=ClickGui`（旧界面）；P5 输入：右 Shift 唤出 ✅ / Esc 关闭 ✅ / 鼠标点选 ✅ / 滚轮 ⏳（合成事件进不了 LWJGL2 队列，待真人验收；派发链已走读无断点）） |
| 1.16.5 | ✅ **真机已实测**（官方 1.16.5 + LWJGL3，`backend=gl-core`、`FaClickGui` 三列 + 中文标签完整，无截断/重叠；`Screen.render(PoseStack,…)` 不会被误判成 GuiGraphics） | ✅ | ✅ 已产出 | ✅ 可见（`tmp/mc1165-final.png`） |
| 26.2 | ✅ **真机已实测**（修复后） | ✅ 帧钩子按设计不注册（GLFW 交换点只驱动输入轮询） | ✅ 已产出 | ✅ 可见可点（`backend=gui-extractor; input=glfw`；`GuiDrawHook` 织入成功；截图 `docs/research/fatality-26.2-fixed.png`） |
| 26.2（Vulkan） | ⏳ | 未验 | ✅ 已产出 | ⏳ 待真机（26.2 的图形 API 需在游戏设置里手动切换，本次实测的是 OpenGL 路径） |
| 26.3 | ✅ 已实测（真实 26.3 + Fabric：attach → agentmain → 引导完成，游戏稳定不崩） | ⏳ 字节码级已验证（SDL 目标；SDL 栈下按设计不注册帧钩子，绘制由 GUI 绘制钩子驱动） | ✅ 已产出 | ✅ 可见可点（`backend=gui-extractor`、`input=game`、`screen=ClickGui`；三列面板与模块名正常，点击 `FullBright` 行状态翻转） |

✅ 已完成 · ⏳ 进行中/待验证。此表只写实测结论，不写「应该能行」。



> **P5-D 输入穿透修复（2026-10-09，真机 26.3 通过）**：界面开着时，同一次点击/按键会**同时**打在后面的
> 原生界面上（点模块顺带按到「回到游戏」、Esc 顺带打开暂停菜单）——因为我们的界面不是 vanilla
> `Screen`，MC 的输入派发不知道我们吃掉了它。
>
> 处置：新增 **输入短路钩子**（`InputBlockTransformer` + bootstrap 层共享标志 `InputBlock`），
> 界面开着时让游戏侧这些入口直接返回：
> `KeyboardHandler#keyPress`、`KeyMapping#setAll/set/click`（移动、攻击、使用、单次动作）、
> `ContainerEventHandler#mouseClicked`（屏幕的点击派发）。
> - **不屏蔽** `MouseHandler#onButton`：它同时负责记录 `activeButton`/`isLeftPressed`，而我们的鼠标按钮
>   正是从那里读的（`MouseButtonInfo` 里没有按下/抬起信息，那是单独的 int 形参），屏蔽它等于把自己的
>   点击也砍掉。屏蔽屏幕的点击入口效果相同且不碰自身输入。
> - 短路时布尔入口返回 `true`（"已处理"）：返回 `false` 会被调用方当成没处理，走到"点外面关界面"那类分支。
> - 真机证据：世界里开着我们的界面按 Esc → 界面关闭、**不出现暂停菜单**；日志
>   `input block registered on ...`（5 处）+ 单元测试把补丁后的类**真正定义并调用**（插入的是分支，
>   栈帧写错就是 VerifyError，只断言字节码没有意义）。

> **P5-C 输入修正（2026-10-09，真机 26.3 通过）**：P4-C 记的"可点"不成立——点击一直落空，因为**鼠标状态取错了源**：
> - `SDL_GetMouseState` 只在"有鼠标焦点的窗口"上给坐标，没焦点时静默返回 `0,0`、掩码 `0`（不报错）；
>   同一环境里 `SDL_GetGlobalMouseState` 也恒为 `0,0`（`SDL_GetKeyboardState` 却是有效缓冲，说明 SDL 实例是活的）。
>   游戏自己收到的事件坐标是真实的：`MouseHandler.xpos()=484,279`。
> - `MouseHandler.isLeftPressed()` 只在"没有 screen、也没有 overlay"时更新（26.3 `onButton` 字节码），
>   主菜单/聊天/背包打开时恒为 false——不能当按钮来源。
>
> 处置：新增输入源 **`GameInput`**（只在 SDL 世代接管，GLFW/LWJGL2 那条轮询路保持不动）。
> 位置取 `MouseHandler.xpos/ypos`，按 `界面逻辑尺寸 / Window.getWidth()` 换算；按钮取
> `MouseHandler.activeButton`（按下时无条件记录）→ `MouseButtonInfo.button()`（1=左 2=中 3=右）；
> 键走 `InputConstants.isKeyDown(scancode)`；指针抓取仍走 `MouseHandler.releaseMouse/grabMouse`。
> - 真机证据：`input=game`；按住左键时 `activeButton=MouseButtonInfo button=1`；
>   `overlay first click: mouse=242,140 left=true ... surface=427x240`（484 × 427/854 = 242）；
>   点击 `FullBright` / `Watermark` 状态翻转（面板区像素差 0.5M–1.2M，全屏 27M，含世界亮度变化）。
> - 顺带修掉：`ExtractorRenderer` 帧间把绘制区尺寸清零，输入侧换算因此读到 0；现在保留最近一次有效值。
> - 滚轮：SDL 世代已落地（见下方 P5-D）；L2/GLFW 世代仍待真人验收。

> **P5-D SDL 滚轮落地（2026-10-10，真机 26.3 验证）**：此前 `GameInput` 在 SDL 世代给不出滚轮
> （SDL 没有轮询接口），界面能开能点但滚不动。
>
> - 落点：`ScrollHook`（agent，织入 `SDLEventHandler#handleMouseWheelEvent`——游戏的事件分发用
>   `lookupswitch` 按类型分流，滚轮**不会**走到 `Window.handleEvent`，那是 `default` 分支）
>   → `ScrollState`（bootstrap 层中转：写方在游戏类加载器、读方在叠加层）→ `GameInput.scrollDelta()`
>   每帧取走；同时短路 `MouseHandler#onScroll`，否则界面开着时滚轮会顺带切快捷栏。
> - 真机证据：`callback hook woven: ...SDLEventHandler.handleMouseWheelEvent`；把一个真实 `SDL_Event`
>   结构喂进钩子后 `scroll wheel event seen: y=4.0` → `scroll delta consumed: 4.0` → 面板整体平移
>   （前后截图对比）。
> - 合成鼠标输入在这台机器上进不了 SDL 队列（独立 SDL3 探针实测：`SendInput` 与
>   `PostMessage(WM_MOUSEWHEEL)` 都收不到滚轮），因此"物理滚轮"一项留给真人按一下确认。
> - 顺带：`CallbackHookTransformer` 现在打印"织入成功 / 目标未命中"——此前目标对不上时静默返回，
>   钩子失效无从发现。


> **P4-F 固定管线代崩溃 / 字号 / 中文（2026-10-18，真机 1.12.2 通过）**
>
> **① 注入后 15–60 秒崩溃（1.8.9 + 1.12.2）**：三次崩溃栈完全一致，落在 MC 自己的
> `EntityRenderer.updateCameraAndRender`，解引用的是 `Minecraft.player`（`LocalPlayer` stub 为
> `bew`/`bud`，经本项目映射表确认）。根因是 `ForeignScreenGuard.applyLegacyFocusFlag`：
> 它每帧按 `!guiOpen` 写 `Minecraft.inGameHasFocus`，**主菜单也照写 → 把游戏置成"在游戏中"**，
> 而主菜单里 `player` 是 null。修法：只在「界面打开 **且** 在世界里」时压制，并且先记下游戏
> 原值、关闭时恢复到原值（主菜单原本是 false，绝不能被写成 true）。
> A/B：修前两次运行均在 15–60 s 内崩；修后同一流程持续存活、无新崩溃报告。
> 对照实验另证：注入但不安装叠加层（钩子照旧）时也稳定 → 崩溃由叠加层触发，钩子无关。
>
> **② 字号太小**：`GlRenderer` 自动缩放用 `round(min(w,h)/320)`，854×480 的窗口被算成 2 倍
> （逻辑 427×240），设计缩放掉到 0.41、字约 4px。改为 `floor(min(w,h)/540)`（540 = 设计稿高度）、
> 下限 1 倍：同窗口逻辑 854×480、设计缩放 0.86（字约 8.4px）。手动 `=` / `-` 仍然可调。
>
> **③ 中文化**：新增 `fatality/FaStrings`（英文→中文对照，只作用于**显示层**；模块名/参数名
> 仍是英文键，不动注册表/搜索/持久化契约）。翻译挂在 `FaDraw` 的三个对齐变体（先翻译再量宽，
> 保证右对齐/居中不偏）与 `BackendFaDraw.text`；搜索框输入中文会先映射回英文名再匹配。
> 正文/标签字体本来就是 MiSans（含中文），无需额外字体回退。
>
> **④ 审计带来的附加修复**：`ModernGlApi` 混合因子查询常量 0x0C30–0x0C33 → 0x80C9/0x80C8/0x80CB/0x80CA
> （核心 profile 下旧值是 GL_INVALID_ENUM，还原会用到垃圾因子）；`NocturneRuntime.onFrame` 给
> `driveModules()` 加兜底（帧钩子里的异常会直接逃进 `Display.update` 打崩游戏）；
> `GlRenderer.deleteTexture` 补 `id<=0` 契约；`GuiOverlay.dispatchKeys` 补派发
> Backspace/Enter/Delete/Tab/方向键（此前文本框能输入、能粘贴，但删不掉也提交不了）。

> **P4-E `gl-core` 纹理上传崩溃修复（2026-10-18，真机 1.21.11 通过，含 A/B 因果与算式证明）**：
> 新界面第一次在真实 `gl-core`（1.21.11 + Fabric 0.19.5 + LWJGL 3.3.3 + NVIDIA 610.74）上跑，
> 首个纹理上传就把 JVM 打崩：`EXCEPTION_ACCESS_VIOLATION`，problematic frame `nvoglv64.dll`，
> Java 栈帧落在 `ModernRenderer.createWhiteTexture()` → `ModernGlApi.uploadTexture()` →
> `GL11C.nglTexImage2D`。
> - **实测状态**（在 `uploadTexture` 里插一次性 `glGetIntegerv` 探针读到）：游戏上传完图集**没有还原**
>   `GL_UNPACK_SKIP_ROWS=864`、`GL_UNPACK_ROW_LENGTH=1152`、`GL_UNPACK_SKIP_PIXELS=288`。
> - **算式**：GL 的像素源地址 = `base + ((SKIP_ROWS*ROW_LENGTH)+SKIP_PIXELS)*bpp`
>   = `base + 3,982,464`，而白纹理缓冲只有 4 字节 → 驱动读未映射内存。
> - **证明**（独立 GL 上下文探针，`docs/research/probes/TexUploadProbe.java`）：把红像素放在该偏移处，
>   以相同解包状态上传 1×1 后读回 `0xFF0000FF` → 偏移确实生效；而 `ROW_LENGTH=4096` 单独一项、
>   绑定 PBO 一项都不崩（PBO 项返回 `GL_INVALID_OPERATION`）——必要条件是 `SKIP_ROWS` 非 0。
> - **A/B**：去掉修复重跑 → 崩（复现 1 次）；加回修复 → 不崩（连续 3 次）。
> - **修复**：上传前把 `GL_UNPACK_ROW_LENGTH/SKIP_ROWS/SKIP_PIXELS` 归零，`gl-core` 与 `gl-fixed`
>   两条路径都改（固定管线走同一套驱动，旧界面没有纹理上传所以从未暴露）。
> - 证据：`docs/research/glcore-texture-upload-crash.md`、崩溃日志 `1.21.11-glcore-crash{,-hs_err}.log`、
>   正常运行截图 `fatality-1.21.11-glcore.png`。
> **P4-D 26.2 提取器修复（2026-10-18，真机 26.2 通过）**：此前 26.2 注入时 因为 `OverlayBootstrap.selectBackend()` 只在 `isSdlStack()`（`SDLVideo` 类存在）时选
> `gui-extractor`，但 26.2 运行的是 GLFW（无 SDL）→ 被误判为 `gl-core` → 帧回调里 `glCreateShader` 在无当前 GL 上下文情况下直接 `FATAL ERROR` / JVM abort（实测：进程 24996 直接挂掉，`FATAL ERROR` 写入日志）。修法两处：① 判定条件改为 `resolveLoaded(EXTRACTOR_CLASS)!=null`（只要 `GuiGraphicsExtractor` 在，任何 26.x 都走 extractor）；② `ExtractorRenderer.supportsTextures()` 报**能力**（`textureSupport`），不再依赖 `frame!=null`，并在 `uploadTexture()` 里加 `frame==null` 自保（帧外拒绝执行），这样 26.2 的帧回调（`glfwSwapBuffers` 里）不再触发 GL 调用，绘制只在 `GuiDrawHook` 提交的真实绘制上下文里发生。截图已存 `docs/research/fatality-26.2-fixed.png`。

> **P4-C 正式实现（2026-10-07，真机 26.3 通过）**：叠加层在 SDL 栈上可用了。
>
> - **绘制后端** `ExtractorRenderer`：把 `Renderer` 原语翻译成 `GuiGraphicsExtractor` 调用——
>   `fill` 收左上/右下两个角、`outline` 收左上 + 宽高、`enableScissor`/`disableScissor` 做裁剪、
>   `text(Font, String, x, y, argb)` 画字；圆角没有原语，用逐行内缩近似。坐标就是游戏的 GUI 缩放坐标
>   （`guiWidth()` = `Window.getGuiScaledWidth()`），所以布局与鼠标换算都不需要额外处理缩放。
> - **两个绘制入口都要织**：`Hud.extractRenderState`（游戏内）与
>   `Screen.extractRenderStateWithTooltipAndSubtitles`（主菜单/任意界面，`final`）。只织 HUD 那条的话
>   主菜单根本不调用它，界面永远画不出来（实测：注入后钩子一行日志都没有）；两条都织时，有界面就让
>   HUD 那条让位——界面在 HUD 之后提取、在上层，画在 HUD 层的内容会被盖住。
> - **坑（值得记住）**：注入到游戏方法里的那条调用由游戏的隔离类加载器解析，`GuiDrawHook` 因此存在
>   两份，静态字段互不可见。早先它直接调 `OverlayBootstrap.setDrawContext`，写进的是空副本，现象是
>   "界面已打开但一个像素都不画"（日志里只有 `overlay opened but renderer not ready`）。修法与帧分发
>   同路：把绘制上下文汇放到 **bootstrap 层**的 `FrameDispatcher`，由 `NocturneRuntime` 转发。
> - **真机证据**：注入后 `backend=gui-extractor; input=sdl`，随后
>   `extractor backend first frame: 427x240, font=Font`；三列面板（MOVEMENT / RENDER / PLAYER）与模块名
>   正常显示，点击 `FullBright` 行状态翻转（前后截图对比）。
> - **仍未做**：HUD 常显（旧 UI 库连同 HUD 一起删除，当前没有 HUD）；`ForeignScreenGuard` 依赖的
>   `Minecraft.inGameHasFocus` 在 26.3 不存在（该守卫在 26.x 上不生效，待换等价判据）；滚轮见 P5-D。

> **P4-C spike 结论（2026-10-06，真机 26.3 通过）**：SDL 栈下**游戏自己的绘制 API 可用**——
> `Screen.extractRenderState(GuiGraphicsExtractor,int,int,float)` 每帧调用（主菜单/世界内都调），
> 在其内调 `extractor.fill(10,10,140,50,0x80FF0000)` 成功在屏幕左上角画出红色半透明矩形，
> 游戏不崩、HUD/世界正常渲染（截图见证）。LWJGL GL 绑定依然全进程不可用（三处时机 native abort），
> 所以路线定为**改用游戏自身的绘制 API**（`fill`/`fillGradient`/`drawString` 走 RenderPearl 管线），
> 「经 SDL 自行 make current 后交 Skia」不再考虑。spike 代码已从生产代码移除（只留结论）。

> **1.8.9 真机结论**：官方 1.8.9（Mojang 直链）+ LWJGL2 上端到端可用——注入、帧钩子、叠加层装载、
> 右 Shift 唤出、四个分类面板与模块名显示全部正常（`backend=gl-fixed; input=lwjgl2; toggle key=54;
> mapping=obfuscated 1.8.9 (60 classes)`；`screen=ClickGui`）。Skija 在非 Java 8 运行时不可用
> （`sun.misc.Cleaner`），按设计回落到固定管线后端。
> 固定管线路径上有三处必须对齐游戏的状态管理，均已修好：① 绘制尺寸必须在 `beginFrame()` 之后同步
> （否则按 0 尺寸布局）；② GL 状态位必须用 `glPushAttrib`/`glPopAttrib` 原样归还（否则游戏
> `GlStateManager` 的布尔缓存与实际状态失配，主菜单背景会退化成无纹理纯色）；③ 绘制文字前要补一次
> `glEnable(GL_TEXTURE_2D)`（同样的缓存失配会让字形采不到字体图集，退化成色块）。

> **1.16.5（代际 B）现状（2026-10-06 真机已关闭）**：注入、帧钩子、输入层与叠加层装载全部正常，
> `backend=gl-core; input=glfw`，右 Shift 开 GUI 后 NOCTURNE 面板可见（当时为旧界面）、
> 字号正常（Skija 不分代，gl-core 画不出的问题被绕过）。`ModernGlApi.getInteger` 的余量校验
> （`flip()` 后查 `remaining()`）已修，不再有每帧 `BufferUnderflowException`。
> **注（后续订正）**：其后 Skija 路径因"直写外部帧缓冲会盖黑游戏"（1.8.9 与 1.16.5 真机实锤）
> 被从 `selectBackend` 移除，1.13+ 一律走 `gl-core`。**这一行记录的是 Skija 启用当时的状态，
> 未按当前代码在 1.16.5 上重新实测**。

> **「注入」列指什么**：目前唯一跑通的是 LWJGL2 实验靶（`tmp/lab189/Fake189v5`：真实 Java 8 +
> 真实 LWJGL2 + 真实 OpenGL 4.6，320×240 空白窗口，120 帧 `Display.update()`），**靶内没有 Minecraft**。
> 它证明的是 attach → `agentmain` → 帧钩子 → 叠加层这条链路在 Java 8 栈上成立；**不证明**任何
> Minecraft 相关行为（FontRenderer / `Gui.drawRect` / Options / player / world / 界面可见性）。

---

## 附：Fatality ClickGUI 移植（本轮，代码已落地，真机待验）

把界面从「Skija 直绘 + 移植自 Setsuna 的控件」换成「自绘 Fatality 风格 ClickGUI」，并**删除整个旧 UI 库**。
代码与离屏验证已完成，**真机矩阵未重跑**——下面这张表就是待验清单（每格要求：注入成功 →
`[nocturne] ... backend=<name>` 日志行 + 一张截图）。

| 版本 | loader | 期望后端 | 状态 |
|---|---|---|---|
| 1.8.9 | 原版 / Forge | `gl-fixed` | ⏳ 待真机（旧界面在该格已验过注入链路） |
| 1.12.2 | Forge | `gl-fixed` | ⏳ 待真机 |
| 1.16.5 | Forge | `gl-core` | ⏳ 待真机 |
| 1.20.1 | Forge / Fabric | `gl-core` | ⏳ 待真机 |
| 1.21.1 | Fabric mod | `gui-extractor` | ✅ **已实测**（Mixin 绘制/输入钩子织入；修复 `NativeImage` 原图写入、独立 UV 区域的 11 参 `blit`、2× 栅格密度与 alpha 混合后，文字和柔和阴影正常；滚轮走 `ScrollState`，日志确认 `view.scroll` 从 34 连续变化到 404 且物品栏不滚动。隔离实例诊断构建验证 Esc 关闭自绘 GUI、原生暂停菜单返回游戏及右 Shift 重开；原生 Screen 与自绘 GUI 同时打开时鼠标拦截为 `false`，暂停菜单上重开自绘 GUI 后，Back to Game 已实机点击返回，原生输入未被拦截；返回按压在松开前不再穿透到自绘 GUI，后续新点击正常；左 Shift（GLFW 340）不再误触发右 Shift（GLFW 344）快捷键。颜色选择器二维渐变已复验无横条；Fabric HUD 改为 Gui.render 捕获 + GameRenderer 深度清理后绘制，准星与快捷栏不再覆盖 GUI，颜色弹窗和滚轮均已复验） |
| 1.21.8 | Fabric / NeoForge | `gl-core` | ⏳ 待真机 |
| 1.21.11 | Fabric | `gl-core` | ✅ **已实测**（Fabric 0.19.5 + LWJGL 3.3.3 + NVIDIA 610.74：注入 → `backend=gl-core; input=glfw` → `overlay active; screen=FaClickGui`，新界面三列渲染正常、颜色 chip/滑条/图集文字都在；截图 `docs/research/fatality-1.21.11-glcore.png`。**首轮注入直接 `nvoglv64` ACCESS_VIOLATION 崩 JVM**，根因与修复见下方 P4-E） |
| 26.1 | Fabric / NeoForge | `gui-extractor` | ⏳ 待真机 |
| 26.2 | Fabric / NeoForge | `gui-extractor` | ⏳ 待真机（OpenGL 与 Vulkan 各一次） |
| 26.3 | Fabric / NeoForge | `gui-extractor` | ⏳ 待真机（OpenGL 已移除，只有 Vulkan） |

**已完成的验证**（可复现，证据在仓库内）：

- `ui` 模块 65 项单测全绿（`gradle :ui:test`）：`GlApi`/`ModernGlApi`/`ExtractorRenderer` 绑定、
  纹理上传的格式与字节序（`TextureUploadTest`）、字形图集复用与失效（`FontRasterizerTest`）、
  `Value` → 控件映射与搜索收窄（`FaGroupMappingTest`）、窗口尺寸/列数/瀑布流不越界（`FaLayoutTest`）。
- 离屏光栅截图：`docs/research/fatality-ui-1.png`（默认界面）、`fatality-ui-2-popup.png`（下拉浮层），
  生成器 `docs/research/LabFatalityGui.java`（用真实 `FaClickGui` + 一个 Java2D 版 `Renderer`）。
- 26.x 自定义纹理支路的签名逐条核对（用 JDK 25 的 `javap` 对 26.1.2 / 26.2 / 26.3 的客户端 jar）：
  `GuiGraphicsExtractor.blit(RenderPipeline, Identifier, int x, int y, float u, float v, int w, int h,
  int uWidth, int vHeight, int texW, int texH, int color)`、`DynamicTexture(String,int,int,boolean)`、
  `TextureManager.register/release`、`Identifier.fromNamespaceAndPath`、`NativeImage.getPointer/setPixel`、
  `MemoryUtil.memByteBuffer`、`RenderPipelines.GUI_TEXTURED`。**注意 26.3 把 RenderPipeline 的包名**
  从 `com.mojang.blaze3d.pipeline` 改成了 `com.mojang.renderpearl.api.pipeline`——按签名形状而非类型名绑定，
  才能三个版本共用一份代码。

**未实现且已降级的部分**：

| 项 | 现状 | 缺什么 |
|---|---|---|
| 3D 玩家预览（Render 分类 Player 卡片） | 已接游戏原生库存人物入口：1.8.9–1.14.4 固定管线 6 参、1.15–1.19.3 PoseStack 7 参、1.20–26.3 GuiGraphics 7/10 参；旋转字段在 `finally` 中恢复。仅声明实现覆盖，未逐版本真机验收 | 不需要自建 `EntityRenderDispatcher` 或 FBO；`INVENTORY_SCREEN` 的方法与玩家朝向字段已进映射表。SDL 仍只走游戏绘制队列 |
| 物品图标（预览卡的装备行）、原版槽位选择器、按键绑定行、子设置抽屉、文本/区间/槽位设置行 | 未移植 | 本项目没有对应的 `Value` 类型（无 TextValue / BoundsNumberValue / SlotValue / KeyValue / GroupValue / MultiBoolValue），也没有物品渲染通路 |
| HUD / HUD 编辑器 | 已随旧 UI 库删除 | 用户确认删除；需要时按新绘制层重写 |
