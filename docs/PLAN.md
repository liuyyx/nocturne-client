# 开发计划

原则：**每个阶段都以可运行的验证收尾**，不写「应该能行」。文档里的状态只有两种：实测通过、未验证。

## 已定架构（不讨论，实现照此）

1. 单 jar，**只有注入**一条路径（无模组形态）。
2. 客户端代码按 **Mojmap 规范名**写，**不写版本分支**。
3. 版本差异 → **每版本一份映射表 JSON**（自动生成 + 人工补丁，打进 jar 资源）。
4. 目标版本 → 注入器判定并传 `mcVersion=`，运行时零探测。
5. 界面与输入 → **用游戏自己的 API**，按代际写后端，成员名查表。
6. 帧信号 → LWJGL 交换函数（3 个签名，与 MC 版本无关）。

## 状态快照（2026-10-06，以实测与入库代码为准）

| 阶段 | 内容 | 状态 |
|---|---|---|
| P0 清理 | 删模组路径 / 启动动画 / 版本适配分支；修指针捕获语义 | ✅ 已完成 |
| P1 版本传递 | `AgentOptions`（组装 + 版本族归一化）；注入器 GUI 与命令行两条路径；agent 解析并按表名取表 | ✅ 已完成 |
| P2 注入点 | 帧交换钩子 + **绘制上下文钩子**（含行为测试） | ✅ 已完成 |
| P3 映射表生成器 | `tools/mapping/` 三档流水线；9 个版本的表**已全部入库**（`client/src/main/resources/mappings-*.json`，1.21.11 与 26.3 已抽查确认为真实内容） | ✅ 完成，收尾见 P3-R |
| P4 绘制后端 | A（1.8.9/1.12.2 固定管线）✅ 真机可用；B（1.16.5 gl-core）✅ 真机可用（linkProgram 缺失 + 视口回退 Window 已修，界面可见可点）；C（26.x SDL 栈）❌ 按设计不装叠加层，游戏原生绘制 spike 通过，正式实现待做 | 部分，P4-B 已关闭，见 P4-C |
| P5 输入 | 右 Shift 唤出 / Esc 关闭 / 鼠标在 A 代际可用；滚轮：LWJGL2 轮询 `getDWheel`、GLFW 回调 `setScrollCallback`，派发链走读无断点（合成事件进不了游戏队列，待真人验收）；26.3 输入栈未实现 | 部分，见 P5 |
| P6 模块 | 4 个模块入库；Tick/Packet/Render/Input 四事件已立类型并接生产（Tick 广播+直调、Render 每帧广播）；FullBright 对齐 OpenVape（Mode/Fade/夜视，1.8.9 真机启用+设置面板已验）；B1 剩余 8 模块待世界绘制挂钩 | 部分，见 P6 |
| P7 自实现 attach | 只有 `JdkAttachStrategy`；`PayloadPack` 已实现有测试、生产零调用 | 未开始，见 P7 |

## 工作纪律（每次开工前读一遍）

- **每做完一个大项 → 立刻 `git commit` + `git push`**。不要攒到最后一起交：真机验证的结论只认已 push 的提交，本地日志不算数。
- 每个大项的验收行里都重复写了这一条：验收没绿 + 没 push = 该项没做完。

### P-1 工作区先修绿（阻塞一切）

工作区有 26 个未提交文件，其中 `ui/.../gl/ModernGlApi.java` 处于**中间态**（`getIntegerv` 已拆成
`getIntegervBuffer` / `getIntegervArray` 双字段，`getInteger` 已按形态分派重写，但
`enableBlendAndReadPrevious` 与字段声明区还残留旧 `getIntegerv` 引用）。此状态下**编译不过**，
编译不过则构建、单测、真机验证全部无从谈起。

- 验收：`gradle build` 通过；`git status` 干净；commit + push。
- 不做：不 revert、不另起重构——迁移方向是对的（H05-B3：旧代码无条件分配 staging 导致数组形态永不可达），只差收尾。

### P3-R 映射表收尾（小活，先清掉）

- `python tools/mapping/generate.py --check` 必须干净；`--javap` 对本地 client jar 全版本校验一遍。
- `requirements.txt` 与 `--report` 的 absent 清单对齐：要么加别名/需求解决，要么确认为版本真实缺失（保留 `"absent": true`）。
- `ObfuscatedMapping` 目前只读**类级** absent，成员级 `"absent": true` 写了也白写（`tools/mapping/README.md`
  末尾的 Note 点名了这个缺口）：补成员级诊断日志，缺成员时响亮报错而不是按规范名硬调。
- 订正 `VERSION-MATRIX.md` §1 四个版本的表状态 ❌→✅。
- 验收：`--check` 绿、`--javap` 绿、`--report` 无意外 absent；缺成员时日志能定位到版本+类+成员；commit + push。
### P4-B 代际 B 画不出界面（离"可用"最近的一个 bug，优先）

现状（1.16.5 真机）：注入、帧钩子、输入层、叠加层装载全绿，`screen=ClickGui`，渲染异常已清零——
就是 `ModernRenderer` 没把像素送上屏幕。怀疑方向按从便宜到贵的顺序查：
投影/视口（含 `getInteger` 余量校验那一带）、绘制时机（回调时 GL 上下文是否 current）、VBO/VAO 状态。

- 验收：1.16.5 真机注入后界面可见、可点；`backend=gl-core` 日志无异常；commit + push。

### P4-C 26.x 绘制路径（架构级难题，单独一个 spike，不承诺交付）

实测结论：26.1 起 SDL 栈下 LWJGL 的 GL 绑定全进程不可用（三处时机都试过，native abort，Java 侧接不住），
所以 agent 按设计不注册帧钩子、不装叠加层。界面要回来，必须是不依赖 LWJGL 绑定的绘制路径。
候选三选一，先做最小 spike 再定：经 SDL 自行 make current 后交 Skia；或改用游戏自身的绘制 API；
HUD 的 `GuiGraphicsExtractor.fill` 思路可先在 lab 里验证。

- 验收（spike）：26.3 真机上画出一个矩形且游戏不崩；结论写进 VERSION-MATRIX（哪条路通、哪两条死）；commit + push。
- spike 失败是可接受的结果：那就把「26.x 无界面」从 ❌ 改成架构决策（只保留注入+模块），P4-C 关闭，不再投时间。
### P5 输入收尾

- 核对滚轮现状：`CallbackHookTransformer` 目前只交首个**引用**形参，滚轮是 `double` 形参——
  查一下最近几轮滚轮修复（视口同步那几笔提交）之后，滚动到底通没通；没通则扩展 transformer 或另设签名。
- 26.3 输入栈按 P4-C 结论定：绘制都没通就不用做输入。
- 验收：1.8.9 与 1.16.5 真机：右 Shift 唤出、Esc 关闭、鼠标点选、滚轮滚动，四项全过；commit + push。

### P6 OpenVape 全模块移植（替代原"模块与事件接线"，范围扩大）

目标：把 `OpenVape4.21/src/main/java/gg/vape/module/` 下的**全部模块**搬到
`client/.../module/modules/`，只写 Mojmap 规范名 + 查映射表，同一份源码在 1.8.9 与 1.16.5 真机都生效。
我方现状只有 4 个自研模块（AutoRespawn / FullBright / Sprint / Watermark），对方家底约 130+ 文件：
战斗 21（AimAssist / Reach / Velocity / KillAura / SilentAura / LeftClicker / RightClicker / WTap …）、
渲染 18（ESP / NameTags / Tracers / Chams / XRay / Freecam …）、走位约 30（Scaffold / Fly / Speed /
Blink / Backtrack / InvWalk / Phase …）、功能 20（AutoArmor / AutoTool / ChestSteal …）、世界 7、
操控 11、HUD 约 20（Keystrokes / ArmorStatus / Compass / FpsDisplay …）。`ModManager.init()` 的
61 核心注册 + 版本限定注册 + `registerHudModules()` 就是**移植范围的权威清单**，照它搬，一个不丢。

#### P6-0 方法红线（先读三遍再动手）

- **只搬行为，不搬代码**：读懂对方模块的触发条件 + 参数 + 游戏交互，然后用我方 `Module` /
  `BooleanValue` / `NumberValue` / `ModeValue` + `GameBridge` 反射桥**重写**。禁止把 `gg.vape.*`
  的类原样拷进来——包名一拷就是派生作品，后续许可说不清；且对方是 Forge 1.8.9/1.21 双栈写法，
  直接拷在我方"无版本分支"架构里跑不起来。
- **许可**：OpenVape4.21 仓库是 CC0，但 README 自述它是 Vape 闭源产品的"恢复工程"——CC0 只覆盖
  贡献者有权处分的部分，底层行为设计仍是第三方商业客户端的。做法：行为对齐（参数名、默认阈值、
  触发逻辑属于功能思想），**不复制实现文本**，不出现 `gg.vape` 包路径与注释原文。
- **版本约束改写**：对方用 `ModRegistrationBuilder + ForgeVersion` 做版本门（1.8.9 专属 / 1.21.4+ 专属 /
  1.16.5 专属…）。我方无版本分支，门要翻译成**表驱动**：模块启动时查映射表对应成员是否存在，
  缺成员就禁用 + 打日志（P3-R 的成员级 absent 诊断就是给这个铺路的）。门逻辑保留，写法换掉。
- 每搬一个模块，先确认它要的成员在 `requirements.txt` + 对应版本表里存在；缺的按 `tools/mapping/README.md`
  §"Adding a requirement or an alias" 流程补（legacy 加别名桥，modern 以官方映射为准，真实改名就认 absent）。

#### P6-1 先接线（三僵尸，要么接上要么删）

- `EventBus` 补首批真实事件类型并接到生产（现在零类型）：Tick / Packet / Render / Input 四个先立起来，
  对方模块的触发点（`@EventHandler` 那套）全部要挂到这四个上，没有事件总线移植无从下手。
- `HudSink` 接上 ui 实现（现在 `setHudSink()` 无调用方）；`HudManager` 接到生产（现在零引用）。
- 验收：Tick 事件在 1.8.9 真机每秒 20 次稳定触发；HUD 有一个元素可见；commit + push。

#### P6-2 分批搬（按依赖从少到多，每批真机验收）

| 批 | 内容 | 为什么先搬 |
|---|---|---|
| B1 渲染显示类 | Fullbright（已有，对齐参数）/ ESP / NameTags / Tracers / ItemESP / StorageESP / Trajectories / Chams / XRay | 只读游戏状态 + 画框/画线，不改包不改输入，最容易验证 |
| B2 点击与连击 | LeftClicker / RightClicker / AimAssist / Reach / HitBoxes / WTap / BlockHit / JumpReset / KnockbackDelay | 读输入 + 改点击，1.8.9 代际 A 输入已通，可独立验证 |
| B3 走位 | Sprint（已有，对齐）/ KeepSprint / Speed / Fly / SafeWalk / Scaffold / NoFall / NoSlowdown / Timer / Blink | 改运动与位置，逐个真机验收，Scaffold 这类 500+ 行的大件单独一批 |
| B4 战斗 | KillAura / SilentAura / Velocity / Triggerbot / BowAimbot / HitSelect / AntiBot | 依赖 B2 的目标选择与 B3 的走位，先有底座再搬 |
| B5 物品与世界 | AutoArmor / AutoTool / InvCleaner / ChestSteal / Refill / FastPlace / AutoPearl / MLG | 背包与方块交互，InventoryAction 互斥逻辑（`isOtherInventoryActionActive`）要重写一份 |
| B6 HUD | Keystrokes / ArmorStatus / Compass / Coordinates / FpsDisplay / ReachDisplay / PotionEffects / Scoreboard / MotionBlur（1.17+ 限定）| 挂到 `HudManager` 上；MotionBlur 要帧末钩子，放到 P4-B/C 之后 |
| B7 版本限定与杂项 | InvWalk（1.8.9）/ Backtrack（1.8.9+1.21.4）/ Triggerbot 系（1.21.4+）/ Freecam / AntiAFK / Panic / ClientSettings | 门最碎的一批，每扇门都要在真机上验证"该出现出现、该禁用禁用" |

- 验收（每批）：1.8.9 与 1.16.5 真机，批内每个模块开关生效、参数可调、缺成员版本正确禁用；`--report` 无新增意外 absent；commit + push。
- 分类：我方 `Category` 只有 4 个（MOVEMENT / RENDER / PLAYER / MISC），对方是 Combat / Blatant /
  Render / Utility / World …——搬的同时把分类映射定下来（Combat→PLAYER？Blatant→MOVEMENT？），写死一张表，
  不要每个模块各自定义。

#### P6-3 移植完成标准

- `ModManager` 清单里的 61 核心 + 版本限定 + HUD 逐项打勾，清单贴在 issue/tracker 里，一项一行。
- 同一份模块源码在 1.8.9 与 1.16.5 真机都生效（不重新编译、不写版本分支）；版本专属模块在错版本上干净禁用。
- `generate.py --check` 绿；全模块 `gradle build` 绿；commit + push。

### P7 自实现 attach

- 内嵌 `sun.tools.attach.*` + 原生通道，摆脱 `jdk.attach` / `tools.jar`，`PayloadPack` 接入生产（现在零调用）。
- 验收：在没有 `jdk.attach` 的裁剪 JRE 里注入成功；commit + push。

### P8 模组式（三加载器：Fabric + NeoForge + Forge）

背景：`824c897` 曾删过模组入口（`NoturneFabric/Forge/NeoForge` + `ModFrameDriver` +
三份元数据），理由是"模组路径拿不到 Instrumentation 就没有帧钩子"。这个理由 half true——
`ModFrameDriver` 当时已用反射挂 `HudRenderCallback` 解决了驱动，真正砍掉的是三份元数据的
版本地狱 + `DrawContextBackend`（478 行，1.21.9+ RenderPipeline 下 GL 直发无效时的答案）。
所以 P8 的前置是 P4-B/P4-C：绘制结论出来了，恢复模组只是"三个入口类 + 三份元数据 + 复用绘制后端"。

- 入口：`NoturneFabric`（`ModInitializer.onInitialize`）/ `NoturneForge`（`@Mod`）/
  `NoturneNeoForge`（`@Mod`），全反射，不引加载器构件（当时 `modStubs` 那套编译期 stub 思路继续用）。
- 驱动：复活 `ModFrameDriver` 思路——Fabric 挂 `ClientTickEvents`/`HudRenderCallback`，Forge/NeoForge
  挂 `ClientTickEvent`，全反射，注册失败静默降级（模组路径下没 GUI 也要能进游戏）。
- 绘制：复用 P4 结论——1.21.9+ 走游戏自己提交绘制（当时 `DrawContextBackend` 的思路），老版本走 GL 后端。
- 互斥：同一 JVM 里 agent + mod 双装会 double-tick，`AtomicBoolean` 扩展一下保证只驱动一次。
- 顺序：Fabric 先行（1.21.x），再 NeoForge，再 Forge（1.8.9 Forge 最碎，放最后）；Vape 兼容表提醒过
  1.20.1-Fabric 有 Knot 类加载隔离坑，Fabric 首验选 1.21.x 别选 1.20.1。
- 验收（每加载器）：对应版本真机进游戏、模块开关生效、界面可见；commit + push。三个都绿 P8 关闭。

## 待办里容易忘的两条

- **滚轮**：见 P5。若 transformer 扩展后滚轮仍为 0，记得现在是「打一次日志说明」而不是静默。
- **26.1 与 26.2/26.3 的 HUD 入口不兼容**（形参 vs 字段），属绘制代际 C 内部的两个子形态——
  按表里的入口描述分别处理，织入代码不能共用。P4-C spike 时一并确认。
