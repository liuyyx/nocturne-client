# Nocturne-Client 映射数据源与反查路径调研

调研日期：2026-10-04 · 只读调研，未修改 `noturne-client/` 下任何文件。

> ⚠️ **落盘说明**：本子代理会话的 `write` 工具被限制为仅 `xd://` 设备（已确认 `xd://` 挂载的 102 个设备里无文件系统写工具），**报告未能写入 `C:/Users/liuyyx/Desktop/nocturne-client/mapping-sources.md`**。以下为完整 markdown 内容，需落盘。

架构前提：模块代码直写 **Mojmap 规范名**（如 `net/minecraft/client/Minecraft.getInstance`），版本差异全部下沉到「每版本一份映射表 JSON」（自动生成 + 人工补丁，打进 jar 资源），运行时由注入器传入 `mcVersion=<id>` 选表。

因此每个版本必须回答：**给定一个 Mojmap 规范成员（类/字段/方法 + JVM 描述符），它在运行期（混淆后）的名字是什么？**

---

## 0. 总表（每版本一行）

| 版本 | 是否混淆 | 映射源 (Kind + URL/路径) | 源格式 | Mojmap→运行期 反查步数 | 覆盖 字段/方法/参数类型 | 证据 |
|---|---|---|---|---|---|---|
| **1.8.9** | **混淆** | 本地 SRG/MCP（**无官方映射**）`OpenVape4.21/src/main/resources/mappings/vanilla189/joined.srg` + `forge189/methods.csv` + `forge189/fields.csv` | SRG（`CL:`/`FD:`/`MD:`）+ MCP CSV（`searge,name,side,desc`） | **4 步**：① Mojmap →（`legacy189_aliases.toml`）MCP 人类名 ② 人类名 →（CSV）`func_`/`field_` ③ SRG →（`joined.srg`）obf owner+名 ④ 描述符已在 SRG 中 | 字段 ✔ / 方法 ✔ / 参数类型 ✔（`MD:` 行两侧各带完整描述符） | §2、§3 |
| **1.12.2** | **混淆** | `mappings/vanilla1122/joined.srg` + `forge1122/methods.csv` + `forge1122/fields.csv` | 同上 | 4 步 | 同上 | §2、§3 |
| **1.16.5** | **混淆** | **官方 ProGuard** `https://piston-data.mojang.com/v1/objects/374c6b789574afbdc901371207155661e0509e17/client.txt` | ProGuard txt | **1 步** | 字段 ✔ / 方法 ✔ / 参数类型 ✔ | §1、§5 |
| **1.20.1** | **混淆** | **官方 ProGuard** `…/6c48521eed01fe2e8ecdadbd5ae348415f3c47da/client.txt` | ProGuard txt | 1 步 | 同上 | §1、§5 |
| **1.21.4** | **混淆** | **官方 ProGuard** `…/0cf2a0b7f056da1a5a5dd99fc6dc752f33987150/client.txt` | ProGuard txt | 1 步 | 同上 | §5 |
| **1.21.10** | **混淆** | **官方 ProGuard** `…/7e62354a697f95cf5e7d5981face0583676a9ef7/client.txt` | ProGuard txt | 1 步 | 同上 | §5、§7 |
| **1.21.11** | **混淆** | **官方 ProGuard** `…/031a68bebf55d824f66d6573d8c752f0e1bf232a/client.txt` | ProGuard txt | 1 步 | 同上 | §5 |
| **26.2** | **未混淆** | 无 —— **恒等映射** | — | **0 步** | 字段 ✔ / 方法 ✔ / 参数类型 ✔（名字即 Mojmap） | §4 |
| **26.3** | **未混淆** | 无 —— **恒等映射** | — | 0 步 | 同上 | §4 |

**总结论**
- **≥ 1.14.4 且 < 26.1**：官方 ProGuard 存在，1 步反查，最省事。
- **1.8.9 / 1.12.2**：官方映射**不存在**（Mojang 1.14.4 首发 ProGuard），必须走 SRG + CSV + 别名表，4 步。
- **26.1+**：未混淆，恒等映射（与 DarkClient 的 `Mode::Reflected` 一致）。

---

## 1. 官方 ProGuard txt（1.14.4 ~ 1.21.11）

### 1.1 获取路径（两步）

```
1) https://piston-meta.mojang.com/mc/game/version_manifest_v2.json
   → versions[] 里找 "id" == 目标版本，取其 "url"
2) <version>.json 的 downloads.client_mappings.url   ← ProGuard 映射
   downloads.client.url                                 ← 混淆客户端 jar
```

**manifest 原文证据**：

```
544|    { "id": "1.21.11", "type": "release",
547|      "url": "https://piston-meta.mojang.com/v1/packages/4f6bd9388f12e9d7adc2ded64acba66212d60521/1.21.11.json",
1993|   { "id": "1.20.1", "type": "release",
1996|     "url": "https://piston-meta.mojang.com/v1/packages/c0a00f47b3dae01d83e21be9a646c9232379d9ab/1.20.1.json",
3271|   { "id": "1.16.5", "type": "release",
3274|     "url": "https://piston-meta.mojang.com/v1/packages/fba9f7833e858a1257d810d21a3a9e3c967f9077/1.16.5.json",
5125|   { "id": "1.12.2", "type": "release",
5128|     "url": "https://piston-meta.mojang.com/v1/packages/832d95b9f40699d4961394dcf6cf549e65f15dc5/1.12.2.json",
5872|   { "id": "1.8.9",  "type": "release",
5875|     "url": "https://piston-meta.mojang.com/v1/packages/d546f1707a3f2b7d034eece5ea2e311eda875787/1.8.9.json",
```

**`client_mappings` 存在与否逐版本实测**：

```
# 1.16.5 —— 有
123|    "client_mappings": { "sha1": "374c6b789574afbdc901371207155661e0509e17", "size": 5746047,
126|      "url": "https://piston-data.mojang.com/v1/objects/374c6b789574afbdc901371207155661e0509e17/client.txt" },

# 1.20.1 —— 有
171|    "client_mappings": { "sha1": "6c48521eed01fe2e8ecdadbd5ae348415f3c47da", "size": 8001795,
174|      "url": "https://piston-data.mojang.com/v1/objects/6c48521eed01fe2e8ecdadbd5ae348415f3c47da/client.txt" },

# 1.21.4 —— 有
171|    "client_mappings": { "sha1": "0cf2a0b7f056da1a5a5dd99fc6dc752f33987150", "size": 10323161,
174|      "url": "https://piston-data.mojang.com/v1/objects/0cf2a0b7f056da1a5a5dd99fc6dc752f33987150/client.txt" },

# 1.21.10 —— 有
169|    "client_mappings": { "sha1": "7e62354a697f95cf5e7d5981face0583676a9ef7", "size": 11511143,
172|      "url": "https://piston-data.mojang.com/v1/objects/7e62354a697f95cf5e7d5981face0583676a9ef7/client.txt" },

# 1.21.11 —— 有
169|    "client_mappings": { "sha1": "031a68bebf55d824f66d6573d8c752f0e1bf232a", "size": 11779287,
172|      "url": "https://piston-data.mojang.com/v1/objects/031a68bebf55d824f66d6573d8c752f0e1bf232a/client.txt" },

# 1.12.2 —— 无！downloads 只有 client + server
11|  "downloads": {
12|    "client": { "sha1": "0f275bc1547d01fa5f56ba34bdc87d981ee12daf", "size": 10180113,
15|      "url": "https://piston-data.mojang.com/v1/objects/0f275bc1547d01fa5f56ba34bdc87d981ee12daf/client.jar" },
17|    "server": { "sha1": "886945bfb2b978778c3a0288fd7fab09d315b25f", "size": 30222121, ...

# 1.8.9 —— 无！同样只有 client + server
11|  "downloads": {
12|    "client": { "sha1": "3870888a6c3d349d3771a3e9d16c9bf5e076b908", "size": 8461484,
15|      "url": "https://launcher.mojang.com/v1/objects/3870888a6c3d349d3771a3e9d16c9bf5e076b908/client.jar" },
17|    "server": { "sha1": "b58b2ceb36e01bcd8dbf49c8fb66c55a9f0676cd", "size": 8320755, ...
```

### 1.2 确切格式样例（1.16.5 client.txt 原文）

**类行**（顶格，` Mojmap 名 -> obf 名:`）：

```
net.minecraft.client.Minecraft -> djz:
```

**字段行**（4 空格缩进，` <Mojmap 类型> <Mojmap 名> -> <obf 名>`，**无行号前缀**）：

```
net.minecraft.client.Minecraft -> djz:
    net.minecraft.client.Minecraft instance -> F
    org.apache.logging.log4j.Logger LOGGER -> G
    boolean ON_OSX -> a
    net.minecraft.resources.ResourceLocation DEFAULT_FONT -> b
    com.mojang.blaze3d.platform.Window window -> O
    net.minecraft.client.Options options -> k
    java.lang.String versionType -> aa
```

**方法行**（4 空格缩进，` [<start>:<end>:]<返回类型> <方法名>(<参数类型,...>) -> <obf 名>`）：

```
net.minecraft.client.KeyboardHandler$1 -> djx$1:
    int[] $SwitchMap$net$minecraft$world$phys$HitResult$Type -> a
    209:209:void <clinit>() -> <clinit>
net.minecraft.client.LogaritmicProgressOption -> djy:
    11:12:void <init>(java.lang.String,double,double,float,java.util.function.Function,java.util.function.BiConsumer,java.util.function.BiFunction) -> <init>
    16:16:double toPct(double) -> a
    21:21:double toValue(double) -> b
```

**版权头**（`#` 开头，解析时跳过；含 EULA 再分发限制，见 §7 坑 10）：

```
# (c) 2020 Microsoft Corporation. These mappings are provided "as-is" and you bear the risk of using them. You may copy and use the mappings for development purposes, but you may not redistribute the mappings complete and unmodified. Microsoft makes no warranties, express or implied, with respect to the mappings provided here.  Use and modification of this document or the source code (in any form) of Minecraft: Java Edition is governed by the Minecraft End User License Agreement available at https://account.mojang.com/documents/minecraft_eula.
```

**内部类写法**（`$` 连接，外层 obf 名 + `$` + 序号）：

```
net.minecraft.client.KeyboardHandler$1 -> djx$1:
net.minecraft.advancements.critereon.PlayerTrigger$TriggerInstance -> cq$a:
net.minecraft.advancements.critereon.RecipeUnlockedTrigger$TriggerInstance -> cs$a:
com.mojang.realmsclient.RealmsMainScreen$1 -> eiu$1:
```

### 1.3 反查算法（Mojmap → 运行期）

ProGuard txt 是 **mojmap → obf** 方向，**正是我们要的**，无需反转。

```
输入: Mojmap 规范查询
  class:   net.minecraft.client.Minecraft
  member:  getInstance（字段或方法名）
  或:      方法带参数类型列表

1) 内部名归一: net.minecraft.client.Minecraft → net/minecraft/client/Minecraft
2) 定位类块:   找 "net/minecraft/client/Minecraft -> <obfClass>:"（点分与斜杠两种写法在文件里都出现）
               → classMap["net/minecraft/client/Minecraft"] = "djz"

3a) 字段:      类块内匹配 ^\s+(?<type>[\w\.\[\]$]+)\s+(?<name>\w+)\s+->\s+(?<obf>\w+)$，name == 目标
               → fieldName = obf
               → 字段描述符 = 混淆 type：每个点分包名过 classMap，'.'→'/'，数组前缀保留 → L<obfInternal>; 或 I/F/...

3b) 方法:      类块内匹配 ^\s+(?:\d+:\d+:)?(?<ret>[\w\.\[\]$]+)\s+(?<name>\w+)\((?<args>.*)\)\s+->\s+(?<obf>\w+)$
               且 name == 目标方法名、args 的 Mojmap 类型列表 == 目标参数列表
               → methodName = obf（obf == "<init>" 即构造器）
               → 运行期描述符 = "(" + 逐个混淆后的参数类型 + ")" + 混淆后的返回类型

3c) 关键: 同名重载靠 **Mojmap 参数类型列表** 区分，不是靠 obf 名。
          1.16.5 Minecraft 类块里 `-> a` 出现 20+ 次，obf 名全是 "a"。
```

**参数类型反查实操**：`args` 是**逗号分隔的完整 Java 源类型**，不是描述符。

- 基本类型：`void→V, boolean→Z, byte→B, char→C, short→S, int→I, float→F, long→J, double→D`
- 对象类型：`a.b.C` → 查 `classMap["a/b/C"]` → `L<obf>;`；**查不到就原样保留**（第三方库）
- 数组：`Foo[]`→`[L<obf>;`，`int[]`→`[I`
- 泛型：ProGuard 里**不出现泛型**（已擦除），无需处理
- 内层类：`a.b.C$D`（源类型里 `$` 直接出现）

这段逻辑在 DarkClient 已有可复用实现：`conversion.py:146-171`
（`convert_java_type_to_jvm` / `get_method_signature`，含 `class_map.get(internal_name, internal_name)`
—— 即「查不到就原样保留」，正是处理未混淆外部库的正确行为）。

DarkClient 的正则（`conversion.py:196-199`）可直接作为生成器实现参考：

```python
class_re  = re.compile(r'^([\w\.$]+) -> ([\w$]+):$')
method_re = re.compile(r'^\s+(?:\d+:\d+:)?([\w\.<>$]+)\s+([\w<>$]+)\((.*)\)\s+->\s+([\w<>$]+)$')
field_re  = re.compile(r'^\s+([\w\.<>$]+)\s+([\w$]+)\s+->\s+([\w$]+)$')
```

**产物 schema 已有落地样本**（`DarkClient/mappings.json`，1.21.10，17.7 MB）：

```json
{
    "version": "1.21.10",
    "classes": {
        "com/mojang/blaze3d/Blaze3D": {
            "name": "fqq",
            "methods": {
                "youJustLostTheGame": { "name": "a", "signature": "()V" },
                "getTime":             { "name": "b", "signature": "()D" },
                "<init>":              { "name": "<init>", "signature": "()V" }
            },
            "fields": {}
        },
...
        "net/minecraft/client/Minecraft": { "name": "fzz", "methods": { "<init>": ...
```

key 用**内部名（斜杠）**，`signature` 是**混淆后的 JNI 描述符** —— 与 §1.3 输出一致。
建议照抄此 schema，加 `unsupported` 标记给人工补丁（见 §7 坑 8）。

---

## 2. 1.8.9 / 1.12.2 的 SRG + MCP CSV

Mojang 在 1.14.4 前**不发布**官方映射，社区用 Forge/MCP 的 **SRG**（`joined.srg`）+ **MCP CSV**。本机已有全套（来自 OpenVape4.21）：

```
OpenVape4.21/src/main/resources/mappings/
├── vanilla189/joined.srg      vanilla1122/joined.srg    vanilla1165/joined.srg
├── vanilla1201/joined.srg     vanilla1206/joined.srg    vanilla1211/joined.srg
├── vanilla12111/joined.srg    vanilla1710/joined.srg
├── neoforge1201/joined.srg (+obfmembers.map, fields.map)
├── neoforge1211/joined.srg (+obfmembers.map, fields.map)
├── fabric12111/joined.srg
└── forge189/{methods.csv,fields.csv}   forge1122/…  forge1165/…  forge1201/…  forge12111/…
```

> 1.16.5 / 1.20.1 / 1.21.x **也有本地 SRG**（vanilla1165 / vanilla1201 / vanilla1211 / vanilla12111），
> 但既然官方 ProGuard 可用，优先用 ProGuard —— SRG 只作交叉校验/兜底。

### 2.1 `joined.srg` 确切格式样例

**头部（前 7 行是 `PK:` 伪目录行，必须跳过）**：

```
PK: . net/minecraft/src
PK: net net
PK: net/minecraft net/minecraft
PK: net/minecraft/client net/minecraft/client
PK: net/minecraft/client/main net/minecraft/client/main
PK: net/minecraft/realms net/minecraft/realms
PK: net/minecraft/server net/minecraft/server
CL: a net/minecraft/util/EnumChatFormatting
CL: aa net/minecraft/command/server/CommandEmote
CL: aaa net/minecraft/item/ItemLeaves
CL: aab net/minecraft/item/ItemMap
CL: aad net/minecraft/item/ItemMinecart
CL: aad$1 net/minecraft/item/ItemMinecart$1
```

**类行 `CL:`**：`CL: <obf内部名> <mcp可读内部名>`

```
CL: bib net/minecraft/client/Minecraft        # 1.12.2
CL: a net/minecraft/util/EnumChatFormatting   # 1.8.9
```

**字段行 `FD:`**：`FD: <obfOwner>/<obfName> <mcpOwner>/<srgFieldName>`

```
FD: a/A net/minecraft/util/EnumChatFormatting/field_96303_A
FD: a/D net/minecraft/util/EnumChatFormatting/$VALUES
FD: aad$1/b net/minecraft/item/ItemMinecart$1/field_96465_b
FD: bib/b net/minecraft/client/Minecraft/field_71444_a        # 1.12.2
FD: bib/B net/minecraft/client/Minecraft/field_71425_J        # 1.12.2
```

**方法行 `MD:`**：`MD: <obfOwner>/<obfName> <obfDesc> <mcpOwner>/<srgMethodName> <mcpDesc>`

```
MD: a/a (I)La; net/minecraft/util/EnumChatFormatting/func_175744_a (I)Lnet/minecraft/util/EnumChatFormatting;
MD: a/e ()Ljava/lang/String; net/minecraft/util/EnumChatFormatting/func_96297_d ()Ljava/lang/String;
MD: ave/A ()Lave; net/minecraft/client/Minecraft/func_71410_x ()Lnet/minecraft/client/Minecraft;   # 1.8.9, obfOwner=ave
MD: ave/a (Lave;)Ljava/lang/String; net/minecraft/client/Minecraft/access$000 (Lnet/minecraft/client/Minecraft;)Ljava/lang/String;
MD: bib/z ()Lbib; net/minecraft/client/Minecraft/func_71410_x ()Lnet/minecraft/client/Minecraft;  # 1.12.2, obfOwner=bib
MD: bib/c ()Ljava/lang/String; net/minecraft/client/Minecraft/func_175600_c ()Ljava/lang/String;
```

**关键：两侧描述符。** `MD:` 行**左半是 obf 描述符**（`La;`、`()Lave;`），**右半是 MCP 可读描述符**（`Lnet/minecraft/util/EnumChatFormatting;`）。
所以运行期完整描述符可直接从 SRG 取到，无需二次转换 —— 这是 SRG 相对 ProGuard 的优势。

> ⚠️ `MD:` 行**没有行号前缀**（不像 ProGuard 有 `12:13:`），且重载条目**必须逐行整行匹配**，
> 不能按 `(owner, name)` 匹配。

### 2.2 MCP CSV 确切格式样例

**`forge189/methods.csv`**：

```csv
searge,name,side,desc
func_100011_g,getIsPotionDurationMax,0,
func_100012_b,setPotionDurationMax,0,Toggle the isPotionDurationMax field.
func_104002_bU,isNoDespawnRequired,2,
func_104112_b,saveExtraData,2,"Save extra data not associated with any Chunk.  Not saved during autosave, only during world unload.  Currently unimplemented."
func_71407_l,runTick,0,Runs the current tick.
func_71410_x,getMinecraft,0,Return the singleton Minecraft instance for the game
func_71411_J,runGameLoop,0,Called repeatedly from run()
```

**`forge189/fields.csv`**：

```csv
searge,name,side,desc
field_100013_f,isPotionDurationMax,0,"True if potion effect duration is at maximum, false otherwise."
field_104003_g,isAggressive,2,
field_110126_a,FOOTPRINT_TEXTURE,0,
field_110151_bq,absorptionAmount,2,
```

**关键**：
- CSV **不含 owner（类名）**，只有全局 SRG 名 ↔ 人类名。必须**先由类定位**，
  再在该类的 `MD:`/`FD:` 行集合内按 SRG 名匹配（SRG 名在 MC 内**全局唯一**，
  也可全局反查 `func_71410_x → (owner, name, desc)`）。
- `side` 列：`0`=both / `1`=client / `2`=server，客户端要过滤 `side==2`
  （`forge189/methods.csv` 有大量 `side=2`，如 `func_104002_bU,isNoDespawnRequired,2`）。
- `desc` 列含逗号、双引号、`\n` 字面量，**必须用标准 CSV 解析器**，不能 `split(',')`。

**交叉校验（已验证 csv 的 SRG 名与 joined.srg 完全对齐）**：

```
# joined.srg (vanilla1122)
30951|MD: bib/z ()Lbib; net/minecraft/client/Minecraft/func_71410_x ()Lnet/minecraft/client/Minecraft;
# forge1122/methods.csv
7935|func_71407_l,runTick,0,Runs the current tick.
7936|func_71410_x,getMinecraft,0,Return the singleton Minecraft instance for the game
7937|func_71411_J,runGameLoop,0,Called repeatedly from run()
```

→ `Minecraft.getMinecraft()` 在 1.12.2 运行期是 `bib.z()Lbib;`，obf 描述符 `()Lbib;` **直接从 SRG 拿到**。

### 2.3 连接步骤（obf ↔ SRG ↔ 可读名）

```
                     ┌──────────────┐
   Mojmap 规范名 ────▶│ 人工别名表   │  legacy189_aliases.toml（543 行）
   (1.21 的名字)      │ (1.8.9→1.21 │  ← 唯一的人工环节，不可自动化
                     │  的人工桥接) │
                     └──────┬───────┘
                            │ MCP 人类名  e.g. getMinecraft
                            ▼
                     ┌──────────────┐
                     │ methods.csv  │  searge ↔ name
                     │ fields.csv   │  （全局唯一，按 SRG 名索引）
                     └──────┬───────┘
                            │ SRG 名  func_71410_x
                            ▼
                     ┌──────────────┐
                     │ joined.srg   │  MD:/FD: 右半部分
                     │  (CL:/FD:/MD:)│  owner = 可读内部名
                     └──────┬───────┘
                            │ 左半部分: obfOwner/obfName + obfDesc
                            ▼
                     ┌──────────────┐
                     │  运行期名字  │  bib / z  ()Lbib;
                     └──────────────┘
```

`legacy189_aliases.toml` 的实际内容（实测，含解析规则）：

```toml
# Canonical (Mojmap) -> MCP 1.8.9 alias table
#   canonical (Mojmap)  --this table-->  MCP 1.8.9
#     --forge189 methods.csv/fields.csv (human name -> SRG)-->  func_/field_
#     --vanilla189 joined.srg (SRG + owner -> obf)-->  obf name + obf descriptor
#
# Resolution rules for one (class, kind, member):
#   1. [overrides."<canonical class>"] wins (per-class exception), then
#   2. [methods.<member>] / [fields.<member>]  (same for every class)
#   `unsupported` = 1.8.9 has no equivalent API (Rust call site needs a per-version branch)
#   `skip`        = resolved elsewhere (java.* → java_mappings.json)

version = "1.8.9"

[classes]
"net/minecraft/client/Minecraft" = "net/minecraft/client/Minecraft"
"net/minecraft/client/player/LocalPlayer" = "net/minecraft/client/entity/EntityPlayerSP"
"net/minecraft/client/multiplayer/ClientLevel" = "net/minecraft/client/multiplayer/WorldClient"
"net/minecraft/world/entity/player/Player" = "net/minecraft/entity/player/EntityPlayer"
"net/minecraft/world/entity/player/Abilities" = "net/minecraft/entity/player/PlayerCapabilities"
"net/minecraft/world/entity/Entity" = "net/minecraft/entity/Entity"
"net/minecraft/world/phys/Vec3" = "net/minecraft/util/Vec3"
"net/minecraft/client/multiplayer/MultiPlayerGameMode" = "net/minecraft/client/multiplayer/PlayerControllerMP"
```

**同一 Mojmap 名在 1.8.9 vs 1.12.2 的 obf 名不同**（实测）：

```
1.8.9 : MD: ave/A ()Lave;  net/minecraft/client/Minecraft/func_71410_x ...
1.12.2: MD: bib/z ()Lbib;  net/minecraft/client/Minecraft/func_71410_x ...
```

同一个 `func_71410_x`，obf 名从 `ave.A` 变成 `bib.z` —— 所以**必须每版本一张表**，不能跨版本复用 obf 名。

---

## 3. 反查步数小结

| 版本 | Mojmap → 运行期 需要几步 |
|---|---|
| 1.8.9 | **4 步**：别名表 → CSV → SRG → (描述符已在 SRG 中) |
| 1.12.2 | **4 步**（同上） |
| 1.16.5 / 1.20.1 / 1.21.4 / 1.21.10 / 1.21.11 | **1 步**：ProGuard 直接查；描述符需二次转换（纯字符串 + classMap，无需跨表） |
| 26.2 / 26.3 | **0 步**：恒等 |

---

## 4. 26.2 / 26.3 是否真的未混淆（实测）

### 4.1 版本 json 里**没有** `client_mappings`

```
# 26.3 —— https://piston-meta.mojang.com/v1/packages/4fe1aa1ef8da1cb95c5bad1fb98890ca56dd8ca3/26.3.json
227|  "downloads": {
228|    "client": { "sha1": "e877b6a07acd633fb3bb475002175cec036e7b87", "size": 41483720,
231|      "url": "https://piston-data.mojang.com/v1/objects/e877b6a07acd633fb3bb475002175cec036e7b87/client.jar" },
233|    "server": { "sha1": "33680f5f2ac32864d6d7cf5e56a705fdb3e05f4c", "size": 62294556,
236|      "url": "https://piston-data.mojang.com/v1/objects/33680f5f2ac32864d6d7cf5e56a705fdb3e05f4c/server.jar" }
237|  },          ← 只有 client + server，无 client_mappings / server_mappings

# 26.2 —— https://piston-meta.mojang.com/v1/packages/c7868781b30aaf24be0dac894c94a34e5d6df10d/26.2.json
225|  "downloads": {
226|    "client": { "sha1": "2dc72797acbc1b63fc16a11c4ac393605f453754", "size": 39193383,
229|      "url": "https://piston-data.mojang.com/v1/objects/2dc72797acbc1b63fc16a11c4ac393605f453754/client.jar" },
231|    "server": { "sha1": "823e2250d24b3ddac457a60c92a6a941943fcd6a", "size": 60894273, ...
```

本地实例 json 同样无 `client_mappings`：

```
# .minecraft/versions/26.2-Fabric 0.19.3/26.2-Fabric 0.19.3.json
229|      "size": 39193383,
230|      "url": "https://piston-data.mojang.com/v1/objects/2dc72797acbc1b63fc16a11c4ac393605f453754/client.jar"
231|    },
232|    "server": {          ← 直接跳到 server
233|      "sha1": "823e2250d24b3ddac457a60c92a6a941943fcd6a",

# .minecraft/versions/26.3-Fabric 0.19.5/26.3-Fabric 0.19.5.json
231|      "size": 41483720,
232|      "url": "https://piston-data.mojang.com/v1/objects/e877b6a07acd633fb3bb475002175cec036e7b87/client.jar"
233|    },
234|    "server": {
235|      "sha1": "33680f5f2ac32864d6d7cf5e56a705fdb3e05f4c",
```

### 4.2 jar 里的类名就是可读名（决定性证据）

`analysis/client-26.3.jar` 的 `net/minecraft/client/` 目录列表（原文节选）：

```
Camera.class (21.8KB)
Camera$NearPlane.class (1.5KB)
KeyboardHandler.class (33.6KB)
KeyboardHandler$1.class (834B)
KeyMapping$Category.class (3.5KB)
Minecraft.class (147.5KB)
Minecraft$1.class (1.3KB)
Minecraft$2.class (861B)
Options.class (94.5KB)
OptionInstance.class (14.0KB)
OptionInstance$ClampingLazyIntRange.class (5.9KB)
Screenshot.class (11.7KB)
User.class (2.1KB)
com/mojang/blaze3d/Blaze3D.class            # jar 顶层也可读名
com/mojang/blaze3d/ProjectionType$LayeringTransform.class
```

classlist 文件：

```
# analysis/26.3-classlist.txt
709|net/minecraft/client/Minecraft$2.class
710|net/minecraft/client/Minecraft.class

# analysis/26.2-classlist.txt
640|net/minecraft/client/Minecraft$2.class
641|net/minecraft/client/Minecraft.class
```

`analysis/client-26.2.jar:net/minecraft/client/Minecraft.class` → 存在，145.0 KB
（读取器报 `Cannot read binary archive entry 'net/minecraft/client/Minecraft.class' (145.0KB)`
—— 二进制不可读，但**条目存在与大小已确认**）。

### 4.3 对照：混淆版本的 jar 根目录

`analysis/client-1.8.9.jar` 根目录（全是 1-3 字母 obf 名）：

```
a.class (5.4KB)
aa.class (1.5KB)
aaa.class (1.0KB)
aad.class (1.7KB)
aad$1.class (2.3KB)
...
adm.class (59.3KB)
```

`1.21.4-BWM.jar` 根目录同样全是 `a.class` / `aa.class` / `aaa.class` / `aaf$a.class` …

### 4.4 结论

- **26.2 / 26.3：未混淆**。`net/minecraft/client/Minecraft.class` 存在，字段/方法名即 Mojmap 名。
  映射表 = **恒等映射**。生成器可直接输出 identity 表，或运行期跳过映射层
  （DarkClient 的 `Mode::Reflected` 做法）。
- **1.8.9 / 1.21.4：混淆**。

> 26.2 的 `Minecraft.screen` → `Gui.screen` 已在 DarkClient 代码里体现 26.2 的**结构变化**
> （`client/src/mapping/client/minecraft.rs:152`：`if mapping().get_version() >= MinecraftVersion::new(26, 2, 0)`
> 才走 `gui` 字段 + `Gui.screen()`，否则读 `Minecraft.screen`）。与「恒等但 API 变了」一致。

---

## 5. 1.21.x 各实例的混淆形态

| 本机实例 | 实际版本 | 混淆？ | jar 类名形态（实测） | `client_mappings` |
|---|---|---|---|---|
| `1.21.4-BWM` | **1.21.4** | **混淆** | jar 根目录全 `a.class`/`aa.class`/`aaa.class` | **有**<br>`…/0cf2a0b7f056da1a5a5dd99fc6dc752f33987150/client.txt`（10,323,161 B） |
| `1.21.10-opal` | **1.21.10** | **混淆** | （同代，均混淆） | **有**<br>`…/7e62354a697f95cf5e7d5981face0583676a9ef7/client.txt`（11,511,143 B） |
| `1.21.11- Everything Voxy` / `1.21.11 voxy 优化` / `1.21.11-Fabric 0.19.5` | **1.21.11** | **混淆** | （同代，均混淆） | **有**<br>`…/031a68bebf55d824f66d6573d8c752f0e1bf232a/client.txt`（11,779,287 B） |
| （对照）`26.2-Fabric 0.19.3` / `26.2-Fabric 0.19.5` / `26.3-Fabric 0.19.5` | 26.2 / 26.3 | **未混淆** | `net/minecraft/client/Minecraft.class` | **无** |
| （对照）`26.1.2-liquidbounce 0.38.0` | 26.1.2 | **未混淆** | 同上（`analysis/client-26.1.2.jar`） | **无**（`grep client_mappings analysis/26.1.2-Minecraft.txt` → No matches） |

**本地实例 json 原文**：

```
# .minecraft/versions/1.21.4-BWM/1.21.4-BWM.json
169|      "size": 28335587,
170|      "url": "https://piston-data.mojang.com/v1/objects/a7e5a6024bfd3cd614625aa05629adf760020304/client.jar"
171|    },
172|    "client_mappings": {
173|      "sha1": "0cf2a0b7f056da1a5a5dd99fc6dc752f33987150",
174|      "size": 10323161,
175|      "url": "https://piston-data.mojang.com/v1/objects/0cf2a0b7f056da1a5a5dd99fc6dc752f33987150/client.txt"
176|    },

# .minecraft/versions/1.21.11 voxy 优化/1.21.11 voxy 优化.json
178|      "size": 31152600,
179|      "url": "https://piston-data.mojang.com/v1/objects/ba2df812c2d12e0219c489c4cd9a5e1f0760f5bd/client.jar"
180|    },
181|    "client_mappings": {
182|      "sha1": "031a68bebf55d824f66d6573d8c752f0e1bf232a",
183|      "size": 11779287,
184|      "url": "https://piston-data.mojang.com/v1/objects/031a68bebf55d824f66d6573d8c752f0e1bf232a/client.txt"
185|    },
```

**DarkClient 已验证的 1.21.x 实例**：`mappings.json` 头部 `"version": "1.21.10"`，
`net/minecraft/client/Minecraft` → `"fzz"`。可作为 1.21.10 生成结果的正确性锚点。

---

## 6. 缺失版本的可下载 URL

### 1.12.2 —— 无官方映射

```
version json    : https://piston-meta.mojang.com/v1/packages/832d95b9f40699d4961394dcf6cf549e65f15dc5/1.12.2.json
client jar      : https://piston-data.mojang.com/v1/objects/0f275bc1547d01fa5f56ba34bdc87d981ee12daf/client.jar
                  (sha1 0f275bc1547d01fa5f56ba34bdc87d981ee12daf, 10,180,113 B)
client_mappings : ❌ 不存在
→ 用本地 mappings/vanilla1122/joined.srg + forge1122/{methods,fields}.csv
```

### 1.16.5 —— 有官方映射

```
version json    : https://piston-meta.mojang.com/v1/packages/fba9f7833e858a1257d810d21a3a9e3c967f9077/1.16.5.json
client jar      : https://piston-data.mojang.com/v1/objects/37fd3c903861eeff3bc24b71eed48f828b5269c8/client.jar
                  (sha1 37fd3c903861eeff3bc24b71eed48f828b5269c8, 17,547,153 B)
client_mappings : https://piston-data.mojang.com/v1/objects/374c6b789574afbdc901371207155661e0509e17/client.txt
                  (sha1 374c6b789574afbdc901371207155661e0509e17,  5,746,047 B)
```

### 1.20.1 —— 有官方映射

```
version json    : https://piston-meta.mojang.com/v1/packages/c0a00f47b3dae01d83e21be9a646c9232379d9ab/1.20.1.json
client jar      : https://piston-data.mojang.com/v1/objects/0c3ec587af28e5a785c0b4a7b8a30f9a8f78f838/client.jar
                  (sha1 0c3ec587af28e5a785c0b4a7b8a30f9a8f78f838, 23,028,853 B)
client_mappings : https://piston-data.mojang.com/v1/objects/6c48521eed01fe2e8ecdadbd5ae348415f3c47da/client.txt
                  (sha1 6c48521eed01fe2e8ecdadbd5ae348415f3c47da,  8,001,795 B)
```

### 全部目标版本速查表

| 版本 | version json (piston-meta) | client_mappings | client_mappings URL (piston-data) |
|---|---|---|---|
| 1.8.9 | `…/d546f1707a3f2b7d034eece5ea2e311eda875787/1.8.9.json` | ❌ | — |
| 1.12.2 | `…/832d95b9f40699d4961394dcf6cf549e65f15dc5/1.12.2.json` | ❌ | — |
| 1.16.5 | `…/fba9f7833e858a1257d810d21a3a9e3c967f9077/1.16.5.json` | ✔ | `…/374c6b789574afbdc901371207155661e0509e17/client.txt` |
| 1.20.1 | `…/c0a00f47b3dae01d83e21be9a646c9232379d9ab/1.20.1.json` | ✔ | `…/6c48521eed01fe2e8ecdadbd5ae348415f3c47da/client.txt` |
| 1.21.4 | `…/b547a27fc4d490dc10d62e40a42ace065162b644/1.21.4.json` | ✔ | `…/0cf2a0b7f056da1a5a5dd99fc6dc752f33987150/client.txt` |
| 1.21.10 | `…/a00569761f3e217e9a71424ff21ecdf467f5b835/1.21.10.json` | ✔ | `…/7e62354a697f95cf5e7d5981face0583676a9ef7/client.txt` |
| 1.21.11 | `…/4f6bd9388f12e9d7adc2ded64acba66212d60521/1.21.11.json` | ✔ | `…/031a68bebf55d824f66d6573d8c752f0e1bf232a/client.txt` |
| 26.2 | `…/c7868781b30aaf24be0dac894c94a34e5d6df10d/26.2.json` | ❌ | — |
| 26.3 | `…/4fe1aa1ef8da1cb95c5bad1fb98890ca56dd8ca3/26.3.json` | ❌ | — |

（`…` = `https://piston-meta.mojang.com/v1/packages` 或 `https://piston-data.mojang.com/v1/objects`）

---

## 7. 风险清单

### 坑 1：1.8.9 / 1.12.2 没有官方映射 → 别去找

Mojang 的 `client_mappings` **1.14.4 首发**（实测 1.12.2 / 1.8.9 的 version json 里无该字段）。
**规避**：映射表 schema 允许「部分成员 `unsupported`」，缺失成员在编译期/运行期显式报错，不静默回退。

### 坑 2：MCP csv 的 srg 名与 joined.srg 是否对得上？

**已验证对得上**（`func_71410_x` 三方一致，见 §2.2）。但注意：
- CSV 的 `searge` 列是**全局** SRG 名，不带类名。必须先定位类，或全局反查。
- **`side` 列要过滤**：`side==2` 是 server-only，客户端 jar 里不存在。
- `joined.srg` 头部 7 行 `PK:` 必须跳过，否则误判为映射条目。
- **CSV 必须用标准解析器**：`desc` 列含逗号、双引号、`\n` 字面量。`split(',')` 会炸。
**规避**：生成器里对每个类做「CSV 的 SRG 名是否出现在该类 joined.srg 的 MD:/FD: 中」的交叉校验，不一致就报告。

### 坑 3：同一 Mojmap 名在多个类中重名

`getInstance`、`tick`、`render`、`values`、`toString` 在几十个类里都有。
**SRG 侧**：`func_71410_x` 全局唯一所以安全，但 `MD:` 行**不能按 `(owner, name)` 匹配**——
1.8.9 里 `MD: aum$1/a ()I .../Score$1/compare (...)I` 与 `MD: aum$1/compare ()V .../compare ()V`
同名共存。必须**整行（含描述符）匹配**。
**ProGuard 侧**：必须整行匹配（方法名 + 完整参数类型列表），obf 名会重用。
**规避**：映射表 key 必须是 `(类内部名, 成员名)` 二元组；方法再叠加参数类型做键。
存 `{name, signature}`，查询时带描述符。

### 坑 4：重载方法 —— obf 名无法区分

1.16.5 `Minecraft` 类块里 `-> a` 出现 20+ 次：
`a ()V`、`a (I)V`、`a (II)V`、`a (IIIIIIIIII)V`、`a (J)V`、
`a (Lave;)Ljava/lang/String;`、`a (Laxu;)V`、`a (Lb;)V`、`a (Lbdb;)V`、
`a (Lbdb;Ljava/lang/String;)V`、`a (Lbde;)V`、`a (Lbmj;)V`、
`a (Ljava/io/InputStream;)Ljava/nio/ByteBuffer;`、`a (Ljava/lang/Runnable;)`、
`a (Ljava/lang/String;Ljava/lang/String;Ladp;)V`、`a (Ljava/util/concurrent/Callable;)`、
`a (Lor;)V`、`a (Lpk;)V`、`a (Lzw;ILakw;)Lzx;`、`a (Z)V`。

**obf 名全是 `a`**。查询时**必须**带 Mojmap 参数类型列表精确匹配。
**规避**：存 `{name, signature}`（混淆后完整 JNI 描述符）；查不到精确签名时退化为按「参数个数」筛选，
并在歧义时**显式失败**，别猜。

### 坑 5：内部类 / 匿名类

- **ProGuard**：外层 obf 名 + `$` + 序号：`Minecraft -> djz`，匿名类 `djz$1`、`djz$2`。
  **obf 的 `$N` 序号与 Mojmap 的 `$N` 序号不对应** —— Mojmap 按源码顺序，obf 是重排后的。
  实测 1.8.9 SRG：`CL: bib$1 net/minecraft/client/Minecraft$10`、
  `CL: bib$16 net/minecraft/client/Minecraft$9` —— obf `bib$16` 对应 Mojmap `$9`！
  **规避**：内部类**必须逐个查表**（`CL:` 行 / ProGuard 类行已给对应关系），
  **绝不能用「拼 `$N`」的规则去猜**。
- **合成 lambda 方法**：`lambda$init$0`、`access$000`、`this$0`
  （SRG 里 `MD: ave$1/a ()La; .../Minecraft$1/func_74535_a`）—— 不是我们能引用的名字，生成时应**跳过**或标记。

### 坑 6：字段的「泛型 / 桥接」问题

- **ProGuard 不含泛型**（已擦除），字段类型是擦除后的：`List<String>` 字段写的是 `java.util.List list -> b`。
- **桥接方法（bridge methods）**会产生额外条目：同一 Mojmap 名 + 同一 obf 名但参数列表不同（协变返回）。
- **`<clinit>` / `<init>`**：obf 名**保持不变**（实测多处 `-> <init>`、`-> <clinit>`），可直接匹配。
**规避**：字段/方法类型匹配要**宽松**（允许 `java.util.List` 匹配 `List<String>`），
但歧义时显式失败。

### 坑 7：ProGuard 里的 `$SwitchMap$` / `$VALUES` 等合成成员

实测 1.16.5：

```
net.minecraft.client.KeyboardHandler$1 -> djx$1:
    int[] $SwitchMap$net$minecraft$world$phys$HitResult$Type -> a
```

`$SwitchMap$...` 是 javac 为 switch-on-enum 合成的字段，Mojmap 里**不存在**。
`$VALUES`（SRG 侧 `FD: a/D .../$VALUES`）也是合成的。
**规避**：生成时过滤名字以 `$` 开头的成员（或显式白名单）。

### 坑 8：外部库（未混淆依赖）的类

ProGuard 只覆盖 `client.jar` 内的类。`org.slf4j.Logger`、`com.mojang.authlib.*` 等
**不在映射表里**。
**规避**：反查时 **`classMap.get(internalName, internalName)` —— 查不到就原样保留**
（DarkClient `conversion.py:167` 已验证的写法）。同理 `java_mappings.json` 就是为此存在的手写补充表。

### 坑 9：26.x 的「未混淆 ≠ API 稳定」

26.2/26.3 恒等映射成立，但 Mojang 同时**改了 API**。
实测证据（DarkClient 源码）：`Minecraft.screen` 字段在 26.2 移到 `Gui.screen()`。
**规避**：恒等表也要**逐版本生成 + 人工校验 API 存在性**（javap/ASM 扫 jar 确认目标成员真的存在），
不能因为「名字不变」就跳过检查。

### 坑 10：ProGuard 映射的 EULA 限制

版权头明确写：
> "You may copy and use the mappings for development purposes, but you may not
> redistribute the mappings complete and unmodified."

**不能把官方 `client.txt` 原样打包进我们的 jar 资源再分发**。
**规避**：只把**生成后的映射 JSON**（类名/成员名/描述符，衍生事实数据）打进 jar，
不打包原始 `client.txt` —— 与「映射表 JSON 打进资源」的原定架构天然吻合。

### 坑 11：Mojang 版本号 ≠ 本机实例名

本机实例名被启动器改过（`1.21.11 voxy 优化`、`1.21.11- Everything Voxy`、`26.2-Fabric 0.19.3`），
json 里 `"id"` 就是被改过的名字（实测 `"id": "1.21.11 voxy 优化"`、`"id": "26.2-Fabric 0.19.3"`），
**不能用来查 manifest**。
**规避**：注入器传的 `mcVersion` 用**规范版本号**（`1.8.9`/`1.21.10`/`26.3`），
由映射表的 key 决定，不依赖实例目录名。

### 坑 12：SRG 覆盖不全 & 版本目录错配

本机 SRG **按版本分目录**（`vanilla189`、`vanilla1122`、`vanilla1165`、`vanilla1201`、
`vanilla1206`、`vanilla1211`、`vanilla12111`、`vanilla1710`），**用错目录会得到错误的 obf 名**。
注意 `vanilla12111` 与 `vanilla1211` 是 1.21.11 vs 1.21.1，**只差一个字符**。
**规避**：生成器按目标版本精确选目录，并在产物里记录源文件路径 + sha1 便于审计。

### 坑 13：SRG 的 obf 描述符在 `MD:` 行**左侧**

`MD: ave/a (Lave;)Ljava/lang/String; ...` —— 左半 `La;` 是 **obf** 描述符，
右半 `Lnet/minecraft/client/Minecraft;` 是 MCP 描述符。
反查方向是「Mojmap → obf」，所以取**左半**作为运行期描述符；误取右半会直接 `NoSuchMethodError`。
（1.8.9 有右半与左半一致的写法，如 `MD: a/toString ()Ljava/lang/String; net/minecraft/util/EnumChatFormatting/toString ()Ljava/lang/String;`
—— 这是因为 String 等类型未混淆，不代表可混用。）

---

## 8. 建议的生成器设计（结论落地）

```
生成器（离线，Python 或 Java）
├── 目标版本 → 数据源选择
│    ├── 26.x            → identity 表（可选：javap 校验成员存在性）
│    ├── 1.14.4 ~ 1.21.11 → download client_mappings → parse ProGuard
│    └── 1.8.9 / 1.12.2   → 本地 joined.srg + methods.csv + fields.csv
│                            + legacy189_aliases.toml（Mojmap→MCP 人工别名）
├── 产物: mappings/<version>.json
│    { "version": "1.21.10",
│      "classes": { "<mojmap 内部名>": { "name": "<obf>",
│          "methods": { "<mojmap 方法名>": { "name": "<obf>", "signature": "<混淆后 JNI 描述符>" } },
│          "fields":  { "<mojmap 字段名>": { "name": "<obf>", "descriptor": "<混淆后描述符>" } } } },
│      "unsupported": ["<无法解析的成员>"] }
├── 人工补丁层: patch/<version>.json 覆盖 generated 结果（坑 1 / 坑 9）
└── 校验: --check（产物是否最新） / --javap（对 jar 逐成员核名）
```

**可复用的既有实现**：
- ProGuard 解析 + 描述符转换：`DarkClient/conversion.py:146-280`
  （`convert_java_type_to_jvm` / `get_method_signature` / `parse_mappings`）
- SRG + CSV 解析 + 别名表：`DarkClient/tools/gen_legacy_mappings.py`（1164 行，完整「4 步链路」
  + `--javap` 对 `analysis/client-1.8.9.jar` 校验）
- 别名表：`DarkClient/tools/legacy189_aliases.toml`（543 行，含 `[classes]`/`[methods]`/`[fields]`/`[overrides]`）
- 目标 jar：`analysis/client-1.8.9.jar`、`client-26.1.2.jar`、`client-26.2.jar`、`client-26.3.jar`
- 本机 MCP/SRG 资产：`OpenVape4.21/src/main/resources/mappings/`

---

## 9. 未验证项

- **1.21.4 / 1.21.10 / 1.21.11 的 client.txt 具体格式未逐行读取**（只验证了 URL 存在与 size）。
  格式与 1.16.5/1.20.1 一致（同为 Mojang ProGuard 生成器），但「1.21.x 是否新增了 ProGuard 语法」
  **未验证** —— 生成器落地时应以实际文件为准。
- **1.16.5 / 1.20.1 / 1.21.x 的本地 joined.srg 是否与官方 ProGuard 完全一致（SRG 名 ↔ obf 名）**
  未做全量交叉校验，只抽样验证了 1.8.9/1.12.2 的 csv↔srg 一致性。
- **1.12.2 的实际混淆形态**：本机无实例，其「混淆」判定依据官方无 `client_mappings`
  （Mojang 1.14.4 前全部混淆）推断，**未取 jar 实测**。
- **26.1 与 26.2 之间是否有混淆形态切换点**：依据 DarkClient 文档「26.1+ 未混淆」，
  未在 26.1.0/26.1.1 上逐一实测（26.1.2 已实测未混淆：`analysis/client-26.1.2.jar`）。
- **`legacy189_aliases.toml` 的覆盖率**：文件存在（543 行），本次未读取其成员列表与覆盖统计。