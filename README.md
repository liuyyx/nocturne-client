# noturne-client

一个 **JVM 注入式 Minecraft 客户端**：单 jar，既是注入器，也是被注入的 agent，同时可作为
Fabric / Forge / NeoForge 模组加载。目标覆盖 **Minecraft 1.8.9 – 26.3**，支持
**Windows / macOS / Linux**。

> 设计参考：DoomsDay 的 JVM 注入机制（免 `tools.jar` 的自实现 attach）、Epsilon 的 UI、
> Vape 的功能架构。实现完全自研，不复制任何第三方代码。

## 目标特性

| 维度 | 目标 |
|---|---|
| 注入方式 | 自实现 attach：自带 `sun.tools.attach.*` 字节码 + `openProcess`/`enqueue`/`closeProcess`，免 `tools.jar`，兼容裁剪 JRE |
| 入口形态 | 单 jar 多入口：`Premain-Class`（-javaagent/attach）、`Main-Class`（双击自注入）、`fabric.mod.json`、`mods.toml`、`neoforge.mods.toml` |
| 平台 | Windows / macOS / Linux（含 ARM64） |
| 版本 | 1.8.9 – 26.3，统一代码 + 运行时适配层 |
| UI | 自绘 ClickGUI / HUD（Epsilon 风格） |
| 附加 | 可选 WinUI 套壳启动器 |

## 技术约束

- **字节码基线 = Java 8（class 52.0）**：agent 会进入 1.8.9 的 JVM，任何在目标 JVM 内运行的类
  都必须能被 Java 8 加载（现代 JVM 可向后兼容运行）。
- 构建工具链用 JDK 21，通过 `options.release = 8` 产出 Java 8 字节码。
- 不依赖 Mixin：运行时改类走 JVMTI / ASM。

## 模块

| 模块 | 职责 |
|---|---|
| `core/` | 注入器与加载器：进程发现、自实现 attach、载荷解密与装载、单 jar 多入口分发 |
| `agent/` | 被注入进目标 JVM 的 agent：`premain`/`agentmain` 接线、`Instrumentation` 管理 |
| `client/` | 客户端核心：事件总线、模块与值框架、映射 / 跨版本适配、游戏 wrapper |
| `ui/` | 自绘 ClickGUI 与 HUD（Epsilon 风格） |

## 构建

```bash
# Gradle wrapper 在本机不可用（下载被拦截），使用已安装的 Gradle：
JAVA_HOME="C:\Program Files\Microsoft\jdk-21.0.12.8-hotspot" \
  sh "$HOME/.gradle/wrapper/dists/gradle-9.2.1-bin/*/gradle-9.2.1/bin/gradle" \
  build --console=plain --no-daemon

# 单独编译
gradle :core:build --console=plain
```

## 状态

见 `docs/PLAN.md`。当前处于 Phase 1（项目骨架）。
