# 多版本支持矩阵

> 目标：**一个 jar** 覆盖 Minecraft **1.8.9 – 26.3**。本文件是"哪个版本走哪条适配路径"的权威清单，
> 也是实现进度的看板。`状态` 列以代码与测试为准。

## 1. 版本 → 适配方式

| Minecraft | JVM 要求 | 混淆 | 适配方式 | 注入方式 | 状态 |
|---|---|---|---|---|---|
| 1.8.9 | Java 8 | MCP 名 | 映射表（MCP） + ASM/JVMTI 改类 | agent / Forge / 双击 | **已实现**（映射 46 类，帧钩子 + GL + 字体 + 4 模块；待真机验证） |
| 1.12.2 | Java 8 | MCP/SRG | 映射表（MCP/SRG） | agent / Forge / 双击 | 计划中 |
| 1.16.5 | Java 8/11 | Mojmap | 映射表（Mojmap） | agent / Forge / Fabric | 计划中 |
| 1.20.1 | Java 17 | Mojmap | 映射表（Mojmap） | agent / Forge / Fabric | 计划中 |
| 1.21.x | Java 21 | Mojmap | 映射表（Mojmap） | agent / Forge / Fabric | 计划中 |
| **26.1+** | Java 21+ | 无混淆 | **反射解析（IdentityMapping）** | agent / Fabric / NeoForge | **已实现（映射层）** |
| 26.2 | Java 21 | 无混淆 | 反射解析 | agent / Fabric / NeoForge | 已实现（映射层） |
| 26.3 | Java 21 | 无混淆 | 反射解析 | agent / Fabric / NeoForge | 已实现（映射层） |

**分界线**：26.1 起 Minecraft 不再混淆 → 类名/方法名就是运行时名，只需要**反射**发现签名；
1.8.9–1.21.x 需要**映射表**把 Mojmap 规范名翻译成运行时的混淆名。

## 2. 加载器 → 入口

| 加载器 | 入口 | 依赖 | 状态 |
|---|---|---|---|
| `-javaagent` / attach | `net.java.f` 风格：`dev.noturne.agent.NoturneAgent.premain` | 无 | 已实现 |
| 双击 / `java -jar` | `dev.noturne.injector.InjectorApp` → attach | 无 | 已实现 |
| Fabric | `fabric.mod.json` → `dev.noturne.agent.mod.NoturneFabric` | 编译期 stub | 已实现 |
| Forge（1.13+） | `mods.toml` → `@Mod("noturne")` → `NoturneForge` | 编译期 stub | 已实现 |
| NeoForge | `neoforge.mods.toml` → `@Mod("noturne")` → `NoturneNeoForge` | 编译期 stub | 已实现 |
| Forge 1.8.9（FML） | `cpw.mods.fml.common.Mod` + `BaseMod` | 需老 API stub | 计划中 |

## 3. 平台 → attach 与渲染

| 平台 | attach | 原生辅助 | 状态 |
|---|---|---|---|
| Windows 10/11 x64 | `jdk.attach`（JDK 9+）/ `tools.jar` 自动重启动（JDK 8） | 计划：DLL | 已实现（JDK 路径） |
| macOS（Intel / Apple Silicon） | `jdk.attach` | 计划：dylib | 已实现（JDK 路径） |
| Linux x64 / arm64 | `jdk.attach` | 计划：.so | 已实现（JDK 路径） |
| 裁剪 JRE（无 `jdk.attach`） | 原生 attach（unix socket / 命名管道） | 需要 | **未实现（Phase 7）** |
| Android（PojavLauncher） | 待定 | 需要 | 未支持 |

## 4. 运行期矩阵（客户端侧）

| 能力 | 1.8.9 | 1.12.2 | 1.16.5 | 1.20.1 | 1.21.x | 26.1+ | 状态 |
|---|---|---|---|---|---|---|---|
| 注入 + 客户端引导 | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | 已实现（版本无关） |
| 模块框架 / 值体系 / 配置 | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | 已实现 |
| ClickGUI 组件树 | ✅ | ✅ | ✅ | ✅ | ✅ | ✅ | 已实现（逻辑层） |
| HUD 渲染接入游戏 | ⏳ | ⏳ | ⏳ | ⏳ | ⏳ | ⏳ | 未实现 |
| 映射层（Mojmap→运行时） | ⏳ | ⏳ | ⏳ | ⏳ | ⏳ | ✅ 反射 | 部分实现 |
| 游戏对象 wrapper | ⏳ | ⏳ | ⏳ | ⏳ | ⏳ | ⏳ | 未实现 |
| 游戏内模块（Combat/Movement/…） | ⏳ | ⏳ | ⏳ | ⏳ | ⏳ | ⏳ | 未实现 |

## 5. 关键约束

- **字节码基线 Java 8**：agent 会进入 1.8.9 的 JVM，因此目标 JVM 内运行的所有类都编译为 `release = 8`。
- **不依赖 Mixin**：运行时改类统一走 JVMTI + ASM，避免与加载器版本耦合。
- **注入器在 JDK 8 上**：attach API 位于 `lib/tools.jar`（不在默认 classpath）→ 启动时自动带
  `tools.jar` 重启自身（`ToolsJarBootstrap`）；JDK 9+ 无需处理。
- **渲染后端差异**：1.8.9 是 OpenGL 2.1 固定管线，26.x 是核心 profile + shader —— 渲染抽象必须分两套。

## 6. 决策记录

| 决策 | 理由 |
|---|---|
| 映射抽象先做 identity | 26.1+ 无需映射，先把接口立起来，再填 1.8.9/1.21.x 的表 |
| 加载器入口用编译期 stub | 零加载器依赖，不受 Fabric/Forge/NeoForge 版本变动影响 |
| JDK 8 用重启而非子类加载器 | 子类加载器会让 `AttachProvider` 的 `ServiceLoader` 解析失败（实测） |
