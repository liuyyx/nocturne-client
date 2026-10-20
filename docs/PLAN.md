# 开发计划

原则：**每个阶段都以可运行的验证收尾**，不写「应该能行」。文档里的状态只有两种：实测通过、未验证。

## 已定架构（不讨论，实现照此）

1. 单 jar，**只有注入**一条路径（无模组形态；P8 的模组入口是后续可选形态，不改动这里的注入主线）。
2. 客户端代码按 **Mojmap 规范名**写，**不写版本分支**。
3. 版本差异 → **每版本一份映射表 JSON**（自动生成 + 人工补丁，打进 jar 资源）。
4. 目标版本 → 注入器判定并传 `mcVersion=`，运行时零探测。
5. **绘制 = 三层，不是「每代一个后端」**（本节 2026-10-20 修订）：
   - **上层**：UI 组件树，只调 `Renderer` 的 5 个原语（`rect / roundedRect / outline / text / texturedQuad` + clip）。**永远一份代码**。
   - **中层 `DrawPlan`**：所有「高级原语」在这里编译成底层命令，并做重写优化。后端不认识圆角、渐变、阴影。
   - **下层 `DrawSink`**：只有 3 个原语 —— `fill(x1,y1,x2,y2,argb)` / `blit(...)` / `scissor(...)`。
   - **翻译层**：启动时**按方法签名装配一次**，把 `DrawSink` 的 3 个原语绑到当前版本的真实方法上；
     调用点没有任何版本判断（不是 `if (version == 1.20)`，而是「候选签名列表，命中即绑定」）。
6. 帧信号 → LWJGL 交换函数（3 个签名，与 MC 版本无关）；1.20+ 另可挂 `Screen.render(Hud.render)` 拿 `GuiGraphics`。

### 为什么改成三层（历史教训，别改回去）

代际 B（1.13–1.21）此前用 `ModernRenderer` 自写 GLSL + 裸 GL 调用，在 `glfwSwapBuffers` 帧钩子里绘制。
1.21.11 真机症状：**文字整块不透明黑**。已排除的原因（全部实测）：

| 检查 | 结果 |
|---|---|
| 图集 GPU 内容（1024² 全尺寸回读） | `glError=0`、`texel0=255,255,255,13`，数据正确 |
| 绘制时 `program` | `cur=7 == prog=7`，未被游戏切走 |
| 混合开关 / 因子 | `GL_BLEND=true`、`SRC_ALPHA/ONE_MINUS_SRC_ALPHA`、`FUNC_ADD` |
| 活动单元 / 绑定 | `unit=GL_TEXTURE0(33984)`、`bound=996`（= 传入句柄） |
| 顶点属性 / UV | `aPos=0`、`aUV=1`，顶点 `v0=(352.0,217.9,0.040039062,0.0)` |
| 调用参数 | `id=996 argb=0x35ece9e1`（浅色，alpha 0.208） |

在 `uColor.a≈0.208` 且标准混合下，纯黑无法由这些输入产生 ⇒ 结论是**通道错了**，不是参数没调对：
裸 GL 与游戏的 `GlStateManager`/`RenderSystem` 缓存脱节，在 swap 时机插入自建管线属于逆着游戏状态干。
1.20+ 有 `GuiGraphics`，用它的 `fill/blit/drawString` 由游戏管状态，这个问题从根上不存在。

### 两个已踩的 GL 坑（别再踩）

- **`glActiveTexture` 在 `GL13`**：`ModernGlApi.find` 的候选类表曾缺 `GL13`，句柄为 null 时调用被静默跳过，
  纹理绑到游戏的活动单元上（已修，提交 `9d5c655`）。以后任何 `find` 新增函数，先确认它属于哪个 GL 版本类。
- **不要随便加 `GL33C` + `glBindSampler`**：实测它会**破坏片元着色器初始化**
  （`uProjection=0 uColor=-1 uSampler=-1 uTexture=-1`，程序里没有片元阶段），去掉调用即恢复。
  该实验已完全回退，不要在未复现验证前重开。

## 状态快照（2026-10-20，以实测与入库代码为准）

| 阶段 | 内容 | 状态 |
|---|---|---|
| P0 清理 | 删模组路径 / 启动动画 / 版本适配分支；修指针捕获语义 | ✅ 已完成 |
| P1 版本传递 | `AgentOptions`（组装 + 版本族归一化）；注入器 GUI 与命令行两条路径共用判定；`mcVersion=` 下发 | ✅ 已完成（fpsmaster 真机验证） |
| P2 注入点 | 帧交换钩子 + 绘制上下文钩子 | ✅ 已完成 |
| P3 映射表生成器 | 66 个 release（1.8.9–26.3）四套命名空间；`--check` / `--javap` 全绿 | ✅ 已完成 |
| P4 绘制 | A（≤1.12 固定管线）✅ 真机；B（1.13–1.21）⏳ **未收尾**（1.21.11 文字黑块）；C（26.x extractor）✅ 真机 | B 未关闭 |
| P4-D 绘制层重构 | DrawPlan + DrawSink + 签名装配翻译层（本计划新增，解决 B） | ⏳ 未开始 |
| P5 输入 | 1.8.9 / 26.3 真机；SDL 滚轮已落地；GLFW/LWJGL2 滚轮待真人 | 部分 |
| P6 模块 | 13 个自研模块；世界覆盖层已铺；Panic 已落地；OpenVape 批量移植未开始 | 部分 |
| P7 自实现 attach | Windows 真机；Linux/macOS 已实现未实机；`PayloadPack` 已接生产 | ✅ 已完成（平台待验） |
| P8 模组形态 | Fabric/Forge/NeoForge 入口 | 未开始（依赖 P4-D） |

## 工作纪律（每次开工前读一遍）

- **每做完一个大项 → 立刻 `git commit` + `git push`**。真机验证的结论只认已 push 的提交，本地日志不算数。
- 验收没绿 + 没 push = 该项没做完。
- 探针一律带开关（`-Dnocturne.ui.debug`），结束必须删除；提交前 `git diff` 里不允许残留诊断代码。

---

## P4-D 绘制层重构（当前主线，替代 P4-B 的继续调参）

目标：把「每代一个自写后端」改成「一份 draw plan + 每代一个薄 sink」，让 1.13–1.21 不再绕开游戏的绘制 API。
1.21.11 的文字黑块作为 D2 的验收项，不单独修。

### D1 DrawPlan + DrawSink（纯 Java，先做）

- `DrawSink` 接口只有三个方法：`fill / blit / scissor`（含嵌套裁剪的栈语义）。
- `DrawPlan` 接收 `Renderer` 的 5 个原语，编译成命令序列：
  - `rect` → 一条 `fill`
  - `roundedRect` → 中央矩形 + 四角分段（段数可配，默认 12；`r<=0.5` 退化为 `rect`）
  - `outline` → 四条 `fill`
  - `texturedQuad` → 一条 `blit`
  - `pushClip/popClip` → `scissor`
- 重写规则（同一个 plan 内，按顺序执行）：
  - 同色相邻矩形合并；
  - 完全落在裁剪区外的命令剔除；
  - 按 scissor 分组，减少状态切换；
  - 同一纹理的 `blit` 连续提交。
- 验收：`ui` 模块单测 —— 给定 `roundedRect` 输出确定数量的 `fill`；合并/剔除规则有断言；无游戏依赖。

### D2 1.20–1.21.x：`GuiGraphics` sink

- 在**现有 `ExtractorRenderer`** 上加签名分支，不新写类：
  - `fill(int,int,int,int,int)`
  - `drawString(Font, String, int, int, int)`（1.21.11 上为 `gir.a(gio,StringIII)V`，已 javap 核实）
  - `blit(...)`：1.21.1 是 `ResourceLocation` 系，1.21.11 已换 `RenderPipeline + GpuTextureView`，**按签名分派**
  - `enableScissor/disableScissor`
- 入口：注册 `Screen.render(GuiGraphics,int,int,float)`（1.21.11 上为 `gsb.a(gir,int,int,float)`，已核实）
  与 `Hud.render(...)`；现在注册的 `extractRenderState` 是 26.x 名字，1.20–1.21 上挂不上才会退回帧钩子。
- `selectBackend`：能拿到 `GuiGraphics` 的版本走本 sink；`ModernRenderer` 不再用于 1.20+。
- 验收（真机，至少 1.21.11 与 1.20.1）：界面可见、**文字正常显示**、可点；`backend=gui-graphics`；
  无 GL 异常日志；commit + push。

### D3 1.13–1.19：`RenderSystem` sink

- 新类（一个，不是每版一个）：按签名装配 `fill` / `drawString` / `blit` / `enableScissor`；
  缺成员时按 P3-R 的 absent 规则降级并打一次性日志。
- 这一段没有 `GuiGraphics`，矩形走 `GuiGraphics.fill`（1.16+ 存在）或 `Gui.drawRect`（1.13–1.15），
  文字走 `Font.drawString`；具体以映射表成员是否存在为准。
- `ModernRenderer` 只在本段保留（自写 GLSL 作为最后的兜底），1.20+ 与 26.x 都不再使用。
- 验收：1.16.5 真机界面可见、文字正常、可点；commit + push。

### D4 收尾

- 删除 `ModernRenderer` 在 1.20+ 路径上的引用；`VERSION-MATRIX.md` 的绘制能力矩阵按三层重写。
- 全仓 `gradle test` 绿；`generate.py --check` 绿；commit + push。

---

## P4-B 关闭条件（并入 D2）

1.16.5 / 1.20.1 / 1.21.4 / 1.21.10 / 1.21.11 逐台注入后：界面可见、文字显示、点击生效、
`backend` 日志与实际通道一致。任一台不满足，P4-B 保持打开。

## P5 输入收尾

- SDL 滚轮已落地（1.26.3 真机链路通过，物理滚轮待真人）。
- 1.8.9 `getDWheel` / GLFW `setScrollCallback` 待真人验收。
- 输入与绘制解耦：换 sink 不影响 `GameInput`/`ReflectiveInput`；1.20+ 的 `Screen.render` 钩子里
  鼠标坐标直接取 `GuiGraphics` 同一帧的 `MouseHandler` 状态。

## P6 模块移植（OpenVape，范围不变）

### P6-A 动作翻译层（与 P4-D 绘制翻译层同构）

模块也要做到「一份算法 + 一份动作翻译」。分三层：

- **算法层**（模块自身）：目标选择、角度插值、冷却节奏、阈值 —— 版本无关，只有这一份。
- **`ActionApi`**（语义层）：`attack(Entity)` / `place(BlockPos, Direction)` / `useItem()` /
  `setRotation(yaw,pitch)` / `sendPacket(...)` / 读状态。模块只调这些，**不直接反射游戏类**。
- **`ActionBinder`**（装配层）：启动时按候选签名绑定到当前版本的真实方法，与 `DrawSink` 同构。

**契约表是唯一事实源**：`contract/actions.yaml`（打进 jar），binder 读取它装配，文档由它生成。
每条动作只写四段：

1. 语义签名（Mojmap 名 + 参数类型，例 `void attack(Entity target)`）；
2. 候选绑定（每版本的 owner / name / params，按顺序尝试）；
3. 能力与降级（例 `attackCooldown`：存在→按冷却门控，缺失→恒放行）；
4. 禁令（模块不得写版本判断、不得直接反射游戏类）。

表里**不写**：算法、混淆名（属于 `mappings-*.json`）、版本故事。

首批六个动作：`attack / place / useItem / setRotation / sendPacket / readState`，覆盖 KillAura、
Scaffold、Clicker。每移一个模块先查表；缺动作 = 契约缺口，**先补表再写模块**。

**明确「不翻译、只降级」的语义差异**：

| 差异 | 处理 |
|---|---|
| 1.9+ 攻击冷却（1.8.9 没有） | `degrade.attackCooldown` |
| 1.8 `PlayerControllerMP` vs 1.16+ `MultiPlayerGameMode` | 候选列表 |
| 1.20.5+ `ItemStack` 数据组件（`getTag` 消失） | 能力查询 + 降级 |
| 1.17+ `setYRot/setXRot` vs 字段 | 映射表 |
| 26.x 交互方法签名不同 | 按签名候选 |

- 验收（每批）：模块代码里 `grep -n "reflect\|version"` 为零；错版本上按 degrade 正确降级并打一次性日志；
- 验收（层）：binder 单测覆盖「候选命中 / 全部未命中 → 禁用该动作并报一次」；commit + push。

红线与分批表见历史版本（B1 渲染显示 → B7 版本限定）。与 P4-D 的关系：

- 世界渲染（ESP/Chams/Tracers）不走 `DrawSink`，仍走 `WorldProjection` + 世界钩子，不受本次重构影响。
- HUD 模块走 `HudManager` → `Renderer`，自动获得三层结构；圆角/渐变等高级效果由 D1 提供。

## P7 自实现 attach（已完成，保留结论）

- Windows 原生通道真机通过；Linux/macOS 域套接字已实现未实机；`PayloadPack` 已接生产。
- 与 P4-D 无依赖。

## P8 模组形态（Fabric / NeoForge / Forge）

- 依赖 P4-D：绘制统一后，模组入口只是「三个入口类 + 三份元数据 + 复用 sink」。
- 入口全反射，不引加载器构件；`AtomicBoolean` 防 agent + mod 双驱动。
- 顺序：Fabric（1.21.x）→ NeoForge → Forge（1.8.9 最碎）。
- 可选：jar 同时带 `Premain-Class`（与 `agentmain` 共用核心），便于 `-javaagent` 启动。

## 待办里容易忘的两条

- **滚轮**：SDL 世代已落地；1.8.9/1.16.5 待真人。失效时先看 `scroll wheel event seen` / `scroll delta consumed` 两条日志。
- **26.1 与 26.2/26.3 的 HUD 入口不兼容**（形参 vs 字段），属代际 C 内部的两个子形态，织入代码不能共用。

## Vape 照抄与移植评估（2026-10-07，结论不变）

- `vendor/vape/` 已照抄 OpenVape 4.21.36（CC0，2985 文件 / 287,137 行），隔离探针编译通过。
- 对方模块依赖自研 `gg.vape.wrapper`（401 类 / 1813 方法），不能直接接入；按 P6 的「只搬行为」重写。
- 评估细节见 `docs/research/vape-port-assessment.md`。
