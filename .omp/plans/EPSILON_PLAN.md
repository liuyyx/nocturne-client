# Epsilon 视觉模块全量移植

## Context

上游 `C:\Users\liuyyx\Desktop\01-我的世界\eps-plus\Epsilon eps plus\Epsilon`（GPL-3.0，Fabric/NeoForge，MC 26.2）的视觉部分包含 `common/src/main/java/com/github/epsilon/modules/impl/render/` 下 41 个模块、`maseffects/` 里 1 个模块（+1 个渲染器），以及 `elements/impl/` 下 9 个 HUD 元素。本仓库 `nocturne-client` 是注入式客户端（Java 8 字节码、Mojmap 规范名 + 66 版映射表、零 GL 的自绘覆盖层），模块层刚被清空、只留 `Kill Aura` 一个已移植模块（`client/src/main/java/dev/nocturne/client/module/modules/combat/KillAuraModule.java` 是移植风格样板）。

目标：把上述 42 个视觉模块与 9 个 HUD 元素全部落到 `Category.RENDER`（`Title Replace` 按上游落 `Category.PLAYER`），沿用上游模块名/设置名/默认值/取值范围；凡是本仓库没有的能力（GL shader、主 framebuffer 后处理、原版聊天/侧边栏/快捷栏/物品模型绘制钩子、实体模型提交管线、纹理资源），一律按后文「降级矩阵」用覆盖层/描边/文字近似实现，不新增死开关。

## 决定（实现者不要重新选）

1. **只画 2D**：所有世界空间视觉都实现 `WorldOverlay`，用 `WorldProjection.project` 把世界坐标投到逻辑像素，再由 `OverlayDraw` 画线/矩形/文字。`World3DOverlay` / `World3DBatch` **不用**：`World3DCapability` 只在 1.8.9–1.12.2 固定管线可用（`ui/src/main/java/dev/nocturne/ui/gl/GuiOverlay.java#installWorld3DBackend`），用它写模块等于在 1.13+ 上全灭。
2. **填充近似**：没有面填充能力。盒类模块统一「12 边描边 + 投影剪影矩形」：剪影矩形取 8 个角投影后的屏幕 min/max，用低 alpha `draw.rect` 铺一层，再压描边。球/锥/圆环一律用 `WorldGeometry` 的圆环与线段逼近。
3. **零分配**：每帧/每 tick 的循环里不 new（`WorldProjection.ScreenPoint` 复用、颜色用 int 运算、字符串只在开关变化或数据源变化时构造）。
4. **设置保留规则**：上游设置项只要能在本仓库真正接线就保留原名、原默认值、原范围/步长；**不能接线的设置项直接删掉**（绝不注册“开了没反应”的开关），并在模块 javadoc 的「上游降级」段写明删了哪几项、为什么。值类型映射：Bool→`BooleanValue`、Double→`NumberValue`（双精度）、Int→`NumberValue`（步长 1，读 `asInt()`）、Color→`ColorValue`（`0xAARRGGBB`）、Enum→`ModeValue`、String→`TextValue`、StringList/RegistryList→`MultiBoolValue`（固定选项集合）。
5. **不新增 Mixin**。Fabric 通道的 Mixin 只服务 HUD/Screen 绘制，视觉模块一律走 `GameplayEvents` 既有钩子或新增的**相机对齐回调钩子**（见阶段 0）。
6. **能力不可用即不装**：模块在 `onStart()` 里先查 `GameplayHook.isWired("…")` / `bridge().mapping().isMemberAbsent(...)`；不可用时打一条 `[nocturne] <模块>: … unavailable on this version (<reason>)` 并让本模块所有绘制/行为早退，**不改用户开关**（与 `XrayModule` 既有自检一致）。
7. **提交纪律**：每个阶段一个中文 scoped conventional commit（`feat(render): 移植 …`），阶段末必须 `gradlew.bat build` 绿；真机结论只写 `docs/VERSION-MATRIX.md`，未跑过的版本不得声称通过。
8. **许可**：Epsilon 为 GPL-3.0，与本仓库 GPL-3.0-or-later 兼容。阶段 0 在 `THIRD-PARTY-NOTICES.md` 追加 Epsilon 条目（仓库名、许可证、被移植的包与文件范围、修改说明：Mixin/事件/设置系统→注入式 ASM 钩子 + 自绘覆盖层）。不拷贝 `com.github.epsilon.*` 包路径与注释原文，注释一律新写中文。

## 阶段 0：公共设施（先做，后续全部依赖）

### 0.1 `WorldGeometry` 增补（`client/src/main/java/dev/nocturne/client/render/WorldGeometry.java`）

新增三个静态方法，循环内不分配：

```java
public static void silhouette(OverlayDraw draw, WorldProjection projection,
        double minX, double minY, double minZ, double maxX, double maxY, double maxZ, int argb);
public static void verticalBox(OverlayDraw draw, WorldProjection projection,
        double centerX, double minY, double centerZ, double radius, double height, int segments, float width, int argb);
public static void label(OverlayDraw draw, WorldProjection projection, String text,
        double x, double y, double z, float size, int argb);
```

- `silhouette`：复用已有的 `BOX_POINTS` 8 点投影（同一份静态数组），跳过 `!visible` 点；全部不可见时直接返回；否则 `draw.rect(minX,minY,maxX-minX,maxY-minY,argb)`。
- `verticalBox`：画上下两个水平圆环 + 4 条母线（`circle` 复用）。
- `label`：`project` 后 `draw.text(text, p.x, p.y, size, argb)`；不可见则跳过。

### 0.2 `RenderSupport`（新文件 `client/src/main/java/dev/nocturne/client/render/RenderSupport.java`）

```java
public final class RenderSupport {
    public interface EntityVisitor { void visit(Object entity); }
    public static void forEachEntity(GameBridge bridge, EntityVisitor visitor);   // 兼容 Iterable / Object[] / 标量
    public static enum Kind { PLAYER, MONSTER, ANIMAL, AMBIENT, OTHER }
    public static Kind kind(GameBridge bridge, Object entity);                     // 用 ClassType 判型（PLAYER→PLAYER，
                                                                                   // MONSTER→MONSTER，ANIMAL/WATER_MOB/VILLAGER→ANIMAL，
                                                                                   // AMBIENT→AMBIENT，其余→OTHER）
    public static int withAlpha(int argb, float factor);                           // 乘 alpha，钳 0..255
    public static int blend(int from, int to, float t);                            // 逐通道插值，t 钳 0..1
    public static int hsv(int alpha, float hue, float saturation, float brightness); // Color.HSBtoRGB + alpha
    public static boolean isSelf(GameBridge bridge, Object entity);                // entity == bridge.player()
}
```

`forEachEntity` 直接从 `bridge.entities()` 取 `Iterable`/数组；不可迭代时静默空转（与 `GameBridge.entities()` 的 `null` 契约一致）。

### 0.3 HUD 元素通道（新增 4 个文件 + 2 处接线）

1. `client/src/main/java/dev/nocturne/client/hud/HudAnchor.java`：枚举 `TOP_LEFT, TOP_CENTER, TOP_RIGHT, CENTER_LEFT, CENTER, CENTER_RIGHT, BOTTOM_LEFT, BOTTOM_CENTER, BOTTOM_RIGHT`。
2. `client/src/main/java/dev/nocturne/client/hud/HudElement.java`：
   ```java
   public interface HudElement {
       String id();                       // 全局唯一，与模块名一致
       HudAnchor anchor();
       int order();                       // 同锚点内的堆叠顺序，小的靠边；默认 0
       float width(OverlayDraw draw);
       float height(OverlayDraw draw);
       void draw(OverlayDraw draw, float x, float y);   // x/y 为元素左上角（逻辑像素）
   }
   ```
3. `client/src/main/java/dev/nocturne/client/hud/HudElementSink.java`：
   ```java
   public interface HudElementSink {
       void addElement(HudElement element);
       void removeElement(String id);
       boolean hasElement(String id);
   }
   ```
4. `client/src/main/java/dev/nocturne/client/module/HudElementModule.java`：`abstract class HudElementModule extends Module`，与 `HudModule` 同构：
   - `protected abstract HudElement element();`
   - 覆写 `onEnable()/onDisable()`（`final`）：向 `NocturneClient.get().hudElementSink()` 注册/注销，汇缺失时留待补登记。
   - 覆写 `onHudElementSinkChanged(HudElementSink previous, HudElementSink current)`（`final`，在 `Module` 新增的空实现上）：迁移注册。
   - 与 `HudModule` 一样用模块对象锁串行化。
5. `NocturneClient` 增补：`volatile HudElementSink hudElementSink`、`hudElementSink()`、`setHudElementSink(HudElementSink)`（变化时遍历 `modules.all()` 调 `module.onHudElementSinkChanged(previous, current)`，逐模块 `catch (Throwable)` 并 `log("hud element sink migration failed …")`）。
6. `Module` 增补一个空方法 `public void onHudElementSinkChanged(HudElementSink previous, HudElementSink current) {}`。
7. `ui/src/main/java/dev/nocturne/ui/gl/GuiOverlay.java`：
   - `implements … dev.nocturne.client.hud.HudElementSink`；字段 `LinkedHashMap<String, HudElement> hudElements`。
   - 构造器里 `client.setHudElementSink(this)`（紧跟现有 `setHudSink(this)`）。
   - `drawFrame()` 在 `drawHudLines()` 之后调用 `drawHudElements()`。
   - `needsFrame(...)` 增加 `hasHudElements()` 入参（GUI 关、无世界覆盖层、无 HUD 文本，但有 HUD 元素时必须开绘制面）。
   - `drawHudElements()`：按 `anchor()` 分组，屏幕边距 6px、元素间距 4px；按 `order()` 升序再按注册顺序；`x/y` 由锚点与 `width()/height()` 算；单个元素 `try { element.draw(overlayDraw, x, y); } catch (Throwable)` 限流记录一次，不中断其它元素。

### 0.4 相机对齐回调钩子（解锁 Free Camera / Sneak Tweak / Camera Clip 的 Action 平滑）

约束（AGENTS.md §4.3）：`GateHookTransformer` 的**末尾**钩子在带 `StackMapTable` 的方法上不能读 `this`/形参（会 `VerifyError`），而 `Camera#setup` 必然带帧。因此用与 `chamsPre/chamsPost` 相同的「方法头捕获 + 末尾无参消费」两段式：

1. `client/src/main/java/dev/nocturne/client/bootstrap/GameplayHook.java`：
   - `Sink` 增加 `default void cameraAligned(Object camera) {}`。
   - 静态 `cameraBegin(Object camera)`：把相机对象压入 bootstrap 共享的 `ThreadLocal<ArrayDeque<Object>>`（与 `CHAMS_STACK` 同构，上限 4，`null` 用哨兵占位）。
   - 静态 `cameraEnd()`：弹出最近一个相机对象并调 `sink().cameraAligned(camera)`；栈空时无操作。
2. `agent/src/main/java/dev/nocturne/agent/NocturneAgent.java` → `registerCameraHooks(...)` 里追加（与 `Camera#getMaxZoom` 同一套 `classNameCandidates` / `methodCandidates` / `resolveDescriptor` 解析，目标 `ClassType.CAMERA` 的方法 `setup`）：
   - 头部：`registerGate(..., "setup", descriptor, "cameraBegin", "V", false /*atMethodEnd*/, true /*passThis*/)`。
   - 尾部：`registerGate(..., "setup", descriptor, "cameraEnd", "V", true /*atMethodEnd*/, false /*passThis*/)`（无参，符合末尾钩子约束）。
   - `Camera#setup` 缺失（1.8.9/1.12.2 无 `Camera` 类）时两条都会 `publishHookState("cameraAligned", false, reason)`，日志只打一次。
3. `client/src/main/java/dev/nocturne/client/render/CameraOverride.java`（新）：`public interface CameraOverride { void applyCamera(Object camera); }`。
4. `client/src/main/java/dev/nocturne/client/event/GameplayEvents.java`：实现 `cameraAligned(Object camera)` —— 遍历 `client.modules().all()`，对 `isEnabled() && instanceof CameraOverride` 的模块调 `applyCamera(camera)`，逐模块 `catch (Throwable)`（限流）。
5. 映射补充（`tools/mapping/requirements.txt`，按需逐条加、以 `generate.py --check` 结论为准）：
   ```
   net/minecraft/client/Camera#setup()
   net/minecraft/client/Camera#position
   net/minecraft/client/Camera#xRot
   net/minecraft/client/Camera#yRot
   net/minecraft/client/Camera#eyeHeight
   net/minecraft/client/Camera#eyeHeightOld
   ```

### 0.5 既有钩子重新接线（`GameplayEvents`，**按阶段增量**）

`05f4786` 删掉的分支按新类名接回来，但**必须与模块落地同阶段提交**，否则阶段 0 编译不过。最终形态（全部按模块名取模块 + `instanceof`，与旧实现同构）：

- `entityRenderGate(Object entity)`：`NoRenderModule.shouldRender(entity) && YsmModelModule.originalAllowed(entity)`；两者都不存在时返回 `true`。（阶段 4 加 `Ysm Model` 分支、阶段 6 加 `No Render` 分支）
- `xrayBlock(Object state, Object pos)`：`XrayModule.shouldRender(...)`。（阶段 2）
- `handTransform(Object pose, Object arm, float inv)`：`HandViewModule.apply(...)`、`TotemAnimationModule.apply(...)`。（阶段 7）
- `cameraZoom(float distance)`：`CameraClipModule.zoom(distance)`。（阶段 7）
- `noFog()`：`NoRenderModule.fog()` 与 `WorldTweaksModule.fog()` 任一开启则 `false`（真值语义是“允许雾”）。（阶段 6）
- `cameraAligned(Object camera)`：见 0.4，阶段 0 就装好，模块在阶段 7 才有实现。
- `destroyStop()`：签名从 `void` 改为 `boolean`（默认 `true`），`No Ghost Blocks` 的 `Breaking` 打开时返回 `false`。（阶段 2）

阶段 0 在 `GameplayEvents` 里只新增 `cameraAligned` 一条转发（不含任何模块分支），其余钩子按上表在对应阶段接入。

### 0.6 映射需求补充（`tools/mapping/requirements.txt`）

按模块实际读的成员逐条补（`scan.py` 只认直接字面量，包装方法必须手工补）：

```
net/minecraft/world/entity/LivingEntity#getArmorValue()
net/minecraft/world/entity/LivingEntity#getAbsorptionAmount()
net/minecraft/world/entity/Entity#onGround
net/minecraft/world/entity/Entity#isFallFlying()
net/minecraft/world/entity/Entity#isCrouching()
net/minecraft/world/entity/player/Player#isSprinting()
net/minecraft/world/entity/player/Player#isUsingItem()
net/minecraft/client/Options#gamma
net/minecraft/client/Options#bobView
net/minecraft/client/player/LocalPlayer#getInventory()
net/minecraft/world/level/Level#players()
net/minecraft/world/entity/item/PrimedTnt#getFuse()
net/minecraft/world/entity/Entity#getBoundingBox()
```

补完跑 `python tools/mapping/generate.py --check`（含 jar 交叉校验；不许用 `--fast` 当门禁）。

## 阶段 1：实体/世界 2D 覆盖层（8 个）

**文件布局**：42 个模块一律放 `client/src/main/java/dev/nocturne/client/module/modules/render/`；`ProjectileSimulator`、`BlockEntityScan` 这类包内辅助类同目录（包内可见，便于单测）；9 个 HUD 元素放 `client/src/main/java/dev/nocturne/client/module/modules/render/hud/`。测试分别放 `client/src/test/java/dev/nocturne/client/module/modules/render/`（镜像包路径）。

全部 `GameModule implements WorldOverlay`，`onTick` 只维护状态，绘制在 `drawWorldOverlay`。共用 `RenderSupport` / `WorldGeometry`。

| 模块（上游名 → 类名） | 设置（保留上游名/默认/范围） | 实现 |
|---|---|---|
| `ESP 2D` → `Esp2DModule` | Players=true、Friends=true、Creatures=false、Monsters=false、Ambients=false、Others=false、Render Health=true、Health Bar Width 2.0 [0.5,6] 0.5、Health Bar Outline=true、Health Bar Outline Width 1.0 [0.5,3] 0.5、Render Box=true、Box Outline=true、Players Color `0xFFFF9200`、Friends Color `0xFF30FF00`、Creatures Color `0xFFA0A4A6`、Monsters Color `0xFFFF0000`、Ambients Color `0xFF7B00FF`、Others Color `0xFFFF0062`、Health Color `0xFF2FFF00` | 逐实体 `entityPosition` + `entityWidth/entityHeight` 组成 AABB，8 点投影取屏幕 min/max（`WorldGeometry.silhouette` 的同一套算；此处需要四角，故在模块内保留 8 个复用 `ScreenPoint`），`boxOutline` 时先画 1px 黑描边再画类型色 1.2px 边；`renderHealth` 在框左侧画竖向血条（背景 `0x80000000`，填充 `Health Color`，比例 `health/maxHealth`，`healthBarOutline` 时外加描边）。Friends 判定统一用 `dev.nocturne.client.control.Friends.isFriend(bridge, entity)`（同名静态方法，名字来自 `bridge.entityName`），队友用 `bridge.isAllied(bridge.player(), entity)`；类别用 `RenderSupport.kind` + 开关。 |
| `Name Tags` → `NameTagsModule` | Range=128 [8,256]、Scale=0.75 [0.4,1.4] 0.05、Height Offset=0.5 [0,1.5] 0.05、Show Self=false、Friend Status=true、Team Status=true、Show Health=true、Outline=true、Name Color `0xF5F7F9FC`、Friend Color `0xF569E5A6`、Team Color `0xF574D296`、Health Color `0xF5D7DCE4`、Background Color `0xA50D1014`、Outline Color `0x1CFFFFFF` | 只处理 `ClassType.PLAYER`（不含自己，除 `Show Self`）：距离 ≤ Range；标签锚点 = 头顶 `y + height + Height Offset + 0.35`（比原版名牌高，避免与未被抑制的原版名牌重叠）；文本按 `name`（+ `❤ health` 当 `Show Health`，+ 好友/同队标记 `[F]`/`[T]`，分别用 `Friends.isFriend(bridge, entity)` 与 `bridge.isAllied(bridge.player(), entity)`）；`draw.roundedRect` 画背景、`outline` 描边、`text(size = 8 * Scale)` 画文字；宽度用 `draw.textWidth(text, size) + 6`。上游的图标字体/模糊/投影全部不做（见降级矩阵）。 |
| `Health` → `HealthModule` | 无 | 准星下方画血量数字：`String.format(Locale.ROOT, "%.1f", health/2)` + 吸收值（`♥` 用文本代替原版心形 sprite），颜色按 health/maxHealth 在 `0xFFFF5555`→`0xFFFFFF55`→`0xFF55FF55` 三段线性插值（`RenderSupport.blend`）；受伤 20 tick 内闪烁（`hurtTime`），血量 <30% 时按 `tickCount` 抖动 ±1px。位置：屏幕中心下方 `draw.height()*0.5 + 30`。 |
| `Jump Circle` → `JumpCircleModule` | Mode=Default/Portal（仅影响颜色：Default=青白、Portal=紫粉）、Ease Out=true、Rotate Speed=2.0 [0.5,5] 0.1、Circle Scale=1.0 [0.5,5] 0.1、Only Self=false | `onTick` 逐玩家比较 `onGround` 的下降沿（模块内保存上次状态 map：`UUID → Boolean`），落地瞬间在脚下 `y = floor(y)+0.001` 记一个圆环（起始时间、半径、色相偏移）；绘制期 5s 内按 `1 - (1-t)^4`（Ease Out）放大、alpha 线性淡出，用 `WorldGeometry.circle(..., 48, 2.0f, color)` 画两层（内圈反相旋转），四段圆弧色相随 `Rotate Speed` 偏移。上游的贴图四边形降级为圆环。 |
| `Hat` → `HatModule` | Mode=Astolfo/Sexy/Fade、Points=30 [3,180] 1、Size=0.5 [0.1,3] 0.1、Offset=2000 [0,5000] 100、Color `0xFFFFFFFF`、Second Color `0xFF000000`、Only Third Person=true | 对每个玩家（`Only Third Person` 时跳过第一人称视角，`bridge.perspective() == 0` 判）：以头顶点 `y + height - 0.15`（潜行再 -0.2）为锥顶，用 `Points` 个点组成底部圆环（半径 `Size`），画母线（`WorldGeometry.line`）+ 底部圆环；颜色按 `Mode`：Astolfo = 6 段固定色序循环、Sexy = `Color`/`Second Color` 交替、Fade = 两色按点序插值；相位随 `Dimension`-无关的 `tickCount` 与 `Offset` 推进；头部朝向用 `yRot` 做水平旋转（`cos/sin` 旋转每个点）。 |
| `Block Highlight` → `BlockHighlightModule` | Mode=Both/BothSide/Fill/FilledSide/Outline/OutlinedSide（默认 Outline）、Color `0x64FFFFFF`、Line Color `0xFFFFFFFF`、Line Width 2.0 [0,5] 0.5 | `actions().pick(4.5, 1.0f)` 取命中，`bridge.hitBlockPosition` 得坐标；`Both/Fill/Outline` 分别画“填充+描边 / 只填充 / 只描边”的整格；`BothSide/FilledSide/OutlinedSide` 用 `bridge.hitDirection(hit)` 取面，画该面的 4 条边（用 `Direction` 的 `getStepX/Y/Z` 反射出法向，缺失时退回整格）。 |
| `TNT Timer` → `TntTimerModule` | Danger Radius=7.0 [0.5,12] 0.1、Lethal Radius=4.0 [0.5,12] 0.1、Outer Thickness=0.12 [0.01,1] 0.01、Inner Thickness=0.16 [0.01,1] 0.01、Alpha=16 [16,255] 1、Segments=360 [16,360] 1、Sphere Rings=10 [2,64] 1、Filled Sphere=true、Wireframe Sphere=false、Pulse Near Explosion=true、Pulse Threshold=2.0 [0.2,10] 0.1、Pulse Amplitude=0.12 [0,0.5] 0.01、Pulse Speed=2.0 [0.2,8] 0.1 | `bridge.tntFuse(entity)`/20 得剩余秒，头顶 `draw.text` 写 `%.1fs`（`Size = 16 * 透视缩放`，缩放由投影点间距近似：用底部/顶部两点屏幕距离算）；危险球 = `Sphere Rings` 层水平圆环（半径 `Danger Radius * sqrt(1-(y/r)^2)`）+ 每层 `Segments` 等分刻度线，`Filled Sphere` 时每层用 `silhouette` 铺低 alpha 圆盘；内层用 `Inner Thickness` 颜色画致死半径环；`Pulse Near Explosion` 且剩余 < 阈值时半径乘 `1 + PulseAmplitude * sin(2π * PulseSpeed * t)`。颜色 red(`0xFFFF4040`)→yellow(`0xFFFFFF40`)→green(`0xFF40FF40`) 随 `fuse/maxFuse` 插值，alpha 用 `Alpha`。 |
| `Logout Spots` → `LogoutSpotsModule` | Notifications=true、Ignore Bots=true、Render Mode=Box（Model 选项删除）、Side Color `0x19FFFFFF`、Line Color `0x7FFFFFFF` | 监听 `PacketEvent(RECEIVE)`：`ClassType.CLIENTBOUND_PLAYER_INFO_UPDATE`（新增玩家）与 `CLIENTBOUND_PLAYER_INFO_REMOVE`（移除 → 快照）；快照存 `UUID → {name, x,y,z, health}`。绘制：剪影填充 + 外框 + 头顶 `name + " " + health + " " + (int)x + "/" + (int)z` 文本；玩家重新上线时移除记录并 `bridge.localMessage` 提示（`Notifications` 控制提示，不控制渲染）。`Ignore Bots`：名字含 `"NPC"`/`"BOT"`（忽略大小写）或不在 tab 列表（`bridge.onlinePlayerUuids`）时丢弃。 |

## 阶段 2：扫描类（4 个）

| 模块 → 类名 | 设置 | 实现 |
|---|---|---|
| `Block ESP` → `BlockEspModule` | Block List（MultiBool：Chest/Trapped Chest/Copper Chest/Ender Chest/Barrel/Shulker Box/Dyed Shulker Box，默认除 Copper 外全选）、Range=64 [1,128]、Side Color `0x1EA0D2FF`、Line Color `0xB4A0D2FF` | 扫描不用全方块遍历：每 1000ms 遍历已加载区块的方块实体（复用 `BlockEntityScan` 的做法，新写 `client/module/modules/render/BlockEntityScan.java` 的等价实现，按 `GET_BLOCK_ENTITIES` 缓存 50ms）→ 按 `isInstance` 判 CHEST/ENDER_CHEST/BARREL/SHULKER 类型 + `blockDescription` 匹配方块名过滤 → 记录 `int[]{x,y,z}`；玩家距离 ≤ Range。绘制每格：`silhouette` + `box`。上游 `Illegals`、`Blur`、`Blur Strength` 删除（`Illegals` 依赖原版视野剔除逻辑，本仓库没有）。 |
| `Hole ESP` → `HoleEspModule` | Start Fade=5.0 [1,20] 0.1、Distance=6.0 [1,20] 0.1、Height=1.0 [-3,3] 0.1、Update Delay=50 [0,1000] 10、Air Height=1.0 [-3,3] 0.01、Air Fill=true、Air Fill Color `0x64940000`、Air Fade=true、Air Fade Color `0x00940000`、Air Box=true、Air Box Color `0x64940000`、Unsafe Fill=true、`0x32FF0000`、Unsafe Fade `0x00FF0000`、Unsafe Box `0x64FF0000`、Safe Fill `0x3208FF4F`、Safe Fade `0x6408FF4F`、Safe Box `0x6408FF4F`、Wall Height=3.0 [-3,3] 0.1、Wall Fill `0x8000FFFF`、Wall Fade `0x4000FFFF`、Wall Box `0xFF00E1FF`、Wall Side Fill `0x8000FFFF`、Wall Side Fade `0x4000FFFF`、Wall Side Box `0xFF00E1FF` | 扫描（`Update Delay` 毫秒节流）：玩家为中心、半径 `ceil(Distance)+1`、y 范围 `eyeY±3`；`isHoleCell(x,y,z)` = 脚下方块有碰撞且非空气 + 该格与上方两格空气；洞型：单格、X 双格、Z 双格、2×2 四格，各生成一个由 4 角围成的盒（`Height` 控盒高）；`isSafeHole` = 下方与四侧全是基岩（按 `blockDescription` 含 `bedrock` 判定，1.8.9 的 `bedrock` 同样命中）；wall = 三连基岩 + 侧向空气，wall 盒高用 `Wall Height`。结果存 `int[]{x0,y,z0,x1,z1,type}` 列表，绘制时按距离 alpha 淡出（`Start Fade` 内 1→0，`Distance` 外跳过），`Fill` 用 `silhouette`（`Fade` 时用双色：顶部实色、底部 fade 色），`Box` 用 `box`。`Air*` 只画头顶 `Air Height` 格的空气标记。上游 `Air Y Check` 与 `Side Check` 删除（`Air Y Check` 依赖上游按玩家 y 分段的洞判定、`Side Check` 依赖上游 wall 侧向朝向枚举，本仓库按上述固定规则判定，避免无效果的开关）。 |
| `Xray` → `XrayModule` | Plugin=Old/New（默认 New）、WallHack=false、Fast=false、Delay=25 [1,100] 1、Radius=5 [1,64] 1、Up=5 [1,32] 1、Down=5 [1,32] 1、Netherite/Diamond/Gold/Iron/Emerald/Redstone/Lapis/Coal/Quartz/Water/Lava=false | 保留 WallHack 走既有 `xrayBlock` 钩子：`shouldRender(state,pos)` 放行选中矿/水/熔岩，其余剔除；钩子未接线（`GameplayHook.isWired("xrayBlock") == false`）时日志说明并只画矿框。矿框：每 `Delay` 毫秒按 `Radius/Up/Down` 用 `blockStateAt` 逐格扫 `blockDescription` 匹配（`netherite_scrap`/`ancient_debris` 等），记录坐标列表，绘制 `box`+`silhouette`；`Fast=true` 时扫描格距从 1 改为 2。上游 `Ore Deobf` 删除（依赖 `gameMode.startDestroyBlock` 发包探测，本仓库没有对应动作面）。开关变化与 `onStart/onStop` 时 `bridge.reloadChunks()`。 |
| `No Ghost Blocks` → `NoGhostBlocksModule` | Breaking=true | 只接 `Breaking`（实现走 `MultiPlayerGameMode#destroyBlock` 布尔门：`GameplayHook.Sink.destroyBlock(Object)` 返回 `false` 抑制客户端预测移除，`NocturneAgent.registerGate(..., "destroyBlock", "Z", ...)` 织入，`GameplayEvents.destroyBlock` 查模块 `shouldSuppressDestroy()`）。原计划设想的 `destroyStop()` boolean 形态未落地：`stopDestroyBlock` 仍是 void 钩子，仅投递 `BlockEvent.STOP`（无订阅者），不记为等价路径。上游 `Placing` 需要 `BlockItem#placeBlock` 注入点，本仓库没有 → 删除该设置并在 javadoc 记录。 |

## 阶段 3：弹道（1 个）

| 模块 → 类名 | 设置 | 实现 |
|---|---|---|
| `Trajectories` → `TrajectoriesModule` | Items（MultiBool：Bow/Crossbow/Trident/Fishing Rod/Snowball/Egg/Ender Pearl/Experience Bottle/Splash Potion/Lingering Potion/Wind Charge，默认全选）、Other Players=true、Fired Projectiles=false、Ignore Wither Skulls=true、Simulation Steps=500 [0,5000] 25、Ignore First Ticks=3 [0,20] 1、Line Color `0xF5FF9600`、Line Width=2.0 [0.5,5] 0.25、Impact Fill=true、Impact Outline=true、Impact Fill Color `0x2DFF9600`、Impact Outline Color `0xF5FFB437`、Render Position Boxes=false、Position Box Size=0.02 [0.01,0.1] 0.005、Position Fill Color `0x1CFF9600`、Position Outline Color `0xB4FFB437` | 新写 `client/module/modules/render/ProjectileSimulator.java`（纯 Java，无反射）：常量表按物品描述 id 给初速（bow 3.0 / crossbow 3.15 / trident 2.5 / fishing rod 1.5 / snowball·egg·pearl 1.5 / exp bottle 0.7 / potion 0.5 / wind charge 1.5）、重力 0.05、阻力 0.99、箭类 0.99 + 重力 0.05、水中阻力 0.6；`step()` 逐 tick 更新位置并按 `bridge.hasBlockCollision(blockStateAt(...))` 判命中（取未命中前的最后位置作为 impact）。触发：`Other Players=true` 时遍历其他玩家主手/副手（`bridge.heldItem`、`bridge.usingItem` 充能判断用 `usingTicks`）；`Fired Projectiles=true` 时遍历 `ABSTRACT_ARROW`/`THROWABLE_PROJECTILE`/`FIREBALL` 实体，用 `entityVelocity` 初始化。绘制：每 `Ignore First Ticks` 之后每步取位置，用 `WorldGeometry.line(..., Line Width, Line Color)` 连成折线；`Render Position Boxes` 时每步画 `Position Box Size` 立方；命中处画 3 轴压扁 0.002 的十字（`Impact Fill` 用 `silhouette`，`Impact Outline` 用 `box`）。模拟节流：每 tick 只重算一次，把结果点存 `double[]` 复用数组。 |

## 阶段 4：实体外观（4 个）

| 模块 → 类名 | 设置 | 实现 / 降级 |
|---|---|---|
| `Chams` → `ChamsModule` | Players=true、Self=true、Friends=true、Crystals=true、Chests=true、Creatures=false、Monsters=false、Ambients=false、Others=false | 上游改写 RenderType 做“透视染色”；本仓库无实体渲染替换能力，**降级为透视描边 + 剪影**：对通过筛选的实体（`RenderSupport.kind` + `Friends.isFriend(bridge, entity)` / `bridge.isAllied` 判定 + `END_CRYSTAL`；`Chests` 用阶段 2 的方块实体扫描结果）画 `silhouette`（`0x33FFFFFF`）与 `box`（按类别色：玩家白、水晶紫、箱子青、其它灰）。实体筛选函数 `accept(GameBridge, Object)` 供 `NoRender` 复用。 |
| `Crystal Chams` → `CrystalChamsModule` | Side Color `0x46A078FF`、Line Color `0xDCE6DCFF`、Scale=1.0 [0.1,3] 0.05、Line Width=2.0 [0.5,5] 0.5 | 遍历 `END_CRYSTAL` 实体：以实体位置为中心画 `Scale` 缩放的竖向盒（宽高 = 2×0.98×Scale，`WorldGeometry.verticalBox` 6 段）+ 剪影填充；不再隐藏原版水晶（需要渲染替换钩子，javadoc 记录）。 |
| `Pop Chams` → `PopChamsModule` | Ignore Self=true、Render Time=1.0 [0.1,6] 0.1、Y Modifier=0.75 [-4,4] 0.05、Scale Modifier=-0.25 [-4,4] 0.05、Fade Out=true、Side Color `0x19FFFFFF`、Line Color `0x7FFFFFFF` | 监听 `PacketEvent(RECEIVE)` 的 `CLIENTBOUND_ENTITY_EVENT`：`bridge.entityEventData(packet)` 得 `{entityId, eventId}`，`eventId == 35`（`PROTECTED_FROM_DEATH`，26.x 同名常量；1.8.9 也是 35）时按 `entityId` 在世界实体里找对应玩家（`bridge.entityId(entity)`），快照 `{x,y,z,width,height,startMs}`。绘制：`Render Time` 内剪影盒按 `y = y0 + Y Modifier * progress` 上浮、宽高乘 `1 + Scale Modifier * progress`，`Fade Out` 时 alpha 乘 `1-progress`。上限 32 条快照，超出丢最旧。 |
| `Ysm Model` → `YsmModelModule` | Model=Auto/Alice/Boqijiang/ToyotaAe86/Yatuoya/Custom、Custom Path=""、Scale=1.0 [0.1,5] 0.05、Animation=Vanilla/OpenYsm、Bone Filter=""、Character Only=true、Show Item=true、Item Scale=1.0 [0.1,5] 0.05、Flip Left Walk=false、Flip Right Walk=false、Flip Other Anim=false | 上游的 YSM 二进制/Zstd/XChaCha 解码与 `SubmitNodeCollector` 模型替换**不移植**。落地方案：`Custom Path` 指向的 **Bedrock 几何 JSON**（`minecraft:geometry[].bones[].cubes[].origin/size`）用 `client/util/MiniJson` 解析（上游 legacy JSON 路径的等价物）；内置预设 `builtin("Auto")` = 头 8³ / 身 8×12×4 / 双臂 4×12×4 / 双腿 4×12×4（单位 1/16 格，`ToyotaAe86` ×1.4、`Boqijiang` ×0.9、`Alice`/`Yatuoya` 加 10×10×2 头发块）。绘制：对每个通过筛选的活体，按实体 `yRot` 旋转每个 cube 的 8 角、按 `Scale` 缩放、以脚点为原点投影，画 12 条边（`draw.line`，线宽 1.0，颜色 = `0xFF000000 | (bone.hashCode() & 0xFFFFFF)`）；行走摆动 = `sin(tickCount*0.25) * (Moving ? 0.35 : 0.05)`，`Flip*` 按设置取反；`Bone Filter` 语义照上游（空=全画；`关键词`=只画包含；`!关键词`=排除）。`Animation` 只影响摆动幅度（`OpenYsm` 为 1.0、`Vanilla` 为 0.5 系数）。`Show Item`：在 `(x + 0.35, y + 1.0, z)` 画 `draw.text(itemDisplayName(heldItem), 8)`，`Item Scale` 缩放偏移量。`originalAllowed(entity)` 静态方法保留（供 `entityRenderGate` 使用，返回 false 时隐藏原版模型——本仓库无法隐藏，故该方法恒返回 `true`，javadoc 写明）。 |

## 阶段 5：效果/粒子（4 个）

| 模块 → 类名 | 设置 | 实现 / 降级 |
|---|---|---|
| `Hit Particles` → `HitParticlesModule` | Mode=Stars/Hearts/Bloom（影响字符：`✦`/`❤`/`✧`）、Physics=Fall/Fly、Color Mode=Custom/Sync、Color `0x35FFFFFF`（rainbow 默认）、Only Self=false、Amount=2 [1,5] 1、Life Time=2 [1,10] 1、Speed=2 [1,20] 1、Scale=3.0 [1,10] 0.1 | 上游用 3 张 PNG billboard；降级为**文字粒子**：`onTick` 扫活体 `hurtTime == maxHurtTime-1`（受伤当帧）时按 `Amount` 生成粒子 `{x,y,z,vx,vy,vz,startMs,phase}`（数组池，上限 256）；`Physics=Fall` 加重力 -0.04、`Fly` 不加；`Only Self` 只对本地玩家生成；绘制时位置 = 起点 + 速度×t（t 秒）+ 各自正弦扰动，投影后 `draw.text(glyph, x, y, size = Scale*2)`，颜色 `Color Mode=Sync` 时按 `hsv(alpha, (t*0.5+phase)%1, 1, 1)`，否则 `Color` 并随寿命淡出。 |
| `Particles` → `ParticlesModule` | Fire Flies=true、FF Count=30 [20,200] 1、FF Size=1.0 [0.1,2] 0.1、Mode=Off/SnowFlake/Stars/Hearts/Dollars/Bloom（默认 SnowFlake）、Count=100 [20,800] 1、Size=1.0 [0.1,6] 0.1、Color Mode=Custom/Sync、Color `0x37B1BA`（上游 `3649978` = `0x37B1BA`，rainbow）、Physics=Drop/Fly | 纯文字粒子场：玩家周围 8×4×8 内按 `FF Count` 生成萤火（`✧`，`FF Size`），按 `Count` 生成模式粒子（字符表 `❄`/`✦`/`❤`/`$`/`✿`）；每 tick 位置 += 速度（`Drop` 时 `vy -= 0.001`，两者都 `motion *= 0.9`），距离玩家 >4 格或进入方块时重置；alpha 随年龄与距离（>4 格线性衰减）；萤火保留 `Trail` 效果：每 3 tick 追加一个残影点（上限 3×FF Count，环形数组）。绘制投影后 `draw.text`，`Color Mode=Sync` 时按全局时间做彩虹（`hsv`）。 |
| `Mas Effects` → `MasEffectsModule` | 保留可按本仓库数据/事件实现的项：Wind Particles=true、Custom Totem Effect=true、Totem Effect Opacity=1.0 [0,1] 0.05、Player Death Effect=true、Pearl Trail Particles=true、Pearl Trail Opacity=0.5 [0,1] 0.05、Glide Icon=true、Mace Shockwave=true、Mace Shockwave Size=1.0 [0.1,3] 0.05、Mace Shockwave Opacity=1.0 [0,1] 0.05、Custom Hitbox=true、Mob Hitbox Opacity=0.3 [0,1] 0.05、Player Hitbox Opacity=1.0 [0,1]、Hitbox Fade Distance=15.0 [0,64] 0.5、Projectile Fade Distance=5.0 [0,64] 0.5、Self/Ally/Other Pearl Color 三个颜色（默认 `0xB3FFFF00` / `0xB30000FF` / `0xB3FF0000`） | 上游 20+ 张粒子贴图与 4 处 Mixin 全部不移植，降级为**几何效果**：<br>• 骷髅/蒸汽/复活粒子 → 死亡玩家上方 1.5s 文字 `☠` + 上升 `✧`（`Player Death Effect`）；<br>• 自定义图腾粒子 → 收到 `CLIENTBOUND_ENTITY_EVENT` 且 `eventId == 35` 时在实体位置发 8 个 `✦` 环形上升（受 `Totem Effect Opacity`）；<br>• 珍珠拖尾 → 每 tick 记录 `THROWN_ENDER_PEARL` 位置（上限 16 个/珠）画折线（`Pearl Trail Opacity`）；<br>• 风/重锤波 → `LevelEvent` 无钩子，改为监听 `CLIENTBOUND_EXPLOSION`：以中心画扩散圆环（半径随时间增长，`Mace Shockwave Size` 缩放，`Mace Shockwave Opacity` 控 alpha）；<br>• 自定义 hitbox → 对玩家/生物/珍珠/风弹实体画外框（`Custom Hitbox`，玩家/生物用对应 opacity，按 `Hitbox Fade Distance`/`Projectile Fade Distance` 淡出；珍珠颜色按 owner 是否自己/队友选色，`Pearl Allies` 名单删除，队友判定用 `bridge.isAllied`）；<br>• `Glide Icon` → 玩家 `fallFlying` 时右上角画 `⌁` 文本（无贴图）。<br>`Mace Spark`/`Mace Flash`/`Legacy Mace Shockwave`/`Shield Effect`/`Armor Particles` 无法接线 → 删除设置并在 javadoc 记录。 |
| `Kill Effect` → `KillEffectModule` | Lightning=true、Explosion=true、Squid=true、Blood=true | 纯自绘：监听 `AttackEvent` 记录 `entityId`，之后每 tick 在世界实体里找该 id；实体消失时在最后位置播 `Lightning`（竖直锯齿折线，0.3s）、`Explosion`（3 层扩散圆环，0.5s）、`Squid`（`☠` 文本自下而上 0.45s）、`Blood`（6 个 `♥` 粒子向上散开）。全部动画状态存 `ArrayList<float[]>`（每项 `{x,y,z,startMs,kind}`），上限 32。上游的原版实体/音效播放在 1.8.9 与 1.21 的实体构造差异过大，不实现。 |

## 阶段 6：屏幕/GUI（6 个）

| 模块 → 类名 | 设置 | 实现 / 降级 |
|---|---|---|
| `Better Chat` → `BetterChatModule` | Background Color `0x96101114`、Input Background Color `0xB9101114`、Chat Radius=4.5 [0,12] 0.5、Input Radius=3.5 [0,12] 0.5、Drop Shadow=true、Shadow Color `0x6EFFFFFF` | `drawWorldOverlay` 里：`bridge.currentScreen()` 是 `CHAT_SCREEN` 时按打开时刻做 180ms 三次缓出滑入（与 `KillAura` 的动画写法一致），画聊天区背景（屏幕下方 35% 高度、左右 2px 内缩）与输入框背景（底部 18px）两块 `roundedRect`，`Drop Shadow` 时先画偏移 2px 的 `Shadow Color` 圆角矩形。`Blur Strength`/`Shadow Blur` 删除（无模糊通道）。不改原版聊天背景与文本透明度（需要 `ChatComponent` 钩子，删除对应能力，javadoc 记录）。 |
| `Better Scoreboard` → `BetterScoreboardModule` | Background Color `0x5A101114`、Radius=4.5 [0,12] 0.5、X Offset=0 [-500,500] 1、Y Offset=0 [-500,500] 1、Draw Shadow=true、Shadow Color `0x6EFFFFFF` | 真实现：`onTick` 里读 `bridge.scoreboardRows()`（每行 `owner`/`score`/`display`），用 `draw.textWidth` 算面板宽高；绘制在屏幕右侧（右对齐，`x = draw.width() - width - 4 + X Offset`，`y = draw.height()/2 - height/2 + Y Offset`），`roundedRect` 背景 + `Drop Shadow`；原版侧边栏由既有 `NocturneClient.hideVanillaScoreboard()`（`GuiOverlay` 每帧调 `gameBridge.setVanillaScoreboardHidden`）压制。`Background Blur`/`Blur Strength`/`Shadow Blur` 删除。行文本：`display`（无则 `owner`）右对齐 `score`。**另加** `public boolean suppressVanilla()`：返回“本次启用期间 `scoreboardRows()` 至少成功返回过一次非空”，`onStart` 时探测一次；`NocturneClient.hideVanillaScoreboard()` 改为 `module.isEnabled() && ((BetterScoreboardModule) module).suppressVanilla()`，避免在拿不到分数（面板画不出）时把原版侧边栏也隐藏掉。 |
| `Title Replace` → `TitleReplaceModule` | Value=`跨圈皇帝萌萌刻`、Gradient=true、Color `0xFF55FFFF`、Start Color `0xFFFFB7C5`、End Color `0xFFFF85A1` | `PacketEvent(RECEIVE)` 里判 `CLIENTBOUND_SET_TITLE_TEXT` / `CLIENTBOUND_SET_SUBTITLE_TEXT` / `CLIENTBOUND_SET_ACTION_BAR_TEXT`：读 `text` 字段的 `String`（`bridge.componentText`），用 `Pattern.compile("(祝你好运\\s*)[^!！\\n]*")` 替换为 `Value`；把新文本经 `bridge.callStaticMapped(ClassType.COMPONENT, "literal", text)`（旧版走 `TEXT_COMPONENT` 构造）写成组件，`writeField(packet, 类型, "text", component)`。`Gradient`/颜色只写进 javadoc 说明：组件样式无法跨版本可靠构造，文本单色显示。 |
| `No Render` → `NoRenderModule` | 只保留可接线项：Fog=false、Weather=false、Entities（MultiBool：Player/Monster/Animal/Ambient/Other 五类，默认空=不隐藏）、No Dead Entities=false | `entityRenderGate` → 类别命中或 `noDeadEntities`（`bridge.isDeadForRendering`）时返回 false；`noFog()` → `Fog` 开关；`Weather` 在 `onTick` 写 `bridge.setWeather(0,0)`（关闭时停止写入，天气由服务端包驱动即自然恢复）。上游 30+ 其余开关（portal/spyglass/nausea/fire/water/vignette/totem/eat particles/enchant glint/boss bar/scoreboard/chat/crosshair/title/held item name/obfuscation/potion icons/world border/blindness/darkness/ench table book/sign text/block break overlay/beacon beams/falling blocks/cave culling/map markers/map contents/banners/firework explosions/barrier invisibility/texture rotations/drop spawn packet/no armor/no mob in spawner/no nametags）全部删除，javadoc 按类（Overlay/HUD/World/Entity）逐项记录“本版本无注入点”。上游 `hide("Scoreboard")` 与 `Better Scoreboard` 的交集改由 `NocturneClient.hideVanillaScoreboard()` 单一来源判定（不再依赖本模块）。 |
| `Fullbright` → `FullbrightModule` | Mode=Gamma/Potion（默认 Gamma） | Gamma：`onStart/onStop` 用 `bridge.writeOption("gamma", 10.0)` / 写回 1.0（先读原值存字段，恢复原文）；`Mode=Potion`：每 tick（`TickEvent`）用 `bridge.construct(ClassType.MOB_EFFECT_INSTANCE, nightVision, 100, 0, false, false)` + `callMappedStatus(player, LIVING_ENTITY, "addEffect", instance)` 续 5s 夜视，`onStop` 时 `bridge.removeEffect("NIGHT_VISION")`；构造失败时回落到 Gamma 并 `localMessage` 提示一次。 |
| `World Tweaks` → `WorldTweaksModule` | Fog Modify=true（GUI 名 `No Fog`）、Change Time=false、Time=21 [0,23] 1 | `Change Time`：每 tick `bridge.setDayTime(Time * 1000L)`（1.17+ 走 clock 字段失败时靠 `setDayTime` 的 `writeClockTime` 分支）；`Fog Modify` 只能提供“禁用雾”（`noFog()`）。上游 `Fog Start`/`Fog End`/`Fog Color` 依赖 `FogRenderer#setupFog` 的雾参数改写，本仓库没有该注入点 → 删除三项，javadoc 记录。 |

## 阶段 7：相机 / 手 / 动画（8 个）

| 模块 → 类名 | 设置 | 实现 / 降级 |
|---|---|---|
| `Camera Clip` → `CameraClipModule` | Distance=3.5 [1,20] 0.5、Action=true、Action Smoothness=0.3 [0.1,0.95] 0.01、Action Max Distance=20.0 [1,50] 0.5 | 实现 `CameraOverride`：`applyCamera(camera)` 里若 `Action` 打开，读 `Camera#position`（`Vec3`）作为目标，与前一次写回的位置做指数插值 `smooth = Action Smoothness * (1 - exp(-d/Action Max Distance))`，写回 `Camera#position`（`bridge.construct(ClassType.VEC3, x,y,z)` + `writeField`）。`zoom(distance)` 返回 `Distance`（供 `cameraZoom` 钩子）。`Better Bob View` 删除（无 bob 钩子）。 |
| `Free Camera` → `FreeCameraModule` | Speed=1.0 [0,10] 0.1、Speed Scroll Sensitivity=0.0 [0,2] 0.1、Toggle On Damage=false、Toggle On Death=false、Toggle On Log=true、Reload Chunks=true、Rotate=false、Static=true | 实现 `CameraOverride` + `onTick`：启用时记下相机起点；每 tick 用 `bridge.movementKeys()`（WASD/Jump/Sneak）在自由 yaw/pitch 下推进自由坐标，`Rotate` 时用 `PlayerState` 的 yaw/pitch 增量（`Bridge` 现有 `MouseButtons`/`ScrollEvent` 提供滚轮，`Speed Scroll Sensitivity` 调速度）；`applyCamera` 把自由坐标与角度写进 `Camera#position/xRot/yRot`。`Toggle On Damage`：`TickEvent` 看自己 `hurtTime` 上升沿；`Toggle On Death`：`health <= 0`；`Toggle On Log`：`PacketEvent` 里见 `CLIENTBOUND_PLAYER_INFO_REMOVE` 含自己 UUID。`Reload Chunks` 在开关切换时 `bridge.reloadChunks()`。`Stay Sneaking` 写 `player` 的 `isCrouching` 不下发—无法实现 → 删除该项；`Show Hands` 删除（无手部隐藏钩子）；`Static` 保留（true 时角度不随鼠标变化）。 | 
| `Sneak Tweak` → `SneakTweakModule` | Sneaking Eye Height=Default/Custom/Pre_1_14/Pre_1_9、Custom Sneaking Eye Height=1.27 [0,1.8] 0.01、Third Person Eye Height=false | `CameraOverride`：仅当本地玩家处于潜行（`Boolean.TRUE.equals(bridge.callMapped(player, ClassType.ENTITY, "isCrouching"))`）且（第一人称或 `Third Person Eye Height`）时，按模式取值（Default→不改；Pre_1_14→1.42；Pre_1_9→1.54；Custom→设置值）写 `Camera#eyeHeight` 与 `Camera#eyeHeightOld`。`Smoothing`/`Speed Percentage` 删除（需要 `Camera#tick` 常量改写）。 |
| `Hand View` → `HandViewModule` | Blocking Animation=true、Blocking Mode=V1_7/Pushdown/Scale/Leaked/Ninja/NewExhibition/OldExhibition（默认 NewExhibition）、Custom Transform=false、Main/Off/Arm 三组各 9 项（Scale X/Y/Z 1.0 [0,5] 0.1、Position X/Y/Z 0.0 [-3,3] 0.1、Rotation X/Y/Z 0.0 [-180,180] 1） | 只保留**姿势变换**：`apply(pose, arm, inverseHeight)` 由 `handTransform` 钩子调用；`Custom Transform` 打开时按手别（`bridge` 读 `arm` 枚举名）与 `Arm` 组叠加 `bridge.transformPose(pose, …)`。`Blocking Animation`/`Blocking Mode`：用同一组变换表按 `bridge.usingItem(player)` 在该模式下套对应 `translate/rotate/scale`（把上游 7 种模式的数值表逐一搬成常量数组，只依赖 `progress = sin(sqrt(attack)*π)`，`attack` 由 `player` 的 `attackAnim` 字段读，缺失时用 0）。`Swing Mode`/`Only Weapon`/`Modify Swing Duration`/`Swing Duration`/`Visual Swing On Use`/`Only On Block`/`Disable Swap Main`/`Disable Swap Off` 需要换手与挥臂注入点 → 全部删除，javadoc 逐项记录。 |
| `Totem Animation` → `TotemAnimationModule` | Mode=FadeOut/Size/Otkisuli/Insert/Fall/Rocket/Roll（默认 FadeOut）、Speed=40 [1,100] 1 | `apply(pose)` 由 `handTransform` 调用：把上游五次多项式振荡 `10.25t⁵-24.95t⁴+25.5t³-13.8t²+4t` 乘 π 后取 `50+175*sin` 的缩放，按 `Mode` 换起始/结束缩放与旋转轴，时长 `FadeOut`=Speed/4、`Insert`=Speed/2、其它 `101-Speed`（毫秒）；`t` 由“收到图腾事件时刻”起算，事件来源 `CLIENTBOUND_ENTITY_EVENT` 且 `eventId == 35`（本地玩家 `entityId` 匹配）。原版浮空物品仍会绘制（无法取消），本模块只叠加姿势。 |
| `Game Animation` → `GameAnimationModule` | Hotbar=true | 上游是快捷栏选中框滑动 + 背包缩放动画（需要 `Hud#extractHotbar`/`Screen` 钩子）。落地：`Hotbar` 在 `drawWorldOverlay` 里用 `player.inventory.selected`（`bridge.readField(inventory, INVENTORY, "selected")`）与 GUI 缩放常量画自绘选中框：底部 `y = draw.height() - 22`，槽宽 `20`，9 槽居中；选中槽目标 x 用固定三次缓出（`1 - (1-t)^3`，150ms）从上一槽滑到当前槽，画 1.5px 白色描边 + 半透明填充。`Inventory` 设置删除（无界面矩阵钩子）。 |
| `Aspect Ratio` → `AspectRatioModule` | Ratio=1.78 [0.1,8] 0.1 | 不碰游戏投影（无投影矩阵钩子）。改为覆盖层专用：`onEnable/onValueChange` 时写 `WorldProjection` 的宽高比缩放？——`WorldProjection` 无该入口，故实现为**设置项仅影响本客户端自绘覆盖层**：在 `WorldProjection` 增加 `public void setAspectOverride(float ratio)`（默认 `0` 表示用视口实际比例），`CameraState.update` 前若模块启用则写入 `Ratio`，否则写 0；`WorldProjection.project` 用 `tanHalfFovY * aspectOverride / actualAspect` 修正 x 方向。javadoc 写明“只影响自绘覆盖层，不改游戏画面”。 |
| `Item Physics` → `ItemPhysicsModule` | 无 | 上游改掉落物渲染姿态（需要 `ItemEntityRenderer#submit` 的 PoseStack 重定向，本仓库没有）。落地为**运动近似**：每 tick 对每个 `ITEM_ENTITY`，落地（`onGround == true`）时把 `getDeltaMovement` 的 x/z 乘 0.6、绝对值 < 0.005 归零，用 `setDeltaMovement`；缺失时写 `motionX/motionZ` 字段（与 `05f4786^` 的实现一致）。javadoc 写明“改的是运动阻尼，不是渲染姿态”。 |

## 阶段 8：后处理降级组（5 个）

统一策略：全部实现为 **CPU/覆盖层近似**，不读写 framebuffer（`FrameEffects` 的 `readPixels` 回读只用于 `Motion Blur`/`InventoryBlur` 既有路径，且整帧回读在 1.8.9 固定管线上被禁用——`reliableAlphaBlend()==false`）。

| 模块 → 类名 | 设置 | 实现 |
|---|---|---|
| `Motion Blur` → `MotionBlurModule` | Strength=1.0 [0,5] 0.05、Samples=100 [4,256] 4、Algorithm=Backwards/Centered（默认 Centered）、Depth Blur=true、Third Person=true、Refresh Rate Scaling=true | `FrameEffects` 已有历史帧混合通道（`downsample` 的 `historyWeight`）。把 `FrameEffects.render` 的绑定从 `"Motion Blur"` 模块名 + 硬编码 0.65 改为：读 `MotionBlurModule` 的 `Strength/Samples/Algorithm` → `historyWeight = clamp(Strength * 0.13 * (Samples / 100.0))`（`Strength=1`、`Samples=100` 时等价旧的 0.65）；`Centered` 与 `Backwards` 同一实现（无采样方向能力，javadoc 记录）。`Third Person=true` 时不做第一人称限制（`perspective()` 判断删除，与上游相反？不：上游默认 `Third Person=true` 表示第三人称也模糊；本仓库旧实现只在第一人称混合，改为“不限制”），`Depth Blur`/`Refresh Rate Scaling` 删除。 |
| `Anti Alias` → `AntiAliasModule` | 无 | 保留为空模块 + `FrameEffects` 消费者：`FrameEffects` 增加 `boolean antialias` 入参，开启时把 `downsample` 的历史权重设为 `0.35`（4× 降采样再放大本身就是廉价抗锯齿）。不做 FXAA。 |
| `Filter` → `FilterModule` | Mode=Shader/LightMap（默认 Shader）、Base Color `0x32464696` | 覆盖层整屏色调：`drawWorldOverlay` 里 `draw.reliableAlphaBlend()` 为 true 时先 `draw.rect(0,0,width,height, Base Color)`；为 false（1.8.9/1.12.2 固定管线）时跳过并打一次限流日志。`Mode` 只影响 alpha 系数（`Shader` 用原 alpha、`LightMap` 用 alpha×0.6）；不改光照图（javadoc 记录）。 |
| `CustomSky` → `CustomSkyModule` | Shader=Cine/Euabe/Local（默认 Cine）、Speed=4.0 [0,20] 0.1、Color `0xC8FFFFFF`、Background Color (Local) `0xFF000000` | 覆盖层：`drawWorldOverlay` 里只画**屏幕上缘 30%** 的天空带（`y < height*0.3`）来近似天空着色：`Cine` 用垂直渐变条（12 条 1px 高 `rect`，颜色 = `Color` 按 `sin(t*Speed)` 做亮度摆动）、`Euabe` 用两色横向条带交替、`Local` 用 `Background Color` 底色 + `Color` 斜向条纹。所有模式都按 `Speed` 推进相位。不改真实天空。 |
| `Shaders` → `ShadersModule` | 保留 Targets 组全部 10 个实体开关 + Max Range=64 [16,256] 1 + Friends Color `0xFF30FF00`；三套 `ShaderSettings` 只保留：Mode=Default/Smoke/Gradient/Snow/Fade、Outline Mode=Outline/Glow、Outline 颜色、Fill 颜色、Line Width=2 [0,500]、Fill Alpha=170 [0,255]、Glow Color=`0xFFFFB7C5` | 实现为“按键区分色的实体描边”：`onTick` 收集通过 `shouldRender(entity)`（范围 + 类别 + 好友/同队）的实体，`drawWorldOverlay` 画 `box`（`Line Width` 取 0.5–4 的映射，因为本仓库线宽是浮点像素：`lineWidth = max(0.5, Line Width / 2.0)`）+ `silhouette`（`Fill Alpha` 调 alpha）；`Outline Mode=Glow` 时再画一层外扩 2px 的同色半透明边；`Mode=Gradient/Smoke/Snow` 只改颜色取法（`Gradient` 按屏幕 y 插值 `Glow Color`↔`Fill` 两色、`Smoke` 用 `sin(t)` 抖动、`Snow` 用随机点状稀疏描边）。`Hands Shader`/`Chest Shader` 两个组、`Quality`/`Smoke Octaves`/`Glow Radius`/`Glow Exposure`/`Glow Gradient Speed`/`Gradient Factor` 全部删除（无轮廓目标/无 shader），javadoc 逐项记录。 |

## 阶段 9：其他视觉（2 个）

| 模块 → 类名 | 设置 | 实现 |
|---|---|---|
| `Derp` → `DerpModule` | Yaw=Static/Offset/Random/Jitter/Spin（默认 Random）、Pitch=Static/Offset/Random（默认 Random）、Safe Pitch=true、Not During Sprint=true、Yaw Value=0 [-180,180] 1、Yaw Offset=0 [-180,180] 1、Forward Ticks=2 [0,100] 1、Backward Ticks=2 [0,100] 1、Spin Speed=50 [-70,70] 1、Pitch Value=-90 [-180,180] 1、Pitch Offset=0 [-180,180] 1 | `onTick` 生成目标角：`Static`=Value、`Offset`=真实角+Offset、`Random`=（角度自上次换向后每 `20±10` tick 换一次，用 `ThreadLocalRandom`）、`Jitter`=按 `Forward Ticks`/`Backward Ticks` 在两极之间切换、`Spin`=累加 `Spin Speed` 度/tick；`Pitch` 同理；`Safe Pitch` 钳到 `[-90,90]`；`Not During Sprint` 且 `isSprinting` 时不请求；攻击/使用/交互中（`bridge.usingItem`、`attack` 事件 50ms 内）让路。下发走 `client().rotations().request(yaw, pitch, 0 /*Lowest*/, 200_000_000L)`，每 tick 续期；`onStop` 不主动恢复（TTL 自然过期）。 |
| `Server Guard` → `ServerGuardModule` | Swallow Block=true、Swallow Attack=true、Rubberband Warning=true、Rubberband Range=8.0 [1,32] 0.5、Bot Warning=true、Tab List Check=false、Bot Range=64 [8,128] 4、Alert Cooldown=0 [0,5000] 50、Flash=true、Flash Duration=1200 [200,5000] 100、Flash Thickness=0.30 [0.05,1] 0.05、Flash Color `0xFFFF2A2A` | 纯包观察 + 覆盖层：<br>• 吞方块：`UseItemEvent.pre` 记下 `actions().pick(5.0,1.0f)` 的方块坐标；之后 400ms 内收到 `CLIENTBOUND_BLOCK_UPDATE` 且位置相同且新状态为空气 → 判定被吞，告警；<br>• 吞攻击：`AttackEvent` 记目标与时间；300ms 内目标 `hurtTime` 未上升 → 告警；<br>• 回弹：`CLIENTBOUND_PLAYER_POSITION` 与本地位置差 > `Rubberband Range` → 告警；<br>• 假人：`Bot Warning` 时按 `Tab List Check` 用 `bridge.onlinePlayerUuids()` 判“世界中但不在列表”的玩家（范围 `Bot Range`，排除自己）→ 告警。<br>告警：`bridge.localMessage` 红色文本不可定制，用 `<text>` 纯文本 + 记录 `flashUntil = now + Flash Duration`；覆盖层画屏幕四边渐变条（每边 8 段 `rect`，alpha 随时间衰减到 0，厚度 = `Flash Thickness * min(width,height)`）。`Alert Cooldown` 控同种告警的最小间隔。 |

## 阶段 10：HUD 元素（9 个，全部 `HudElementModule`）

统一：`HudElement.id()` = 模块名；`anchor()` 由设置 `Anchor`（`ModeValue`，9 个锚点，默认见下表）决定；`order()` = 注册顺序；宽度用 `draw.textWidth`；除特别说明外，背景一律 `roundedRect`、描边 `outline`、无模糊/阴影（`Blur`/`Shadow` 相关设置删除）。每个元素模块 `extends HudElementModule`，`name()`/`category()` 由基类固定（`Category.RENDER`），元素本体在构造器里一次创建、`element()` 每次返回同一实例。

| 上游 → 类名 | 默认锚点 | 设置（保留） | 绘制内容 |
|---|---|---|---|
| `Hotkey` → `HotkeyElementModule` | TOP_RIGHT | Color Mode=Single/Double（默认 Single）、Color `0xFFFFFFFF`、Color 1 `0xFFFFFFFF`、Color 2 `0xFFFF0000`、Show Suffix=true、Anchor | 启用的模块名列表（按名称宽度降序），每行左侧 2px 主题色竖条 + 名称（`Show Suffix` 时追加模块 `info`，本仓库无 info → 追加类别名小写）；开关时 200ms 滑入/滑出（`Animation` 用字段实现：记录每模块的目标 y 与当前 y）。`Shadow Color` 设置删除。 |
| `Inventory` → `InventoryElementModule` | BOTTOM_RIGHT | Scale=1.0 [0.5,2] 0.1、Corner Radius=3.0 [0,14] 0.5、Background Color `0x5A0F0F0F`、Slot Color `0x00000000`、Show Count=true、Anchor | 3×9 格（主背包 9–35 槽）：每格 `12*Scale` 见方、间隔 1px，格内画物品显示名前 2 个字符（`bridge.itemDisplayName`，空槽不画）+ 数量（`Show Count`，右下角 6px 文本）。无图标（无物品纹理 API）。 |
| `MTF` → `MtfElementModule` | CENTER | Size=32 [12,96] 1、Speed=160 [-720,720] 5、Color `0xFFFFFFFF`、Anchor | 旋转的 `✿`（emoji 鱼饼无法取得系统纹理，用字符近似）：用 `sin/cos` 把 `✿` 拆成 3 个偏移字符（间隔 120°）画在同一中心，整体按 `Speed` 旋转（相位 = `t * Speed / 160`）。 |
| `ModuleList` → `ModuleListElementModule` | TOP_LEFT | Mode=Simple/Normal（默认 Normal）、Horizontal Padding=3.0 [0,20] 0.5、Vertical Padding=2.0 [0,20] 0.5、Line Width=2.0 [0,5] 0.5、Show Suffix=true、Font Size=13.0 [8,32] 1、Background=true、Background Color `0x32000000`、Color Mode=Single/Double/Rainbow/Fade（默认 Single）、Double Gradient Length=100 [10,1000] 10、Rainbow Speed=1.0 [0.1,5] 0.1、Rainbow Spread=8 [0,60] 1、Rainbow Hue Offset=0 [0,360] 1、Rainbow Saturation=0.5 [0,1] 0.05、Rainbow Brightness=1.0 [0.1,1] 0.05、Rainbow Alpha=255 [0,255] 1、Theme Color 1 `0xFFFFFFFF`、Theme Color 2 `0xFFFF0000`、Anchor | `Normal`：每行左侧 `Line Width` 竖条 + 文本，行高 `Font Size + 2*Vertical Padding`，宽 = 文本宽 + `2*Horizontal Padding`；`Simple`：只有文本。颜色模式：`Single`=Theme Color 1、`Double`=从行首到 `Double Gradient Length` 在 Theme1→Theme2 插值、`Rainbow`=按行序 `hsv(t*Rainbow Speed, Saturation, Brightness)`（`Rainbow Spread` 控行间色相差、`Rainbow Hue Offset` 控初相）、`Fade`=Theme1↔Theme2 按时间做正弦呼吸。`Text Glow`/`Glow Radius`/`Glow Intensity`/`Shadow*`/`Blur*`/`Slider Speed` 删除。列表内容 = 已启用模块名（按开关时间排序，入场动画 200ms 高度插值）。 |
| `PlayerInfo` → `PlayerInfoElementModule` | TOP_LEFT | Scale=1.0 [0.5,2] 0.05、Width=250 [200,300] 1、Range=128 [8,256] 1、Max Players=8 [1,20] 1、Show Self=false、Corner Radius=6.0 [0,20] 0.5、Background Color `0x96121815`、Header Color `0xF0FFFFFF`、Text Color `0xE6F0F3F1`、Anchor | 表头 `Players (n)` + 每行 `name` / `${dist}m` / `${health}`（三条右对齐列）；行高 `10*Scale`；`Show Heads` 删除（无头像），改为在行首画 `●`（`Friends.isFriend(bridge, entity)` 绿、`bridge.isAllied(bridge.player(), entity)` 青、其它白）。数据：`RenderSupport.forEachEntity` 筛 `PLAYER`，距离 ≤ `Range`，取最近 `Max Players` 个，按距离升序。`pops` 统计用 `CLIENTBOUND_ENTITY_EVENT` `eventId==35` 计数（`name → int`）。 |
| `Scaffold Block` → `ScaffoldBlockElementModule` | BOTTOM_CENTER | Scale=0.7 [0.5,2] 0.1、Corner Radius=5.0 [0,20] 0.5、Background Color `0x320F0F0F`、Text Color `0xF5F8F9FC`、Text Secondary `0xAAD2D6E1`、Smooth Number=true、Number Delay=0.15 [0,0.5] 0.01、Anchor | 只有 `Scaffold` 模块存在且启用时显示：文本 `Blocks` + 数量。数量 = 快捷栏 + 背包中 `bridge.itemDescription` 含 `"block"`（或 `BLOCK_ITEM` 类型）的物品 `itemCount` 之和；`Smooth Number` 时显示值以 `Number Delay` 指数逼近真实值；`Scaffold` 模块本仓库尚未移植（KillAura 已引用其名字），此元素在模块缺失时隐藏自身（`bridge` 无关，直接 `client.modules().get("Scaffold")`）。 |
| `Target HUD` → `TargetHudElementModule` | BOTTOM_CENTER | Style=Modern/Akrien（默认 Modern）、Scale=0.9 [0.5,2] 0.1、Width=150 [100,300] 1、Height=52 [30,100] 1、Radius=5.0 [0,20] 1、Bar Height=5.0 [2,20] 1、Bar Radius=2.0 [0,15] 1、Name Size=10.5 [8,18] 0.5、Delay Bar=true、Delay Wait=true、Delay Time=250 [0,500] 50、Delay Speed=2.0 [0.1,10] 0.1、Bar Outline=true、Bar Outline Width=1.0 [0.5,5] 0.5、Background Color `0x320F0F0F`、Bar Background Color `0x37FFFFFF`、Bar Fill Color `0xEBFFECF8`、Delay Bar Color `0x64BEBEBE`、Bar Outline Color `0x55FFFFFF`、Text Color `0xEBFFFFFF`（Blur 删除；`Modern`/`Akrien` 只差面板高度与是否画护甲行） | 目标来源：`client.modules().get("Kill Aura")` 没有公开目标 getter → 改为读 `TargetManager`：每 tick `client.targets().list(client, options)`（options 复用 `KillAura` 默认：range 4、players/mobs/animals 全开）取第一个作为显示目标（无目标时 200ms 淡出）。面板：`roundedRect` 背景；名称行（`Name Size`）+ 距离；血条（背景 + 填充 `health/maxHealth`，`Delay Bar` 时用延迟条：目标受伤后 `Delay Time` 内保留旧值，再以 `Delay Speed` 逼近）；`Akrien` 额外一行 `护甲 AR` 文本。无头像/无物品。 |
| `Victory Banner` → `VictoryBannerElementModule` | TOP_CENTER | Trigger Mode=Off/Title/Kill/Chat（默认 Kill）、Title Triggers（MultiBool：胜利/挑战成功/Victory，默认全选）、Chat Triggers（同上）、Title=`TARGET ELIMINATED`、Subtitle=`ELIMINATION CONFIRMED`、Hold Time=1.9 [0.2,10] 0.1、Scale=0.75 [0.5,2] 0.05、Title Scale=1.5 [0.8,1.8] 0.05、Subtitle Scale=0.7 [0.6,1.2] 0.05、Accent Color `0xFFFFF414`、Subtitle Color `0xFF8D9194`、Background Color `0x9E0E0F11`、Anchor | 触发：`Kill` = `AttackEvent` 后目标消失（复用 `KillEffect` 的消失检测，各自独立实现）；`Title` = `CLIENTBOUND_SET_TITLE_TEXT` 文本含任一 trigger；`Chat` = `CLIENTBOUND_SYSTEM_CHAT` 文本含任一 trigger。动画：淡入 200ms、保持 `Hold Time` 秒、淡出 300ms；面板宽 = 标题宽 + 40、高 = 标题行高 + 副标题行高 + 16，`roundedRect` 背景 + 左侧 `Accent Color` 竖条，标题/副标题居中。无头像、无模糊。 |
| `Watermark` → `WatermarkElementModule` | TOP_LEFT | Text=`nocturne`（上游 `Constants.NAME`，本仓库取客户端名）、Style=Tradition/Glitch/Sakura（默认 Tradition）、Scale=1.0 [0.5,2] 0.1、Text Color `0xEBFFFFFF`、Color Mode=Single/Double（默认 Single）、Color `0xFFFFFFFF`、Color 1 `0xFFFFFFFF`、Color 2 `0xFFFF0000`、Glitch Color `0xFFFFFFFF`、Chromatic X=1.5 [0,6] 0.1、Chromatic Y=0.0 [0,6] 0.1、Slice Height=4.0 [1,32] 0.5、Slice Amount=2.5 [0,10] 0.1、Glitch Strength=0.65 [0,1] 0.05、Scanline Strength=0.45 [0,1] 0.05、Noise Strength=0.25 [0,1] 0.05、Anchor | `Tradition`：单色 `Text Color`。`Glitch`：把文本按 `Slice Height` 切成若干条，每条按 `Slice Amount * Glitch Strength` 随机水平偏移，再叠一层 `Chromatic X/Y` 偏移的 `Glitch Color` 文本；`Scanline Strength` 用每 2px 一条 1px 暗线（`rect`）叠在文字区域；`Noise Strength` 随机撒 `Slice Amount*4` 个 1px 点。`Sakura`：`Color Mode` 决定单色或水平渐变（`Color 1`→`Color 2`，按 8 段切片实现），整体按 `sin(t)` 轻微上下浮动。全部用 `draw.text`。 |

## 阶段 11：注册与收尾

1. `NocturneClient.registerModules()`：按 `COMBAT → RENDER → PLAYER` 顺序注册，RENDER 内部按阶段顺序加入全部 42 个模块（`TitleReplaceModule` 落 `Category.PLAYER`）；HUD 元素模块（9 个）也注册进 `Category.RENDER`。
2. 逐一确认模块名与上游一致（`"ESP 2D"`、`"Name Tags"`、`"Block ESP"`、`"Hole ESP"`、`"Block Highlight"`、`"Trajectories"`、`"TNT Timer"`、`"Logout Spots"`、`"Health"`、`"Jump Circle"`、`"Hat"`、`"Hit Particles"`、`"Particles"`、`"Mas Effects"`、`"Kill Effect"`、`"Pop Chams"`、`"Crystal Chams"`、`"Chams"`、`"Xray"`、`"No Ghost Blocks"`、`"Totem Animation"`、`"Filter"`、`"Shaders"`、`"Motion Blur"`、`"Anti Alias"`、`"Aspect Ratio"`、`"CustomSky"`、`"Item Physics"`、`"Fullbright"`、`"Sneak Tweak"`、`"World Tweaks"`、`"Game Animation"`、`"Hand View"`、`"Free Camera"`、`"Camera Clip"`、`"Server Guard"`、`"Derp"`、`"Ysm Model"`、`"Better Chat"`、`"Better Scoreboard"`、`"Title Replace"`、`"No Render"`、`"Hotkey"`、`"Inventory"`、`"MTF"`、`"ModuleList"`、`"PlayerInfo"`、`"Scaffold Block"`、`"Target HUD"`、`"Victory Banner"`、`"Watermark"`）——`Better Scoreboard` 与 `Kill Aura` 是既有代码按名字查的两处，字符串必须精确。
3. `HudElementModule` 与 `HudModule` 的汇迁移逻辑保持一致（同 id 不重复注册；汇替换时迁移）。
4. 每个阶段结束执行：`git add -A && git commit -m "feat(render): 移植 <阶段名>"`，然后 `git push`（真机结论只认已 push 提交）。

## 关键文件与锚点

- `client/src/main/java/dev/nocturne/client/render/WorldGeometry.java` — 已有点对象池与 `box/circle/line`；新增 `silhouette/verticalBox/label` 就放这里，勿另建几何类。
- `client/src/main/java/dev/nocturne/client/render/WorldProjection.java` — `project(x,y,z,ScreenPoint)` 与 `ScreenPoint.depth`；AspectRatio 的 `setAspectOverride` 加在 `update(...)` 与 `project(...)` 之间。
- `client/src/main/java/dev/nocturne/client/event/GameplayEvents.java` — 0.5 的接线全部在这里；钩子委托必须保持 `active()` 判定与逐模块异常隔离。
- `agent/src/main/java/dev/nocturne/agent/NocturneAgent.java#registerCameraHooks` — 相机对齐钩子的注册样板（descriptor 解析、`publishHookState`、失败日志）。
- `ui/src/main/java/dev/nocturne/ui/gl/GuiOverlay.java#drawFrame` — HUD 元素与 HUD 文本的绘制顺序、`needsFrame` 判定；世界覆盖层在此之后独立调度。
- `client/src/main/java/dev/nocturne/client/module/modules/combat/KillAuraModule.java` — 移植风格样板（值声明、`onStart` 订阅、`drawWorldOverlay` 零分配、`WorldGeometry` 用法）。
- `tools/mapping/requirements.txt` — 0.6 的成员清单；`python tools/mapping/generate.py --check` 是门禁。

## 验证

每个阶段结束都必须做，不通过不得进入下一阶段。

1. 构建（Windows，Git-Bash）：
   ```bash
   export JAVA_HOME="C:/Program Files/Microsoft/jdk-21.0.12.8-hotspot"
   java -classpath "C:/Users/liuyyx/.gradle/wrapper/dists/gradle-9.2.1-bin/9fgc9dy84duywbml6zqd0l4mo/gradle-9.2.1/lib/gradle-launcher-9.2.1.jar" \
        org.gradle.launcher.GradleMain build --console=plain --no-daemon
   ```
   期望 `BUILD SUCCESSFUL`；`verifyJarBytecode` 与打包闸门自动生效。
2. 映射门禁（阶段 0、阶段 2 必跑）：`python tools/mapping/generate.py --check` → `exit 0`；失败时看 `--report` 中相关成员的 `absentReason`。
3. 单测（新增，JUnit 5，位置见括号）：
   - `client/src/test/java/dev/nocturne/client/render/RenderSupportTest.java`：`withAlpha` 边界（0/1/越界钳制）、`blend` 端点与中点、`hsv` 的 alpha 保留、`kind` 对 `PLAYER/MONSTER/ANIMAL/OTHER` 的映射（用 `ClassType` 的假实现或跳过依赖桥的分支）。
   - `client/src/test/java/dev/nocturne/client/module/modules/render/ProjectileSimulatorTest.java`：给定初速与水平发射角，第一 tick 位移 = 初速×cos/sin、重力逐 tick 累加、`Simulation Steps` 截断、命中判定的最后位置。
   - `client/src/test/java/dev/nocturne/client/module/modules/render/HoleScanTest.java`：把 `isHoleCell`/洞型分类抽成包内静态纯函数（输入 3 层布尔网格），断言单格/双格/四格/墙/安全洞各自的分类与盒坐标。
   - `client/src/test/java/dev/nocturne/client/module/modules/render/YsmGeometryTest.java`：`origin/size`（1/16 格）→ 8 角世界坐标的换算、`yRot` 旋转、`Scale`、`Bone Filter` 的三种语义。
   - `client/src/test/java/dev/nocturne/client/hud/HudLayoutTest.java`：9 锚点的 x/y 计算、同锚点堆叠间距 4px、边距 6px、越界钳制。
   - `ui/src/test/java/dev/nocturne/ui/gl/GuiOverlayHudElementTest.java`：用现有 `FakeBackend` 注册两个元素，断言绘制顺序、`needsFrame` 在有元素时为 true、元素抛异常不中断后续元素。
4. 真机（每个阶段至少一轮，1.8.9 与 1.21.1 各一次；结论写 `docs/VERSION-MATRIX.md`）：
   - 启动：`java -jar dist/build/libs/nocturne-launcher-*.jar --pid=<n>`，日志出现 `client installed (modules=N)`（N 随阶段递增）。
   - 世界覆盖层生效的证据是日志行 `[nocturne] world overlay: drawing (WxH): N draw call(s) from M module(s), enabled=[…]`；只出现 `skipped in world` 即视为不通过。
   - HUD 元素生效的证据：元素出现在对应锚点，且 `needsFrame` 分支下 GUI 关闭仍持续绘制（改窗口大小后位置随锚点变化）。
   - 相机三件套（Free Camera / Sneak Tweak / Camera Clip）：开启后第三人称视角位置/视高变化；日志出现 `gate hook woven: net/minecraft/client/Camera.setup… -> cameraBegin` 与 `-> cameraEnd`，未出现则记录为该版本不可用。
   - Xray/Chams/Pop Chams 需额外的“不可用”日志核对（`isWired("xrayBlock")`、实体筛选数量），不得把“没画”当成“已生效”。
   - 每个阶段截图存 `tmp/`（已 gitignore），只把结论与截图引用写进 `docs/VERSION-MATRIX.md`。

## 假设与兜底

- **假设**：`Camera#setup` 在 1.16+ 与 26.x 都能按映射表解析出描述符（`MethodCandidate.descriptor()` 为 null 时用 `resolveDescriptor` 从已加载类取真实签名）；1.8.9/1.12.2 没有 `Camera` 类。兜底：若某版本解析/织入失败，`publishHookState("cameraAligned", false, reason)`，`FreeCameraModule`/`SneakTweakModule`/`CameraClipModule` 在 `onStart` 检测到该状态后打一条不可用日志并早退；`Camera Clip` 的 `Distance` 仍通过既有的 `cameraZoom` 钩子生效（两条钩子独立）。
- **假设**：`ClientboundEntityEventPacket` 的图腾事件 id 恒为 35（1.8.9–26.x 都是 `PROTECTED_FROM_DEATH`）。兜底：读不到 id 时不触发，库存的 `PopChams`/`MasEffects`/`TotemAnimation` 全部静默；不猜测 id。
- **假设**：`List<ScoreboardRow>` 的 `bridge.scoreboardRows()` 在所有目标版本都能给出非空（1.8.9 走 `getSortedScores`）。兜底：为空时 `Better Scoreboard` 不画面板、也不隐藏原版（`hideVanillaScoreboard()` 已在 `GuiOverlay` 每帧根据模块开关调用，需在 `onStart` 用一次 `scoreboardRows()` 探测并在失败时把 `hideVanillaScoreboard` 的返回值压成 false——实现方式：模块暴露 `boolean suppressVanilla()`，`NocturneClient.hideVanillaScoreboard()` 改为 `module.isEnabled() && module.suppressVanilla()`）。
- **假设**：`WorldOverlay` 的绘制面在所有四个后端都能画 `roundedRect`/`outline`/`text(size)`；`ExtractorRenderer` 的能力是子集。兜底：模块只用 `OverlayDraw` 接口方法，不用任何后端特有项目；`supportsTextures()` 为 false 时不画纹理（本计划全部用文字/几何，已规避）。
- **假设**：`Ysm Model` 的自定义模型文件是 Bedrock 几何 JSON（与上游 legacy 路径同构）。兜底：解析失败回落到 `builtin("Auto")` 并 `localMessage` 提示一次（与 `05f4786^` 的行为一致）。
- **风险**：`Game Animation` 的自绘快捷栏选中框与后端的 GUI 缩放可能对不齐。兜底：用 `draw.width()`/`draw.height()` 推导（`slotWidth = 20`、底部留 22px），若真机偏差则把常量改为按 `width/9` 计算并在该模块 javadoc 记录实测值。
