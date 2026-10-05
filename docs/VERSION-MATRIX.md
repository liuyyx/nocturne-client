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
| 1.21.10 | 混淆 | 官方 ProGuard | B | `glfwSwapBuffers(J)V` | ❌ 未产出（`client-1.21.10.jar.part`，下载未完成） |
| 1.21.11 | 混淆 | 官方 ProGuard | B | `glfwSwapBuffers(J)V` | ❌ 未产出 |
| 26.2 | 未混淆 | 恒等（+ 逐成员存在性校验） | C | `SDL_GL_SwapWindow(J)Z` | ❌ 未产出 |
| 26.3 | 未混淆 | 恒等（+ 逐成员存在性校验） | C | `SDL_GL_SwapWindow(J)Z` | ❌ 未产出 |

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
| 1.8.9 | ✅ **真机已实测**（官方 1.8.9 + LWJGL2：attach → `agentmain` → 帧钩子 live → 叠加层 attach → 右 Shift 可切换；`tmp/mc189.log`） | ✅ | ✅ 已有表 | ⚠️ 部分：遮罩可见，面板/文字未渲染（见下） |
| 1.12.2 | ⏳ | 未验 | ✅ 已产出 | ⏳ |
| 1.16.5 / 1.20.1 / 1.21.4 | ⏳ | 字节码级已验证（GLFW 目标） | ✅ 已产出 | ⏳ |
| 1.21.10 / 1.21.11 | ⏳ | 字节码级已验证（GLFW 目标） | ❌ 未产出 | ⏳ |
| 26.2 | ⏳ | 未验 | ❌ 未产出 | ⏳ |
| 26.3 | ✅ 已实测（真实 26.3 + Fabric：attach → agentmain → 引导完成，游戏稳定不崩；`tmp/mc263-*.log`） | ⏳ 字节码级已验证（SDL 目标；SDL 栈下按设计不注册） | ❌ 未产出 | ❌ 不可用（见下） |

✅ 已完成 · ⏳ 进行中/待验证。此表只写实测结论，不写「应该能行」。

> **26.x 的界面限制（实测）**：26.1 起渲染后端改为 SDL（`org.lwjgl.sdl.*`），**LWJGL 的 GL 绑定在整个
> 进程里都不可用**——实测三处时机（帧回调的 `SDL_GL_SwapWindow`、GUI 绘制路径 `Hud.extractRenderState`、
> 游戏自己的呈现入口 `GlSurface.present`）调用 GL 都会让 LWJGL `FATAL ERROR` 终止 JVM（native abort，
> Java 侧捕获不到）。因此 agent 在 SDL 栈下**不注册帧钩子、也不安装叠加层**（`OverlayBootstrap` 用
> `Instrumentation` 的已加载类判定 SDL），只保留注入本身（模块框架、映射表、事件总线）。
> 26.2/26.3 的界面需要先接入**不依赖 LWJGL 绑定**的绘制路径（例如经 SDL 自行 make current 后交给
> Skia，或改用游戏自身的绘制 API）。

> **1.8.9 真机观察（待修）**：用官方 1.8.9（Mojang 直链）+ LWJGL2 实测，注入与叠加层装载全部成功
> （`GUI overlay attached; backend=gl-fixed; input=lwjgl2; toggle key=54; mapping=obfuscated 1.8.9 (60 classes)`），
> 按右 Shift 能打开/关闭叠加层（全屏遮罩随开随关），但**面板与文字没有出现**。Skija 在该 JDK 上不可用
> （`sun.misc.Cleaner`，非 Java 8 运行时的已知差异）因此回落到固定管线后端；问题定位在
> `GlRenderer` 的投影/视口或 `ClickGui` 的布局尺寸这一层。

> **「注入」列指什么**：目前唯一跑通的是 LWJGL2 实验靶（`tmp/lab189/Fake189v5`：真实 Java 8 +
> 真实 LWJGL2 + 真实 OpenGL 4.6，320×240 空白窗口，120 帧 `Display.update()`），**靶内没有 Minecraft**。
> 它证明的是 attach → `agentmain` → 帧钩子 → 叠加层这条链路在 Java 8 栈上成立；**不证明**任何
> Minecraft 相关行为（FontRenderer / `Gui.drawRect` / Options / player / world / 界面可见性）。
