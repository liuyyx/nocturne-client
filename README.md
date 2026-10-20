# nocturne-client

**JVM 注入式 Minecraft 客户端**：单个 jar 既是注入器，也是被注入进游戏的 agent。
不产出 `fabric.mod.json` / `mods.toml`，不放进 `mods/`，只有 attach 一条路径。

- **映射覆盖**：`1.8.9 – 26.3` 全部 66 个 release，每版本带 `vanilla` / `fabric` / `forge` / `neoforge`
  四套运行期名（`client/src/main/resources/mappings-*.json`，由 `tools/mapping/` 生成）。
- **真机跑过**：`1.8.9`（Forge + LWJGL2，固定管线）、`1.16.5`（LWJGL3）、`26.3`（Fabric + SDL）。
  逐条证据见 `docs/VERSION-MATRIX.md`。

## 三条硬规则

1. **只有注入一条路径**。不做模组形态，不做其它入口花样。
2. **客户端代码不按版本分支**。模块一律按 **Mojmap 规范名**写（`net.minecraft.client.Minecraft`），
   版本差异全部下沉到每版本一份映射表 JSON。
3. **运行时不探测版本**。目标版本由注入器判定并随 agent 参数传入（`mcVersion=1.8.9`）；
   加载器（原版 / Fabric / Forge / NeoForge）也不探测——表里带齐四套名字，按候选顺序试。

## 模块

| 模块 | 职责 |
|---|---|
| `core/` | 进程发现、attach（自实现 Windows/POSIX 通道 + JDK attach 兜底）、载荷解密装载、agent 选项组装（`AgentOptions`）；免 GUI 的 CLI 入口 `dev.nocturne.core.Nocturne` |
| `agent/` | 目标 JVM 内的入口（`agentmain`/`premain`）、ASM 子加载器、两种注入点、按版本选表、`OverlayBootstrap`（选后端 / 输入源 / 安装稳定器） |
| `client/` | 事件总线、模块与值框架、映射层（`Mapping` / `ObfuscatedMapping` / `IdentityMapping`）、游戏桥（`GameBridge`）、世界覆盖层（ESP / Tracers / StorageEsp / Search / Trajectories …） |
| `ui/` | **Fatality 风格 ClickGUI**（自绘）：分类 Tab / 三列瀑布流 / 侧栏 / 下拉与调色板浮层 / 底部搜索；绘制只面向 `Renderer` 抽象，三个后端同一份代码 |
| `injector/` | Swing 注入器 GUI（扫描进程 → 选版本 → 注入），并作为 jar 的 Main-Class；`--list-json` / `--pid=` 走同一入口 |
| `dist/` | 把上述模块合并成单个多入口 `nocturne-*.jar` |
| `launcher/` | 独立 WPF(.NET 8) 启动器壳：只把 jar 当子进程驱动；不含业务逻辑，不在 Gradle 构建里 |

## 三个绘制后端

| 后端 | 适用 | 绘制方式 |
|---|---|---|
| `gl-fixed` | 1.8.9 – 1.12.2（LWJGL2） | `GlRenderer` + `GlApi`：反射绑 `org.lwjgl.opengl.GL11`，立即模式 + **批次层**（`ensureBatch` / `flushBatch`），矩阵与能力位自己接管 |
| `gl-core` | 1.13+（LWJGL3/GLFW） | `ModernRenderer` + `ModernGlApi`：GLSL150 + VBO |
| `gui-extractor` | 26.x（SDL） | `ExtractorRenderer`：把绘制交给游戏自己的 `GuiGraphicsExtractor`，GL 与 Vulkan 都由游戏决定 |

1.8.9 走的是 `gl-fixed`。这一层的两条**运行期不变量**（踩过真机事故，改代码前务必读）：

1. **任何非顶点类 GL 调用之前必须 `flushBatch()`**（能力位、纹理绑定/参数、`glGet*`、矩阵、裁剪、线宽）。
   `glGenTextures`/`glGetInteger` 落在 `glBegin/glEnd` 之间是 `GL_INVALID_OPERATION`，驱动不报错、
   结果静默丢失——曾经表现为文字乱码或整段消失。
2. **纹理句柄必须唯一**。`genTexture`/`deleteTexture` 使用**专用直接缓冲**；共享缓冲会被
   `boundTexture()`/`viewport()` 写脏，导致所有图集拿到同一个纹理名、互相覆盖。
3. **`GL_TRIANGLE_FAN` 是连续条带，不能跨调用合批**。两次 `beginFan()` 之间必须 `glEnd()`——
   否则后一组的中心点会与前一组最后一个顶点连成巨大三角面（半透明圆角块会出现横贯斜纹）。
4. **全局纹理状态每帧复位**：活动纹理单元（`glActiveTexture(GL_TEXTURE0)`）与纹理矩阵
   （`glMatrixMode(GL_TEXTURE)` + `loadIdentity`），以及 `glShadeModel(GL_SMOOTH)`。
   1.8.9 的光照贴图会在 1 号单元绑定、EntityRenderer 会切纹理矩阵，游戏都不还原。

能力位与纹理绑定还要**先经游戏的 `GlStateManager`（1.8.9 混淆名 `bfl`）同步缓存，再无条件下发真实 GL 调用**——
只发裸 GL 会让游戏缓存与真实状态脱钩（1.8.9 的剔除缓存就是这样让整个界面被背面剔除的）。

## 两个注入点（`agent/transform/`）

| 转换器 | 插入什么 | 用在哪种入口 |
|---|---|---|
| `FrameHookTransformer` | 方法开头一条**无参**静态调用 | 缓冲交换点：`Display.update()` / `glfwSwapBuffers(J)` / `SDL_GL_SwapWindow(J)` |
| `CallbackHookTransformer` | 方法开头**把首个引用形参**交给钩子 | 绘制上下文是**回调形参**的那代 API（`DrawContext` / `GuiGraphics` / `GuiGraphicsExtractor`） |

两者都只动指定类、指定方法的第一条指令；不命中或异常一律返回 `null`（沿用原字节码）。
保留原始 `StackMapTable`（丢帧会让含分支的方法 `VerifyError`）。

## 文字与纹理

界面的文字**不走游戏字体**：Manrope / MiSans / Lucide / FA-Solid 四个 TTF 由运行期 Java2D
栅格进 1024² 白化图集（RGB=白、A=覆盖度，非预乘），按 `(fontId, pxSize)` 缓存，经
`Renderer.uploadTexture` / `texturedQuad` 绘制。阴影与圆角描边角弧用解析解生成 alpha 场
（`text/RasterTextures`）。

- 后端不支持纹理或 `java.desktop` 被裁剪时，整体退化为**游戏字体 + ASCII 图标**
  （`MinecraftTextRenderer`；1.8.9 用 `drawString` 签名探测，26.x 无绘制方法则明确报一次）。
- 单位链：设计单位 → 逻辑像素（`scale`）→ 物理像素（`backendScale`，固定管线按屏幕高度取整）。
- 图集新增字形会整张重传；这是已知的性能点（详见 `docs/AUDIT-2026-10-19.md`）。

## 映射表

同一版本在不同加载器下**运行期名字不同**，所以一张表里存四套：

| 命名空间 | 谁这样跑 | 类名 / 成员名 |
|---|---|---|
| `vanilla` | 不带加载器 | 混淆名（`enn` / `N` / `f_90977_`） |
| `fabric` | Fabric / Quilt | intermediary（`net.minecraft.class_1657` / `method_1551`） |
| `forge` | Forge | 可读类名 + SRG（`func_`/`field_` ≤1.15.2，`m_`/`f_` ≥1.16.5） |
| `neoforge` | 1.20.1 同 Forge；1.20.2+ 即 Mojmap 名 | 未混淆 |

数据源：Mojang `client_mappings`（1.14.4+）、`FabricMC/intermediary`（1.14+）、
`Legacy-Fabric/Legacy-Intermediaries`（1.8.2–1.13.2）、`MinecraftForge/MCPConfig`（1.12.2+）、
MCP `joined.srg`（1.9–1.12.1），以及 1.8.9 / 1.12.2 的别名桥。
`1.9 – 1.14.3` 没有官方映射也没有 MCP 人类名，走 **intermediary 锚点**。

* 重新生成：`python tools/mapping/generate.py`
* 一致性门禁：`python tools/mapping/generate.py --check`（表字节稳定）
* 逐版本核对：`python tools/mapping/generate.py --javap`（对真实 client jar 校验名与描述符）
* 已知缺口：`1.9.1 / 1.9.3 / 1.10.1` 缺 `forge`

## 构建与运行

```bash
# 构建（Gradle wrapper 在本机不可用，用已安装的 Gradle；路径按本机替换）
JAVA_HOME="C:\Program Files\Microsoft\jdk-21.0.12.8-hotspot" \
  sh "$HOME/.gradle/wrapper/dists/gradle-9.2.1-bin/*/gradle-9.2.1/bin/gradle" \
  build --console=plain --no-daemon

# 测试
sh .../gradle test --console=plain --no-daemon          # 全部模块
sh .../gradle :ui:test --tests "*GlApiTest*"            # 单类

# 运行
java -jar dist/build/libs/nocturne-<version>.jar --list-json    # 列出可注入进程
java -jar dist/build/libs/nocturne-<version>.jar --pid=<pid>   # 注入指定进程
```

游戏里按**右 Shift** 唤出界面（`guiKey` 由注入器传入，AWT VK 码）。

### 真机调试开关

```bash
-Dnocturne.ui.debug=1
```

每秒打印一行：

```
[nocturne] draw batches/s=… vertices/s=… tex batches/s=… text/s=… upload full/s=… sub/s=…
```

- `batches/s`：本秒提交的 `glBegin` 次数；`tex batches/s`：其中带纹理的批次数。
- `text/s`：**游戏字体路径**的调用数。走字形图集时它恒为 0，这是预期值，不是故障。
- `upload full/s` / `sub/s`：整张纹理上传与子区域上传次数。图集新增字形只走 `sub`；
  `full` 在稳态应为 0（每张图集只在创建时整传一次）。
- 另有一次性诊断：`fonts: atlas=… renderer=… textures=… registry=…`、
  `gui render: backend=… scale=… backendScale=… atlas=…`、`text renderer bound: …`。
- `GL ERROR 1282` 是 `GL_INVALID_OPERATION`，几乎总是「状态调用落在批次内」或「在 `glBegin` 内做查询」。

## 状态（诚实版）

| 项 | 状态 |
|---|---|
| 注入链路（attach → `agentmain` → 帧钩子 live → 叠加层装载） | **已实测**（官方 1.8.9；另有真实 LWJGL2 靶单独验证 Java 8 栈） |
| 自实现 attach（免 `jdk.attach` / `tools.jar`） | **Windows 已实测**，含裁剪 JRE（无 `tools.jar`）；Linux/macOS 通道已实现并单测，**未实机** |
| 1.8.9 真机 | **已复验（2026-10-19）**：`backend=gl-fixed`、`scale≈0.86`、`backendScale=1`；Fatality 界面标题/Tab/侧栏/卡片文字**全部正常显示**，`text/s=0`（图集路径），无 GL 错误；合批后批次量约为改造前的 1/5 |
| 1.16.5 真机 | 注入 / 帧钩子 / 输入 / 叠加层当时全部正常（`gl-core`）；新界面未复验 |
| 26.3 真机 | 真实 26.3 + Fabric 上 attach → 界面可见可点（`gui-extractor` / `sdl`）；SDL 栈不注册帧钩子、不碰 GL；新界面未复验 |
| 映射表生成器（66 release × 4 命名空间） | **已完成**：`--check` 全绿；`--javap` 对每版本真实 client jar 逐成员校验；Forge 1.20.1 / NeoForge 1.21.11 另用运行产物核对 166/166 |
| 界面：Fatality 风格 ClickGUI | 离屏光栅验证（`docs/research/fatality-ui-*.png`）+ **1.8.9 真机复验**；`ui` 模块单测覆盖字形图集、纹理上传字节序、批次顺序、Value→控件映射与布局 |
| 旧 UI 库（Skija / Setsuna 移植件 / 组件树 / HUD / HUD 编辑器） | **已整体删除**；HUD 不再存在（`client` 侧 `HudSink` 接口保留，暂无实现） |
| 3D 玩家预览（Render 分类 Preview 卡片） | **未实现，降级为占位**：映射表缺实体渲染面，26.x 又不能自建离屏 framebuffer |
| 世界覆盖层（ESP / Tracers / NameTags / ItemEsp / StorageEsp / Search / Trajectories） | **已接上并画出来**；`WorldProjection` 自己算投影，跨代通用 |
| 自销毁（Panic） | **已实现并有测试**：停用全部模块、关闭驱动闸门、清空分发器；**不可逆**（agent 无法卸载自己） |
| 模组形态 | 已移除 |

### 已知问题

- ~~数字键 6 与右 Shift 的哨兵值冲突~~ **已修**：右 Shift 改用 AWT 数值区之外的哨兵
  `0x1F01`（`KeyMap.VK_RIGHT_SHIFT`），数字 6 恢复为普通按键。跨模块契约 K1-b：
  `client` / `agent` / `ui` / `injector` / `core` 五处的取值必须一起改。
- **右 Shift 与第三方客户端冲突**：目标装了 FPSMaster 之类时，它的 ClickGUI 也绑在右 Shift。
  注入器会提示，请在设置里改成别的键。
- ~~图集整张重传~~ **已修**：新增字形改为 `glTexSubImage2D` 只传变化的脏矩形；
  首秒实测 `full/s=7`（每张图集一次）+ `sub/s=136`，稳态为 0/0。
- ~~`FontRasterizer` 的 `scale` 一帧滞后~~ **已修**：`bind()` 移到 `updateScale()` 之后，按本帧缩放绑定。
- **`gl-core`（1.13–1.21）文字渲染为黑块**（2026-10-20 发现，**未修**）：
  1.21.11 + Fabric 上界面布局/开关/滑块/色块/裁剪全部正确，但文字是纯黑矩形。
  真机证据：`atlas=true`、`backend=gl-core`、图集上传句柄 996、字形四边形
  `argb=0x35ece9e1` 与 UV 正确、上传源像素 `p0=0x0dffffff`（白 + 覆盖度）、`texImage2D` 已解析、
  无 REFLECT FAIL/GL 错误 ⇒ 问题在「采样到的纹理为黑」，不在坐标/颜色/UV。
  已做的判定实验：
  1. **A/B**：强制 `uTexture=false`（纯色分支）时文字变成**白块** ⇒ 几何/顶点色正常，
     执行的是纹理分支，且该分支采样到**黑**。
  2. **绑定状态**（`texturedQuad` 内、`drawTriangles` 前读回）：`bound=996`
     （正是图集句柄）、`unit=GL_TEXTURE0(33984)`、`samplerLoc=2` ⇒ 绑定与单元都正确。
  3. **着色器直出**：把片元改成 `vec4(texture(...).rgb, 1.0)` 后仍是黑 ⇒ **采样值本身是黑**。
  4. 图集上传源数组正确（`p0=0x0dffffff`），1×1 白纹理与阴影纹理的 `glGetTexImage`
     回读正确；但**尚未对 1024² 图集本身做过回读**（此前用 4 字节缓冲读 1024² 纹理
     直接导致 JVM 崩溃——回读必须分配全尺寸缓冲）。

  ⇒ 下一步就一件事：上传 1024² 图集后，用 4 MB 缓冲 `glGetTexImage` 回读并检查
  `glGetError()`，确认 `glTexImage2D` 是否真的写入了这张纹理。
  （已修的相关缺陷：`ModernGlApi` 的类候选表缺 `GL13`，`glActiveTexture` 句柄为 null
  导致纹理绑到游戏的活动单元；现已加入 `GL13C/GL13`，并把 `activeTexture` 列为
  `hasTextures()` 的必要条件。）

## 文档索引

| 文档 | 内容 |
|---|---|
| `docs/ARCHITECTURE.md` | 模块与数据流 |
| `docs/VERSION-MATRIX.md` | 每版本映射来源、绘制代际、帧钩子目标、验收状态 |
| `docs/AUDIT-2026-10-19.md` | 已知缺陷与根因记录（固定管线状态、图集、纹理） |
| `docs/PLAN.md` | 开发计划与工作纪律（每个大项做完即 commit + push） |
| `docs/research/draw-api-per-version.md` | 各版本绘制 API 差异 |
| `docs/research/LabFatalityGui.java` · `fatality-ui-*.png` | 界面离屏光栅验证（Lab 源码 + 截图） |
| `docs/research/mapping-sources.md` | 映射数据源与反查路径 |
| `tools/mapping/README.md` | 映射生成器：schema、数据源、镜像/离线用法 |
| `THIRD-PARTY-NOTICES.md` | 第三方组件、来源与许可 |

## 许可

本项目以 **GPL-3.0-or-later** 分发，全文见 `LICENSE`。

仓库内保存 **Setsuna** 源码快照（`vendor/setsuna/`，上游 `e4915ae`，作者 ShiYi，GPL-3.0-or-later），
仅作参考、不参与构建。`ui/fatality/` 是 **FatalityClickGui**
（`https://github.com/BanCN-Re/FatalityClickGui`）的移植，上游**没有 LICENSE 文件**，
移植与再分发以仓库使用者的授权为准（见 `THIRD-PARTY-NOTICES.md`）。

分发要求：保留版权与许可声明、注明来源，并以 GPL-3.0-or-later 提供完整对应源码。
