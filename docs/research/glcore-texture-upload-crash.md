# `gl-core` 纹理上传崩溃：现象、实测状态、机制与修复（2026-10-18）

移植后的新界面首次在真实 `gl-core`（Minecraft 1.21.11 + Fabric 0.19.5 + LWJGL 3.3.3 +
NVIDIA RTX 5060 / 610.74）上运行时，**首个纹理上传就把 JVM 打崩**。

## 1. 现象

注入后日志停在

```
[nocturne] GUI overlay attached; backend=gl-core; input=glfw; ...
[nocturne] overlay first frame: input=glfw mouse=244,45 toggleKey=54 down=false
```

随后 JVM 直接死：

```
#  EXCEPTION_ACCESS_VIOLATION (0xc0000005) at pc=0x00007ffe69318b3a
# Problematic frame:
# C  [nvoglv64.dll+0x15b8b3a]
```

原始日志与 hs_err：`1.21.11-glcore-crash.log`、`1.21.11-glcore-crash-hs_err.log`。

hs_err 的 Java 栈帧把责任点钉死在我们自己的代码上：

```
J  org.lwjgl.opengl.GL11C.nglTexImage2D(IIIIIIIIJ)V
j  dev.nocturne.ui.gl.ModernGlApi.uploadTexture(II[I)Z
j  dev.nocturne.ui.gl.ModernRenderer.createWhiteTexture()I
j  dev.nocturne.ui.gl.ModernRenderer.initialise()Z
j  dev.nocturne.ui.gl.GuiOverlay.onFrame()V
```

即：`ModernRenderer` 初始化时创建 1×1 白纹理（供纯色路径采样用）的那次 `glTexImage2D`。

## 2. 实测：游戏留下的 GL 解包状态

在 `uploadTexture` 里插一次性探针，用 `glGetIntegerv` 读上传前的状态（1.21.11 真机）：

```
[nocturne] GL unpack state at first upload: ROW_LENGTH=1152 SKIP_ROWS=864 SKIP_PIXELS=288
```

游戏在上传图集时把 `GL_UNPACK_ROW_LENGTH/SKIP_ROWS/SKIP_PIXELS` 设成当前精灵矩形的坐标，
**上传完没有还原**——这三个值是全局 GL 状态，于是轮到我们的上传时仍然生效。

## 3. 机制：源地址被推到 3.98 MB 之后

按 GL 规格，`glTexImage2D` 的像素源地址是

```
addr = base + ((SKIP_ROWS * ROW_LENGTH) + SKIP_PIXELS) * bpp
     = base + ((864 * 1152) + 288) * 4
     = base + 3,982,464            // ≈ 3.98 MB
```

我们的白纹理缓冲区只有 **4 字节**（1×1 RGBA），驱动却从 base 之后 3.98 MB 处读 → 读到未映射
内存 → ACCESS_VIOLATION（崩在驱动内部，Java 侧捕获不到）。

隔离实验（`tmp/TexUploadProbe.java`，同一驱动/同机，独立 GL 上下文）用**非崩溃**方式证明了这个
算式：把红像素 `0xFF0000FF` 放在大缓冲的 `((864*1152)+288)*4` 偏移处，其余为 0，然后以相同的
解包状态上传 1×1 并读回：

```
[case6] big buffer, red pixel placed at offset 3982464
[case6] texel read back = 0xFF0000FF (expect 0xFF0000FF if offset honoured)
```

→ 驱动确实从那个偏移处取像素。同一探针里：`ROW_LENGTH=4096` 单独一项、绑定了 PBO 一项都
**不会**崩（PBO 那项返回 `GL_INVALID_OPERATION`）——即崩溃的必要条件是 `SKIP_ROWS` 非 0
（把地址推远）而缓冲区小到那片地址不可映射。

## 4. 因果（A/B，同机同版本）

| 构建 | 结果 |
|---|---|
| 未修（只设 `GL_UNPACK_ALIGNMENT`） | **崩**（2 次：首次发现 + 去修复重跑复现） |
| 已修（上传前三项归零） | **不崩**（第 3 次确认见文末） |

## 5. 修复

`ModernGlApi.uploadTexture` / `GlApi.uploadTexture` 在上传前把三项显式归零：

```java
Reflect.call(pixelStorei, null, GL_UNPACK_ALIGNMENT, 4);
Reflect.call(pixelStorei, null, GL_UNPACK_ROW_LENGTH, 0);
Reflect.call(pixelStorei, null, GL_UNPACK_SKIP_ROWS, 0);
Reflect.call(pixelStorei, null, GL_UNPACK_SKIP_PIXELS, 0);
```

两条后端路径都改：固定管线（`gl-fixed`，1.8.9–1.12.2）走的是同一套 NVIDIA 驱动、同一类全局
状态污染，旧界面没有任何纹理上传所以从未暴露过这个坑。

证据：`fatality-1.21.11-glcore.png` / `.log`。

## 6. 附带排查：输入坐标曾出现野值（结论：非缺陷）

崩溃复现过程中日志出现 `overlay first frame: mouse=32572,32531`（未点击前即如此）。排查结论：

- 探针 `docs/research/probes/CursorProbe.java` 证明 LWJGL 3.3.3 的
  `glfwGetCursorPos(long, double[], double[])` 与 `DoubleBuffer` 重载返回值一致（非签名错配）；
- `glfwGetCursorPos` 返回的是**相对于窗口内容区的真实光标位置**（`glfwSetCursorPos` 对无焦点窗口无效），
  因此窗口被最小化到 `(-32000,-32000)` 时坐标自然变成 `32000+光标`（32572 = 32000+572）；
- 窗口恢复到屏幕内后，同一路径读到的是正常值（`mouse=244,45`）。

→ 该现象是「注入时窗口在屏幕外」的产物，输入路径本身与游戏一致（游戏自己的 `MouseHandler` 也用同一 API）。
真机验收的点击/拖动仍需真人操作：本机合成输入不会更新 GLFW 的光标跟踪状态。

## 7. 复现实验的跑法

```
B=<.minecraft>/libraries/org/lwjgl
CP="$B/lwjgl/3.3.3/lwjgl-3.3.3.jar;$B/lwjgl/3.3.3/lwjgl-3.3.3-natives-windows.jar;$B/lwjgl-glfw/3.3.3/lwjgl-glfw-3.3.3.jar;$B/lwjgl-glfw/3.3.3/lwjgl-glfw-3.3.3-natives-windows.jar;$B/lwjgl-opengl/3.3.3/lwjgl-opengl-3.3.3.jar;$B/lwjgl-opengl/3.3.3/lwjgl-opengl-3.3.3-natives-windows.jar"
java -cp "$CP" docs/research/probes/TexUploadProbe.java
```

两个探针都是独立进程（不属于构建），只依赖 LWJGL jar。
