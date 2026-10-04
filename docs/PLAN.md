# 开发计划

目标：**Doomsday 式 JVM 注入 + Epsilon 风格 UI + Vape 式功能**，单 jar 多入口，
覆盖 Minecraft 1.8.9 – 26.3，Windows / macOS / Linux。

每个阶段都必须以**可运行的验证**收尾（不是"能编译"就算完）。

---

## Phase 1 — 骨架与构建

| 交付 | 状态 |
|---|---|
| Gradle 多模块（`core`/`agent`/`client`/`ui`），全部 `release = 8` | ✅ |
| `README.md`、`docs/ARCHITECTURE.md`、`docs/PLAN.md` | ✅ |
| `core` 首个真实实现：`ProcessScanner`（跨平台 JVM 枚举 + MC 识别） | ✅ |
| `core` 首个真实实现：`Attacher`（反射 attach）+ `Noturne.main` | ✅ |
| **验收**：`gradle build` 通过；`java -jar core.jar` 能打印本机 JVM 进程列表 | ✅ |

## Phase 2 — 注入核心（core）

- [ ] 自实现 attach：内嵌 `sun.tools.attach.*` 字节码（按 JDK 版本分支）+ attach 原生库加载，
      摆脱 `tools.jar` / `jdk.attach` 模块依赖。**未开始**（当前走 JDK attach API）。
- [x] 载荷格式：定义自有封装（密文 → 解密 → 解压 → `名称→字节码` 表），内存 `ClassLoader` 装载。
- [x] 单 jar 多入口元数据：`Main-Class` / `Premain-Class` / `Agent-Class` /
      `fabric.mod.json` / `mods.toml` / `neoforge.mods.toml` 同时打进一个产物。
- [ ] **验收**：对真实运行的 Minecraft（1.8.9 与 26.x 各一）成功 attach，agent 侧打印握手信息。
      已在普通 JVM 上验证通过（`ATTACH OK` + `agent loaded via agentmain` + `client installed`），
      **真实 Minecraft 待验证**。

## Phase 3 — agent 运行时

- [x] `premain` / `agentmain` 接线，持有 `Instrumentation`，另起线程初始化客户端。
- [x] 客户端引导：事件总线、模块注册表、映射初始化。
- [x] **验收**：注入后目标 JVM 内可见 noturne 的日志与（空的）模块列表。
      —— 已在普通 JVM 上验证：`[noturne] client installed (modules=4)`。
- [x] 帧钩子：ASM 在帧交换点（`Display.update()` / `glfwSwapBuffers`）插入 `NoturneRuntime.onFrame()`。

## Phase 4 — UI（Epsilon 风格）

- [x] 渲染层抽象（兼容 1.8.9 的 OpenGL 与 26.x 的核心 profile）。
      —— 固定管线路径补上了正交投影与 `glScissor` 裁剪；核心 profile 路径自带投影矩阵。
- [x] ClickGUI：组件树（Panel/Button/Slider/ToggleSwitch/ModeSelector/ColorPicker）、主题、字体、动画。
      —— 另含分类栏拖动、滚轮滚动、右键唤出设置面板、鼠标捕获控制。
- [x] HUD 组件与配置面板。
- [ ] **验收**：`Right Shift` 唤出 GUI，交互流畅，视觉与参考 UI 一致。
      —— 输入层（两代 LWJGL 的键鼠轮询 + 指针捕获 + 事件合成）已实现，**待游戏内实机验证**。
- [ ] 视觉对齐 Epsilon：按 `MD3Theme` 的配色与尺寸常量重做（规格见 `local://epsilon-gui-spec.md`）。
      注意 Epsilon 为 GPL-3.0，本项目为 All Rights Reserved，**只对齐视觉，不复制代码**。

## Phase 5 — 功能模块（Vape 式）

- [ ] 模块基类 + 值体系 + 分类（Combat / Movement / Render / Player / World / Misc）。
- [ ] 事件挂钩：Tick、Packet、Render、Input。
- [ ] 首批模块：AimAssist、Reach、Velocity、Fly、Scaffold、ESP、Xray、FullBright。
- [ ] **验收**：模块可在游戏内开关并生效。

## Phase 6 — 跨版本适配层

- [ ] `Mapping` 抽象 + 三模式（1.8.9 MCP / 1.21.x Mojmap / 26.1+ 反射）。
- [ ] wrapper 层：`Minecraft` / `LocalPlayer` / `World` / `Entity` …
- [ ] **验收**：同一份模块代码在 1.8.9 与 26.x 上都能跑通。

## Phase 7 — 平台与套壳

- [ ] 平台层：Windows / macOS / Linux 的差异封装（原生辅助库可选）。
- [ ] WinUI 套壳启动器（可选）。
- [ ] **验收**：三平台均可完成"启动 → 扫描 → 注入 → GUI"闭环。

---

## 设计决策记录

| 决策 | 理由 |
|---|---|
| 字节码基线 Java 8 | agent 必须能进 1.8.9 的 JVM |
| 不引入 Mixin | 避免加载器/版本耦合，统一走 JVMTI + ASM |
| 映射反射优先 | 26.1+ 无混淆，可直接反射；老版本查表 |
| 模块依赖单向 `ui→client→agent→core` | 注入器可独立运行 |
| 资源全内存加载 | 不留磁盘痕迹 |
