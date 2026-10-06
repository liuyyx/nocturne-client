# Vape 模块移植评估（照抄 vs 接入 vs 改写）

> 结论先行：**"照抄"已完成（源码 287k 行 + 可编译 + 本体可重建）**；
> 要在游戏里真正用到这 129 个模块，最短路径是 **跑 Vape 本体（B）**；
> 而把它们"长在我们自己的客户端上"（C）等于**重写一个比现有客户端大得多的东西**。

## 三份实测数据

| 维度 | 数字 | 来源 |
|---|---|---|
| Vape 全部源码 | **2985 文件 / 287,137 行** | `vendor/vape/src/main/java`（照抄） |
| Vape 模块 | **457 文件 / 66,107 行**，注册模块 **78 个** | `MODULE-INVENTORY.md`（脚本从 `ModManager` 生成） |
| Vape 的 Minecraft 包装层 | **401 类 / 约 1813 个 public 方法** | `gg/vape/wrapper/**` |
| 模块直接引用的 wrapper 类 | **150 种**（Minecraft 154 次、EntityPlayerSP 124 次、ItemStack 105 次、ForgeVersion 91 次…） | 对 `module/**` 的 import 统计 |
| 我方的对应能力 | `client` 模块 **41 个 java 文件**；`GameBridge` **15 个 public 方法**；`ClassType` **18 项** | 我们自己的代码 |
| 我方现有模块 | **13 个**（与 Vape 同名的 8 个：Chams / Sprint / NameTags / Search / Tracers / Trajectories …） | `client/.../module/modules` |
| 运行期 native 依赖 | `NativeBridge` 16 个 native / 全源码 **139 处调用 / 34 文件**；权威面是 9 个 `RegisterNatives` 方法 | `native/README.md` + 统计 |

## 三条路线

### A. 仅归档（现状）
源码照抄 + 隔离探针可编译 + 清单/文档。**已完成并推送**。
功能不可用（Java 层是 native DLL 的薄壳，单独跑不起来）。

### B. 跑 Vape 本体（**已实测可复现**）
从照抄的源码出发、**不改一行**，重建出完整产品（`BUILD-NATIVE.md` 记录了命令）：

* `Vape-v4.21.36.exe` 35.4 MB（单文件注入器）/ `Vape-v4.21Native.dll` 35.1 MB / payload jar 35.0 MB
* native 自测三模式全部通过（握手 + token + 进度协议 / standalone 哨兵 / 畸形拒绝）
* 上游还自带两道校验并已通过：payload 必需类齐全且无 Java 9+ class、中文字体覆盖

代价：零改写；得到的是**完整的 Vape 客户端**（129 模块 + 它自己的 UI）。
局限：与我们自己的客户端是**两套独立注入链路**，不能混用；它自带账号/许可/在线服务逻辑。

### C. 改写进我们的框架（= 重写）
要让这 129 个模块用我们的 `GameBridge`/`Value`/`EventBus` 跑，需要：

1. 为 **150 个 wrapper 类 + 1800 余个方法** 提供等价实现（我们现有对应物只有 15 个方法 / 18 项类型）；
2. 逐行改写 **66k 行**模块代码的调用点（`wrapper` → `GameBridge`、`value` → 本项目 `Value`、`event` → 本项目 `EventBus`）；
3. 逐模块验证行为（含多版本差异）。

工作量参照：这比当前整个客户端（41 个 java 文件）**大一个数量级以上**；
且与"照抄、不要自由发挥"的要求直接冲突（每一步改写都是"自由发挥"）。

## 建议

* 想要**功能**：走 **B**（产物现成、零改写、可复现）。
* 想要**我们自己的客户端里有这些能力**：那是一次独立的、以月计的移植计划，
  应按"先 wrapper 层 + 事件总线 → 再逐模块"分层推进，而不是逐模块硬搬。
* 无论选哪条，**不必**再做"逐文件抄写"——源码已在 `vendor/vape`，`MODULE-INVENTORY.md`
  给了每个模块的路径与行数，需要哪个抄哪个。
