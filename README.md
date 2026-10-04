# noturne-client

一个 **JVM 注入式 Minecraft 客户端**：单 jar 既是注入器 GUI，也是被注入进游戏的 agent。
覆盖 **1.8.9 / 1.12.2 / 1.16.5 / 1.20.1 / 1.21.x / 26.2 / 26.3**（Windows / macOS / Linux）。

## 三条硬规则

1. **只有注入一条路径**。不做模组形态（不产出 `fabric.mod.json` / `mods.toml`），不做 `-javaagent` 之外的入口花样。
2. **客户端代码不按版本分支**。模块一律按 **Mojmap 规范名**写（`net.minecraft.client.Minecraft`），
   版本差异全部下沉到 **每版本一份映射表 JSON**（自动生成，见 `tools/mapping/`）。
3. **运行时零探测**。目标版本由注入器判定并随 agent 参数传入（`mcVersion=1.8.9`），
   agent 只做一件事：读 `/mappings-<版本>.json`。

## 模块

| 模块 | 职责 |
|---|---|
| `core/` | 进程发现、attach（JDK attach API + JDK 8 的 tools.jar 自举）、agent 选项组装（`AgentOptions`） |
| `agent/` | 目标 JVM 内的入口（`agentmain`/`premain`）、ASM 子加载器、两种注入点（帧交换钩子 / 绘制上下文钩子）、按版本选表 |
| `client/` | 事件总线、模块与值框架、映射层（`Mapping` / `ObfuscatedMapping` / `IdentityMapping`）、游戏桥（`GameBridge`） |
| `ui/` | ClickGUI 与 HUD（组件树 / 主题 / 动画）、**按代际**的游戏绘制后端、输入 |
| `injector/` | Swing 注入器 GUI（扫描进程 → 选版本 → 注入） |
| `tools/mapping/` | 映射表生成器（见 `docs/research/mapping-sources.md` 调研报告） |

## 两个注入点（都在 `agent/transform/`）

| 转换器 | 插入什么 | 用在哪种入口 |
|---|---|---|
| `FrameHookTransformer` | 方法开头一条**无参**静态调用 | 缓冲区交换点：`Display.update()` / `glfwSwapBuffers(J)` / `SDL_GL_SwapWindow(J)` |
| `CallbackHookTransformer` | 方法开头**把首个引用形参**交给钩子 | 绘制上下文是**回调形参**的那代 API（`DrawContext` / `GuiGraphics` / `GuiGraphicsExtractor`） |

两者都只动指定类、指定方法、方法第一条指令；不命中或异常一律返回 `null`（沿用原字节码），
内部错误打印一次。保留原始 `StackMapTable`（丢帧会让含分支的方法 `VerifyError`）。

## 版本差异怎么处理

| 关注点 | 做法 |
|---|---|
| 类/字段/方法名 | 映射表（每版本一份 JSON，打进 jar 资源） |
| 目标版本 | 注入器传入 `mcVersion`，运行时**不探测** |
| 界面绘制与字体 | **用游戏自己的 API**，按代际写 3 个后端（成员名查表） |
| 输入 | 用游戏自己的键鼠状态（成员名查表） |
| 帧信号 | LWJGL 的交换函数（3 个签名，属于 LWJGL 而非 MC，MC 改版不影响） |

## 构建与运行

```bash
# 构建（Gradle wrapper 在本机不可用，用已安装的 Gradle）
JAVA_HOME="C:\Program Files\Microsoft\jdk-21.0.12.8-hotspot" \
  sh "$HOME/.gradle/wrapper/dists/gradle-9.2.1-bin/*/gradle-9.2.1/bin/gradle" \
  build --console=plain --no-daemon

# 注入（双击 noturne.bat 走 GUI；命令行两参数走 CLI）
java -jar dist/build/libs/noturne-<version>.jar --list-json      # 列出可注入的进程
java -jar dist/build/libs/noturne-<version>.jar --pid=<pid>     # 注入指定进程
```

游戏里按**右 Shift** 唤出界面（`guiKey` 由注入器传入，AWT VK 码）。

## 状态（诚实版）

| 项 | 状态 |
|---|---|
| 注入链路（attach → agentmain → 帧钩子生效 → 叠加层装载） | **在真实 Java 8 + LWJGL2 + OpenGL 栈上已验证**（`tmp/lab189/targetH.log`：帧钩子 live、叠加层 attach、`backend=gl-fixed`）。⚠️ 该验证跑在 LWJGL2 实验靶（`Fake189v5`，320×240 空白窗口、120 帧）上，**JVM 内没有 Minecraft**——Minecraft 相关的一切（FontRenderer、Gui.drawRect、Options、player/world）均未验证 |
| 映射表生成器（9 个版本） | 进行中（`tools/mapping/`） |
| 版本号传递、两种注入点 | 已完成并有测试 |
| 界面/输入改为「用游戏自己的 API」 | 待做（当前仍是自绘 GL 路径） |
| 模组形态 | 已移除（不再支持放进 `mods/`） |

## 许可

本项目以 **GPL-3.0-or-later** 分发，全文见根目录 `LICENSE`。

之所以是 GPL：`ui/` 中的 Skija 绘制层与部分控件视觉移植自 **Setsuna**（上游 commit
`e4915ae`，作者 ShiYi，声明许可 `GPL-3.0-or-later`）。上游源码快照保存在 `vendor/setsuna/`
（含其 `LICENSE`、`LICENSE-APACHE` 与来源说明 `UPSTREAM.txt`）。

分发要求：保留版权与许可声明、注明来源，并以 GPL-3.0-or-later 提供完整对应源码；
**不得**再附加"禁止转售/禁止商用"之类的额外限制。第三方组件清单见 `THIRD-PARTY-NOTICES.md`。
