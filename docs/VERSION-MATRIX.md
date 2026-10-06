# 版本矩阵

目标版本：**1.8.9 / 1.12.2 / 1.16.5 / 1.20.1 / 1.21.4 / 1.21.10 / 1.21.11 / 26.2 / 26.3**。

## 1. 每版本的差异点（全部是「数据」，不是代码分支）

| 版本 | 混淆 | 映射表来源 | 绘制代际 | 帧钩子目标 | 表状态 |
|---|---|---|---|---|---|
| 1.8.9 | 混淆 | 本机 SRG + MCP CSV + 人工别名（四步） | A | `Display.update()V` | ✅ 已产出（48 类 / 47 具名 / 88 方法 / 68 字段） |
| 1.12.2 | 混淆 | 同上（`vanilla1122` / `forge1122`） | A | `Display.update()V` | ✅ 已产出（48 / 47 / 88 / 68） |
| 1.16.5 | 混淆 | 官方 ProGuard `client.txt`（一步） | B | `glfwSwapBuffers(J)V` | ✅ 已产出（48 / 45 / 85 / 68） |
| 1.20.1 | 混淆 | 官方 ProGuard | B | `glfwSwapBuffers(J)V` | ✅ 已产出（48 / 47 / 85 / 68） |
| 1.21.4 | 混淆 | 官方 ProGuard | B | `glfwSwapBuffers(J)V` | ✅ 已产出（48 / 47 / 85 / 68） |
| 1.21.10 | 混淆 | 官方 ProGuard | B | `glfwSwapBuffers(J)V` | ✅ 已产出 |
| 1.21.11 | 混淆 | 官方 ProGuard | B | `glfwSwapBuffers(J)V` | ✅ 已产出（已抽查确认为真实内容） |
| 26.2 | 未混淆 | 恒等（+ 逐成员存在性校验） | C | `SDL_GL_SwapWindow(J)Z` | ✅ 已产出 |
| 26.3 | 未混淆 | 恒等（+ 逐成员存在性校验） | C | `SDL_GL_SwapWindow(J)Z` | ✅ 已产出（已抽查确认为真实内容） |

> 表的规模固定为 48 个类（客户端实际解析的规范面），因此各版本行数一致；差异在 `name` 为 `null`
> 的条目数（表示该版本运行时**没有**对应类，标注为 absent）。

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
| 1.8.9 | ✅ **真机已实测**（官方 1.8.9 + LWJGL2：attach → `agentmain` → 帧钩子 live → 叠加层 attach → 右 Shift 开/关，四个分类面板与模块名正常显示；`tmp/mc189*.log`） | ✅ | ✅ 已有表 | ✅ 可见（`backend=gl-fixed`、`screen=ClickGui`） |
| 1.12.2 | ✅ **真机已实测可用**（官方 1.12.2 + LWJGL2，与 1.8.9 同代际：`backend=gl-fixed`、`screen=ClickGui`、四个分类面板与模块名正常显示；`tmp/mc1122.log`） | ✅ | ✅ 已产出 | ✅ 可见 |
| 1.16.5 | ✅ 注入已实测（官方 1.16.5：`backend=skija; input=glfw`、帧钩子 live、`overlay active; screen=SetsunaClickGui`） | 字节码级已验证（GLFW 目标） | ✅ 已产出 | ✅ 可见（Skija 直绘，不走 gl-core；854x480，UI_SCALE=1.5 字号正常） |
| 1.21.10 / 1.21.11 | ⏳ | 字节码级已验证（GLFW 目标） | ✅ 已产出 | ⏳ |
| 26.2 | ⏳ | 未验 | ✅ 已产出 | ⏳ |
| 26.3 | ✅ 已实测（真实 26.3 + Fabric：attach → agentmain → 引导完成，游戏稳定不崩；`tmp/mc263-*.log`） | ⏳ 字节码级已验证（SDL 目标；SDL 栈下按设计不注册） | ✅ 已产出 | ❌ 不可用（见下） |

✅ 已完成 · ⏳ 进行中/待验证。此表只写实测结论，不写「应该能行」。

> **26.x 的界面限制（实测）**：26.1 起渲染后端改为 SDL（`org.lwjgl.sdl.*`），**LWJGL 的 GL 绑定在整个
> 进程里都不可用**——实测三处时机（帧回调的 `SDL_GL_SwapWindow`、GUI 绘制路径 `Hud.extractRenderState`、
> 游戏自己的呈现入口 `GlSurface.present`）调用 GL 都会让 LWJGL `FATAL ERROR` 终止 JVM（native abort，
> Java 侧捕获不到）。因此 agent 在 SDL 栈下**不注册帧钩子、也不安装叠加层**（`OverlayBootstrap` 用
> `Instrumentation` 的已加载类判定 SDL），只保留注入本身（模块框架、映射表、事件总线）。
> 26.2/26.3 的界面需要先接入**不依赖 LWJGL 绑定**的绘制路径（例如经 SDL 自行 make current 后交给
> Skia，或改用游戏自身的绘制 API）。

> **1.8.9 真机结论**：官方 1.8.9（Mojang 直链）+ LWJGL2 上端到端可用——注入、帧钩子、叠加层装载、
> 右 Shift 唤出、四个分类面板与模块名显示全部正常（`backend=gl-fixed; input=lwjgl2; toggle key=54;
> mapping=obfuscated 1.8.9 (60 classes)`；`screen=ClickGui`）。Skija 在非 Java 8 运行时不可用
> （`sun.misc.Cleaner`），按设计回落到固定管线后端。
> 固定管线路径上有三处必须对齐游戏的状态管理，均已修好：① 绘制尺寸必须在 `beginFrame()` 之后同步
> （否则按 0 尺寸布局）；② GL 状态位必须用 `glPushAttrib`/`glPopAttrib` 原样归还（否则游戏
> `GlStateManager` 的布尔缓存与实际状态失配，主菜单背景会退化成无纹理纯色）；③ 绘制文字前要补一次
> `glEnable(GL_TEXTURE_2D)`（同样的缓存失配会让字形采不到字体图集，退化成色块）。

> **1.16.5（代际 B）现状**：注入、帧钩子、输入层与叠加层装载全部正常（`backend=gl-core; input=glfw`、
> `screen=ClickGui`）；顺带修掉了每帧刷屏的 `BufferUnderflowException`——`ModernGlApi.getInteger` 在
> `flip()` 后没校验 `remaining()`，GL 未写入时 `get()` 直接抛，把整条帧回调打挂。但核心 profile
> 后端（`ModernRenderer`）尚未把界面画到屏幕上，需继续排查投影/视口与绘制时机。

> **「注入」列指什么**：目前唯一跑通的是 LWJGL2 实验靶（`tmp/lab189/Fake189v5`：真实 Java 8 +
> 真实 LWJGL2 + 真实 OpenGL 4.6，320×240 空白窗口，120 帧 `Display.update()`），**靶内没有 Minecraft**。
> 它证明的是 attach → `agentmain` → 帧钩子 → 叠加层这条链路在 Java 8 栈上成立；**不证明**任何
> Minecraft 相关行为（FontRenderer / `Gui.drawRect` / Options / player / world / 界面可见性）。
