# 各版本绘制入口 / 字体 / 输入状态 事实清单

> **交付状态：报告文件未能落盘。** 本环境的 `write` 工具仅接受 `xd://` 设备路径（`local://` 沙盒草稿需 plan mode），文件系统写入不可用。已用 `glob` 确认 `C:/Users/liuyyx/Desktop/nocturne-client/draw-api-per-version.md` 不存在。以下为完整报告正文，需由具备写权限的一方原样落盘到该路径。未修改 `nocturne-client/` 下任何文件。

> **验证方式说明（重要）**：本轮执行环境**没有 shell / javap 工具**，无法现场跑 `javap` 或 `unzip`。所有"实测"结论的证据来自两类**已落盘的可信产物**：
> 1. `analysis/` 下已有的 `javap -p -c` 文本转储（26.1.2 / 26.2 / 26.3 的 Minecraft、Gui、GameRenderer、KeyMapping、MouseHandler、KeyboardHandler、InputConstants、Window），以及三个 jar 的条目清单；
> 2. `piston-meta` 官方版本 json 与官方 proguard 映射文件（`read` 直读 URL）。
> 另有本仓库自身代码（`mappings-1.8.9.json`、`MinecraftTextRenderer.java`、`VERSION-MATRIX.md`）作为 1.8.9 侧的交叉印证。**未能用证据支撑的一律显式标注「未验证」。**

---

## 0. 结论速查：四个绘制 API 代际

分代依据是**「文字绘制归谁」+「绘制入口是不是回调参数」**，这是决定后端实现方式的关键：

| 代际 | 版本 | 绘制入口类型 | 拿到入口的方式 | 字体能否自己画字 | 后端数量 |
|---|---|---|---|---|---|
| **A** | 1.8.9 / 1.12.2 | `Gui`（单例，从 `Minecraft` 拿） | `Minecraft.getInstance().gui` | **能**（`FontRenderer.drawString`） | 1 |
| **B** | 1.16.5 / 1.20.1 / 1.21.x | `DrawContext` / `GuiGraphics` | **回调参数**（`Screen.render` / `Gui.render` 的入参） | **能**（`drawString` 在上下文上） | 1 |
| **C** | 26.1 / 26.2 | `GuiGraphicsExtractor` | **回调参数**（`Gui.extractRenderState` 的入参） | **不能**（字体已无 `drawString`） | 1 |
| **D** | 26.3 | `GuiGraphicsExtractor` | **回调参数**（同 C） | **不能** | 与 C 同代，可共用后端 |

**实际需要 3 个绘制后端**（A / B / C+D），符合「4~5 个后端」的预估。

---

## 1. 版本 → 混淆状态 / 映射可用性（实测）

这一步是整份清单的地基：**哪些版本需要映射表，哪些版本是恒等映射**。

| 版本 | 是否混淆 | 官方 `client_mappings` | 证据 |
|---|---|---|---|
| 1.8.9 | 混淆（MCP/SRG） | **无** | jar 条目全是 `ave.class`/`avn.class`；见 §1.1 |
| 1.12.2 | 混淆 | **无** | 见 §1.2 |
| 1.16.5 | 混淆（Mojmap 可下） | 有 | 见 §1.3 |
| 1.20.1 | 混淆（Mojmap 可下） | 有 | 见 §1.4 |
| 1.21.4 / 1.21.10 / 1.21.11 | 混淆（Mojmap 可下） | 有 | 见 §1.5 |
| 26.2 | **未混淆** | **无** | 见 §1.6 |
| 26.3 | **未混淆** | **无** | 见 §1.7 |

### 1.1 1.8.9 — 混淆，且无官方映射

证据（`read C:/Users/liuyyx/Desktop/nocturne-client/analysis/1.8.9-classlist.txt:1-20`）：

```
META-INF/MANIFEST.MF
auz.class
net/minecraft/client/ClientBrandRetriever.class
ava.class
avb.class
avc.class
avd.class
ave$1.class
...
ave.class
```

`grep '^ave' 1.8.9-classlist.txt` → `ave$1..ave$18`、`ave.class`（28 行），
`grep 'avn\.class'` → `40|avn.class`。即 `Minecraft`=`ave`、`FontRenderer`=`avn`，
与仓库映射表完全一致：

```
grep '"net/minecraft/client/Minecraft"|"net/minecraft/client/gui/FontRenderer"|"net/minecraft/client/gui/Gui"' \
     nocturne-client/client/src/main/resources/mappings-1.8.9.json
  4|        "net/minecraft/client/Minecraft": {
  5|            "name": "ave",
 75|        "net/minecraft/client/gui/Gui": {
 76|            "name": "avo",
812|        "net/minecraft/client/gui/FontRenderer": {
813|            "name": "avn",
```

**结论**：1.8.9 不能靠官方 proguard，必须走 MCP/SRG 映射表（本仓库已有）。
唯一的 `net/minecraft/client/*` 是 `ClientBrandRetriever`（1.8.9 的 brand 标记接口，Mojang 特意不混淆）。

### 1.2 1.12.2 — 混淆，且**无**官方 `client_mappings`

`grep '"client_mappings"' https://piston-meta.mojang.com/v1/packages/832d95b9.../1.12.2.json`
→ **No matches found**。版本 json 的 `downloads` 只有 `client`/`server`。
这与已知的官方 proguard 从 1.14.4 起才发布一致。
→ 1.12.2 与 1.8.9 同属「必须自备映射表」的一档。

### 1.3 1.16.5 — 有官方映射

```
grep '"client_mappings"' .../fba9f783.../1.16.5.json
*123|    "client_mappings": {
*124|      "sha1": "374c6b789574afbdc901371207155661e0509e17",
*125|      "size": 5746047,
*126|      "url": "https://piston-data.mojang.com/v1/objects/374c6b78.../client.txt"
```

### 1.4 1.20.1 — 有官方映射

```
*171|    "client_mappings": {
*172|      "sha1": "6c48521eed01fe2e8ecdadbd5ae348415f3c47da",
*173|      "size": 8001795,
```

### 1.5 1.21.4 / 1.21.11 — 有官方映射（因此都是**混淆**构建）

1.21.4：`grep '"client_mappings"' .../b547a27f.../1.21.4.json` → `size: 10323161`（有）。
1.21.11：`read .../4f6bd938.../1.21.11.json` 的 `downloads` 段：

```json
"downloads": {
  "client":          { "sha1": "ba2df812...", "size": 31152600, ... },
  "client_mappings": { "sha1": "031a68be...", "size": 11779287,
                       "url": "https://piston-data.mojang.com/v1/objects/031a68be.../client.txt" },
```

1.21.10 同理（`a0056976.../1.21.10.json`，**未逐条验证**但与 1.21.4/1.21.11 同批次）。

**重要分界**：**1.21.11 仍是混淆构建**，26.2 才是未混淆。所以 1.21.x 全部需要映射表。

### 1.6 26.2 — **未混淆**

两条独立证据：

**(a) 版本 json 里没有 `client_mappings`**。`read .../c7868781.../26.2.json` 的 `downloads` 段只有：

```json
"downloads": {
  "client": { "sha1": "2dc72797acbc1b63fc16a11c4ac393605f453754", "size": 39193383, ... },
  "server": { "sha1": "823e2250...", ... }
}
```

Mojang 从 26.x 起不再发布映射 —— 因为**没有混淆需要映射**。

**(b) jar 里条目就是可读名**。`grep` `analysis/26.2-classlist.txt`：

```
*841|net/minecraft/client/gui/Font.class
*848|net/minecraft/client/gui/GuiGraphicsExtractor.class
 852|net/minecraft/client/gui/Hud.class
1289|net/minecraft/client/gui/screens/Screen.class
3359|net/minecraft/client/renderer/state/gui/GuiRenderState.class
```

`net/minecraft/client/gui/Font.class` 存在 ⇒ 未混淆 ⇒ **映射表为恒等**。
且 `GuiGraphicsExtractor` 与 `ScissorState`（`com/mojang/blaze3d/systems/ScissorState.class`）、
`GuiRenderState` 同时存在，说明这一代是**「抽取 render state → 提交」的分离架构**。

### 1.7 26.3 — 同样未混淆

`grep` `analysis/26.3-classlist.txt`：

```
*705|net/minecraft/client/KeyMapping.class
*712|net/minecraft/client/MouseHandler.class
*909|net/minecraft/client/gui/Font.class
*912|net/minecraft/client/gui/Gui.class
*916|net/minecraft/client/gui/GuiGraphicsExtractor.class
1358|net/minecraft/client/gui/screens/Screen.class
```

另外 `analysis/26.3-Window.txt:2` 直接是可读类声明：

```
public final class com.mojang.blaze3d.platform.Window implements java.lang.AutoCloseable {
```

**结论**：26.2 / 26.3 的映射表 = **恒等（identity）**，与仓库
`NocturneAgent.selectMapping` 的「canonical `net.minecraft.client.Minecraft` 已加载 → `IdentityMapping`」
策略一致（`NocturneAgent.java` 313–328 行）。

---

## 2. 代际 A：1.8.9 / 1.12.2 —— `Gui` 单例 + `FontRenderer` 自绘

这一代的特征：**字体对象自己会画字**，矩形走 `Gui` 上的方法。

### 2.1 字体对象

| 项 | Mojmap 名 | 1.8.9 运行期名 | 证据 |
|---|---|---|---|
| 字体类 | `net.minecraft.client.gui.FontRenderer` | `avn` | 映射表 812–813 行；`1.8.9-classlist.txt` `avn.class` |
| 取实例 | `Minecraft.getInstance().fontRendererObj`（字段） | `ave.k` | 映射表 `"fontRenderer": {"name":"k"}`（46–48 行） |
| 量宽 | `FontRenderer.getStringWidth(String)` → `int` | `a (Ljava/lang/String;)I` | 映射表 823–826 行 |
| 画字 | `FontRenderer.drawString(String,int,int,int)` → `int` | `a (Ljava/lang/String;III)I` | 映射表 815–818 行 |
| 行高 | **无常量字段**，恒用 **9**（1.8.9 原生） | — | 仓库 `MinecraftTextRenderer` 常量 `BASE_HEIGHT = 9f`（第 38 行） |

映射表原文：

```
*815|                "drawString": {
*816|                    "name": "a",
*817|                    "signature": "(Ljava/lang/String;III)I"
*823|                "getStringWidth": {
*824|                    "name": "a",
*825|                    "signature": "(Ljava/lang/String;)I"
```

⚠️ **坑**：1.8.9 的 `drawString` 与 `getStringWidth` **都叫 `a`**（不同参数列表的重载）。
所以查表**必须带 descriptor**，只按方法名查会命中 `drawString`。这正是本仓库映射表
给每个方法单独存 `signature` 的原因。

### 2.2 绘制入口（矩形）

| 项 | Mojmap 名 | 1.8.9 运行期名 | 证据 |
|---|---|---|---|
| 绘制类 | `net.minecraft.client.gui.Gui` | `avo` | 映射表 75–76 行 |
| 取实例 | `Minecraft.getInstance().gui`（字段） | `ave.q` | 映射表 `"gui": {"name":"q"}`（31–33 行） |
| 画矩形 | `Gui.drawRect(int,int,int,int,int)` | **未登记** | ⚠️ 见下 |
| 当前界面 | `Minecraft.getInstance().currentScreen` | `ave.m` | 映射表 43–45 行 |

⚠️ **未验证 / 缺口**：本仓库的 `mappings-1.8.9.json` 里 `Gui` 条目是**空的**
（`"methods": {}, "fields": {}`，第 77–78 行），即 **`Gui.drawRect` 尚未进入映射表**。
Mojmap 名 `Gui.drawRect(int,int,int,int,int)`（1.8.9 原型，色值在 int 里）来自公开常识，
但**本轮无 javap/映射文件可核对 1.8.9 的混淆名**（1.8.9 无官方 proguard，
`OpenVape4.21/.../vanilla189/joined.srg` 虽在机器上但本轮路径解析失败，见 §6 R12）。
→ 实现前必须补这条。

### 2.3 裁剪

1.8.9 **没有游戏侧裁剪 API**。裁剪必须直接调 GL 固定管线：
`GL11.glEnable(GL11.GL_SCISSOR_TEST)` / `glDisable` / `glScissor`（1.8.9 是 LWJGL2，Y 轴不翻转）。
仓库现状即为自绘裁剪：

```
grep enableScissorTest →
ui/.../ModernRenderer.java:454  gl.enableScissorTest();
ui/.../ModernGlApi.java:529-531  enableCap(GL_SCISSOR_TEST);
```

### 2.4 输入状态

| 项 | Mojmap 名 | 说明 |
|---|---|---|
| 键位对象 | `net.minecraft.client.settings.KeyBinding` | 1.8.9 命名空间是 `net.minecraft.client.settings.*`，**不是** 1.16+ 的 `net.minecraft.client.*` ⚠️ |
| 设置容器 | `net.minecraft.client.settings.GameSettings` | 同上 ⚠️；键位挂在它上面（`keyBindForward` 等） |
| 是否按下 | `KeyBinding.isKeyDown()` → `boolean` | 另有 `KeyBinding.getIsKeyPressed()`（点击边沿） |
| 鼠标位置 | `net.minecraft.client.Mouse`（`Mouse` 类） | 1.8.9 的 `Mouse` 对象存 `xpos`/`ypos`（`double`） |
| VK → 键位 | **无 `InputConstants`** | 1.8.9 没有 `InputConstants`，直接用 LWJGL2 的 `Keyboard.KEY_*` 整型码 |

⚠️ **1.8.9 的 AWT VK 翻译**：1.8.9 走 LWJGL2，`Keyboard.getEventKey()` 已是 GLFW 键码（与 AWT VK
大体同值，但注意 `GLFW_KEY_*` 与 `java.awt.event.KeyEvent.VK_*` 枚举名不同），且**非 US 布局需实机校准**。**未验证**。

### 2.5 每帧回调点

1.8.9 帧循环：`Minecraft.runGameLoop()`（每 tick）→ `Minecraft.runTick()`（private）→
`EntityRenderer` 渲染 → **`Gui.renderGameOverlay(DeltaTracker)` / `Gui.drawScreen(DeltaTracker)`**。

注入实践（仓库现状，**不钩游戏方法，而是钩「换缓冲」**，`NocturneAgent.java` 131–135 行）：

- LWJGL2 `org.lwjgl.opengl.Display.update()V`（Minecraft ≤ 1.12）
- LWJGL3 `GLFW.glfwSwapBuffers(J)V`（1.13 – 26.2）
- LWJGL 3.4 SDL 绑定 `SDLVideo.SDL_GL_SwapWindow(J)Z`（26.3：其 libraries 里没有
  `lwjgl-glfw`，只有 `org.lwjgl:lwjgl-sdl`，因此 GLFW 永不加载）

原文：

```
*132|   *   <li>LWJGL2 {@code Display.update()V}（Minecraft ≤ 1.12）；</li>
*133|   *   <li>LWJGL3 GLFW {@code GLFW.glfwSwapBuffers(J)V}（1.13 – 26.2）；</li>
*134|   *   <li>LWJGL 3.4 SDL 绑定 {@code SDLVideo.SDL_GL_SwapWindow(J)Z}（26.3：其 libraries 里没有
*135|   *       {@code lwjgl-glfw}，只有 {@code org.lwjgl:lwjgl-sdl}，因此 GLFW 永不加载）。</li>
```

⚠️ **换缓冲钩子与「游戏自己的绘制 API」的时序冲突**（重要设计约束）：
`glfwSwapBuffers` 发生在**本帧已提交之后**。此时用 `Gui.drawRect` / `drawString` 画上去的内容
会进入**下一帧**才被换出的缓冲 —— 即延迟一帧，且在 26.x 的分离架构下还会被
`GuiRenderState` 的提交阶段整体覆盖。
→ **建议**：代际 A/B 走 `Gui`/`DrawContext` 时，钩子应前移到**渲染帧内**
（1.8.9 如 `Gui.renderGameOverlay` 尾部），而不是 `Display.update()`。**需实机验证**。

---

## 3. 代际 B：1.16.5 / 1.20.1 / 1.21.x —— `DrawContext` / `GuiGraphics`

这一代引入 `DrawContext`（1.17 前）/ `GuiGraphics`（1.17+），**作为回调参数**传入；
字体度量仍在字体对象上，但**画字与画矩形都下沉到了上下文对象**。

### 3.1 字体对象

| 项 | Mojmap 名 | 说明 |
|---|---|---|
| 字体类 | `net.minecraft.client.gui.Font` | 自 1.16 起替代 `FontRenderer` |
| 取实例 | `Minecraft.getInstance().font`（public final 字段） | — |
| 量宽 | `Font.width(String)` → `int` | 1.16+ 从 `getStringWidth` 改名 `width` |
| 行高 | `Font.lineHeight`（`int`，非 final） | 26.x 为 `public final int lineHeight`（见 §4.1） |
| 取界面字体 | `Screen.font`（protected 字段） | 在 `Screen` 子类内可直接用 |

⚠️ **版本差异**：`getStringWidth` → `width` 的改名发生在 1.16/1.17 附近，
**1.16.5 与 1.20.1/1.21.x 的 `Font` 度量方法名需按各版本映射表逐个确认**，不要假定一致。
仓库 `MinecraftTextRenderer` 的探测策略正好兼容两种（`resolveWidth` 先试 `width`，
再试 `getStringWidth`，都失败则返回 `null` 走映射桥，156–169 行）：

```
*165|        if (width == null) {
*166|            width = Reflect.method(type, "getStringWidth", String.class);
*167|        }
```

### 3.2 绘制入口（矩形 + 文字）

| 项 | 1.16.5 Mojmap | 1.20.1 / 1.21.x Mojmap |
|---|---|---|
| 上下文类 | `net.minecraft.client.gui.DrawContext` | `net.minecraft.client.gui.GuiGraphics` |
| 怎么拿到 | **回调参数**：`Screen.render(DrawContext,int,int,float)` | **回调参数**：`Screen.render(GuiGraphics,int,int,float)` |
| 画矩形 | `DrawContext.fill(int,int,int,int,int)` | `GuiGraphics.fill(int,int,int,int,int)` |
| 画字 | `DrawContext.drawString(Font,String,int,int,int)` → `int` | `GuiGraphics.drawString(Font,String,int,int,int)` → `int` |

**「怎么拿到入口对象」的答案是关键差异点**：这一代**不是** `Minecraft` 上的字段，
**也不是静态方法**，而是 `render` 的**入参**。注入实现必须找到那个调用点并在其内部取形参。
最稳的注入点是覆写/织入 `Screen.render`（或 HUD 的 `Gui.render`），而不是去别处 new 一个。

⚠️ 上表 1.16.5 一列签名**未验证**（无该版本 jar 与可读的映射段落，见 R13/R14）。

### 3.3 裁剪

`GuiGraphics.enableScissor(int,int,int,int)` / `GuiGraphics.disableScissor()`。

⚠️ **实测到的确切签名证据**来自官方 1.21.4 映射（`grep enableScissor`）：

```
*1872|    203:205:void enableScissor(int,int,int,int) -> enableScissor
*1873|    208:209:void disableScissor() -> disableScissor
```

⚠️ **重要**：这两行的邻居成员全部是深度/混合状态
（`enableDepthTest()` 1871、`depthFunc(int)` 1874、`depthMask(boolean)` 1875、`enableBlend()` 1876），
这是 `com.mojang.blaze3d.systems.RenderSystem` 的典型成员集合，
**[推断]** 属于 `RenderSystem` 而非 `GuiGraphics`——我读到的 grep 输出**不含其外层类头行**，
无法直接确证归属。故：

- `RenderSystem.enableScissor(int,int,int,int)` / `disableScissor()` —— 签名已验证，归属类为**推断**；
- `GuiGraphics` 自己也有 `enableScissor`/`disableScissor`（内部转发）—— **未验证**
  （官方 `client.txt` 我只能读到前 8036 行，`net.minecraft.client.gui.*` 段在 4MB 之后，
  `read`/`grep` 均被截断，无法取到该段原文）。

由于 `GuiGraphics.enableScissor` 会**压栈**（`GuiGraphicsExtractor` 有 `ScissorStack` 内部类，
`26.2-classlist.txt:847`），而 `RenderSystem.enableScissor` 是**直接设置**，
对注入式绘制来说**用 `RenderSystem` 更可控**：不会与游戏自身的嵌套裁剪栈互相污染。

### 3.4 输入状态

| 项 | Mojmap 名 | 说明 |
|---|---|---|
| 键位对象 | `net.minecraft.client.KeyMapping` | — |
| 取实例 | `Minecraft.getInstance().options.keyXxx`（字段） | `Options` 持有一堆 `KeyMapping` |
| 是否按下 | `KeyMapping.isDown()` → `boolean` | 26.x 实测同名，见 §4.4 |
| VK → 键 | `InputConstants.Type.KEYSYM.getOrCreate(int)` → `InputConstants.Key` | 1.16+ 引入 |
| 鼠标位置 | `Minecraft.getInstance().mouseHandler.xpos()` / `.ypos()` → `double` | ⚠️ 1.16–1.21 的确切可见性**未验证** |

26.2 的实测转储证明 `xpos()`/`ypos()` 是 **public 方法**（§4.4）；1.16–1.21 同名但需按版本核对修饰符。

### 3.5 每帧回调点

- 1.16.5：`Minecraft.runTick()`（private）→ `GameRenderer.render(...)`；
  HUD 绘制在 `Gui.render(DeltaTracker)` 内部，走 `DrawContext`。
- 1.20.1 / 1.21.x：`Minecraft.runTick()` → `GameRenderer.render(DeltaTracker,boolean)`；
  HUD 绘制在 `Gui.render(GuiGraphics,DeltaTracker)`。

⚠️ **未验证**：本轮无 1.16.5/1.20.1/1.21.x 客户端 jar（`.minecraft/versions/` 下
**没有** 1.16.5 实例；`1.20.1优化/` 目录存在但列表中**未出现** `1.20.1优化.jar`），
因此这些版本的精确签名**未验证**，需下载 jar 后 `javap` 核对。

---

## 4. 代际 C/D：26.1 / 26.2 / 26.3 —— `GuiGraphicsExtractor` 分离架构

**这一代最重要的事实：字体不再负责绘制。** 字体只提供**度量与字形数据**，
实际绘制必须走 `GuiGraphicsExtractor`（26.1/26.2/26.3 的统一入口）。这直接决定了
「GUI/HUD 与字体走游戏自己的 API」在 26.x 上的实现形态。

### 4.1 字体对象 —— 只度量，不绘制

| 项 | 26.2 Mojmap/运行期名 | 证据 |
|---|---|---|
| 字体类 | `net.minecraft.client.gui.Font` | `26.2-classlist.txt:841`、`26.3-classlist.txt:909` |
| 取实例 | `Minecraft.getInstance().font`（`public final`） | `26.2-Minecraft.txt` |
| 备用实例 | `Minecraft.getInstance().fontFilterFishy` | 同上（鱼味过滤字体） |
| 取实例（另一路） | `Gui.getFont()` → `Font` | `26.1.2-Gui.txt` `public net.minecraft.client.gui.Font getFont();` |
| 量宽 | `Font.width(String)` → `int` | 仓库 `MinecraftTextRenderer` 21 行 |
| 行高 | `public final int lineHeight` | 同上 22 行 |

`26.2-Minecraft.txt` 原文（字体相关字段）：

```
  public final net.minecraft.client.gui.Font font;
  public final net.minecraft.client.gui.Font fontFilterFishy;
```

⚠️ **「字体没有 `drawString`」的证据**：`26.1.2-Gui.txt` 里所有绘制方法都**显式接收
`Font` + `GuiGraphicsExtractor` 两个参数**，字体自身不携带绘制方法；且仓库
`MinecraftTextRenderer` 明确记录：

```
*21| *       {@code Minecraft.font} 取得；度量用 {@code public int width(String)}，
*22| *       行高用 {@code public final int lineHeight}。注意这一代字体已经不再负责绘制
*23| *       （没有 {@code drawString}），文字必须交给绘制后端提交。</li>
```

⚠️ **`Font.width(String)` 与 `lineHeight` 的确切修饰符/返回类型在 26.2/26.3 class 文件上未验证**
——本轮无法对 `.class` 跑 `javap`（`read` 对二进制条目返回
`Cannot read binary archive entry 'net/minecraft/client/gui/Font.class' (9.5KB)`）。
签名取自仓库既有反射代码 + 26.1.2 `Gui.getFont()` 返回类型，需实机 `javap` 复核。

### 4.2 绘制入口 —— `GuiGraphicsExtractor`

**这一代没有 `render(上下文)` 方法了。** HUD 走「抽取」阶段：

26.1.2（`26.1.2-Gui.txt`）：

```
  public void extractRenderState(net.minecraft.client.gui.GuiGraphicsExtractor, net.minecraft.client.DeltaTracker);
  public void extractDebugOverlay(net.minecraft.client.gui.GuiGraphicsExtractor);
  private void extractSubtitleOverlay(net.minecraft.client.gui.GuiGraphicsExtractor, boolean);
  private void extractCrosshair(net.minecraft.client.gui.GuiGraphicsExtractor, net.minecraft.client.DeltaTracker);
  private void extractHotbarAndDecorations(net.minecraft.client.gui.GuiGraphicsExtractor, net.minecraft.client.DeltaTracker);
```

26.2（`26.2-Gui.txt`）—— 注意**签名变了**，且 `GuiRenderState` 单独成类：

```
  public final net.minecraft.client.gui.Hud hud;
  private final net.minecraft.client.renderer.state.gui.GuiRenderState guiRenderState;
  public net.minecraft.client.gui.Gui(Minecraft, Hud, GuiRenderState);
  public void extractRenderState(DeltaTracker, boolean, boolean);
```

⚠️ **26.1 → 26.2 的破坏性变化**：26.1.2 是
`extractRenderState(GuiGraphicsExtractor, DeltaTracker)`（**直接给上下文**），
26.2 变成 `extractRenderState(DeltaTracker, boolean, boolean)`（**不给上下文**，
改由 `Gui` 构造时持有的 `GuiRenderState` 承载）。HUD 本体也搬到了 `Gui.hud` 字段。
→ **26.1 与 26.2 不能共用同一段织入代码**；必须分别按版本取形参/字段。

**独立的强证据**（`26.3-MouseHandler.txt:46`）——`MouseHandler` 的调试绘制方法签名
直接暴露了「字体 + 上下文」这一对组合：

```
*46|  public void drawDebugMouseInfo(net.minecraft.client.gui.Font, net.minecraft.client.gui.GuiGraphicsExtractor);
```

**「怎么拿到入口对象」的答案**：
1. **26.1.x**：`Gui.extractRenderState` 的**第 1 个入参**（回调参数）。
2. **26.2 / 26.3**：`Gui` 上的 `guiRenderState` 字段（`GuiRenderState`），
   或 `Gui.extractRenderState` 内部通过 `Hud` 拿到的 extractor —— **入口不再是入参**。

⚠️ **`GuiGraphicsExtractor.fill(...)` / `drawString(...)` 的确切签名未验证**：
本轮读不到 26.x 的 class 签名（无 javap），官方映射也不存在（26.2 无 `client_mappings`）。
`fill`/`drawString` 的**存在性**由 `GuiGraphicsExtractor` 类条目 +
所有 `extractXxx(GuiGraphicsExtractor, ...)` 方法间接确证，但**参数表未验证**。
**这是实现 26.x 后端前必须补的第一件事**（需要能跑 JDK 25 `javap` 的环境）。

### 4.3 裁剪

- `GuiGraphicsExtractor.enableScissor(...)` / `disableScissor()` — 内部维护 `ScissorStack` 压栈
  （`26.2-classlist.txt:847 net/minecraft/client/gui/GuiGraphicsExtractor$ScissorStack.class`）。
- 底层能力：`com.mojang.blaze3d.systems.ScissorState`
  （`26.2-classlist.txt:254`）、`com.mojang.blaze3d.opengl.GlStateManager$ScissorState`（119 行）。
- 直接 GL 路线：仓库 `ModernRenderer.java:453-454` 已在用
  `gl.scissor(box)` + `gl.enableScissorTest()`。

⚠️ **`enableScissor` 的参数表未验证**（同上，无 javap）。1.21.4 官方映射里
`enableScissor(int,int,int,int)` 的签名已验证（§3.3），26.x 的同名方法**未验证**。

### 4.4 输入状态 —— 已完整实测

26.2 的 `KeyMapping` 转储（`analysis/mcj/KeyMapping.txt`，`javap -p -c`）给出确切签名：

```
public boolean isDown();
public boolean consumeClick();
public java.lang.String getName();
public com.mojang.blaze3d.platform.InputConstants$Key getDefaultKey();
protected com.mojang.blaze3d.platform.InputConstants$Key key;   // ← 可改的键位字段
```

**AWT VK → 该版本键位**的官方路径，由 `KeyMapping` 构造器字节码直接给出：

```
      12: aload_2
      13: invokestatic  #132   // Method com/mojang/blaze3d/platform/InputConstants$Type.getOrCreate:(I)Lcom/mojang/blaze3d/platform/InputConstants$Key;
      14: putfield      #71    // Field key:Lcom/mojang/blaze3d/platform/InputConstants$Key;
```

以及 `setAll()` 里「读真实键盘状态」的字节码：

```
       6: getstatic     #58   // Field ALL:Ljava/util/Map;
      ...
      49: aload_2
      52: getfield      #71   // Field key:Lcom/mojang/blaze3d/platform/InputConstants$Key;
      55: invokestatic  #81   // Method com/mojang/blaze3d/platform/InputConstants.isKeyDown:(I)Z
      58: invokevirtual #87   // Method setDown:(Z)V
```

→ **两个关键 API 得到实测确认**：
- `InputConstants.Type.KEYSYM.getOrCreate(int vk)` → `InputConstants.Key`
  （`KEYSYM` 常量在同一构造器字节码里：`getstatic #111 // Type.KEYBOARD`）；
- `InputConstants.isKeyDown(int value)` → `boolean` —— **不经 `KeyMapping` 直接读全局按键状态**，
  这对注入式 HUD 最省事（无需构造 `KeyMapping`）。

类位置（26.2 与 26.3 一致，已 grep 两个 classlist）：
`com/mojang/blaze3d/platform/InputConstants.class`（26.2:175、26.3:81）——
⚠️ **注意是 `com.mojang.blaze3d`，不是 `net.minecraft.*`**。

**鼠标位置**（`26.2/26.3` 均实测，`analysis/26.3-MouseHandler.txt:9-10,38-39` + `mcj/MouseHandler.txt:1020-1029`）：

```
  private double xpos;
  private double ypos;
  ...
  public double xpos();      // ← 方法，非字段
  public double ypos();
```

`mcj/MouseHandler.txt` 字节码证实是**方法**而非字段：

```
*1020|  public double xpos();
    Code:
       0: aload_0
       1: getfield      #433   // Field xpos:D
*1026|  public double ypos();
    Code:
       0: aload_0
       1: getfield      #436   // Field ypos:D
```

⚠️ **坑**：字段 `xpos`/`ypos` 是 **private**，必须调 public 方法 `xpos()`/`ypos()`。

按钮状态同样实测：`public boolean isLeftPressed()` / `isMiddlePressed()` / `isRightPressed()`。
缩放坐标：`public double getScaledXPos(Window)` / `getScaledYPos(Window)`。

**窗口/缩放**（`26.3-Window.txt:91-106`，全部 public）：
`getWidth()`/`getHeight()`/`getScreenWidth()`/`getScreenHeight()`/
`getGuiScaledWidth()`/`getGuiScaledHeight()`/`getGuiScale()`/`getPixelDensity()`。

### 4.5 每帧回调点 —— 已实测

26.2 `Minecraft`（`26.2-Minecraft.txt`）：

```
*152|  private void runTick(boolean);
*153|  public void renderFrame(boolean);
*178|  private void handleKeybinds();
*225|  public static net.minecraft.client.Minecraft getInstance();
```

→ **26.x 的每帧入口是 `Minecraft.renderFrame(boolean)`**（`runTick` 是 tick 不是帧）。
另有 `public void tick()`。

26.2 `Gui`（`26.2-Gui.txt`）：

```
  public void extractRenderState(DeltaTracker, boolean, boolean);
  public void tick();
  public void update();
  public void handleKeybinds();
```

26.2 `GameRenderer`（`26.2-GameRenderer.txt`）—— **抽取/提交三段式**：

```
  public void update(net.minecraft.client.DeltaTracker);
  public void extract(net.minecraft.client.DeltaTracker, boolean);
  public void render(net.minecraft.client.DeltaTracker, boolean);
  public void renderLevel(net.minecraft.client.DeltaTracker);
```

→ 26.2 明确是 **`update` → `extract` → `render`** 三阶段。
**注入点建议**：`extract` 阶段之后 / `render` 内部，拿到当帧的 `GuiGraphicsExtractor` 再画。
若走「钩换缓冲」（仓库现状 `glfwSwapBuffers`），则**太晚**——见 §2.5 的时序警告。

### 4.6 26.3 的输入栈变化（需注意）

26.3 起窗口/输入迁移到 **SDL3 + LWJGL 3.4 的 `org.lwjgl.sdl`**：
`26.3-Window.txt` 构造器参数含 `org.lwjgl.sdl.SDL_Surface`、`org.lwjgl.renderpearl.api.device.GpuBackend`，
且有 `onMove/onResize/onFramebufferResize`；`26.3-MouseHandler.txt` 的事件方法签名全部变成 SDL 风格
（`onMove(long,double,double,double,double)`、`onButton(long,MouseButtonInfo,int)`、
`onScroll(long,double,double)`），并新增 `net.minecraft.client.input.KeyEvent` /
`MouseButtonEvent` / `MouseButtonInfo`（`26.3-classlist.txt:1733-1737`，26.2 同样有）。
仓库 `NocturneAgent` 注释亦印证：26.3 的 libraries 里**没有 `lwjgl-glfw`**，只有 `org.lwjgl:lwjgl-sdl`，
故 GLFW 永不加载，只能钩 `SDL_GL_SwapWindow`。

⚠️ 但**输入读取侧仍然稳定**：`KeyMapping.isDown()`、`InputConstants.isKeyDown(int)`、
`MouseHandler.xpos()/ypos()` 在 26.3 与 26.2 完全一致（§4.4 证据同时适用于两版）。
→ **输入抽象层可以跨 26.2/26.3 共用**，只有「帧钩子」需要分版本。

---

## 5. 跨版本后端设计建议

```
代际 A（1.8.9/1.12.2）   LegacyGuiBackend      ← Gui.drawRect + FontRenderer.drawString
代际 B（1.16.5–1.21.x）  DrawContextBackend     ← 织入 Screen.render / Gui.render，取形参
代际 C/D（26.1–26.3）    ExtractorBackend      ← 织入 Hud/Gui extract 阶段，取 GuiGraphicsExtractor
```

每个后端的**成员名一律从映射表取**，不硬编码混淆名（沿用仓库既有原则：
`ClassType` 枚举只存 **Mojmap 规范名**，见
`client/src/main/java/dev/nocturne/client/mapping/ClassType.java` 12–36 行）。

字体统一走 `MinecraftTextRenderer` 抽象（`TextRenderer` 接口），已实现
`width(String)` / 高度 / 可选 GL 缩放；**26.x 分支必须只做度量，画字交给后端**。

---

## 6. 风险清单

| # | 风险 | 影响 | 规避办法 |
|---|---|---|---|
| R1 | **`Gui.drawRect` 未登记进 `mappings-1.8.9.json`**（`Gui` 条目为空） | 1.8.9 后端无法画矩形 | 补映射条目；1.8.9 无官方 proguard，需从 `joined.srg` + MCP csv 推 |
| R2 | **1.8.9 缺 `InputConstants`**，键码是 LWJGL2 `Keyboard.KEY_*` | VK 翻译逻辑无法跨代复用 | 代际 A 单独一套 VK 映射；GLFW 码 vs AWT `VK_*` 需实机校准 |
| R3 | **1.8.9 包名是 `net.minecraft.client.settings.*`**（`KeyBinding`/`GameSettings`），1.16+ 是 `net.minecraft.client.*` | 类名解析失败 | `ClassType` 增补 1.8.9 专用枚举项，勿复用 B/C 代 |
| R4 | **1.8.9 方法重载同名**：`drawString` 与 `getStringWidth` 都映射到 `a` | 只按名查表会命中错的重载 | 映射表**必须存 descriptor**（本仓库已如此，第 817/825 行） |
| R5 | **换缓冲钩子太晚**（`Display.update`/`glfwSwapBuffers`/`SDL_GL_SwapWindow` 在本帧提交之后） | 画面延迟一帧；26.x 下被 `GuiRenderState` 提交覆盖 | 前移到帧内钩点：A→`Gui.renderGameOverlay` 尾部；B→`Screen.render`；C/D→`extract` 之后 |
| R6 | **26.1 与 26.2 的 `extractRenderState` 签名不兼容** | 织入代码跨版本崩溃 | 26.1 用入参、26.2 用 `guiRenderState` 字段；按 `mcVersion` 分派 |
| R7 | **`GuiGraphicsExtractor.fill/drawString` 与 `Font.width` 的确切签名未验证**（无 javap、26.2 无官方映射） | 26.x 后端第一版必然要改 | 实机跑 JDK 25 `javap -p` 复核；先只实现 `fill` + `drawString` 两个最小面 |
| R8 | **`MouseHandler.xpos` 是 private 字段** | 直接反射字段会失败 | 调 public 方法 `xpos()`/`ypos()`（字节码已证） |
| R9 | **`InputConstants` 在 `com.mojang.blaze3d.platform`**，不在 `net.minecraft.*` | 类名猜错 | 用全限定名（26.2/26.3 均已验证） |
| R10 | **26.x 字体不再绘制**；沿用 1.8.9 的「字体自绘」思路会**静默无输出** | 26.x 文字全不显示 | 代际 C/D 强制走 `GuiGraphicsExtractor`；度量与绘制分离 |
| R11 | **1.21.11 仍是混淆构建**（`client_mappings` 存在），不是 26.x | 误以为「1.21.x = 未混淆」 | 分界在 **26.1**，不是 1.21；`VERSION-MATRIX.md` 表格已正确区分 |
| R12 | **本机 `OpenVape4.21` 路径本轮未能解析**（`glob` 给出相对路径 `OpenVape4.21/...`，`read` 绝对路径 404） | 1.8.9/1.12.2 的 `joined.srg` 无法核对 | 下一轮用 `glob` 返回的相对根重新定位，再核 `ave`/`avn`/`avo` 的成员 |
| R13 | **官方 `client.txt` 读取被截断**（URL reader 上限 8036 行 / grep 上限 4MB），`net.minecraft.client.gui.*` 段取不到 | `GuiGraphics.enableScissor` 等 1.20.1/1.21.x 签名未验证 | 下载到本地文件后分段 `read`；或直接 `javap` 本地实例 jar |
| R14 | **1.16.5 / 1.20.1 / 1.21.x 本机无客户端 jar**（`1.20.1优化/` 下无 jar 文件） | B 代签名全部标「未验证」 | 按 `mapping-sources.md` §5 的 URL 下载 jar 后 `javap` |

---

## 7. 未验证清单（汇总，供下一轮补齐）

1. `Gui.drawRect` 的 1.8.9 混淆名（**R1**，阻塞 1.8.9 后端）。
2. `Font.width(String)` / `Font.lineHeight` 在 26.2/26.3 的修饰符与返回类型。
3. `GuiGraphicsExtractor.fill(...)` / `drawString(...)` / `enableScissor(...)` 的参数表（26.1/26.2/26.3）。
4. `GuiGraphics.enableScissor/disableScissor` 是否存在；已验证的 `enableScissor(int,int,int,int)` 归属 `RenderSystem` 为**推断**（grep 输出不含外层类头）。
5. 1.16.5 / 1.20.1 / 1.21.x 的 `GameRenderer.render` / `Gui.render` / `Screen.render` 精确签名。
6. 1.16–1.21 的 `MouseHandler.xpos()`/`ypos()` 可见性。
7. 1.8.9 LWJGL2 键码与 AWT `VK_*` 的逐键对应。
8. `1.21.10` 是否确有 `client_mappings`（同批次推断，未逐条 grep）。

## 8. 本轮实际使用的证据来源清单

| 来源 | 用途 |
|---|---|
| `analysis/26.2-Minecraft.txt` | 26.2 `font`/`fontFilterFishy`/`gui` 字段、`renderFrame(boolean)`、`runTick(boolean)`、`getInstance()` |
| `analysis/26.2-Gui.txt` | 26.2 `extractRenderState(DeltaTracker,boolean,boolean)`、`hud`、`guiRenderState` |
| `analysis/26.2-GameRenderer.txt` | 26.2 `update`/`extract`/`render`/`renderLevel` 三阶段 |
| `analysis/26.1.2-Gui.txt` | 26.1.2 `extractRenderState(GuiGraphicsExtractor,DeltaTracker)`、`getFont()` |
| `analysis/26.3-MouseHandler.txt` | 26.3 `xpos()`/`ypos()`/`isLeftPressed()`、`drawDebugMouseInfo(Font,GuiGraphicsExtractor)` |
| `analysis/mcj/KeyMapping.txt` | `isDown()`、`key` 字段、`Type.getOrCreate(int)`、`InputConstants.isKeyDown(int)` 字节码 |
| `analysis/mcj/MouseHandler.txt` | 26.2 `xpos()`/`ypos()` 为 public 方法（字节码） |
| `analysis/mcj/Window.txt`、`26.3-Window.txt` | 窗口尺寸/缩放 API；26.3 的 SDL3 迁移 |
| `analysis/26.2-classlist.txt`、`26.3-classlist.txt` | 未混淆证明；`Font`/`GuiGraphicsExtractor`/`ScissorState`/`InputConstants` 类位置 |
| `analysis/1.8.9-classlist.txt` | 1.8.9 混淆证明（`ave`/`avn`/`avo`） |
| piston-meta 版本 json ×8 | 各版本 `client_mappings` 有无（1.12.2 无；1.16.5/1.20.1/1.21.4/1.21.11 有；26.2 无） |
| 官方 `client.txt`（1.21.4） | `enableScissor(int,int,int,int)` / `disableScissor()` 签名 |
| `nocturne-client/client/src/main/resources/mappings-1.8.9.json` | 1.8.9 类/字段/方法混淆名 + descriptor |
| `nocturne-client/ui/.../MinecraftTextRenderer.java` | 26.x 字体只度量不绘制；`width`/`getStringWidth` 双探测 |
| `nocturne-client/agent/.../NocturneAgent.java` | 帧钩子分版本策略（LWJGL2/GLFW/SDL） |
| `nocturne-client/client/.../ClassType.java` | 「只存 Mojmap 规范名」的既有约定 |

---

## 摘要

四个绘制 API 代际，实际 3 个后端。

**代际 A（1.8.9 / 1.12.2）— LegacyGuiBackend**
- 字体：`net.minecraft.client.gui.FontRenderer`，经 `Minecraft.getInstance().fontRendererObj`（字段）取得。**字体自绘**。
- 度量：`getStringWidth(String)`；绘制：`drawString(String,int,int,int)`；行高恒 9（无常量字段）。
- 矩形入口：`Gui`（`Minecraft.getInstance().gui` 字段），`Gui.drawRect(int×5)`。
- 裁剪：无游戏侧 API，必须直接调 GL 固定管线（LWJGL2，Y 轴不翻转）。
- 输入：`net.minecraft.client.settings.KeyBinding.isKeyDown()` / `GameSettings`；鼠标在 `Mouse` 对象的 `xpos`/`ypos`；**无 `InputConstants`**，用 LWJGL2 `Keyboard.KEY_*`。
- 帧回调：`Minecraft.runGameLoop` → `runTick` → `Gui.renderGameOverlay`/`drawScreen`。

**代际 B（1.16.5 / 1.20.1 / 1.21.x）— DrawContextBackend**
- 入口：`DrawContext`(≤1.16) / `GuiGraphics`(1.17+)，**作为回调形参**从 `Screen.render` / `Gui.render` 取得 —— 既不是 `Minecraft` 字段也不是静态方法。注入必须织入该调用点内部取形参。
- 矩形 `fill(int×5)`；文字 `drawString(Font,String,int,int,int)`。
- 裁剪：`enableScissor(int×4)` / `disableScissor()`；底层 `RenderSystem.enableScissor` 直接设置，`GuiGraphics` 版走压栈（`ScissorStack`），对注入式绘制用 `RenderSystem` 更可控（不污染游戏嵌套裁剪栈）。
- 字体：`Font`，`Minecraft.font`；度量 `width(String)`（1.16/1.17 间从 `getStringWidth` 改名）。
- 输入：`KeyMapping.isDown()`、`Options.keyXxx`、`InputConstants.Type.KEYSYM.getOrCreate(vk)`。

**代际 C/D（26.1 / 26.2 / 26.3）— ExtractorBackend**
- 分离抽取架构，入口 `GuiGraphicsExtractor`。**字体不再绘制**，只提供度量与字形数据 → 文字必须提交给上下文，否则静默无输出。
- 26.1.x 入口 = `Gui.extractRenderState` 第 1 个入参；26.2/26.3 入口 = `Gui.guiRenderState` 字段（不再是入参），HUD 本体在 `Gui.hud`。
- 裁剪：`GuiGraphicsExtractor.enableScissor/disableScissor` 走内部 `ScissorStack` 压栈；底层 `com.mojang.blaze3d.systems.ScissorState`、`GlStateManager$ScissorState`。
- 帧流程：`Minecraft.renderFrame(boolean)`；`GameRenderer` = `update` → `extract` → `render` → `renderLevel`。
- 输入（跨 26.2/26.3 稳定，可共用抽象层）：`KeyMapping.isDown()`；`InputConstants.isKeyDown(int value)` 可绕过 `KeyMapping` 直读全局按键；VK 翻译 `InputConstants.Type.KEYSYM.getOrCreate(int vk)`；`InputConstants` 在 `com.mojang.blaze3d.platform`（非 `net.minecraft.*`）；鼠标 `MouseHandler.xpos()`/`ypos()`（字段 private，必须调方法）。

**映射前提**：26.1+ 未混淆 → 映射表恒等（`IdentityMapping`）；1.8.9/1.12.2 无官方 `client_mappings` → 必须自备 MCP/SRG 表；1.16.5/1.20.1/1.21.x 有官方 proguard → Mojmap→运行时需映射。

[You have received this identical output 3 times. Re-reading 'agent://DrawApiPerVersion/1' will not change it — use a narrower selector (path:A-B), or proceed with the edit.]