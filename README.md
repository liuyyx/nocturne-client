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

## 使用

### 注入式

1. 双击项目根目录的 `noturne.bat`（它自己挑 JDK 的 `javaw`，不依赖 `.jar` 文件关联）；
2. 点「扫描游戏」，选中目标进程；
3. 点「注入」；
4. 在游戏里按 **右 Shift** 唤出 GUI。按键可在设置里改，**改完要重新注入**才生效。

### 模组式

把 `dist/build/libs/noturne-<version>.jar` 放进实例的 `mods/` 目录，用启动器正常启动即可。
同一份 jar 同时是 Fabric / Forge / NeoForge 模组，也是 Java agent（三套元数据都在里面）。

> 模组路径下没有 `Instrumentation`，装不了帧钩子，叠加层改挂加载器的逐帧渲染事件
> （Fabric 用 `HudRenderCallback`），所以**模组式下 GUI 同样能显示**；
> 但依赖 `Instrumentation` 的能力（字节码插桩）在模组路径下不可用。
>
> 绘制后端按运行环境自动选择：**MC 1.21.9+ 走 `DrawContext`**（让游戏自己提交绘制 ——
> 它的新渲染管线会覆盖直接发出的 GL 调用，画面上什么都不会留下），更老的版本回退到 GL 后端。
> 模组路径的开关按键固定为右 Shift；注入器里录制的按键只作用于注入路径。

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

见 `docs/PLAN.md`。当前进度：

- **Phase 1 骨架与构建** ✅ —— 多模块构建通过，进程枚举可用。
- **Phase 2 注入核心** 部分 —— 单 jar 多入口（`Main-Class` / `Premain-Class` / `Agent-Class` +
  三套模组元数据）已交付并验证；attach 走 JDK attach API，**自实现 attach 尚未开始**。
- **Phase 3 agent 运行时** ✅ —— `premain`/`agentmain` 接线、帧钩子（ASM 字节码插桩）、客户端引导。
- **Phase 4 UI** 部分 —— ClickGUI（分类栏 / 模块行 / 设置面板 / 拖动 / 滚动 / 裁剪）、HUD、
  组件树与主题已实现；游戏内实机验证待做。
- **Phase 5–7** 未开始。

### 已验证

- 注入：`Attacher.attach` → `agentmain` → `client installed (modules=4)`，agent 参数（含 GUI 开关键码）送达。
- 模组：`fabric.mod.json` / `META-INF/mods.toml` / `META-INF/neoforge.mods.toml` 与三个入口类
  （`NoturneFabric` / `NoturneForge` / `NoturneNeoForge`）均已打进单 jar。
- 测试：全模块 97+ 用例通过。
