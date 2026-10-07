# 版本矩阵

映射表现已覆盖 **1.8.9 – 26.3 的全部 66 个 release**（含 vanilla/Fabric/Forge/NeoForge 四套命名空间）；
下面标注"真机"的行指**实际在机器上跑过**的版本：**1.8.9 / 1.12.2 / 1.16.5 / 1.20.1 / 1.21.4 / 1.21.10 / 1.21.11 / 26.2 / 26.3**。

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
| 1.8.9 | ✅ **真机已实测**（官方 1.8.9 + LWJGL2：attach → `agentmain` → 帧钩子 live → 叠加层 attach → 右 Shift 开/关，四个分类面板与模块名正常显示；`tmp/mc189*.log`） | ✅ | ✅ 已有表 | ✅ 可见（`backend=gl-fixed` 固定管线、`screen=ClickGui`；Skija 包 fb0 直写盖黑游戏已实锤，LWJGL2 不再 probe Skija；P5 输入：右 Shift 唤出 ✅ / Esc 关闭 ✅ / 鼠标点选 ✅ / 滚轮 ⏳（合成事件进不了 LWJGL2 队列，待真人验收；派发链已走读无断点）） |
| 1.16.5 | ✅ **真机已实测**（`vanilla-1.16.5` + LWJGL3：attach → `agentmain` → `glfwSwapBuffers` 帧钩子 live → 叠加层 attach；`tmp/mc1165*.log`） | ✅ | ✅ 已产出 | ✅ 可见（`backend=gl-core`、`screen=ClickGui`，三列面板 + FullBright 行可点选；修过 `glLinkProgram` 缺失 + 视口回退游戏 Window；toggle 用扩展右 Shift；文字待复验） |
| 26.2 | ⏳ | 未验 | ✅ 已产出 | ⏳ |
| 26.3 | ✅ 已实测（真实 26.3 + Fabric：attach → agentmain → 引导完成，游戏稳定不崩；`tmp/mc263-*.log`） | ⏳ 字节码级已验证（SDL 目标；SDL 栈下按设计不注册） | ✅ 已产出 | ❌ 不可用（见下） |

✅ 已完成 · ⏳ 进行中/待验证。此表只写实测结论，不写「应该能行」。

> **P4-C spike 结论（2026-10-06，真机 26.3 通过）**：SDL 栈下**游戏自己的绘制 API 可用**——
> `Screen.extractRenderState(GuiGraphicsExtractor,int,int,float)` 每帧调用（主菜单/世界内都调），
> 在其内调 `extractor.fill(10,10,140,50,0x80FF0000)` 成功在屏幕左上角画出红色半透明矩形，
> 游戏不崩、HUD/世界正常渲染（截图见证）。LWJGL GL 绑定依然全进程不可用（三处时机 native abort），
> 所以路线定为**改用游戏自身的绘制 API**（`fill`/`fillGradient`/`drawString` 走 RenderPearl 管线），
> 「经 SDL 自行 make current 后交 Skia」不再考虑。spike 代码已从生产代码移除（只留结论）。
> 下一步：把叠加层的绘制后端接到 `GuiGraphicsExtractor` 上（P4-C 正式实现），输入栈随后跟进。
> 在此之前 agent 在 SDL 栈下仍**不注册帧钩子、不安装叠加层**（`OverlayBootstrap` 已有判定保持不动）。

> **1.8.9 真机结论**：官方 1.8.9（Mojang 直链）+ LWJGL2 上端到端可用——注入、帧钩子、叠加层装载、
> 右 Shift 唤出、四个分类面板与模块名显示全部正常（`backend=gl-fixed; input=lwjgl2; toggle key=54;
> mapping=obfuscated 1.8.9 (60 classes)`；`screen=ClickGui`）。Skija 在非 Java 8 运行时不可用
> （`sun.misc.Cleaner`），按设计回落到固定管线后端。
> 固定管线路径上有三处必须对齐游戏的状态管理，均已修好：① 绘制尺寸必须在 `beginFrame()` 之后同步
> （否则按 0 尺寸布局）；② GL 状态位必须用 `glPushAttrib`/`glPopAttrib` 原样归还（否则游戏
> `GlStateManager` 的布尔缓存与实际状态失配，主菜单背景会退化成无纹理纯色）；③ 绘制文字前要补一次
> `glEnable(GL_TEXTURE_2D)`（同样的缓存失配会让字形采不到字体图集，退化成色块）。

> **1.16.5（代际 B）现状（2026-10-06 真机已关闭）**：注入、帧钩子、输入层与叠加层装载全部正常，
> `backend=skija; input=glfw`、`screen=SetsunaClickGui`，右 Shift 开 GUI 后 NOCTURNE 面板可见、
> 字号正常（Skija 不分代，gl-core 画不出的问题被绕过）。`ModernGlApi.getInteger` 的余量校验
> （`flip()` 后查 `remaining()`）已修，不再有每帧 `BufferUnderflowException`。

> **「注入」列指什么**：目前唯一跑通的是 LWJGL2 实验靶（`tmp/lab189/Fake189v5`：真实 Java 8 +
> 真实 LWJGL2 + 真实 OpenGL 4.6，320×240 空白窗口，120 帧 `Display.update()`），**靶内没有 Minecraft**。
> 它证明的是 attach → `agentmain` → 帧钩子 → 叠加层这条链路在 Java 8 栈上成立；**不证明**任何
> Minecraft 相关行为（FontRenderer / `Gui.drawRect` / Options / player / world / 界面可见性）。
