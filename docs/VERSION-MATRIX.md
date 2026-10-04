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
| 1.8.9 | ✅ 链路已验证（真实 Java 8 + LWJGL2 + OpenGL 栈，`tmp/lab189/targetH.log`；靶内**无 Minecraft**） | ✅ | ✅ 已有表 | ⏳ 待验 |
| 1.12.2 | ⏳ | 未验 | ✅ 已产出 | ⏳ |
| 1.16.5 / 1.20.1 / 1.21.4 | ⏳ | 字节码级已验证（GLFW 目标） | ✅ 已产出 | ⏳ |
| 1.21.10 / 1.21.11 | ⏳ | 字节码级已验证（GLFW 目标） | ❌ 未产出 | ⏳ |
| 26.2 | ⏳ | 未验 | ❌ 未产出 | ⏳ |
| 26.3 | ⏳ | 字节码级已验证（SDL 目标） | ❌ 未产出 | ⏳ |

✅ 已完成 · ⏳ 进行中/待验证。此表只写实测结论，不写「应该能行」。

> **「注入」列指什么**：目前唯一跑通的是 LWJGL2 实验靶（`tmp/lab189/Fake189v5`：真实 Java 8 +
> 真实 LWJGL2 + 真实 OpenGL 4.6，320×240 空白窗口，120 帧 `Display.update()`），**靶内没有 Minecraft**。
> 它证明的是 attach → `agentmain` → 帧钩子 → 叠加层这条链路在 Java 8 栈上成立；**不证明**任何
> Minecraft 相关行为（FontRenderer / `Gui.drawRect` / Options / player / world / 界面可见性）。
