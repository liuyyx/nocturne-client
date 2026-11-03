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

## 状态快照（2026-11-01，以实测与入库代码为准）

| 阶段 | 内容 | 状态 |
|---|---|---|
| P0 清理 | 删模组路径 / 启动动画 / 版本适配分支；修指针捕获语义 | ✅ 已完成 |
| P1 版本传递 | `AgentOptions`（组装 + 版本族归一化）；注入器 GUI 与命令行两条路径共用判定；`mcVersion=` 下发 | ✅ 已完成（fpsmaster 真机验证） |
| P2 注入点 | 帧交换钩子 + 绘制上下文钩子 | ✅ 已完成 |
| P3 映射表生成器 | 66 个 release（1.8.9–26.3）四套命名空间；`--check` / `--javap` 全绿 | ✅ 已完成 |
| P4 绘制 | A（≤1.12 固定管线）✅ 真机；B（1.13–1.21）⏳ **仍打开**（1.21.11 文字黑块/旧后端未收尾）；C（26.x extractor）✅ 真机，26.3 运行中 attach 也已复验 | B 未关闭 |
| P4-D 绘制层重构 | D1 完成；D2 已重新接线并在 26.3 真机可见；1.20.1 HUD 钩子/末尾注入已修，但合成键盘无法验收 GUI；D3 已落地，1.16.5 真机验收后**改为默认 gl-core**（`gui-stack` 观感粗糙，降为兜底；双端钩子 + PoseStack 文本缩放 + RenderSystem 裁剪回退仍保留）；D4 未完成 | ⏳ 进行中（= B 的关闭路径） |
| P5 输入 | 1.8.9 / 26.3 真机；GUI 打开时 1.8.9 压 `inGameHasFocus`、26.3 屏蔽 `MouseHandler#grabMouse`；SDL 滚轮已落地；1.8.9/1.16.5 滚轮与真实点击仍待真人 | 部分 |
| P6 模块 | 21 个注册模块（Chams 与 Xray 因渲染拦截点未接入而暂不注册，避免"开了没反应"）；ActionApi/ActionBinder + RotationController/TargetManager；B1–B9 已验收；KillAura/Scaffold/AimAssist 已移植 | 部分 |
| P7 自实现 attach | Windows 真机；Linux/macOS 已实现未实机；`PayloadPack` 已接生产 | ✅ 已完成（平台待验） |
| P8 模组形态 | Fabric/Forge/NeoForge 入口 | 未开始（依赖 P4-D） |

## 2026-11-01 本轮结论

已验证并入库（`b53265b`、`9806ab7`）：

- **1.8.9 GUI 视角**：打开界面时压 `Minecraft.inGameHasFocus=false`，关闭恢复；真机 `true → false`，
  移动鼠标后 yaw/pitch 不变。
- **1.8.9 模块驱动**：1.8.9 帧钩子直接调 bootstrap `FrameDispatcher.dispatch()`，原先模块 tick 根本没跑
  （`ticks=0`）。新增 `ModuleTicker` 作为普通监听器挂同一分发器；真机 `KillAura targets=1 attacks=9`。
- **1.8.9 状态映射**：补 `Entity#onGround` 的 `onGround` 别名，`PlayerState` 不再返回空快照。
- **26.3 运行中 attach**：先 `retransformClasses` 捕获带 Fabric Mixin 接口的当前字节，再注入
  `Hud/Screen`；真机可见完整 GUI。`MouseHandler#grabMouse` 在 GUI 打开时短路，点击后光标不再回中心。
- **中文化**：补分类/模块/设置词条；修复“先截断再翻译”导致长标签仍显示英文的问题。
- **Insert**：GUI 同时接受配置键与 INSERT，规避右 Shift 被第三方客户端占用。

已验证与仍开放（按优先级）：

1. **P0 1.8.9 世界覆盖层相机**：已修。零参 `getEyePosition` 会误命中同名无参方法并返回 World
   对象；按 `Vec3` 返回类型剔除后 `CameraState.update=true`。
2. **P0 26.3 运行中 attach 的游戏事件**：已修。`LocalPlayer/Minecraft/Connection/MultiPlayerGameMode/
   KeyboardHandler/MouseHandler` 全部用当前字节注入；运行中 attach 真机 NoFall
   `motion.events=136 forcedGround=54`。产品路径确定为**运行中 attach**，不强制启动期 agent。
3. **P4-D 绘制层重构**：D1、D3 已落地；D3 已在 1.16.5 真机验收（界面可见、文字正常、可点），下一步 D4；P4-B 才算关闭。
4. **1.8.9 服务端命中**：已复验。服务端召唤僵尸，KillAura 范围 6、Mobs=true，血量
   `17.16 → 10.72`、`attacks=10`，攻击包到达服务端。
5. P5 滚轮真人验收（1.8.9/LWJGL2、1.16.5/GLFW）仍未做，需要物理滚轮。

下一阶段顺序：D3 真机复验 → D4 → P5 滚轮真人验收。

## 工作纪律（每次开工前读一遍）

- **每做完一个大项 → 立刻 `git commit` + `git push`**。真机验证的结论只认已 push 的提交，本地日志不算数。
- 验收没绿 + 没 push = 该项没做完。
- 探针一律带开关（`-Dnocturne.ui.debug`），结束必须删除；提交前 `git diff` 里不允许残留诊断代码。

---

## P4-D 绘制层重构（当前主线，替代 P4-B 的继续调参）

目标：把「每代一个自写后端」改成「一份 draw plan + 每代一个薄 sink」，让 1.13–1.21 不再绕开游戏的绘制 API。
1.21.11 的文字黑块作为 D2 的验收项，不单独修。

### D1 DrawPlan + DrawSink（纯 Java，先做）✅ 已完成

> `ui/render/DrawPlan.java`、`DrawSink.java`、`TextSink.java` 已落地，`DrawPlanTest` 覆盖圆角降级、
> 相邻同色矩形合并、裁剪外剔除和嵌套 scissor。当前缺口是**尚未接入各绘制后端**，不是接口/算法。

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

### D2 1.20–1.21.x：`GuiGraphics` sink（进行中）

> 2026-11-01：`PlannedBackend` 已重新接线；补上文本/渐变前 flush，避免缓冲几何盖住文字。
> 26.3 真机 GUI 正常（`backendClass=PlannedBackend`，文字/勾选/Bind 行均可见）。
> 1.20.1 修复了 HUD 入口：`ClassType.HUD` 映射到 vanilla `eow`，描述符修正为
> `eow.render(Leox;F)V`；`attackPost` 的末尾 `chop_frame` 注入改为安全跳过，世界不再 VerifyError。
> 但当前自动化只能向窗口发合成键盘，1.20.1 打不开自绘 GUI，无法完成可见性/点击验收；
> 需真人按键或可注入键盘的测试环境。1.21.4/1.21.10 未测。

- 在**现有 `ExtractorRenderer`** 上加签名分支，不新写类：
  - `fill(int,int,int,int,int)`
  - `drawString(Font, String, int, int, int)`（1.21.11 上为 `gir.a(gio,StringIII)V`，已 javap 核实）
  - `blit(...)`：1.21.1 是 `ResourceLocation` 系，1.21.11 已换 `RenderPipeline + GpuTextureView`，**按签名分派**
  - `enableScissor/disableScissor`
- 入口：注册 `Screen.render(GuiGraphics,int,int,float)`（1.21.11 上为 `gsb.a(gir,int,int,float)`，已核实）
  与 `Hud.render(...)`；现在注册的 `extractRenderState` 是 26.x 名字，1.20–1.21 上挂不上才会退回帧钩子。
- `selectBackend`：能拿到 `GuiGraphics` 的版本走本 sink；`ModernRenderer` 不再用于 1.20+（例外见 `OverlayBootstrap`：1.21.1 attach 兼容豁免与 `-Dnocturne.ui.backend=gl` 仍强制 gl-core）。
- 验收（真机，至少 1.21.11 与 1.20.1）：界面可见、**文字正常显示**、可点；日志出现 `gui-graphics stack`（现状 `backendName()` 仍报 `gui-extractor`）；
  无 GL 异常日志；commit + push。

### D3 1.16–1.19：`gui-stack` sink（静态 `fill`/`drawString` + 游戏 `PoseStack`）✅ 真机已验收（2026-11-03），**现降为 gl-core 的兜底**

> 2026-11-03 观感结论：`GuiComponent.fill` 是整数像素矩形（圆角逐行 1px 阶梯 + `Math.round` 取整），
> 文字是位图字体按 `size/9` 矩阵缩放（NEAREST 采出方块字）。真机对比 gl-core 后明显粗糙，
> 因此 `OverlayBootstrap` 对 1.16–1.19 改为**先试 gl-core**，只有 `GlApi.bind` 失败才装配 `gui-stack`
> （`-Dnocturne.ui.backend=game` 可强制对照）。本节的装配与修复全部保留。

落地位置（不新增后端类，复用 `ExtractorRenderer`）：

- `ui/gl/ExtractorRenderer.java`：
  - 新增 `poseArg` 形状分支——`callFill`/`callText` 按「静态方法 + 首参绘制栈」或「实例方法 + 隐式 `this`」
    两种形状调用同一个方法句柄；`backendName()` 在 `poseArg` 时返回 `gui-stack`。
  - 新增 `bindLegacy(guiType, mapping, minecraft, fontSource)`：装配 `fill` / `drawString` /
    `enableScissor` / `disableScissor`；辅助 `windowHandles`（`Window.getGuiScaledWidth/Height`）、
    `isFontType`（映射表 `FONT` 候选名 + `width(String)` 特征兜底）、
    `mappedNames`（`ClassType.GUI_COMPONENT` 的成员候选名）。
- `agent/OverlayBootstrap.java`：新增 `resolveLegacyGui()` / `hasStaticFill()` 与 `guiStack` 分支（1.16–1.19）。
  判定是「映射表 `ClassType.HUD` 类名候选中，存在静态 `void fill(PoseStack,int×5,int)`」，命中才选
  `gui-stack`；否则 `guiStack=false` 回落 gl-core。
- `NocturneAgent`：`installGuiGraphicsHook` 的首参判定放宽为「非基本类型」（不再只认 `GuiGraphics` 类名）；
  `installHudRenderHook` 接受 `render(PoseStack,float)`（`Gui.render` 的 1.16–1.19 形状），
  与 `Gui.render(GuiGraphics,*)` 共用一条 HUD 入口。
- `tools/mapping/requirements.txt` 新增 `net/minecraft/client/gui/GuiComponent` 及
  `fill()` / `drawString()` / `enableScissor()` / `disableScissor()`；66 份 `mappings-*.json` 已重新生成，
  `generate.py --check` 通过。

**绑定规则（1.16–1.19 必须按映射名查表，不能只靠签名）**：`fill` 与 `drawString` 的签名会碰撞 ——
1.16.5 实测 `fill=a`、`drawString=b`、`drawCenteredString=a`，后两者参数表**完全相同**。
因此优先按映射名 `ClassType.GUI_COMPONENT` 绑定，名字缺失时才退回形状匹配。
`enableScissor`/`disableScissor` 在 1.16–1.18 的 `GuiComponent` 上不存在（1.19.1 起才有）；这两代回退到 `com.mojang.blaze3d.systems.RenderSystem.enableScissor(int,int,int,int)`（静态、收 `(x,y,w,h)`），绑定日志记为 `scissor=rendersystem`。

**能力边界（1.13–1.15 不在此段，不是待办 bug）**：1.13–1.15 没有 `PoseStack`，`AbstractGui`/`GuiComponent`
的 `fill` 是实例方法而非 `static` → `resolveLegacyGui()` 返回 null、`guiStack=false`，
回落 gl-core 的 `ModernRenderer`（1.13/1.14/1.15 的 LWJGL 依赖里有 GLFW 类，`isModernRenderStack` 为真）。
`ModernRenderer` 因此只为 1.13–1.15 保留（自写 GLSL 兜底），1.20+ 与 26.x 都不再使用它。

真机状态（2026-11-03，1.16.5 + LWJGL3，`gui-stack`）：面板可见、三列布局正常、中文文字正常（字号按 `size/9` 缩放）、开关点击生效。
日志证据：`gui-stack bind: dkv fill=a text=b scissor=rendersystem poseScale=true`、`extractor backend first frame`、`text width probe: ... scaled=true`。
两条关键修复：① 1.16–1.19 绘制入口改「开头捕获 + 末尾无参消费」（末尾 `aload` 形参槽会 VerifyError）；② `ClassType.POSE_STACK` 绑定 `pushPose/popPose/scale`，文本度量与绘制同源缩放。

### D3.1 渲染性能与状态收尾（2026-11-03，1.16.5 gl-core 真机）

开「透视框 / NameTags / 容器透视」时游戏卡顿、服务端 tick 掉队、画面异常。十个并行审计 +
真机定位后落地的修复：

| 处 | 问题 | 修法 | 证据 |
|---|---|---|---|
| `EspModule` / `ItemEspModule` / `StorageEspModule` / `ChamsModule` | 每帧每实体手写「候选名逐个 `Class.forName`」循环，未命中候选每次抛异常 | 改走 `GameBridge.type()` 的 `typeCache`（桥解析失败才回退原循环，覆盖 Fabric 隔离加载器） | 代码 + 编译 |
| `CameraState` | `getEyePosition(0.0F)`、`yRot/xRot` 直接取 tick 末值，框以 20Hz 阶梯相对游戏画面甩动 | 从 `Minecraft#pausePartialTick` 取插值量（映射表新增），眼位传真实 partialTicks，朝向 `yRotO + (yRot-yRotO)*pt` | `--check` 全绿；1.16–1.20.1 有该字段，1.8.9/1.21+ 无则退回 0 |
| `ModernRenderer` 几何 | 一次 `outline` = 4 次、`roundedRect` = 7 次 `glBufferData`+`drawArrays` | 同色合并到 16K float 缓冲；`text`/`texturedQuad`/`drawPlayer`/`pushClip`/`popClip`/`uploadTexture`/`endFrame` 强制 flush | `draw/s=1056 vertices/s=68904`（120fps → 每帧 8.8 次下发，合批前约 95 次） |
| `ModernRenderer` 文字 | 每条文字 `useProgram(0)+bindVertexArray(0)` 再抢回 | 让出后不立刻抢回，由下一条几何/纹理/预览/帧末统一 `reclaimFromText()` | 主菜单与叠加层界面逐像素正常 |
| `StorageEspModule` | 陷阱箱判定每帧每箱子 3 次反射 | 按方块实体身份缓存到 `WeakHashMap`（随区块卸载回收） | 编译 + 单测 |
| `NameTagsModule` | `Player/Animal/Mob Max Distance` 三个设置**从未被读取** | 按三者最大值裁剪（0 仍表示不限），`label()` 复用已读坐标 | 代码 |
| `FullBrightModule` | Gamma 启用 → 切 Night Vision → 禁用：只摘药水就 return，gamma 永停在模块值 | 两个分支都走 `restoreOriginal()` | 代码 |
| `ChamsModule` | 只做门检查，双 pass 重绘未接入 → 空转开关 | 按 Xray 先例从注册表移除（类与门逻辑保留） | 真机 `modules=21` |
| `TracersModule` | 同 ESP：`onRender` 与 `drawWorldOverlay` 各遍历一次实体 | 只留门检查 + 走 `GameBridge.type()` | 代码 |
| `SearchModule` 建点 | 每格 `above/east/south` 三次方向面反射 | 改用桥缓存的 `BlockPos(int,int,int)` 构造器，每格一次 | `cost` 96–575 µs |
| `CameraState` yaw 插值 | 直接 `yRot - yRotO`，跨 ±180 那一帧差值为 ∓358 → 相机瞬移、框乱窜 | 先 `wrapDegrees` 再插值（`Mth.rotLerp` 语义） | 代码；`-Dnocturne.debug.noCameraInterp` 可整体关插值 |
| `CameraState` 眼位 | 只插值朝向、眼位仍走零参 `getEyePosition()`（tick 末值）→ 移动时框比画面慢半拍 | 有插值量时优先调 `getEyePosition(float)`，零参只兜底 | 真机无缺成员日志 |
| `BlockEntityScan` | 每帧遍历所有已加载区块 + 每区块反射 | 按 20Hz 缓存（与模块 tick 同频） | `cost` 1500–2700 → 884–984 µs |
| `NameTagsModule` / `StorageEspModule` | 名字/宽度/容器坐标/陷阱箱判定每帧重复反射 | 按实体身份或文本缓存（弱引用随卸载回收） | 代码 |
| `MinecraftTextRenderer` | `draw/s` 计数在 `draw()` 开头，字体未就绪也 +1 | 移到真正绘制点 | 空世界不再报 11000+ |
| `EspModule` 框不可见 | 头顶/脚下两点投影差被透视压到 1–4px（真机探针 `h=2 w=1`），叠加深绿 `0xFF249824` + 1px 描边 → 草地/夜晚/洞穴完全不可辨；玩家报"开了看不见框" | `MIN_BOX_PX=8` 最小尺寸 + 默认色改亮绿 `0xFF3CF03C` + 半透明填充；新增 `Max Distance`（默认 64）避免远处被放大成噪声 | 截图为证：同位置从零框 → 5 个清晰框 |
| `EspModule` 计数 | 删 `countVisible` 时漏了回写 `lastVisibleCount`，HUD 数量恒 0 | 绘制循环末尾回写，并加 `ESP: entities=N drawn=M` 每秒诊断 | 真机 114 实体 / 42 框 |

**已知结构性限制**：1.16.5 的 client jar **没有 `GlStateManager`**（只有
`com.mojang.blaze3d.systems.RenderSystem`），而 `RenderSystem` 的 shader 管理是
`setShader(ShaderInstance)`，没有 `setProgram(int)`。因此自建 GLSL 程序的绑定**无法同步到
游戏的缓存**——`gl-core` 上 `useProgram` 只能裸调，游戏下次设置同一 program 时会被缓存判为
「已绑定」而跳过。缓解：游戏每帧自己会切换 shader，污染会被覆盖。另：`endFrame` 曾尝试
「把 program/VAO/纹理恢复成进入前的原值」，实测在游戏 VAO 上 `glBindBuffer` 会改写它自己的
ARRAY_BUFFER 绑定，直接刷 `GL_INVALID_OPERATION` 并让服务端掉 100+ tick，已回退为解绑到 VAO 0。

**覆盖层耗时实测（2026-11-03，1.16.5 + gl-core，120fps）**：

| 场景 | `world overlay cost` | 每帧顶点 |
|---|---|---|
| 空世界，ESP+NameTags+StorageESP | 884–984 µs | ~2280 |
| 同上 + `/fill` 192 个箱子（视野内约 75 个） | 1396–2051 µs | ~2270 |
| 7 个渲染模块全开（再加 Tracers/SpawnerFinder/ItemEsp/Search） | 1404–1584 µs | ~2700 |

帧预算 8.3 ms，覆盖层占 11–25%；每容器约 5 µs，线性可控。为此加的护栏：
方块实体扫描按 20Hz 缓存、`NameTags` 名字/宽度缓存、容器坐标与陷阱箱判定按方块实体缓存、
NameTags 与 StorageESP 每帧 256 绘制上限。排查「开覆盖层后画面乱窜」时用
`-Dnocturne.debug.noCameraInterp` 可关掉相机插值二分（默认开）。
`-Dnocturne.debug.modules=ESP,NameTags` 可在启动时直接启用模块（GUI 点击在无焦点会话里不可达）。
`-Dnocturne.debug.motion` 打印「一 tick 位移 > 5 格」或「`setPos` 写回值与原值偏差 > 0.5 格」——
`postMotion` 在 pre 阶段会把事件坐标写回玩家，这段此前完全不可见；玩家报「人物飞速移动」时
这条日志直接区分「位置被改坏」与「只是掉帧看起来快」。

**帧驱动**：1.16.5 实测 `frameHook=0 drawHook=120` —— `glfwSwapBuffers` 钩子没有触发，
实际由绘制钩子单驱动；`NocturneRuntime` 仍保留 `onFrame`/`onDrawFrame` 拆分与
`contextDriven` 判定，供 26.x（无帧钩子）与双驱动栈使用。诊断开关 `-Dnocturne.debug.frames`
打印每秒驱动次数与来源。

### D4 收尾

- 删除 `ModernRenderer` 在 1.20+ 路径上的引用；`VERSION-MATRIX.md` 的绘制能力矩阵按三层重写。
- 全仓 `gradle test` 绿；`generate.py --check` 绿；commit + push。

---

## P4-B 关闭条件（并入 D2/D3）

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
