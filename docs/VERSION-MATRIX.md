# 版本矩阵

映射表现已覆盖 **1.8.9 – 26.3 的全部 66 个 release**（含 vanilla/Fabric/Forge/NeoForge 四套命名空间）；
>下面标注"真机"的行指**实际在机器上跑过**的版本：**1.8.9 / 1.12.2 / 1.16.5 / 1.20.1 / 1.21.1 / 1.21.4 / 1.21.10 / 1.21.11 / 26.2 / 26.3**。

## Epsilon 视觉移植：阶段 0 公共通道（2026-11-12）

- 新增共享颜色/实体分类、剪影/竖向盒/世界标签、九锚点 HUD 元素汇与生命周期迁移；
  该阶段没有注册视觉功能模块，正式 GUI 仍只有此前的 `Kill Aura`，不得把公共通道当作 51 项移植完成。
- `Camera#setup` 使用头部捕获 `this`、尾部无参消费，不读取 RETURN 处的局部槽；
  只织入映射候选的配对描述符，不枚举同混淆名的其它重载。26.x 规范 `setup` 映射到真实 `update`。
- `gradle build --console=plain --no-daemon` **实测通过**，包括单测、ASM 载荷闸门与
  `verifyJarBytecode`；颜色边界、分类、几何、九锚点/反向堆叠、汇迁移、绘制异常隔离、
  相机栈配对及 HUD/菜单字体缓存共存均有测试覆盖。
- `python tools/mapping/generate.py --check` **实测通过**：66 张表最新，177 个规范类；
  旧版独立相机/库存 getter 等缺失按真实 `absentReason` 保留，未伪装成可用。
- **1.8.9 真机公共 HUD 通道实测通过**：`client installed (modules=1)`、`backend=gl-fixed`；
  关闭 ClickGUI 后两个临时元素仍持续绘制，截图 `tmp/epsilon-stage-0c-1.8.9-hud.png`，
  日志 `tmp/epsilon-stage-0c-1.8.9.log`。独立相机按映射缺失输出 `camera aligned hook unavailable`。
- **1.21.1 真机公共 HUD / 相机回调实测通过**：`backend=gl-core`，关闭 ClickGUI 后元素及文字可见；
  图集仅供 core profile 使用，固定管线/游戏 API 绘制栈沿用原有文字面。
  日志有 `camera aligned hook woven: ffy.a(Ldcc;Lbsr;ZZF)V -> cameraBegin + cameraEnd`
  与 `stage zero camera callback verified: ffy`。
  证据：`tmp/epsilon-stage-0c-1.21.1.log`、`tmp/epsilon-stage-0c-1.21.1-hud.png`；
  窗口放大后的锚点重排截图 `tmp/epsilon-stage-0b-1.21.1-hud-resized.png`。
- 临时 `HUD TOP LEFT` / `HUD BOTTOM RIGHT` 仅是通道验收标记，**不属于产品模块**，
  验收实例已退出，探针源码与 jar 已清除；后续批次必须注册真实模块并从 GUI 验证。
- 边界：0b 轮 1.21.1 日志仍观察到既有 Hud/Screen/Chams 重定义的 `VerifyError`；
  本节不将这些路径或尚未落地的视觉模块记为通过，其余版本真机均**未验证**。

## Kill Aura 移植（2026-11-12，真机未验证）

- 已按 eps 上游行为重写并注册 `Kill Aura`：Single/Switch 选靶、四类优先级、受限旋转、
  1.8 CPS 与 1.9+ 冷却攻击、Hit Select、进食/Scaffold 暂停，以及 CaptureMark / Circle /
  Firefly / Deobf 四种文字或线条 ESP。`Pause On Eat` 当前以“正在使用任意物品”近似，
  Deobf 不依赖上游纹理资源。
- `TargetManager` 新增 Players / Mobs / Animals / Villagers / Ambient / Water / Others 七类开关，
  水生生物优先于动物分类，盔甲架始终排除；距离改为眼睛到 AABB 的三轴外距，缺少包围盒时退回脚部。
- 映射面新增 `VILLAGER / MONSTER / WATER_MOB` 及 modern 别名、1.8.9/1.12.2 legacy 桥接；
  66 份表已重写，`python tools/mapping/generate.py --check`（含 javap）全部通过。
- 定向目标/几何测试、完整 `gradle test` 与 `gradle build` 均通过；`verifyJarBytecode` 通过，
  agent 与 launcher 两个 jar 均已生成。新增测试覆盖分类开关、Water 优先级、盔甲架排除、
  眼到 AABB 距离、box 12 边与 circle 分段数。
- **真机未验证**：本次没有运行中的游戏进程，未执行 1.21.1 注入；旋转、实际攻击与四种 ESP
  的真机表现均不得视为通过。

## 模块层清空（2026-11-12，待重写）

模块实现（`client/.../module/modules/**`，211 个 main 源文件 + 33 个测试）已整体删除，**模块层重写中**。
框架保留且单测覆盖：`Module` / `ModuleRegistry` / `GameModule` / `HudModule` / `ModuleKeybinds` / `Category` /
`ModuleConfigStore`、`TargetManager` 的好友与视线过滤、`HudModuleTest` 的 HUD 行迁移。
随之删除的挂钩点：`NocturneClient.registerModules` / `DEFAULT_ENABLED_MODULES` / `-Dnocturne.debug.modules`、
`GameplayEvents` 的 Xray/HandView/Totem/CameraClip/Timer/NoFog/NoRender 分支、`FrameEffects` 的
AntiAlias/Filter/CustomSky/Shaders 后处理与 Game Animation 动画、`GuiOverlay` 的 HUD 文本设置样式、
`TargetManager` 的 TargetFilter/AntiBot 过滤。本节之前所有「N 个注册模块」的验收结论**只属于已删除的实现**，
不得当成当前构建的能力。

## 映射生成器强化（2026-11-08，仅离线/单测，**未真机**）

本批不碰渲染数学，只把"表里的名字和描述符真的存在吗"这件事变成可执行的门禁：

- **描述符来自真实 jar**：类层级的 javap 结果现在被用来生成 `signatures` / `descriptors`，并作为
  `ModernResolver._on_jar` 的择优依据——候选方法必须在 jar 里声明同样的描述符，否则丢弃并试下一个
  重载。1.16.x 的 `Style#withColor` 之前写成 `(I)Lk;`（真实是 `(Lk;)Lob;`），就是这样被清掉的。
- **`--check` 默认连 jar 一起校验**（新增 `--fast` 退回纯文本比较）；`--javap` 单独跑交叉校验。
- **`absent` 带 `absentReason`**：`verified` / `ambiguous` / `unverified`，`--javap` 打印
  `absent verified=N unverified=M`，出现 `ambiguous` 直接 exit 1；旧版 anchor/legacy 路径一律记
  `unverified`，不再冒充"确认没有"。
- **成员别名表** `tools/mapping/member-aliases.txt`：改名不写 `absent`，按链式别名回退；
  `requirements.txt` 新增 `#成员!field` 强制字段标记（同名方法/字段并存时用）。
- **扫描面扩大**到 `agent/` 与 `ui/`，并修掉"同语句内两个字面量都被当成字段"的误判（改为按直接包裹的
  桥调用判定）；生成器对"按字段登记但映射源里是方法"直接报错。agent 的 timer 钩子因此补上了
  `resolveDescriptor`（`()F|()D`）。
- 规模：**165 个规范类 / 66 张表**。各版本 resolved / absent（`generate.py` 汇总行里的
  `absent` 是成员级 + 类整缺之和，下表分开列）：

| 版本 | resolved | absent（成员级） | 类整体 absent |
|---|---|---|---|
| 26.3 | 407 | 103 | 22 |
| 1.21.11 | 416 | 95 | 23 |
| 1.20.1 | 418 | 80 | 23 |
| 1.16.5 | 403 | 88 | 31 |
| 1.12.2 | 308 | 132 | 57 |
| 1.8.9 | 311 | 120 | 59 |

- 门禁：`generate.py --check --javap` → exit 0、0 problems、66 张表最新；单测 554 项
  （agent 71 / client 225 / ui 147 / core 65 / injector 46）。`client` 里
  `WorldProjectionNearPlaneTest.pointInsideTheNearPlaneStaysOnScreen` 由本批之外的新渲染测试引入，
  断言与透视数学不自洽（沿视线插值到近平面不改变屏幕位置），由渲染批次负责修；本批不改渲染数学。
- UI 侧：`UiBackend` 新增 `reliableAlphaBlend` 能力位（默认 `false`，`ModernRenderer`/`ExtractorRenderer`
  报 `true`），`OverlayDraw`/`BackendOverlayDraw` 据此决定整屏 alpha 混合；`GameBridge` 增加前置检查。

## 真机回归验收（提交 2d6c6a6）

- 2026-11-08：`2d6c6a6` 在 **1.8.9 与 26.3** 真机上完成回归，全部通过；测试 501 项零失败，
  66 张映射表 `generate.py --check` 通过，映射面 **162 个类**（26.3：resolved 393 / absent 147；该批之后已扩到 165 / 407，见上节）。
- 门禁命令：`gradle :agent:test :client:test :ui:test :dist:distJar --rerun-tasks --offline` →
  `BUILD SUCCESSFUL`；测试 agent 69 / client 184 / ui 140 / core 62 / injector 46 = **501 项零失败**。
- 交付 jar：`nocturne-0.1.0-SNAPSHOT.jar` 1170 个类，major 只有 51/52（`over52=0`），无
  `META-INF/versions`、无 `module-info`；`nocturne-launcher-0.1.0-SNAPSHOT.jar` 在此基础上多 37 个
  `net/miginfocom/**`（MigLayout，major 55），属启动器专用，agent 可见面仍是纯 Java 8。
- 1.8.9（原版 + LWJGL2）：GUI **开 / 关 / 重开**正常；GUI 打开时鼠标、滚轮、键盘都不穿透到游戏；
  关闭 GUI 后指针抓取与输入恢复；按 Esc 关闭自绘 GUI **不弹出暂停菜单**。
- 26.3（Fabric 0.19.5）：`sink=true`；`grabMouse` / `turnPlayer` / `onScroll` / SDL 滚轮四处输入钩子
  `patched=true`；原生暂停菜单与自绘 GUI 同时存在时不再互相遮挡；`disableScissor` 的
  `InvocationTargetException` 出现 **0 次**，无 `NoSuchMethodError` / `VerifyError` / 崩溃。
- 真机日志：`tmp/mc-1_8_9-run.log`、`tmp/mc-26_3_Fabric_0_19_5.log`（推送前的旧构建里这两份日志
  记录的 `screenOpen` / `disableScissor` / `getDisplayObjective` 三类报错均已在本轮修复）。
- 2026-11-08 `38705b4`：26.x 改由 `Gui.setScreen(Screen)` 投递 `ScreenEvent`（`Minecraft` 侧
  `setScreen` 仍 absent），26.3 真机 `hook H10 hit`；1.8.9 / 1.12.2 / 1.20.1 的 `Gui` 无此方法，
  表里显式 `absent`，不重复挂载。复验门禁：测试 505 项零失败（client 184 → 188），
  映射面仍 162 个类（26.3 resolved 394 / absent 147）。

### 已知缺口与明确降级（同一次回归的边界）

| 项 | 现状 | 说明 |
|---|---|---|
| 早期世代的 `ScreenEvent` | 1.8.9 / 1.12.2 / 1.20.1 的 `Gui` 没有 `setScreen`，表中显式标 `absent` | 26.x 已改为由 `Gui.setScreen(Screen)` 投递（`38705b4`，真机 `hook H10 hit`）；两代按版本互斥，不重复挂载，早期世代也不假装能投 |
| 1.21.5–26.3 Chams / 部分 Xray | 没有真实绘制入口时显示 `unavailable`，Xray 只画矿框 | `EntityRenderDispatcher#render`、`BlockRenderDispatcher#renderBatched` 在 26.x 找不到可织入的描述符；日志显式写 `unavailable`，不静默假装生效 |
| 1.8.9 `Couldn't load chunk` | 存档自身问题，非客户端缺陷 | 该 NPE 的栈帧全部在 `1.8.9.jar` 内（`dn.a` → `MinecraftServer.run`），没有 nocturne 帧；换存档不复现 |

## 跨版本移植依赖验收

- 2026-11-06：映射要求扩至 **161 个规范类**；补 `KeyMapping#setAll/set/click`、`Entity#getDisplayName/getCustomName`、
  `Level#getBlockState` 与 1.14.4 的 `MultiPlayerLevel#entitiesForRendering`。`GameBridge` 增加
  `ClientLevel → MultiPlayerLevel → Level`、`ClientLevel → LevelReader → Level` 的方块/实体回退；
  输入短路按映射表所有类名/方法名/描述符组合注册，Fabric/Forge 命名空间不再只试原版混淆名。
- 2026-11-08：GUI 打开时阻断真实输入链——26.x `MouseHandler#turnPlayer(D)V`、`MouseHandler#onScroll(JDD)V`；
  1.8.9/1.12.2 `MouseHelper#mouseXYChange()V` 与 `Inventory#changeCurrentItem(I)V`。前两者防止视角旋转/快捷栏滚动，
  后者配合 `Minecraft.inGameHasFocus=false` 锁住 1.8.9 相机。
- 2026-11-04：公共动作、实体/物品/世界桥、移动与输入事件、双向包队列、OverlayDraw 线/纹理原语
  已接通；映射要求扩展到 125 个规范类。旧版字段 fallback 覆盖背包、物品数量、实体尺寸、TNT 引信，
  队伍与旁观者方法按真实 `EntityPlayer` 声明位置解析。
- 修正 1.12.2 `Vec3i` 上游源错位：三套运行期坐标固定为 vanilla `a/b/c`、intermediary
  `field_11175/field_11174/field_11173`、SRG `field_72450_a/field_72448_b/field_72449_c`。
- `build test dist --rerun-tasks` 通过，共执行 447 个测试且零失败；分发 jar 包含 66 张映射表和动作契约，
  抽查新增类均为 Java 8 字节码。
- 带原始 StackMapTable 的 move/packet 回归样本在修复前均抛出真实 `VerifyError`，补回参数
  `CHECKCAST` 后通过；混合 3/4 参与 6/7 参移动包构造器也有独立回归覆盖。
- `python tools/mapping/generate.py --javap` 对 66/66 个真实 client jar 的全部已解析 vanilla 成员
  校验通过。此处不声明 66 个版本均完成真机运行验收。

## OpenVape 全量模块移植验收

- 2026-11-05：按 `ModManager` 权威注册表覆盖 **108 个去重顶层模块**，另保留 Watermark/AutoRespawn，
  总计 110 个注册模块。分类数量为 COMBAT 23 / MOVEMENT 9 / RENDER 38 / WORLD 11 /
  INVENTORY 11 / PLAYER 11 / MISC 7。重复注册的 BedPlates/NoItemRelease/Timer 只保留一个实例。
- `OpenVapeModuleRegistryTest` 验证全部模块构造、分类数量与无游戏环境启停；不可逆 Panic 单独排除。
  完整 `build test` 通过，457 个测试零失败。
- 所有转换器按钩子类与方法做精确幂等检查；attackPost 在头部捕获 Player/Entity、所有 RETURN
  消费捕获，取消攻击不产生 post。真实 ASM 样本覆盖 StackMapTable、pre/post 链和重复 retransform。
- Chams 接 `EntityRenderDispatcher` 首参回调；Xray 接 `BlockRenderDispatcher` 的
  `BlockState + BlockPos` 双参门控并支持真实 Cave Mode；Timer 按版本写真实字段
  （1.8.9 `timerSpeed`、1.12.2 `tickLength`、1.16.5+ `msPerTick`），1.21+ 的 `DeltaTracker`
  接口没有可织入方法体，能力表显式报不可用而不是假 woven。新增转换器均通过 JVM verifier 行为测试。
- `GuiOverlay` 已提供真实文本/多行 HUD、ActiveModules、后端渲染状态与帧缓冲读取；
  InventoryBlur 使用 4× 降采样，MotionBlur 使用可配权重的历史帧混合。
- 映射要求扩展至 161 个规范类；`python tools/mapping/generate.py --javap` 对 66/66 个真实 client jar
  的全部已解析 vanilla 成员校验通过。复杂商业 AI、完整轨迹预测与伤害模型按 Nocturne 的公共动作/目标/
  放置/渲染接口重写核心行为，不复制 OpenVape 实现文本，也不把该结论外推为全部版本真机通过。

## 模块框架扩展验收

- 基线有 14 个模块类、13 个已注册模块；`XrayModule` 仍未注册，没有因分包而启用未接通的渲染功能。
  本轮新增 `KeepSprintModule`、`FastPlaceModule`、`SpawnerFinderModule`、`AutoToolModule`、
  `AutoClickerModule`、`NoFallModule` 后为 20 个模块类、19 个已注册模块。
- `Category` 增至七项，模块按分类分包并连续注册。Java 8 进程实际启动后，
  `FaClickGui` 分类摘要为 `COMBAT, MOVEMENT, RENDER, PLAYER, MISC (5/7)`，空分类不占 Tab。
- `gradle build test` 通过；修正效果常量的持有类为 `MobEffects` 并重建映射后，
  66 张表的 `generate.py --check` 与 `generate.py --javap` 均通过。
- 1.8.9 / 1.16.5 实机主菜单上确认原有四个 Tab 可见；截图为
  `tmp/phase0-1.8.9.png`、`tmp/phase0-1.16.5.png`。本轮不据此声明模块游戏行为已验收。
- 26.3 Fabric 的本轮 `premain` 诊断只确认启动与模块装配；日志出现 Mixin 注册，
  但未出现绘制回调、GUI 不可见，因此不记为通过。证据：`tmp/phase0-26.3-retry.log`。

## 游戏事件投递验收

- 2026-10-30 在 26.3 原版主类运行、注入当前构建后，H1–H8 均在真机日志命中：玩家 tick 前后、
  `sendPosition` 前后、双向网络包、实体攻击前置/后置、左键与使用物品、SDL 键盘和鼠标输入。
- 抓取运行中的 `MultiPlayerGameMode` 字节码确认：`attack` 先调用 `GameplayHook.attack`，取消时直接返回；
  正常路径末尾调用 `GameplayHook.attackPost`。同类多转换器链保留前置与后置钩子，且后置先织、门控后织，
  避免门控新增的 `RETURN` 被误当作正常返回。单元测试覆盖这两条顺序约束。
- `sh C:/Users/liuyyx/Desktop/nocturne-client/tmp/gw.sh test dist` 全绿；
  本轮只验收 26.3，不把该结论外推到其余 65 个映射版本。

## 动作翻译层验收

- `contract/actions.yaml` 已打入交付 jar；原有 `attack / place / useItem / setRotation / sendPacket /
  readState` 六项已扩展为攻击/点击、主副手使用与挥手、容器、聊天、换槽、射线、移动与玩家命令等
  完整移植动作面。契约测试覆盖内容与重复 id 拒绝；绑定测试覆盖候选回退、真实 `Method`/构造器
  调用、全部未命中和调用失败后的一次性禁用。
- 2026-10-30 在 26.3 真机上原有六个动作均 `available=true`；`readState` 返回真实坐标、朝向与地面状态，
  `setRotation`、`place`、`useItem`、`sendPacket` 均实际调用返回 `true`。`attack` 的候选绑定与真实
  方法调用由单元测试覆盖，26.3 上绑定成功。
- 真机曾发现 `sendPacket` 错绑 Netty `Connection`：`LocalPlayer.connection` 的实际类型是
  `ClientPacketListener`，调用因此被 `IllegalArgumentException` 拒绝。契约已改为
  `ClientPacketListener.send(Packet)`，并在新实例复测 `sendPacket=true`；契约测试固定该宿主。

## 设置值与 GUI 可见性验收

- 新增 `TextValue / BoundsNumberValue / SlotValue / KeyValue / GroupValue / MultiBoolValue`，
  覆盖文本截断、区间钳制与排序、槽位/按键哨兵、子组、固定选项多选。`Value.visibleWhen`
  是动态条件，只影响 GUI，不改变模块读值。
- Fatality 已提供文本编辑、双滑轨、九槽、按键捕获、多选弹层和分组行；隐藏行高度归零，
  子行同时受父组展开状态、父组条件和自身条件约束。单元测试覆盖全部行映射和这些组合规则。
- 离屏运行真实 `FaClickGui` 的 `docs/research/LabFatalityGui.java`，`Controls` 卡片显示六类新控件
  与展开子项，`LAB distinctColors=650 / PASS`，证据 `tmp/fatality-controls.png`。首轮发现文本行
  未把当前值同步到编辑缓冲、只显示占位符，已修复并重新渲染确认。此项是离屏表面验收，
  不冒充 26.3 或其他版本的真机交互结论。

## 动作映射面扩充验收

- 需求表补入 `ClientPacketListener.send`、`MultiPlayerGameMode.useItemOn/useItem`、
  `LocalPlayer.connection`、`InteractionHand.MAIN_HAND`、`Minecraft.hitResult` 与
  `BlockHitResult.getBlockPos/getDirection`。`MAIN_HAND` 由映射字段名解析，不在混淆版本上硬编码。
- 交互方法存在两代签名：1.16.5 的 `useItem` 带 `Level`、`useItemOn` 带 `ClientLevel`，26.x 已移除。
  契约给每个候选显式声明 `arguments`，绑定器选中间标后按该顺序装配实参；缺失首候选时回退下一形状。
- `--javap` 首轮暴露 22 个旧版错误：1.8.9–1.14.3 的 `getBlockPos` 被全局规则误指到
  `TileEntity.getPos`；1.14 又把 `RayTraceResult` 基类当成 `BlockHitResult`，且方法 ID 已拆成
  `method_17777/17780`。已分别修正别名、1.14 类锚点与方法锚点。
- 全量 `generate.py --javap` 通过：66 个版本的每一条已解析成员都与真实客户端 jar 对上，
  22 个问题清零。26.3 真机用修正后的多签名契约复测 `place=true`、`useItem=true`。
- 1.8.9 没有 `InteractionHand`，因此 `place/useItem` 仍按契约显式降级；没有伪报为已支持。

## B1 状态与输入模块验收

- `KeepSprint` 已移植：攻击前捕获水平动量，攻击后仅在确认原版 0.6 减速确实发生、玩家存活且满足
  空中条件时恢复动量与疾跑；Velocity/Normal 两组参数、Buffer Abuse 累计与回落均已接上。
  映射同时支持现代 `getDeltaMovement/setDeltaMovement` 与旧版 `motionX/motionZ`，受击计时读
  `LivingEntity.hurtTime`。Buffer 计数状态机单独测试了爆发、回落与重置序列。
- `FastPlace` 已移植：按 All/Blocks/Projectiles 过滤主副手物品，通过映射类别判定
  `BlockItem/SnowballItem/EggItem`，再压缩 `Minecraft.rightClickDelay`。
- 2026-10-31 的 26.3 真机同步验收：两个模块均注册成功，`FastPlace.onTick()` 在同一线程把
  `rightClickDelay` 从 4 写成 1；异步采样看到 0 是游戏每 tick 递减造成的，不是模块失效。
- 新增物品/运动/延迟需求后，`generate.py --javap` 对 66 个版本全绿（82 类），
  `gradle test dist` 全绿。
- 尚未移植的复杂输入模块（SafeWalk/InvWalk/AntiAFK）依赖移动输入锁或旋转控制，留给对应批次，
  不把它们算进本次“已验收”。

## B2 渲染覆盖层模块验收

- `SpawnerFinder` 已移植：复用新抽出的 `BlockEntityScan` 遍历已加载区块，按
  `SpawnerBlockEntity#getSpawner` 读刷怪逻辑，优先 `BaseSpawner#getEntityNameToSpawn`，
  26.3 退回 `getOrCreateDisplayEntity`；白名单按 `minecraft:` 命名空间归一化后匹配。
  `StorageEsp` 同步改用共享扫描器，三套区块表形态只在 `BlockEntityScan` 维护一份。
- 已补 `SpawnerBlockEntity/BaseSpawner` 及其方法映射；1.8.9/1.12.2 走
  `TileEntityMobSpawner.getSpawnerBaseLogic`。全量 66 版本 `generate.py --javap` 全绿（84 类）。
- 真机 26.3：夜晚放置带 Zombie 的刷怪笼后，扫描到 `spawnerName=Zombie`；
  独立投影 + 计数型 `OverlayDraw` 直接调用模块得到 `draws=3 text=Zombie [4]`，
  证明模块收集、匹配、投影与标签绘制链路正确。
- **26.3 Fabric 已修复并真机可见**：SDL 栈没有帧钩子，唯一入口是 `Screen/Hud.extractRenderState*`。
  attach 时这两个类已被 Fabric Mixin 追加接口，`redefineClasses` 会报“attempted to change
  superclass or interfaces”；改为 `premain` 在类首次定义前注册 ASM 回调，26.3 日志出现
  `hook transform`、`gui draw hook active`、`frame hook is live`、`GUI overlay attached`，
  桌面截图可见三列 FatClickGui。`premain` 还提前注册 `LocalPlayer` 事件钩子，避免同样的
  Mixin 结构变化。
- **26.3 运行中 attach 已恢复 GUI**：先 `retransformClasses` 捕获包含 Fabric Mixin 接口的**当前字节**，
  再在其上注入并 redefine，`Hud/Screen` 不再因接口集合变化被拒。真机日志出现
  `hook transform`、`gui draw hook active`、`GUI overlay attached`，主菜单截图可见完整三栏。
  游戏事件钩子也改为同一策略：`LocalPlayer.tick/sendPosition`、`Connection.send/channelRead0`、
  `MultiPlayerGameMode.attack`、`Minecraft.startAttack/startUseItem`、`KeyboardHandler.keyPress`
  均以当前字节注入；运行中 attach 真机验证 NoFall `motion.events=136 forcedGround=54`。
  不再要求 26.3 必须启动期 `-javaagent`。
- 开关键除配置键外额外接受 **INSERT**（AWT `VK_INSERT`）：第三方客户端常占用右 Shift，
  两个入口都能开关，避免“按了只开别人的面板”。1.8.9 已用真实按键注入分别验证右 Shift 与 INSERT。
- 26.3 点击自绘界面会把光标拽回中心：`MouseHandler#onButton` 不能整条屏蔽（`activeButton` 是界面
  按钮来源），改为对 `MouseHandler#grabMouse` 做输入短路。真机将光标放到 `(1000,800)` 点击后仍为
  `(1000,800)`，不再居中。
- 2026-11-01 追加：`InputBlock` 的 `blocked` 也会被 Knot 再加载一份，导致 `grabMouse` 短路读到
  `false`。占用/按键状态已统一搬到 bootstrap 的 `KeySink`；复验光标 `(1000,800)` 点击后仍为
  `(1000,800)`，GUI 保持打开。
- 用户指正刷怪笼白天不刷怪；已把世界设为夜晚后验证，测试环境正确。

## B3 背包与物品模块验收

- `AutoTool` 已移植：准星指向方块时，按 `ItemStack#getDestroySpeed(BlockState)` 在快捷栏
  0–8 槽中选最高分，`Instant swap / Swap to delay / Swap back / Swap back delay /
  Only while sneaking` 已接。武器评分、战斗中/使用中过滤没有可靠映射面，故不提供假开关。
- 映射补入 `ClassType.INVENTORY`、`Player#inventory`、`Inventory#selected`、
  `ItemStack#getDestroySpeed`；1.8.9/1.12.2 分别走 `currentItem` 与 `getStrVsBlock`。
- 单元测试覆盖最高分、全空、并列保持当前槽。26.3 真机同步验收：石头目标下钻石镐速度 8.0，
  模块把选中槽从 1 切到 0；命令语法错误与“打泥土不加速”两个测试假象已排除。
- 全量 66 版本 `generate.py --javap` 全绿（84 类），`gw.sh test dist` 全绿。

## B4 点击与攻击模块验收

- 动作契约新增 `click`，绑定 `Minecraft#startAttack`（零参），模块不再自行反射点击入口。
- `AutoClicker` 已移植：CPS、Normal/Extra/Extra+ 随机化、Jitter、Hold to click、Trigger mode
  （准星为 `EntityHitResult` 才点击）；左键按下状态由 `MouseButtonEvent` 维护。
- 26.3 真机：`clickAvailable=true`，投递按住事件后 800ms 内 `clicks=5`，日志同时出现 H6 命中，
  证明点击确实进入游戏左键入口。
- 物品白名单、破方块保留、HitSelect/BlockHit 互锁尚未移植，因此未提供对应假开关。
- 全量 66 版本 `--javap` 全绿（85 类），客户端测试与 `dist` 构建通过。

## B5 旋转与放置模块验收

- 新增共享 `RotationController`：优先级 + TTL 仲裁，收到请求立即把 yaw/pitch 写入本地玩家，
  Motion 事件同向提交；`AimAssist` 与 `Scaffold` 只登记朝向，不各自反射。
- `Scaffold` 移植可运行子集：扫快捷栏方块、选槽、向下寻找“实心支撑 + 上方空气”的表面、
  通过动作层对支撑面 UP 执行放置；不做移动接管或 GodBridge。
- 26.3 真机：请求 yaw=90/pitch=20 后 `PlayerState` 立即读到 `yaw=90.0 pitch=20.0`；
  支撑方块非空气、上方目标为空气，`placeAt=true` 后目标 `targetAirAfter=false`（方块真实落下）。
- 1.8.9 绑定面：`action.place=true`、`action.attack=true`、`action.click=true`、
  `action.setRotation=true`；旧版放置候选 `onPlayerRightClick` 已进映射。
- 1.8.9 真机补齐：打开 GUI 时每帧把 `Minecraft.inGameHasFocus` 压为 false，实测 `true → false`
  且移动鼠标后 yaw 不变；`Entity#onGround` 补 `onGround` 别名后 `PlayerState` 不再返回空快照，
  Scaffold/AimAssist 能读到真实坐标。
- 1.8.9 相机：零参 `getEyePosition` 的 arity 回退会误命中同名无参方法并返回 World 对象；
  按 `Vec3` 返回类型剔除后，`CameraState.update=true`，世界覆盖层恢复。

## B7 大型状态机模块验收

- `KillAura` 移植：目标列表复用 `TargetManager`，按 Swing/Attack range、Max angle、Max targets、
  APS 与目标过滤执行真实 `MultiPlayerGameMode#attack`。
- 26.3 真机：召唤僵尸后 `targets=1 distance≈3.0`，1–3 秒内血量由 20 → 18.12 → 14.06，
  证明攻击动作确实到达服务端。
- 1.8.9 真机：帧钩子原先直接调 `FrameDispatcher.dispatch`，模块 tick 根本未执行；新增
  `ModuleTicker` 挂到同一分发器后，`ticks` 正常增长，客户端测试僵尸 `targets=1 attacks=9`。
- 1.8.9 集成服务端：通过游戏聊天命令在服务端生成僵尸，KillAura（Mobs=true，范围 6）使血量
  `17.16 → 10.72`，`attacks=10`；攻击包确实到达服务端。

## B8 目标选择与视角模块验收

- 新增共享 `TargetManager`：活体/玩家/怪物/隐身过滤、距离、水平半角、按距离/角度/血量排序。
- `AimAssist` 移植：距离、Max angle、水平/垂直速度、Require mouse down、目标模式与目标过滤；
  26.3 真机在按住目标期间 yaw 由 61.56° 平滑推进到 90°，同时目标仍在 3 格内。
- 不提供穿墙可见性过滤；模块不把“角度内”宣称成“能看见”。

## B6 网络包模块状态

- `NoFall` 已实现：订阅 `MotionEvent.pre`，仅当未滑翔、非创造/飞行、下落距离 >2.224 且垂直速度为负时，
  把提交给服务端的 `onGround` 置 true；跳跃/鞘翅 `isFallFlying`、Abilities 字段均走映射并带旧版回退。
- 补入 `Abilities`、`Player#getAbilities/capabilities`、`isFallFlying`、`motionY` 映射；
  全量 66 版本 `--javap` 全绿（86 类），纯条件单测覆盖滑翔/创造/速度边界。
- **26.3 真机通过**：`premain` 在类定义期织入 `LocalPlayer.sendPosition` 的 motionPre/Post；
  桥接 bootstrap 与游戏加载器的 `GameplayHook.Sink` 后，3.5s 内观察到 `motion.events=140`、
  `forcedGround=56`。把玩家切生存并传送至空中后，56 个提交包被 NoFall 改成 onGround=true，
  证明 MotionEvent 与 NoFall 的提交改写都在真实游戏路径上生效。
- 1.8.9 侧 `motionPre/Post` 已由 `--javap` 匹配的旧名（`bew.p()` 等）织入；本轮未进入世界，
  不额外声明 1.8.9 NoFall 真机通过。

## B9 既有模块设置项对齐

- `Sprint` 按上游补 `Cancel Invis`：隐身且勾选时不再强制疾跑，避免疾跑粒子暴露位置；
  映射补 `Entity#isInvisible`（1.8.9/1.12.2 走别名），纯条件单测覆盖蹲下/隐身/静止。
- 已逐项检查现有模块：可执行行为均有真实设置项；上游依赖渲染 pass、目标管理或物品白名单的设置
  未伪造，按能力边界不提供。B9 以“可执行设置已对齐”收口，后续新增能力时再补对应项。

## 1. 映射表：每版本一张，四个加载器命名空间

表的规模为 **165 个类**（客户端实际解析的规范面；1.8.9–26.3 共 66 张表，26.3 resolved 407 / absent 103）。每个类/成员带
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
| 1.8.9 | 混淆 | SRG+MCP CSV+人工别名 → +fabric/forge 命名空间 | A | `Display.update()V` | ✅ 已产出（193 具名成员；`--javap` 通过） |
| 1.12.2 | 混淆 | 同上（`vanilla1122` / `forge1122`） | A | `Display.update()V` | ✅ 已产出（189，`--javap` 通过） |
| 1.16.5 | 混淆 | ProGuard + MCPConfig + intermediary | B | `glfwSwapBuffers(J)V` | ✅ 已产出（212，`--javap` 通过） |
| 1.21.4 | ✅ **真机已实测**（BMWClient 整合包 + Fabric：`backend=gui-extractor`、`Screen.render` 钩子织入；我方 UI 渲染正常，被整合包自带 LiquidBounce 菜单覆盖） | ✅ | ✅ 已产出 | ✅ 可见（三列面板 + 中文标签） |
| 1.21.10 | ✅ **真机已实测**（`1.21.10-opal` + Fabric：`class_437.method_25394` 织入 → `backend=gui-extractor` → `first frame 427x240, font=class_327`；`overlay active`） | ✅ | ✅ 已产出 | ✅ 可见（三列 + 文字） |
| 1.21.11 | ✅ **真机已实测**（`class_437.method_25394` 织入 → `backend=gui-extractor` → `first frame 427x240, font=class_327`；`FaClickGui` 完整显示，文字按设计字号缩放） | ✅ | ✅ 已产出 | ✅ 可见（三列 + 中文标签，无截断） |
| 1.20.1 | 混淆 | 同上 | B | `glfwSwapBuffers(J)V` | ✅ 已产出（219，`--javap` 通过） |
| 1.21.4 | 混淆 | 同上 | B | `glfwSwapBuffers(J)V` | ✅ 已产出（219，`--javap` 通过） |
| 1.21.10 | 混淆 | 同上 | B | `glfwSwapBuffers(J)V` | ✅ 已产出（221，`--javap` 通过） |
| 1.21.11 | 混淆 | 同上 | B | `glfwSwapBuffers(J)V` | ✅ 已产出（220，`--javap` 通过） |
| 26.2 | 未混淆 | 恒等（+ 逐成员存在性校验） | C | `SDL_GL_SwapWindow(J)Z` | ✅ 已产出（221，`--javap` 通过） |
| 26.3 | 未混淆 | 恒等（+ 逐成员存在性校验） | C | `SDL_GL_SwapWindow(J)Z` | ✅ 已产出（221，`--javap` 通过） |

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

| 能力 | 代际 A（1.8.9/1.12.2） | 代际 B（1.13–1.21.x） | 代际 C（26.1–26.3） |
|---|---|---|---|
| **UI 绘制后端** | `gl-fixed`（固定管线） | 1.13–1.19 = `gl-core`（`ModernRenderer`；1.16–1.19 默认走它——`gui-stack` 只有 gl-core 绑不上核心 profile 时才用，`fill` 整数像素矩形 + 位图字体缩放导致圆角阶梯与方块字）；1.20–1.21 = `gui-graphics`（D2 sink；日志 `gui-graphics stack`，`backendName()` 仍报 `gui-extractor`；1.21.1 attach 与 1.21.11 的历史实测仍是 `gl-core`） | `gui-extractor` |
| 矩形 | `Gui.drawRect` | 1.16–1.19 `GuiComponent.fill(PoseStack,…)`；1.20–1.21 `GuiGraphics.fill` | `GuiGraphicsExtractor.fill` |
| 文字 | `FontRenderer.drawString`（自绘） | `drawString(PoseStack,Font,…)` / `GuiGraphics.drawString(Font,…)` | `extractor.text(Font,…)`（字体不自绘） |
| 字体度量 | `getStringWidth` / 行高 9 | `Font.width` | `Font.width` / `lineHeight` |
| 裁剪 | 直接 GL `glScissor` | 1.19.1+ `GuiComponent.enableScissor(x1,y1,x2,y2)`；1.16–1.18 无该原语，回退 `RenderSystem.enableScissor(x,y,w,h)`（静态） | `enableScissor/disableScissor` |
| 按键 | `KeyBinding` + LWJGL2 `Keyboard` | `KeyMapping` / `InputConstants.Type.KEYSYM.getOrCreate(vk)` | `InputConstants.isKeyDown` |
| 鼠标 | LWJGL2 `Mouse` | `MouseHandler.xpos()/ypos()` | 同左 |

### 3.1 1.16–1.19 `GuiComponent` 成员可用性（映射表实测）

数据直接读 `client/src/main/resources/mappings-<版本>.json` 的 `net/minecraft/client/gui/GuiComponent.methods`；
`absent` 是生成器标注的「该版本运行时没有此成员」。`drawCenteredString` 未进 `tools/mapping/requirements.txt`，
表里没有该项（1.16.5 实测为 `a`，与 `fill` 同名，这正是绑定必须先查映射名、不能只按签名匹配的原因）。

| 版本 | `fill` | `drawString` | `drawCenteredString` | `enableScissor` | `disableScissor` |
|---|---|---|---|---|---|
| 1.16 | `a` | `c` | 未纳入表 | `absent` | `absent` |
| 1.16.1 | `a` | `c` | 未纳入表 | `absent` | `absent` |
| 1.16.2 | `a` | `b` | 未纳入表 | `absent` | `absent` |
| 1.16.3 | `a` | `b` | 未纳入表 | `absent` | `absent` |
| 1.16.4 | `a` | `b` | 未纳入表 | `absent` | `absent` |
| 1.16.5 | `a` | `b` | 未纳入表 | `absent` | `absent` |
| 1.17 | `a` | `b` | 未纳入表 | `absent` | `absent` |
| 1.17.1 | `a` | `b` | 未纳入表 | `absent` | `absent` |
| 1.18 | `a` | `b` | 未纳入表 | `absent` | `absent` |
| 1.18.1 | `a` | `b` | 未纳入表 | `absent` | `absent` |
| 1.18.2 | `a` | `b` | 未纳入表 | `absent` | `absent` |
| 1.19 | `a` | `b` | 未纳入表 | `absent` | `absent` |
| 1.19.1 | `a` | `b` | 未纳入表 | `a` | `l` |
| 1.19.2 | `a` | `b` | 未纳入表 | `a` | `l` |
| 1.19.3 | `a` | `b` | 未纳入表 | `a` | `l` |
| 1.19.4 | `a` | `b` | 未纳入表 | `a` | `l` |

### 3.2 输入钩子矩阵

| 目标 | 织入方式 | 关键约束 |
|---|---|---|
| `KeyboardInput#tick()V` | 头部 `inputTickCapture(Object)` + 末尾无参 `inputTick()`（`InputTickHookTransformer`） | 1.16.5 的 `RETURN` 处 StackMapTable 会把槽 0 裁成 `top`；末尾 `aload 0` 会触发 `VerifyError: Bad local variable type`。多返回点重复消费为空操作。 |
| `EntityRenderDispatcher#render(...)` | 头部 `chamsPre(Object)` 捕获实体 + 末尾无参 `chamsPost()`（双端 `CallbackHookTransformer`） | 实体形参在末尾可能被裁成 `top`；禁止单点末尾 `aload`。 |
| `Hud` / `Screen` 绘制入口 | 头部捕获绘制上下文 + 末尾无参消费 | 1.16–1.19 的 `PoseStack` 与 26.x 的 `GuiGraphicsExtractor` 同一规则；单点末尾注入已从注册路径移除。 |
| `KeyMapping#setAll/set/click` | `InputBlockTransformer` | 映射表必须带 `setAll()V`、`set(InputConstants$Key,boolean)`、`click(InputConstants$Key)` 的三命名空间描述符；注册按所有类名/方法名/描述符组合尝试。 |

## 4. 验收状态

| 版本 | 注入 | 帧钩子 | 表 | 界面可见（真机） |
|---|---|---|---|---|
| 1.8.9 | ✅ **真机已实测**（官方 1.8.9 + LWJGL2：attach → `agentmain` → 帧钩子 live → 叠加层 attach → 右 Shift 开/关，四个分类面板与模块名正常显示；`tmp/mc189*.log`） | ✅ | ✅ 已有表 | ✅ 可见（`backend=gl-fixed` 固定管线、`screen=ClickGui`（旧界面）；P5 输入：右 Shift 唤出 ✅ / Esc 关闭 ✅ / 鼠标点选 ✅ / 滚轮 ✅（2026-11-08 真人验收，@2d6c6a6；此前合成事件进不了 LWJGL2 队列，只能走读派发链）；2026-11-08 复验：`openGui=true` 进世界后 `inGameHasFocus=false`，`MouseHelper#mouseXYChange` 与 `Inventory#changeCurrentItem` 均 `patched=true`，视角/快捷栏输入链已短路；@2d6c6a6 再验：开/关/重开正常、鼠标/滚轮/键盘不穿透、关闭后恢复、Esc 关闭自绘 GUI 不弹暂停菜单） |
| 1.16.5 | ✅ **真机已实测**（官方 1.16.5 + LWJGL3；**默认 `backend=gl-core`**；D3.1 性能收尾后：同色几何合批 `draw/s=1056`、相机按 partialTicks 插值、类解析走桥缓存、陷阱箱判定按方块实体缓存）（2026-11-03 起：`gui-stack` 观感明显粗糙——圆角 1px 阶梯、位图字体缩放方块字，因此降为 gl-core 不可用时的兜底，`-Dnocturne.ui.backend=game` 可强制）；`gui-stack` 绑定亦已验收（`dkv fill=a text=b scissor=rendersystem poseScale=true`）） | ✅ | ✅ 已产出 | ✅ 可见可点（`backend=gl-core`；滚轮经 `MouseHandler#onScroll` 织入 `ScrollState`，界面开着时物品栏不再跟着滚；点击不再穿透挥手臂；2026-11-06 复验：`KeyboardInput#tick` 改为头捕获/尾无参后进世界不再 `VerifyError`，INSERT 可开中文面板） |
| 26.2 | ✅ **真机已实测**（修复后） | ✅ 帧钩子按设计不注册（GLFW 交换点只驱动输入轮询） | ✅ 已产出 | ✅ 可见可点（`backend=gui-extractor; input=glfw`；`GuiDrawHook` 织入成功；截图 `docs/research/fatality-26.2-fixed.png`） |
| 26.2（Vulkan） | ⏳ | 未验 | ✅ 已产出 | ⏳ 待真机（26.2 的图形 API 需在游戏设置里手动切换，本次实测的是 OpenGL 路径） |
| 26.3 | ✅ 已实测（真实 26.3 + Fabric：attach → agentmain → 引导完成，游戏稳定不崩） | ⏳ 字节码级已验证（SDL 目标；SDL 栈下按设计不注册帧钩子，绘制由 GUI 绘制钩子驱动） | ✅ 已产出 | ✅ 可见可点（`backend=gui-extractor`、`input=game`、`screen=ClickGui`；三列面板与模块名正常，点击 `FullBright` 行状态翻转；2026-10-29 原版 26.3 复验浮点字形矩阵与线性采样生效；2026-11-08 已补 `MouseHandler#turnPlayer(D)V`/`onScroll(JDD)V` 输入短路；@2d6c6a6 真机复验：`sink=true`、`grabMouse`/`turnPlayer`/`onScroll`/SDL 滚轮均 `patched=true`，GUI 开/关/重开正常，鼠标/滚轮/键盘不穿透，关闭后恢复，原生暂停菜单不再遮挡自绘 GUI，`disableScissor` 的 `InvocationTargetException` 出现 0 次） |

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
>   → `ScrollState`（bootstrap 层中转）→ `GameInput.scrollDelta()` 每帧取走；同时短路
>   `MouseHandler#onScroll`，否则界面开着时滚轮会顺带切快捷栏。
> - **2026-11-01 滚轮修复**：26.3 的 Knot 给 `ScrollHook` 再加载一份 `ScrollState`，与叠加层的
>   bootstrap 份静态字段分裂（hook class id 1820305762 / UI id 972588132）。现在 `ScrollState`
>   只做 API 转发，真正状态放在只在 bootstrap 存在的 `KeySink`；真机日志变为
>   `scroll wheel event seen: y=...` → `scroll delta consumed: ...`，面板实际滚动。
> - **2026-11-01 性能修复**：26.3 开 NameTags 从 100+ 掉到 11 帧。JFR 采样显示热点不是文字绘制，而是
>   `GameBridge.inferParameters` 每个实体、每一帧重新扫描继承链方法表（`collectMethods`/`signature`
>   占满 Render thread）。NameTags 同时做了一遍 `onRender` 计数和一遍绘制；现在去掉重复扫描、
>   按类缓存方法表，并把 LivingEntity 解析与玩家坐标缓存/复用。同场景真机回到 101 帧。
> - **2026-11-01 D2/DrawPlan**：`PlannedBackend` 重新接入 extractor；文本/渐变/玩家模型前先 flush 几何，
>   防止底色盖字。26.3 真机 GUI 正常。1.20.1 修正 HUD 映射（vanilla `eow`）与描述符
>   `eow.render(Leox;F)V`；`attackPost` 的 chop 帧末尾注入改为安全跳过，世界不再 VerifyError。
>   1.20.1 的 GUI 可见性仍缺一次真人键盘验收（合成键盘到不了 GLFW）。
> - 原生 Screen（暂停菜单/主菜单/聊天）出现时停止绘制自绘 GUI 与世界覆盖层；NameTags 不再盖住
>   “回到游戏”菜单。
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
> 被从 `selectBackend` 移除。**这一行记录的是 Skija 启用当时的状态，未按当前代码在 1.16.5 上重新实测**。
> **2026-11-02 再订正**：1.13+ 不再一律走 `gl-core` —— 1.16–1.19 已改为 `gui-stack`
> （`GuiComponent.fill/drawString` + 游戏 `PoseStack`），只有 1.13–1.15 回落 `gl-core`；见 §3 与 §4。

> **「注入」列指什么**：目前唯一跑通的是 LWJGL2 实验靶（`tmp/lab189/Fake189v5`：真实 Java 8 +
> 真实 LWJGL2 + 真实 OpenGL 4.6，320×240 空白窗口，120 帧 `Display.update()`），**靶内没有 Minecraft**。
> 它证明的是 attach → `agentmain` → 帧钩子 → 叠加层这条链路在 Java 8 栈上成立；**不证明**任何
> Minecraft 相关行为（FontRenderer / `Gui.drawRect` / Options / player / world / 界面可见性）。

---

## 附：Fatality ClickGUI 移植（代码已落地；1.8.9 / 1.16.5 / 1.21.1 / 1.21.11 / 26.3 已真机验收，其余待验）

把界面从「Skija 直绘 + 移植自 Setsuna 的控件」换成「自绘 Fatality 风格 ClickGUI」，并**删除整个旧 UI 库**。
代码与离屏验证已完成，**真机矩阵部分重跑**——下面这张表里 ⏳ 的格子才是待验清单（每格要求：注入成功 →
`[nocturne] ... backend=<name>` 日志行 + 一张截图）。

| 版本 | loader | 期望后端 | 状态 |
|---|---|---|---|
| 1.8.9 | 原版 / Forge | `gl-fixed` | ✅ **已实测 2026-11-08 @2d6c6a6**（`backend=gl-fixed; input=lwjgl2`，`overlay active; screen=FaClickGui`；开/关/重开正常，鼠标/滚轮/键盘不穿透，Esc 关闭不弹暂停菜单） |
| 1.12.2 | Forge | `gl-fixed` | ⏳ 待真机 |
| 1.16.5 | Forge | `gl-core`（1.13–1.19；`gui-stack` 仅兜底） | ✅ 真机已验收 2026-11-03（gl-core 渲染；`gui-stack` 绑定亦验证 `scissor=rendersystem poseScale=true`） |
| 1.20.1 | Forge / Fabric | `gui-graphics`（1.20–1.21） | ⏳ 待真机（旧记录为 `gl-core`） |
| 1.21.1 | Fabric mod | `gui-extractor` | ✅ **已实测**（Mixin 绘制/输入钩子织入；修复 `NativeImage` 原图写入、独立 UV 区域的 11 参 `blit`、2× 栅格密度与 alpha 混合后，文字和柔和阴影正常；滚轮走 `ScrollState`，日志确认 `view.scroll` 从 34 连续变化到 404 且物品栏不滚动。隔离实例诊断构建验证 Esc 关闭自绘 GUI、原生暂停菜单返回游戏及右 Shift 重开；原生 Screen 与自绘 GUI 同时打开时鼠标拦截为 `false`，暂停菜单上重开自绘 GUI 后，Back to Game 已实机点击返回，原生输入未被拦截；返回按压在松开前不再穿透到自绘 GUI，后续新点击正常；左 Shift（GLFW 340）不再误触发右 Shift（GLFW 344）快捷键。颜色选择器二维渐变已复验无横条；Fabric HUD 改为 Gui.render 捕获 + GameRenderer 深度清理后绘制，准星与快捷栏不再覆盖 GUI，颜色弹窗和滚轮均已复验） |
| 1.21.8 | Fabric / NeoForge | `gui-graphics`（1.20–1.21） | ⏳ 待真机（旧记录为 `gl-core`） |
| 1.21.11 | Fabric | `gui-graphics`（旧实测 `gl-core`） | ✅ **已实测（旧路径）**；新 sink **待复验**（Fabric 0.19.5 + LWJGL 3.3.3 + NVIDIA 610.74：注入 → `backend=gl-core; input=glfw` → `overlay active; screen=FaClickGui`，新界面三列渲染正常、颜色 chip/滑条/图集文字都在；截图 `docs/research/fatality-1.21.11-glcore.png`。**首轮注入直接 `nvoglv64` ACCESS_VIOLATION 崩 JVM**，根因与修复见下方 P4-E） |
| 26.1 | Fabric / NeoForge | `gui-extractor` | ⏳ 待真机 |
| 26.2 | Fabric / NeoForge | `gui-extractor` | ⏳ 待真机（OpenGL 与 Vulkan 各一次） |
| 26.3 | Fabric / NeoForge | `gui-extractor` | ✅ **已实测 2026-11-08 @2d6c6a6**（`backend=gui-extractor; input=game`，`sink=true`、输入钩子 `patched=true`，原生暂停菜单不遮挡；绘制走游戏 extractor，OpenGL 已移除只剩 Vulkan，帧钩子按设计不注册） |

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
| 原版槽位选择器、按键绑定行、子设置展开、文本/区间/多选设置行 | 已移植并完成离屏验收 | 对应 `TextValue / BoundsNumberValue / SlotValue / KeyValue / GroupValue / MultiBoolValue` 与实际 Fatality 控件已落地；每个模块基类自带 `Bind` 行，捕获完整 AWT VK 表（字母/F 键/Insert 等），热键由 `ModuleKeybinds` 统一分发 |
| 物品图标（预览卡的装备行） | 未移植 | 物品渲染通路仍未实现，与设置值/GUI 可见性无关 |
| HUD / HUD 编辑器 | 已随旧 UI 库删除 | 用户确认删除；需要时按新绘制层重写 |
