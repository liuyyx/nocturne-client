# 移植 eps上游 KillAura（Kill Aura）

## Context

从 `C:\Users\liuyyx\Desktop\01-我的世界\eps-plus\eps上游` 的 `common/src/main/java/com/github/epsilon/modules/impl/combat/KillAura.java`（354 行）移植战斗模块到本仓库。当前 `client/src/main/java/dev/nocturne/client/module/modules/` 已被清空，`NocturneClient.install()` 不再注册任何模块；框架（ModuleRegistry / 配置 / 旋转仲裁 / 目标选择 / 动作层 / 世界覆盖层）保留。目标：新增 `KillAuraModule`，按当前仓库的 `GameModule` + `TargetManager` + `RotationController` + `ActionApi` + `WorldOverlay` 契约重写上游行为，并注册进客户端。用户已确认：ESP 四种模式全部移植（用文字/线条近似），目标分类完整七类（新增映射类）。

## Approach

### 1. 映射层：新增 VILLAGER / MONSTER / WATER_MOB 三个 ClassType

`client/src/main/java/dev/nocturne/client/mapping/ClassType.java`，在 `AMBIENT` 之后追加：

```java
/** 村民；1.14–1.21.11 的规范路径是 net.minecraft.world.entity.npc.Villager（见 member-aliases）。 */
VILLAGER("net.minecraft.world.entity.npc.villager.Villager"),
/** 敌对生物基类；1.8.9/1.12.2 与 MOB 都落到 MCP 的 EntityMob。 */
MONSTER("net.minecraft.world.entity.monster.Monster"),
/** 水生生物基类；1.14–1.21.11 的规范路径是 net.minecraft.world.entity.animal.WaterAnimal（见 member-aliases）。 */
WATER_MOB("net.minecraft.world.entity.animal.fish.WaterAnimal"),
```

`tools/mapping/member-aliases.txt` 追加（左侧用 ClassType 常量名，生成器会翻成 canonical）：

```
VILLAGER = net/minecraft/world/entity/npc/Villager
WATER_MOB = net/minecraft/world/entity/animal/WaterAnimal
```

`tools/mapping/aliases-1.8.9.toml` 和 `aliases-1.12.2.toml` 的 `[classes]` 段各追加 5 行（canonical 与别名两条路径都登记，保证 legacy 的 anchor 能学到）：

```toml
"net/minecraft/world/entity/npc/villager/Villager" = "net/minecraft/entity/passive/EntityVillager"
"net/minecraft/world/entity/npc/Villager" = "net/minecraft/entity/passive/EntityVillager"
"net/minecraft/world/entity/animal/fish/WaterAnimal" = "net/minecraft/entity/passive/EntityWaterMob"
"net/minecraft/world/entity/animal/WaterAnimal" = "net/minecraft/entity/passive/EntityWaterMob"
"net/minecraft/world/entity/monster/Monster" = "net/minecraft/entity/monster/EntityMob"
```

运行 `python tools/mapping/generate.py` 重写全部 66 份 `client/src/main/resources/mappings-*.json`；再跑 `python tools/mapping/generate.py --check`（含 javap 交叉校验）。新增类在 1.14–1.21.11 通过别名解析、26.x 通过 canonical 解析；若某版本确实没有对应类，生成器写 `absent`，运行期 `GameBridge.type()` 返回 null，分类逻辑按“未命中”处理，不手改表。

### 2. TargetManager：七类过滤 + 眼到 AABB 距离

`client/src/main/java/dev/nocturne/client/control/TargetManager.java`：

- `Options` 新增字段（默认值保持现有调用方行为）：
  ```java
  public boolean animals = false;
  public boolean villagers = false;
  public boolean ambient = false;
  public boolean water = false;
  public boolean others = false;
  ```
- `Target` 新增存储字段 `height`、`width`，`aimY` 改为构造时计算（不再用 `y + 1.2` 硬编码）：
  - 有包围盒时：`aimY = feetY + clamp(eyeY - feetY, 0, (maxY - minY) * 0.9)`，`height = maxY - minY`，`width = maxX - minX`，`distance` = 眼睛到 AABB 的三轴外距（`dx = max(minX-eyeX, 0, eyeX-maxX)`，y/z 同理，再开方）。
  - 无包围盒（映射缺失/测试替身）时：`distance` 用眼睛到脚部中心，`aimY = feetY + 1.2`，`height/width` 分别用 `bridge.entityHeight` / `bridge.entityWidth`。
  - 眼睛位置读取顺序：`callMapped(player, ClassType.ENTITY, "getEyePosition", Float.valueOf(0.0f))` → 零参 `getEyePosition()` → `player` 脚部（现有 `position` 结果）。
  - 包围盒读取：`callMapped(entity, ClassType.ENTITY, "getBoundingBox")`，再读 `ClassType.AABB` 的 `minX/minY/minZ/maxX/maxY/maxZ`（这些成员已在 `requirements.txt`）。
  - `yaw` 仍读 `player` 的 `yRot` 字段。
- `consider()` 的类别判定改为下面的顺序（先判具体类，再判基类；`ARMOR_STAND` 直接排除）：
  1. `PLAYER` → `options.players`
  2. `VILLAGER` → `options.villagers`
  3. `WATER_MOB` → `options.water`（放在 `ANIMAL` 前，否则现代 `WaterAnimal extends Animal` 会让 Water 开关永远无效；这是对上游顺序的有意修正）
  4. `ANIMAL` → `options.animals`
  5. `AMBIENT` → `options.ambient`
  6. `MONSTER` → `options.mobs`
  7. `MOB` → `options.mobs`
  8. 其它 `LivingEntity` → `options.others`
  - `Friends` 过滤保持只对玩家；`invisible` 过滤改为只对玩家生效（与上游一致）；`lineOfSight` 仍按 `options.lineOfSight`（KillAura 自己会传 false，见第 4 步）。

`Target` 的公开 getter 增加 `height()`、`width()`；`aimY()` 改为返回存储值。`Target` 构造器是 private，仅本类使用，无其它调用点。

### 3. 新增 `client/src/main/java/dev/nocturne/client/render/WorldGeometry.java`

纯静态工具（无状态、无模块依赖），供 KillAura 的 ESP 使用；实现方式与 HEAD 中已删除的 `modules/render/WorldGeometry.java` 相同（可用 `git show HEAD:client/src/main/java/dev/nocturne/client/module/modules/render/WorldGeometry.java` 取回）：

```java
public final class WorldGeometry {
    public static void box(OverlayDraw draw, WorldProjection projection,
                           double minX, double minY, double minZ,
                           double maxX, double maxY, double maxZ, float width, int argb);
    public static void circle(OverlayDraw draw, WorldProjection projection,
                              double x, double y, double z, double radius,
                              int segments, float width, int argb);
    public static void line(OverlayDraw draw, WorldProjection projection,
                            double x1, double y1, double z1,
                            double x2, double y2, double z2, float width, int argb);
}
```

`box` 用 8 角投影 + 12 条边；`circle` 用 `segments+1` 个点连成水平环；`line` 投影两点后调用 `draw.line`。每帧调用使用 `projection.project(x,y,z,out)`，`ScreenPoint` 由调用方复用（工具内部为 8 角/环点各预分配一个 `ScreenPoint` 数组即可，避免每次 new）。

### 4. 新增 `client/src/main/java/dev/nocturne/client/module/modules/combat/KillAuraModule.java`

`public final class KillAuraModule extends GameModule implements WorldOverlay`，构造器 `super("Kill Aura", Category.COMBAT)`。全部注释用中文。

#### 4.1 设置项（名称/默认值/范围必须逐项一致）

设置项一律用 `add(new XValue(...))` 字段初始化器。

| 字段 | 类型 | 名称 | 默认 | 范围/选项 |
|---|---|---|---|---|
| pauseOnEat | BooleanValue | Pause On Eat | true | |
| pauseOnScaffold | BooleanValue | Pause On Scaffold | true | |
| hitSelect | BooleanValue | Hit Select | true | |
| mode | ModeValue | Mode | `1.8` | `1.8`, `1.9+` |
| targetMode | ModeValue | Target Mode | `Single` | `Single`, `Switch` |
| switchDelay | NumberValue | Switch Delay | 100 | 0–500，步长 1（毫秒） |
| priorityMode | ModeValue | Priority Mode | `None` | `None`, `Health`, `Fov`, `Range` |
| searchRange | NumberValue | Search Range | 4.0 | 1–6，0.1 |
| aimRange | NumberValue | Aim Range | 3.0 | 1–6，0.1 |
| fov | NumberValue | FOV | 360 | 10–360，1 |
| rotationSpeed | NumberValue | Rotation Speed | 180 | 10–180，10 |
| rotationPriority | ModeValue | Rotation Priority | `High` | `Lowest`, `Low`, `Medium`, `High`, `Highest` |
| cps | NumberValue | CPS | 12 | 1–20，1 |
| players | BooleanValue | Players | true | |
| mobs | BooleanValue | Mobs | true | |
| animals | BooleanValue | Animals | true | |
| villagers | BooleanValue | Villagers | false | |
| ambient | BooleanValue | Ambient | false | |
| water | BooleanValue | Water | false | |
| others | BooleanValue | Others | false | |
| invisible | BooleanValue | Invisible | true | |
| swingHand | BooleanValue | SwingHand | true | |
| esp | BooleanValue | ESP | true | |
| espMode | ModeValue | ESP Mode | `Circle` | `CaptureMark`, `Circle`, `Firefly`, `Deobf` |
| deobfSize | NumberValue | Deobf Size | 0.75 | 0.25–2，0.05 |
| deobfSpins | NumberValue | Deobf Spins | 3.0 | 0.5–8，0.25 |
| deobfWobble | NumberValue | Deobf Wobble | 1.0 | 0–2，0.1 |
| deobfFlyHeight | NumberValue | Deobf Fly Height | 5.0 | 1–12，0.5 |
| espMain | ColorValue | ESP Main | `0xFFFFB7C5` | |
| espSecond | ColorValue | ESP Second | `0xFFFF85A1` | |
| espSize | NumberValue | ESP Size | 1.2 | 0.5–3，0.1 |
| rotSpeed | NumberValue | Rot Speed | 2.0 | 0.5–10，0.1 |
| waveSpeed | NumberValue | Wave Speed | 3.0 | 0.5–10，0.1 |
| sideColor | ColorValue | Side Color | `0xFFFFFFFF` | |
| lineColor | ColorValue | Line Color | `0xE9FFFFFF` | |
| circleRadius | NumberValue | Circle Radius | 0.75 | 0.1–2，0.05 |
| circleAlphaFactor | NumberValue | Circle Alpha Factor | 1.0 | 0–2，0.05 |
| fireflyColorMode | ModeValue | Firefly Color Mode | `Blend` | `Solid`, `Blend`, `Rainbow` |
| fireflyColor | ColorValue | Firefly Color | `0xFF959595` | |
| fireflyColor2 | ColorValue | Firefly Color 2 | `0xFFFF85A1` | |
| fireflyColorMix | NumberValue | Firefly Color Mix | 0.65 | 0–1，0.05 |
| fireflyColorSpeed | NumberValue | Firefly Color Speed | 1.2 | 0.1–6，0.1 |
| fireflyRainbowSpeed | NumberValue | Firefly Rainbow Speed | 1.0 | 0.1–6，0.1 |
| fireflyRainbowSaturation | NumberValue | Firefly Rainbow Saturation | 0.85 | 0.1–1，0.05 |
| fireflyRainbowBrightness | NumberValue | Firefly Rainbow Brightness | 1.0 | 0.1–1，0.05 |
| fireflyLength | NumberValue | Firefly Length | 14 | 8–128，1 |
| fireflyFactor | NumberValue | Firefly Factor | 8 | 1–10，1 |
| fireflyShaking | NumberValue | Firefly Shaking | 1.8 | 0.25–10，0.25 |
| fireflyAmplitude | NumberValue | Firefly Amplitude | 3.0 | 0–10，0.25 |

不移植 `Deobf Mode`（上游用它选纹理；本仓库没有这两个纹理资源，保留就是死设置）。`ESP Mode` 的 Deobf 分支改用下面的线框近似。

#### 4.2 状态与生命周期

字段：

```java
private TargetManager.Target target;
private float aimYaw = Float.NaN;
private int switchIndex;
private long lastSwitch;
private long nextAttack;
private final java.util.Map<Integer, DeobfEffect> deobfEffects = new java.util.LinkedHashMap<>();
private final WorldProjection.ScreenPoint[] points = new WorldProjection.ScreenPoint[8]; // 构造器里 new
```

`DeobfEffect` 为私有静态内部类：`Object entity; long startedAtMs; long killedAtMs = -1;`。

- `onStart()`：`listen(WorldChangeEvent.class, event -> clearState())`。
- `onStop()`：`clearState(); client().rotations().release(rotationPriorityValue());`。
- `clearState()`：`target = null; aimYaw = Float.NaN; switchIndex = 0; lastSwitch = 0; nextAttack = 0; deobfEffects.clear();`。

#### 4.3 `onTick()`（20Hz）

```
NocturneClient c = client(); GameBridge b = bridge();
if (c == null || b == null || actions() == null) return;
PlayerState s = state();
if (!s.present()) return;

// Deobf 命中特效维护
if (!esp.get() || !espMode.is("Deobf")) deobfEffects.clear();
else pruneDeobf(now);

if (pauseOnScaffold.get() && moduleEnabled("Scaffold")) { clearState(); return; }

TargetManager.Options o = new TargetManager.Options();
o.range = searchRange.get();
o.maxAngle = fov.get();                 // TargetManager 内部按 maxAngle/2 判定
o.players = players.get(); o.mobs = mobs.get();
o.animals = animals.get(); o.villagers = villagers.get();
o.ambient = ambient.get(); o.water = water.get(); o.others = others.get();
o.invisibles = invisible.get();
o.lineOfSight = false;                  // 上游靠 raytrace 拦截隔墙目标，不预先剔除
o.sort = sortForPriority();             // None/Range→DISTANCE，Health→HEALTH，Fov→ANGLE
List<TargetManager.Target> targets = c.targets().list(c, o);

if (targets.isEmpty()) { target = null; aimYaw = Float.NaN; switchIndex = 0; return; }

long now = System.currentTimeMillis();
if (targetMode.is("Switch")) {
    if (now - lastSwitch >= switchDelay.asInt()) { switchIndex++; lastSwitch = now; }
} else switchIndex = 0;
switchIndex %= targets.size();
target = targets.get(switchIndex);

aim(target, s);          // 见 4.4
attack(target, s);       // 见 4.5
```

`pauseOnEat`：上游按“手持食物 + isUsingItem”判断；本仓库没有食物组件映射，沿用已删除的旧版移植约定，用 `bridge().usingItem(state().player())`（任何物品使用中都会暂停）。此偏差写进类注释。

`moduleEnabled("Scaffold")`：私有方法，`client().modules().get("Scaffold")` 存在且 `isEnabled()`；当前没有 Scaffold 模块时为 no-op。

#### 4.4 `aim(TargetManager.Target t, PlayerState s)`

```
double dx = t.x() - s.x(), dz = t.z() - s.z();
double horizontal = Math.hypot(dx, dz);
float targetYaw = RotationController.yawTo(dx, dz);
float targetPitch = RotationController.pitchTo(horizontal, t.aimY() - eyeY(s));
float step = rotationSpeed.asFloat();
boolean accepted = client().rotations().request(
        RotationController.approach(s.yaw(), targetYaw, step),
        RotationController.approach(s.pitch(), targetPitch, step),
        rotationPriorityValue(), 150_000_000L);
aimYaw = accepted ? targetYaw : Float.NaN;
```

`eyeY(PlayerState)`：`bridge().callMapped(s.player(), ClassType.ENTITY, "getEyePosition", Float.valueOf(0f))` → 零参 `getEyePosition()` → `s.y() + 1.62`；读 `ClassType.VEC3` 的 `y` 字段。

`rotationPriorityValue()`：`Lowest=0, Low=10, Medium=50, High=100, Highest=1000`（`High` 为兜底）。

#### 4.5 `attack(TargetManager.Target t, PlayerState s)`

```
if (Float.isNaN(aimYaw)) return;
if (!client().rotations().isAimedAt(aimYaw, 4.0f)) return;
long now = System.currentTimeMillis();
if (now < nextAttack) return;

Object hit = actions().pick(aimRange.get(), 1.0f);
Object hitEntity = bridge().hitEntity(hit);
if (hitSelect.get()) {
    if (hitEntity == null || hitEntity != t.entity()) return;
    if (bridge().isInstance(hitEntity, ClassType.PLAYER)) {
        Object self = s.player();
        if (bridge().isAllied(self, hitEntity)) return;
        // 上游：目标 hurtTime <= 延迟tick+1，或自己被击退（hurtTime>=6）时放行。
        // 本仓库没有 ping 映射，延迟按 0 处理，即目标 hurtTime <= 1。
        if (bridge().hurtTime(hitEntity) > 1 && bridge().hurtTime(self) < 6) return;
    }
} else if (bridge().isBlockHit(hit)) {
    return;   // 射线先命中方块：与上游 “BLOCK 直接 return” 等价
}

if (mode.is("1.9+") && s.attackStrengthScale() < 1.0f) return;

if (!actions().attack(t.entity())) return;
actions().swing(false, !swingHand.get());   // swingHand=true 走玩家挥手；false 只发挥手包
if (esp.get() && espMode.is("Deobf")) markHit(t.entity());
nextAttack = mode.is("1.8")
        ? now + Math.max(1L, (long) Math.ceil(1000.0 / Math.max(1, cps.asInt())))
        : 0L;
```

`markHit(entity)`：`int id = bridge().entityId(entity); if (id < 0) return;` 若不存在或已标记 killed，则 `deobfEffects.put(id, new DeobfEffect(entity, System.currentTimeMillis()))`。

`pruneDeobf(now)`：遍历 map；实体血量 `bridge().healthOrNull(entity)` 为 null/≤0 时，若 `killedAtMs < 0` 置为 now；`killedAtMs >= 0 && now - killedAtMs > 1800` 删除；`startedAtMs` 超过 10 秒也删除；实体不在 `ClassType.LIVING_ENTITY` 时删除。

#### 4.6 `drawWorldOverlay(OverlayDraw draw, WorldProjection projection)`

```
if (!esp.get()) return;
if (espMode.is("Deobf")) { drawDeobf(draw, projection); return; }
TargetManager.Target t = target;
if (t == null) return;
if (espMode.is("CaptureMark")) drawCaptureMark(draw, projection, t);
else if (espMode.is("Circle")) drawCircle(draw, projection, t);
else drawFirefly(draw, projection, t);
```

**Circle**（上游 `CircleESP` 的线条近似）：

```
float ticks = (System.currentTimeMillis() % 1_000_000L) * 0.004f;
float alpha = 0.35f + 0.65f * ((sin(ticks * 1.8f) + 1f) * 0.5f) * circleAlphaFactor.asFloat();
double y = t.y() + 1.0 + sin(ticks);
int line = withAlpha(lineColor.argb(), alpha);
int side = withAlpha(sideColor.argb(), 0.52f * alpha);
WorldGeometry.circle(draw, projection, t.x(), y, t.z(), circleRadius.asFloat(), 64, 2f, line);
double lower = y - sin(ticks + 1f) / 2.7;
WorldGeometry.circle(draw, projection, t.x(), lower, t.z(), circleRadius.asFloat(), 64, 1f, side);
// 竖直“墙”：32 条线段，从 lower 环连到 y 环
for (int i = 0; i < 32; i++) { 角度 = i*2π/32; 两点投影后 draw.line(..., 1f, side); }
```

**CaptureMark**（上游旋转 billboard 的线条近似）：中心 `(t.x(), t.y() + t.height()*0.5, t.z())`；`rotation = (float)(-((timeSeconds * rotSpeed * 60.0) % 360.0))`，`s = espSize * 0.5`；4 个角用相机基向量：

```
wx = cx + (cos(a)*projection.rightX() + sin(a)*projection.upX()) * s;  // y/z 同理
a = toRadians(rotation + k * 90)
```

角颜色 `blend(espMain, espSecond, (sin(k*π/2 + timeSeconds*waveSpeed) + 1) / 2)`；相邻可见角之间 `draw.line(..., 2f, 该角颜色)`。

**Firefly**（上游轨迹的文本近似）：`age = Entity#tickCount`（`bridge().readField(entity, ClassType.ENTITY, "tickCount")`，缺失为 0）；`w = t.width()`，`halfH = t.height()*0.5`；三层 `j=0..2` × `i=0..fireflyLength`：

```
radians = toRadians((((i / 1.5f) + age) * factor + j * 120) % (factor * 360));
sinQuad = sin(toRadians(age * 2.5f + i * (j + 1)) * amplitude) / shaking;
x = t.x() + cos(radians) * w;
y = t.y() + halfH + sinQuad;
z = t.z() + sin(radians) * w;
offset = i / (float) length;
size = 2f + 6f * offset;              // 逻辑像素
project(x,y,z,points[0]); if (visible) draw.text("✦", points[0].x, points[0].y, size, color);
```

`color` 按 `fireflyColorMode`：

- `Solid`：`fireflyColor.argb()`。
- `Blend`：`wave = (sin(age*colorSpeed*0.25f + offset*2π + j)+1)/2`；`blend(fireflyColor, fireflyColor2, clamp(wave*fireflyColorMix,0,1))`。
- `Rainbow`：`hue = frac(age*0.01f*rainbowSpeed + offset + j*0.17f)`；用 `java.awt.Color.HSBtoRGB(hue, saturation, brightness)` 取 RGB，alpha 用 `fireflyColor` 的 alpha。

`frac` 自己写（`x - Math.floor(x)`）。

**Deobf**（命中特效的线框近似）：遍历 `deobfEffects`：

```
long now = System.currentTimeMillis();
float intro = Math.min(1f, (now - e.startedAtMs) / 1600f);
double size = deobfSize.get() * intro;
if (size <= 0) continue;
double time = now * 0.001;
double y = 实体 y + deobfFlyHeight.get() * 0.1 + sin(time * waveSpeed.get()) * deobfWobble.get();
float rotation = (float)((now * 0.001 * deobfSpins.get() * 60.0) % 360.0);
int color = espSecond.argb();
if (e.killedAtMs >= 0) color = withAlpha(color, Math.max(0f, 1f - (now - e.killedAtMs) / 1800f));
```

画一个绕 Y 轴旋转的方框：底面和顶面各 4 个角（`angle = rotation + k*90`，`px = x + cos(angle)*size`，`pz = z + sin(angle)*size`，`py` 分别为实体脚部 y 与 `y + size*2`），画 2 个水平四边形（每组 4 条线）+ 4 条竖直边 + 中心 `draw.text("◈", ...)` 用 `espMain`。不加载任何纹理。

颜色工具：`withAlpha(int argb, float factor)` 保留 RGB，`alpha = round(((argb >>> 24) & 255) * clamp(factor, 0, 1))`；`blend(int a, int b, float t)` 对 RGB 线性插值、alpha 取 `a` 的 alpha（与旧移植一致）。

### 5. 注册模块

`client/src/main/java/dev/nocturne/client/NocturneClient.java`：

- 恢复 `registerModules()` 私有方法（当前已被删除）：
  ```java
  private void registerModules() {
      modules.register(new dev.nocturne.client.module.modules.combat.KillAuraModule());
  }
  ```
- `install()` 第一行调用 `registerModules();`，位置在所有配置读取之前；随后保留现有 `moduleConfig = new ModuleConfigStore(modules); moduleConfig.load();` 顺序，使旧配置里的 “Kill Aura” 开关/设置能按名恢复。
- 更新 `install()` 中“当前没有任何内置模块”的注释为“只注册已移植模块”。

### 6. 测试

`client/src/test/java/dev/nocturne/client/control/TargetManagerFilterTest.java`：

- `setUp()` 的 `TestMapping` 增加 `.classOverride(ClassType.MOB, Living.class)`（否则现有 `addMob` 的替身不属于任何类别，旧用例会失败）。
- 给替身 `Living` 增加：
  ```java
  public Vec eye;          // 默认 null → 回退脚部
  public Vec getEyePosition(float partialTicks) { return eye; }
  public Vec getEyePosition() { return eye; }
  public Object box;       // 默认 null → 回退脚部距离
  public Object getBoundingBox() { return box; }
  ```
  新增静态 `Box` 类，含 `public double minX, minY, minZ, maxX, maxY, maxZ;`，并在需要包围盒的用例里 `.classOverride(ClassType.AABB, Box.class)`。
- 新增用例：
  1. `villagersAnimalsWaterAmbientObeyTheirOwnSwitches`：分别用继承 `Living` 的 `Villager`/`AnimalMob`/`WaterMob`/`Ambient` 替身 + `classOverride(VILLAGER/ANIMAL/WATER_MOB/AMBIENT, ...)`；断言只开 `players` 时为 0，开对应开关后各自单个可见；`WaterMob` 同时是 `Animal` 子类时仍归 Water（验证检查顺序）。
  2. `armorStandsAreNeverTargets`：`classOverride(ARMOR_STAND, ...)` 的替身即使 `mobs=true` 也不出现。
  3. `distanceUsesEyeToBoundingBox`：self 在 `(0,0,0)`、eye `(0,1.62,0)`，目标脚部 `(3,0,0)`、box `2.5..3.5 / 0..1.8 / -0.3..0.3`；断言 `Target.distance()` ≈ 2.5（脚部距离是 3.0），`aimY()` 落在 `0..1.62` 内。
- 新增 `client/src/test/java/dev/nocturne/client/render/WorldGeometryTest.java`：用 `WorldProjection.update(0,0,0, 0,0, 70, 100,100)` + 记录 `line` 的假 `OverlayDraw`；对 `box(-0.5,-0.5,2, 0.5,0.5,3)` 断言恰好 12 次 `line` 调用、坐标都在视口内；对 `circle(...,radius=1,segments=8...)` 断言 8 次。

## Critical files & anchors

- `client/src/main/java/dev/nocturne/client/module/modules/combat/KillAuraModule.java`（新建，全部行为）
- `client/src/main/java/dev/nocturne/client/control/TargetManager.java`（`Options`、`Target`、`consider`、`position`）
- `client/src/main/java/dev/nocturne/client/mapping/ClassType.java`（`AMBIENT` 附近追加三个常量）
- `client/src/main/java/dev/nocturne/client/NocturneClient.java`（`install()` 与新增 `registerModules()`）
- `tools/mapping/member-aliases.txt` + `tools/mapping/aliases-1.8.9.toml` + `tools/mapping/aliases-1.12.2.toml`（类别名与 legacy 桥接）

## Verification

工作目录 `C:/Users/liuyyx/Desktop/nocturne-client/nocturne-client`。

1. 映射表：
   - `python tools/mapping/generate.py` → 66 份表重写，输出每个版本 `resolved` 行。
   - `python tools/mapping/generate.py --check` → 退出码 0（含 javap）。若默认 javap 路径不存在，先 `export NOCTURNE_JAVAP=<本机 JDK 的 javap.exe>`。
   - `python tools/mapping/generate.py --report` → `VILLAGER` / `MONSTER` / `WATER_MOB` 没有 `ambiguous` 条目。
2. 单测：
   ```bash
   export JAVA_HOME="C:\Program Files\Microsoft\jdk-21.0.12.8-hotspot"
   "C:\Users\liuyyx\.gradle\wrapper\dists\gradle-9.2.1-bin\9fgc9dy84duywbml6zqd0l4mo\gradle-9.2.1\bin\gradle.bat" test --console=plain --no-daemon
   ```
   期望 `BUILD SUCCESSFUL`、0 失败；新增的三个 TargetManager 用例与 WorldGeometryTest 必须通过。
3. 打包：
   ```bash
   "…/gradle.bat" build --console=plain --no-daemon
   ```
   期望 `BUILD SUCCESSFUL`，`dist/build/libs/nocturne-<version>.jar` 与 `nocturne-launcher-<version>.jar` 均存在，`verifyJarBytecode` 通过。
4. 真机（能启动游戏时）：对 1.21.1 执行 `java -jar dist/build/libs/nocturne-launcher-*.jar --pid=<n>`；GUI 开启 “Kill Aura”。可观察：目标在 Search Range 内被逐步转向（Rotation Speed），1.8 模式按 CPS 节奏攻击、1.9+ 只在冷却满时攻击；目标受伤且挥手可见；切到 Circle 有旋转半径环、切 CaptureMark 有旋转菱形、切 Firefly 有环绕粒子、切 Deobf 命中后出现旋转线框。任何一格未跑就按仓库规则在 `docs/VERSION-MATRIX.md` 记“未验证”，不得写“应该可用”。

## Assumptions & contingencies

- `Member-aliases` 的类别名解析失败（某版本确实没有 Villager/WaterAnimal/Monster）→ 生成器写 `absent`，`TargetManager` 按“未命中”落到 `others` 或 `mobs`，不补手写表。
- `actions().pick()` 在该版本不可用（`isAvailable("pick") == false`）→ 攻击退化为不做射线校验（hitSelect 打开时因拿不到实体命中而不攻击）；沿用动作层的一次性日志，模块不额外刷屏。
- `actions().attack()` 返回 false（被 Gate 取消/能力缺失）→ 不挥手、不记 Deobf 特效、不推进 `nextAttack`。
- 没有 `Scaffold` 模块时 `Pause On Scaffold` 为 no-op；等该模块移植后按名自动生效。
- 目标过滤沿用 `Friends` 内建好友过滤；没有 AntiBot 模块，因此不移植上游的 `AntiBot.isBot` 排除（不新增未要求的模块）。
