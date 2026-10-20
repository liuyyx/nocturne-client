package dev.nocturne.ui.gl;

import dev.nocturne.client.game.Reflect;

import java.lang.reflect.Method;
import java.nio.FloatBuffer;

/**
 * 对现代渲染器所需的 OpenGL 3.2 核心 profile 子集的反射绑定。
 *
 * <p>Minecraft 26.x 运行在<em>核心 profile</em> 上：{@link GlApi} 使用的固定管线调用
 * （{@code glBegin}、{@code glVertex2f} 等）已被移除。因此 UI 必须自带 VBO + 着色器路径。
 * 与 {@link GlApi} 一样，编译期不做任何链接——入口点都在游戏的类加载器上解析。
 * LWJGL3 同时提供 legacy（{@code GL15}）与 core（{@code GL15C}）两套类，
 * 所以每个函数都要按两种写法分别查找。
 *
 * <p>句柄全部在 {@link #bind} 中一次性解析后直接写入实例字段（而非超长构造参数），
 * 未解析到的保持 {@code null}，对应的包装方法据此安全跳过调用。
 */
public final class ModernGlApi {

    // OpenGL 枚举常量：属于 ABI，永不改变，故以内联常量形式给出。
    /** glVertexAttribPointer 的分量类型：32 位浮点。 */
    public static final int GL_FLOAT = 0x1406;
    /** drawArrays 的图元模式：三角形列表。 */
    public static final int GL_TRIANGLES = 0x0004;
    /** 顶点数组缓冲绑定目标。 */
    public static final int GL_ARRAY_BUFFER = 0x8892;
    /** 缓冲用途提示：数据频繁改写（UI 顶点每帧都变）。 */
    public static final int GL_DYNAMIC_DRAW = 0x88E8;
    /** 混合开关。 */
    public static final int GL_BLEND = 0x0BE2;
    /** 混合因子：源 alpha。 */
    public static final int GL_SRC_ALPHA = 0x0302;
    /** 混合因子：1 - 源 alpha。 */
    public static final int GL_ONE_MINUS_SRC_ALPHA = 0x0303;
    /** 背面剔除开关。 */
    public static final int GL_CULL_FACE = 0x0B44;
    /** 深度测试开关。 */
    public static final int GL_DEPTH_TEST = 0x0B71;
    /** glCreateShader 的着色器类型：顶点着色器。 */
    public static final int GL_VERTEX_SHADER = 0x8B31;
    /** glCreateShader 的着色器类型：片元着色器。 */
    public static final int GL_FRAGMENT_SHADER = 0x8B30;
    /** glGetShaderi 查询项：编译状态。 */
    public static final int GL_COMPILE_STATUS = 0x8B81;
    /** glGetProgrami 查询项：链接状态。 */
    public static final int GL_LINK_STATUS = 0x8B82;
    /** 裁剪测试开关。 */
    public static final int GL_SCISSOR_TEST = 0x0C11;
    /** glGetIntegerv 查询项：当前视口。 */
    public static final int GL_VIEWPORT = 0x0BA2;
    /** 二维纹理目标。 */
    public static final int GL_TEXTURE_2D = 0x0DE1;
    /** 纹理单元 0。 */
    public static final int GL_TEXTURE0 = 0x84C0;
    /** 纹理像素通道布局：RGBA。 */
    public static final int GL_RGBA = 0x1908;
    /** 像素分量类型：每通道 8 位无符号。 */
    public static final int GL_UNSIGNED_BYTE = 0x1401;
    /** 纹理采样参数：缩小过滤。 */
    public static final int GL_TEXTURE_MIN_FILTER = 0x2801;
    /** 纹理采样参数：放大过滤。 */
    public static final int GL_TEXTURE_MAG_FILTER = 0x2800;
    /** 过滤模式：最近邻。 */
    public static final int GL_NEAREST = 0x2600;
    /** 纹理环绕参数：S 轴。 */
    public static final int GL_TEXTURE_WRAP_S = 0x2802;
    /** 纹理环绕参数：T 轴。 */
    public static final int GL_TEXTURE_WRAP_T = 0x2803;
    /** 环绕模式：夹取到边缘。 */
    public static final int GL_CLAMP_TO_EDGE = 0x812F;
    /** glPixelStorei 参数：解包行对齐字节数。 */
    public static final int GL_UNPACK_ALIGNMENT = 0x0CF5;
    /**
     * glPixelStorei 参数：解包时每行读取的像素数（{@code GL_UNPACK_ROW_LENGTH}）。
     *
     * <p>必须在上传前显式归零：游戏自己上传图集时会把它设成图集宽度，而<strong>这个值会影响
     * 我们的上传</strong>——驱动会按「行内 4096 像素」去读我们的小缓冲，直接读越界到驱动里
     * 崩掉（1.21.11 真机实测：{@code nvoglv64.dll} ACCESS_VIOLATION，栈帧落在
     * {@code ModernGlApi.uploadTexture} 的 {@code glTexImage2D}）。
     */
    public static final int GL_UNPACK_ROW_LENGTH = 0x0CF2;
    /** glPixelStorei 参数：解包时跳过的行数（{@code GL_UNPACK_SKIP_ROWS}），同样必须归零。 */
    public static final int GL_UNPACK_SKIP_ROWS = 0x0CF3;
    /** glPixelStorei 参数：解包时跳过的像素数（{@code GL_UNPACK_SKIP_PIXELS}），同样必须归零。 */
    public static final int GL_UNPACK_SKIP_PIXELS = 0x0CF4;

    // 以下为已解析的 GL 入口点。标记「可选」的句柄在老版本 LWJGL 上可能缺失，
    // 此时对应包装方法会安全地跳过调用；未标记的则由 bind 保证非 null。
    /** {@code glGenBuffers}（必需）。 */
    private Method genBuffers;
    /** {@code glBindBuffer}（必需）。 */
    private Method bindBuffer;
    /** {@code glBufferData}（必需）。 */
    private Method bufferData;
    /** {@code glDeleteBuffers(int[])}（可选）。 */
    private Method deleteBuffers;
    /** {@code glCreateShader}（必需）。 */
    private Method createShader;
    /** {@code glShaderSource}（必需）。 */
    private Method shaderSource;
    /** {@code glCompileShader}（必需）。 */
    private Method compileShader;
    /** {@code glGetShaderi}（可选，缺失时 {@link #compileOk} 只会返回 false）。 */
    private Method getShaderi;
    /** {@code glGetShaderInfoLog}（可选，缺失时日志为空）。 */
    private Method getShaderInfoLog;
    /** {@code glDeleteShader}（可选）。 */
    private Method deleteShader;
    /** {@code glCreateProgram}（必需）。 */
    private Method createProgram;
    /** {@code glAttachShader}（可选）。 */
    private Method attachShader;
    /** {@code glBindAttribLocation}（可选，缺失时属性位置交给驱动分配）。 */
    private Method bindAttribLocation;
    /** {@code glLinkProgram}（可选）。 */
    private Method linkProgram;
    /** {@code glGetProgrami}（可选，缺失时 {@link #linkOk} 只会返回 false）。 */
    private Method getProgrami;
    /** {@code glGetProgramInfoLog}（可选，缺失时日志为空）。 */
    private Method getProgramInfoLog;
    /** {@code glDeleteProgram}（可选）。 */
    private Method deleteProgram;
    /** {@code glUseProgram}（必需）。 */
    private Method useProgram;
    /** {@code glGetUniformLocation}（可选）。 */
    private Method getUniformLocation;
    /** {@code glUniformMatrix4fv}（可选）。 */
    private Method uniformMatrix4fv;
    /** {@code glUniform4f}（可选）。 */
    private Method uniform4f;
    /** {@code glGenVertexArrays}（必需）。 */
    private Method genVertexArrays;
    /** {@code glBindVertexArray}（必需）。 */
    private Method bindVertexArray;
    /** {@code glDeleteVertexArrays}（可选）。 */
    private Method deleteVertexArrays;
    /** {@code glEnableVertexAttribArray}（必需）。 */
    private Method enableVertexAttribArray;
    /** {@code glVertexAttribPointer}（必需）。 */
    private Method vertexAttribPointer;
    /** {@code glDrawArrays}（必需）。 */
    private Method drawArrays;
    /** {@code glEnable}（可选）。 */
    private Method enable;
    /** {@code glDisable}（可选）。 */
    private Method disable;
    /** {@code glIsEnabled}（可选，缺失时无法保存/还原开关的先前状态）。 */
    private Method isEnabled;
    /** {@code glBlendFunc}（可选）。 */
    private Method blendFunc;
    /** {@code glBlendFuncSeparate}（可选；缺失时 ALPHA 对按 RGB 对还原）。 */
    private Method blendFuncSeparate;
    /** {@code glScissor}（可选）。 */
    private Method scissor;
    /** {@code BufferUtils.createFloatBuffer}（可选，缺失时无法上传顶点）。 */
    private Method newFloatBuffer;
    /** {@code glGetIntegerv(int, IntBuffer)}（LWJGL3 真实形态，优先）。 */
    private Method getIntegervBuffer;
    /** {@code glGetIntegerv(int, int[])}（老版本兜底形态）。 */
    private Method getIntegervArray;
    /** {@code glGenTextures(int[])}。 */
    private Method genTextures;
    /** {@code glDeleteTextures(int[])}。 */
    private Method deleteTextures;
    /** {@code glBindTexture(int, int)}。 */
    private Method bindTexture;
    /** {@code glTexImage2D(..., ByteBuffer)}。 */
    private Method texImage2D;
    /** {@code glTexParameteri(int, int, int)}。 */
    private Method texParameteri;
    /** {@code glPixelStorei(int, int)}。 */
    private Method pixelStorei;
    /** {@code glActiveTexture(int)}。 */
    private Method activeTexture;
    /** {@code glUniform1i(int, int)}。 */
    private Method uniform1i;

    /** 纹理句柄读写与像素上传复用的直接缓冲；不足时按需扩容。 */
    private java.nio.ByteBuffer pixelStaging;


    /** 复用的直接缓冲区，避免每次上传都 {@code memAlloc}；不足时按需扩容。 */
    private FloatBuffer staging;
    /** 复用的直接 IntBuffer，供 {@link #getInteger} 读取 GL 参数（如视口）。 */
    private java.nio.IntBuffer viewportStaging;
    /** 句柄在 {@link #bind} 中逐项解析后写入；未解析到的保持 {@code null}。 */
    private ModernGlApi() {
    }

    /**
     * 绑定核心 profile 入口点。
     *
     * @param loader 游戏的类加载器，用于解析 LWJGL3 类
     * @return 可用的绑定；若绘制必需的子集不完整则返回 {@code null}
     */
    public static ModernGlApi bind(ClassLoader loader) {
        // LWJGL 的数组重载是 glGenBuffers(int[])，不存在 (int, int[]) 这种形式。
        Method genBuffers = find(loader, "glGenBuffers", int[].class);
        Method bindBuffer = find(loader, "glBindBuffer", int.class, int.class);
        Method bufferData = find(loader, "glBufferData", int.class, FloatBuffer.class, int.class);
        Method createShader = find(loader, "glCreateShader", int.class);
        Method shaderSource = find(loader, "glShaderSource", int.class, CharSequence.class);
        Method compileShader = find(loader, "glCompileShader", int.class);
        Method createProgram = find(loader, "glCreateProgram");
        Method useProgram = find(loader, "glUseProgram", int.class);
        Method genVertexArrays = find(loader, "glGenVertexArrays", int[].class);
        Method bindVertexArray = find(loader, "glBindVertexArray", int.class);
        Method enableVertexAttribArray = find(loader, "glEnableVertexAttribArray", int.class);
        Method vertexAttribPointer = find(loader, "glVertexAttribPointer",
                int.class, int.class, int.class, boolean.class, int.class, long.class);
        Method drawArrays = find(loader, "glDrawArrays", int.class, int.class, int.class);

        // 必需子集：缺任何一个都无法完成「上传顶点 → 着色器 → 绘制」，故直接判定不可用。
        if (genBuffers == null || bindBuffer == null || bufferData == null || createShader == null
                || shaderSource == null || compileShader == null || createProgram == null
                || useProgram == null || genVertexArrays == null || bindVertexArray == null
                || enableVertexAttribArray == null || vertexAttribPointer == null || drawArrays == null) {
            // 核心 profile 绑定失败时列出缺了哪一项：1.13+ 只有核心 profile，
            // 回退到固定管线等于整个 GUI 画不出来，必须能一眼看出原因。
            System.out.println("[nocturne] core profile bind miss:"
                    + " genBuffers=" + (genBuffers != null)
                    + " bindBuffer=" + (bindBuffer != null)
                    + " bufferData=" + (bufferData != null)
                    + " createShader=" + (createShader != null)
                    + " shaderSource=" + (shaderSource != null)
                    + " compileShader=" + (compileShader != null)
                    + " createProgram=" + (createProgram != null)
                    + " useProgram=" + (useProgram != null)
                    + " genVertexArrays=" + (genVertexArrays != null)
                    + " bindVertexArray=" + (bindVertexArray != null)
                    + " enableVertexAttribArray=" + (enableVertexAttribArray != null)
                    + " vertexAttribPointer=" + (vertexAttribPointer != null)
                    + " drawArrays=" + (drawArrays != null));
            return null;
        }

        ModernGlApi api = new ModernGlApi();
        api.genBuffers = genBuffers;
        api.bindBuffer = bindBuffer;
        api.bufferData = bufferData;
        // LWJGL3 只有 glDeleteBuffers(int[]) 与 (int, IntBuffer)，没有 (int, int[])。
        api.deleteBuffers = find(loader, "glDeleteBuffers", int[].class);
        api.createShader = createShader;
        api.shaderSource = shaderSource;
        api.compileShader = compileShader;
        api.getShaderi = find(loader, "glGetShaderi", int.class, int.class);
        api.getShaderInfoLog = find(loader, "glGetShaderInfoLog", int.class);
        api.deleteShader = find(loader, "glDeleteShader", int.class);
        api.createProgram = createProgram;
        api.attachShader = find(loader, "glAttachShader", int.class, int.class);
        api.bindAttribLocation = find(loader, "glBindAttribLocation",
                int.class, int.class, CharSequence.class);
        api.linkProgram = find(loader, "glLinkProgram", int.class);
        api.getProgrami = find(loader, "glGetProgrami", int.class, int.class);
        api.getProgramInfoLog = find(loader, "glGetProgramInfoLog", int.class);
        api.deleteProgram = find(loader, "glDeleteProgram", int.class);
        api.useProgram = useProgram;
        api.getUniformLocation = find(loader, "glGetUniformLocation", int.class, CharSequence.class);
        api.uniformMatrix4fv = find(loader, "glUniformMatrix4fv", int.class, boolean.class, float[].class);
        api.uniform4f = find(loader, "glUniform4f",
                int.class, float.class, float.class, float.class, float.class);
        api.genVertexArrays = genVertexArrays;
        api.bindVertexArray = bindVertexArray;
        api.deleteVertexArrays = find(loader, "glDeleteVertexArrays", int[].class);
        api.enableVertexAttribArray = enableVertexAttribArray;
        api.vertexAttribPointer = vertexAttribPointer;
        api.drawArrays = drawArrays;
        api.enable = find(loader, "glEnable", int.class);
        api.disable = find(loader, "glDisable", int.class);
        api.isEnabled = find(loader, "glIsEnabled", int.class);
        api.blendFuncSeparate = find(loader, "glBlendFuncSeparate",
                int.class, int.class, int.class, int.class);
        api.blendFunc = find(loader, "glBlendFunc", int.class, int.class);
        api.scissor = find(loader, "glScissor", int.class, int.class, int.class, int.class);
        api.newFloatBuffer = findBufferUtils(loader);
        // LWJGL3 只暴露 glGetIntegerv(int, IntBuffer)；没有 (int, int[]) 重载。
        // 修复前试图查找 (int, int[]) 结果必然 null → 视口读不出来 → 26.x 上 GUI 一个像素都画不出。
        // LWJGL 的 checkBuffer 需要 buffer 有余量，所以容量给 32 元素（大于视口实际 4）。
        // 注意：两个形态分开记录。H05-B3 指出旧代码无条件分配 viewportStaging，
        // 导致数组形态命中时仍走 IntBuffer 路径 → invoke 签名错配抛错被吞 → 视口永读不出。
        api.getIntegervBuffer = find(loader, "glGetIntegerv", int.class, java.nio.IntBuffer.class);
        if (api.getIntegervBuffer == null) {
            // 兜底：老版本 LWJGL3 可能有 legacy (int, int[]) 形态，继续尝试
            api.getIntegervArray = find(loader, "glGetIntegerv", int.class, int[].class);
        }
        if (api.getIntegervBuffer != null) {
            api.viewportStaging = java.nio.ByteBuffer
                    .allocateDirect(32 * 4)
                    .order(java.nio.ByteOrder.nativeOrder())
                    .asIntBuffer();
        }
        // 纹理支路：全部按可选处理，缺项时 supportsTextures() 返回 false，UI 走降级路径。
        api.genTextures = find(loader, "glGenTextures", int[].class);
        api.deleteTextures = find(loader, "glDeleteTextures", int[].class);
        api.bindTexture = find(loader, "glBindTexture", int.class, int.class);
        api.texImage2D = find(loader, "glTexImage2D", int.class, int.class, int.class, int.class,
                int.class, int.class, int.class, int.class, java.nio.ByteBuffer.class);
        api.texParameteri = find(loader, "glTexParameteri", int.class, int.class, int.class);
        api.pixelStorei = find(loader, "glPixelStorei", int.class, int.class);
        api.activeTexture = find(loader, "glActiveTexture", int.class);
        api.uniform1i = find(loader, "glUniform1i", int.class, int.class);
        return api;
    }

    /** @return 是否具备绘制纹理所需的最小句柄集（生成 / 绑定 / 上传 / 采样参数都由本类包装）。 */
    public boolean hasTextures() {
        // activeTexture 必须可用：没有它就无法保证纹理绑在我们采样的那个单元上。
        return activeTexture != null && genTextures != null && bindTexture != null && texImage2D != null
                && texParameteri != null;
    }

    /** @return 新建纹理句柄；不可用或失败时为 0 */
    public int genTexture() {
        if (genTextures == null) {
            return 0;
        }
        int[] ids = new int[1];
        Reflect.call(genTextures, null, ids);
        return ids[0];
    }

    /** 删除纹理句柄；{@code id <= 0} 或不可用时为空操作。 */
    public void deleteTexture(int id) {
        if (deleteTextures == null || id <= 0) {
            return;
        }
        Reflect.call(deleteTextures, null, new int[]{id});
    }

    /** 绑定二维纹理。 */
    public void bindTexture2D(int id) {
        if (bindTexture == null) {
            return;
        }
        Reflect.call(bindTexture, null, GL_TEXTURE_2D, id);
    }

    /**
     * 选择当前活动的纹理单元（对应 {@code glActiveTexture}）。
     *
     * @param unit 形如 {@link #GL_TEXTURE0} 的枚举值
     */
    public void activeTexture(int unit) {
        Reflect.call(activeTexture, null, unit);
    }

    /** 设置采样器 uniform 指向的纹理单元；{@code location < 0} 时跳过。 */
    public void uniform1i(int location, int value) {
        if (location < 0 || uniform1i == null) {
            return;
        }
        Reflect.call(uniform1i, null, location, value);
    }

    /** 设置纹理采样参数。 */
    public void texParameteri(int target, int pname, int param) {
        Reflect.call(texParameteri, null, target, pname, param);
    }

    /** 设置像素存储模式。 */
    public void pixelStorei(int pname, int param) {
        Reflect.call(pixelStorei, null, pname, param);
    }

    /**
     * 上传一张 RGBA 纹理的全量像素（非预乘 {@code 0xAARRGGBB}、行优先、左上原点）。
     *
     * @return 下发成功返回 {@code true}
     */
    public boolean uploadTexture(int width, int height, int[] argb) {
        if (texImage2D == null || width <= 0 || height <= 0 || argb == null) {
            return false;
        }
        int needed = width * height * 4;
        java.nio.ByteBuffer buffer = pixelStaging;
        if (buffer == null || buffer.capacity() < needed) {
            buffer = java.nio.ByteBuffer.allocateDirect(needed)
                    .order(java.nio.ByteOrder.nativeOrder());
            pixelStaging = buffer;
        }
        buffer.clear();
        for (int i = 0; i < width * height; i++) {
            int pixel = argb[i];
            buffer.put((byte) ((pixel >> 16) & 0xFF));  // R
            buffer.put((byte) ((pixel >> 8) & 0xFF));   // G
            buffer.put((byte) (pixel & 0xFF));          // B
            buffer.put((byte) ((pixel >>> 24) & 0xFF)); // A
        }
        buffer.flip();
        if (pixelStorei != null) {
            // 行对齐：RGBA 每行字节数必然是 4 的倍数，显式设为 4 以免游戏把全局值改过。
            Reflect.call(pixelStorei, null, GL_UNPACK_ALIGNMENT, 4);
            // 这三个解包状态必须显式归零：GL 的源地址 = base + ((SKIP_ROWS*ROW_LENGTH)+SKIP_PIXELS)*bpp，
            // 而游戏上传图集时会把它们留在非 0 值上。1.21.11 真机实测（首次上传时读到）：
            // ROW_LENGTH=1152、SKIP_ROWS=864、SKIP_PIXELS=288 → 源地址在 base 之后 3,982,464 字节，
            // 而我们的缓冲区只有几字节 → 驱动读越界，直接 native abort（EXCEPTION_ACCESS_VIOLATION，
            // problematic frame nvoglv64.dll）。归零后同一流程不再崩（同机 A/B 已验证）。
            Reflect.call(pixelStorei, null, GL_UNPACK_ROW_LENGTH, 0);
            Reflect.call(pixelStorei, null, GL_UNPACK_SKIP_ROWS, 0);
            Reflect.call(pixelStorei, null, GL_UNPACK_SKIP_PIXELS, 0);
        }
        Reflect.call(texImage2D, null, GL_TEXTURE_2D, 0, GL_RGBA, width, height, 0,
                GL_RGBA, GL_UNSIGNED_BYTE, buffer);
        return true;
    }

    /**
     * 读取整型 GL 参数（如 {@link #GL_VIEWPORT}）。
     *
     * @param name  参数名
     * @param count 输出数组长度（如视口为 4）
     * @return 读到的值；{@code glGetIntegerv} 不可用或调用失败时返回 {@code null}
     */
    public int[] getInteger(int name, int count) {
        // 按实际命中的签名形态分派（H05-B3）：旧代码用 viewportStaging != null 判定，
        // 而 staging 无条件分配 → 数组形态命中时仍走 IntBuffer 路径，invoke 签名错配抛错被吞。
        if (getIntegervBuffer != null && viewportStaging != null) {
            viewportStaging.clear();
            viewportStaging.limit(count);
            try {
                getIntegervBuffer.invoke(null, name, viewportStaging);
            } catch (Throwable t) {
                return null;
            }
            int[] out = new int[count];
            // 这里**不能** flip()：GL 是按原生地址写缓冲区的，Java 侧的 position 始终停在 0，
            // flip() 会把 limit 置成 position(=0)，于是 remaining() 恒为 0——旧代码据此判定
            // "GL 没写入"并返回 null，视口因此永远退回 Window 推算、混合因子恢复成了死代码。
            // invoke 已经成功过一次（失败会抛并被上面捕获），按 limit=count、position=0 直接读即可。
            viewportStaging.get(out, 0, count);
            return out;
        }
        if (getIntegervArray != null) {
            // 数组形态（老版本 LWJGL3）
            int[] out = new int[count];
            try {
                // 不走 Reflect.call：后者把失败一律变成 null，而 void 方法成功时也是 null，
                // 结果就是「句柄存在但调用抛异常」被伪装成 {0,0,0,0}。
                getIntegervArray.invoke(null, name, (Object) out);
            } catch (Throwable t) {
                return null;
            }
            return out;
        }
        return null;
    }

    /** 便捷方法：以三角形列表绘制一段顶点，假定所需 program 与 VAO 已处于启用状态。 */
    public void drawTriangles(int first, int count) {
        if (drawArrays != null) {
            Reflect.call(drawArrays, null, GL_TRIANGLES, first, count);
        }
    }

    /**
     * 按名字与签名在 LWJGL 的 core/legacy 两种类写法中查找函数。
     *
     * <p>找不到时返回 {@code null}——调用方据此把该能力视为「不可用」。
     */
    private static Method find(ClassLoader loader, String name, Class<?>... parameters) {
        String[] owners = {
                "org.lwjgl.opengl.GL30C", "org.lwjgl.opengl.GL30",
                "org.lwjgl.opengl.GL20C", "org.lwjgl.opengl.GL20",
                // glActiveTexture 从 GL13 起：不列它的话句柄为 null，调用被静默跳过，
                // 纹理就绑到游戏当时的活动单元上，而采样器固定看 0 号单元——表现为文字全是黑块。
                "org.lwjgl.opengl.GL13C", "org.lwjgl.opengl.GL13",
                "org.lwjgl.opengl.GL15C", "org.lwjgl.opengl.GL15",
                "org.lwjgl.opengl.GL11C", "org.lwjgl.opengl.GL11",
                "org.lwjgl.opengl.GL14C", "org.lwjgl.opengl.GL14",
        };
        for (String owner : owners) {
            Method method = Reflect.method(Reflect.loadWithoutInit(owner, loader), name, parameters);
            if (method != null) {
                return method;
            }
        }
        return null;
    }

    /** 解析 {@code BufferUtils.createFloatBuffer}，用于把 float 数组包成直接缓冲区。 */
    private static Method findBufferUtils(ClassLoader loader) {
        return Reflect.method(Reflect.loadWithoutInit("org.lwjgl.BufferUtils", loader),
                "createFloatBuffer", int.class);
    }

    // ------------------------------------------------------------- 原始包装方法

    /** @return 新建的顶点缓冲对象 id；失败时为 0 */
    public int genBuffer() {
        int[] ids = new int[1];
        // glGenBuffers 的签名是 glGenBuffers(int[])，多传一个 count 会让调用抛
        // IllegalArgumentException 并被 Reflect.call 吞掉，ids[0] 恒为 0。
        Reflect.call(genBuffers, null, ids);
        return ids[0];
    }

    /** 绑定顶点数组缓冲。 */
    public void bindArrayBuffer(int id) {
        Reflect.call(bindBuffer, null, GL_ARRAY_BUFFER, id);
    }

    /**
     * 删除一个顶点缓冲对象；{@code glDeleteBuffers} 不可用时为空操作。
     */
    public void deleteBuffer(int id) {
        if (deleteBuffers == null || id == 0) {
            return;
        }
        Reflect.call(deleteBuffers, null, new int[]{id});
    }

    /**
     * 删除一个顶点数组对象；{@code glDeleteVertexArrays} 不可用时为空操作。
     */
    public void deleteVertexArray(int id) {
        if (deleteVertexArrays == null || id == 0) {
            return;
        }
        Reflect.call(deleteVertexArrays, null, new int[]{id});
    }

    /**
     * 把 float 数组上传到当前绑定的数组缓冲。
     *
     * @param data 源数组
     */
    public void uploadArrayBuffer(float[] data) {
        uploadArrayBuffer(data, data == null ? 0 : data.length);
    }

    /**
     * 把 float 数组的前 {@code length} 个元素上传到当前绑定的数组缓冲。
     *
     * <p>复用内部的直接缓冲区，避免每次调用都新建一个 {@code FloatBuffer}（每帧几十上百次
     * {@code memAlloc} 会造成原生内存压力与 GC 抖动）。
     *
     * <p>缺失 {@code createFloatBuffer} 或 {@code glBufferData} 时静默跳过，
     * 绘制层另有就绪判断兜底。
     *
     * @param data   源数组
     * @param length 需要上传的元素个数
     */
    public void uploadArrayBuffer(float[] data, int length) {
        if (bufferData == null || newFloatBuffer == null || data == null) {
            return;
        }
        if (length <= 0 || length > data.length) {
            return;
        }
        FloatBuffer buffer = staging;
        if (buffer == null || buffer.capacity() < length) {
            Object created = Reflect.call(newFloatBuffer, null, length);
            if (!(created instanceof FloatBuffer)) {
                return;
            }
            buffer = (FloatBuffer) created;
            staging = buffer;
        }
        buffer.clear();
        buffer.put(data, 0, length);
        buffer.flip();
        Reflect.call(bufferData, null, GL_ARRAY_BUFFER, buffer, GL_DYNAMIC_DRAW);
    }

    /**
     * 创建着色器对象。
     *
     * @param type {@link #GL_VERTEX_SHADER} 或 {@link #GL_FRAGMENT_SHADER}
     * @return 着色器 id；返回类型异常时为 0
     */
    public int createShader(int type) {
        Object id = Reflect.call(createShader, null, type);
        return id instanceof Number ? ((Number) id).intValue() : 0;
    }

    /** 为着色器设置 GLSL 源码。 */
    public void shaderSource(int shader, String source) {
        Reflect.call(shaderSource, null, shader, source);
    }

    /** 编译着色器；结果需用 {@link #compileOk} 检查。 */
    public void compileShader(int shader) {
        Reflect.call(compileShader, null, shader);
    }

    /** @return 编译是否成功；缺少 {@code glGetShaderi} 时返回 {@code false} */
    public boolean compileOk(int shader) {
        Object status = Reflect.call(getShaderi, null, shader, GL_COMPILE_STATUS);
        return status instanceof Number && ((Number) status).intValue() != 0;
    }

    /** @return 着色器编译日志；无可用日志时返回空串 */
    public String shaderLog(int shader) {
        Object log = Reflect.call(getShaderInfoLog, null, shader);
        return log == null ? "" : log.toString();
    }

    /** 删除着色器对象。 */
    public void deleteShader(int shader) {
        if (shader != 0) {
            Reflect.call(deleteShader, null, shader);
        }
    }

    /** @return 新建的着色器程序 id；返回类型异常时为 0 */
    public int createProgram() {
        Object id = Reflect.call(createProgram, null);
        return id instanceof Number ? ((Number) id).intValue() : 0;
    }

    /** 把着色器附加到程序上。 */
    public void attachShader(int program, int shader) {
        Reflect.call(attachShader, null, program, shader);
    }

    /**
     * 在链接前为程序绑定顶点属性位置。
     *
     * <p>不绑定的话，驱动可以把 {@code aPos} 分配到 0 以外的位置，而顶点布局却按索引 0 描述，
     * 结果是属性未被描述、绘制出随机三角形。
     *
     * @return 下发成功返回 true；句柄缺失返回 false（P5：调用方必须按失败处理，
     *         不能带着错乱的位置继续）。
     */
    public boolean bindAttribLocation(int program, int index, String name) {
        if (bindAttribLocation == null) {
            return false;
        }
        Reflect.call(bindAttribLocation, null, program, index, name);
        return true;
    }

    /** 链接着色器程序；结果需用 {@link #linkOk} 检查。 */
    public void linkProgram(int program) {
        Reflect.call(linkProgram, null, program);
    }

    /** @return 链接是否成功；缺少 {@code glGetProgrami} 时返回 {@code false} */
    public boolean linkOk(int program) {
        Object status = Reflect.call(getProgrami, null, program, GL_LINK_STATUS);
        return status instanceof Number && ((Number) status).intValue() != 0;
    }

    /** @return 程序链接日志；无可用日志时返回空串 */
    public String programLog(int program) {
        Object log = Reflect.call(getProgramInfoLog, null, program);
        return log == null ? "" : log.toString();
    }

    /** 删除着色器程序。 */
    public void deleteProgram(int program) {
        if (program != 0) {
            Reflect.call(deleteProgram, null, program);
        }
    }

    /**
     * 查询 uniform 位置。
     *
     * @return uniform 位置；查询不可用或名字不存在时返回 -1
     */
    public int uniformLocation(int program, String name) {
        Object location = Reflect.call(getUniformLocation, null, program, name);
        return location instanceof Number ? ((Number) location).intValue() : -1;
    }

    /**
     * 设置 vec4 颜色 uniform，取值 0–1。
     *
     * <p>位置为 -1（uniform 不存在或程序链接失败）时跳过调用：对 -1 调用 {@code glUniform4f}
     * 每帧都会产生一次 {@code GL_INVALID_VALUE}，而颜色/变换本就无法生效。
     */
    public void uniform4f(int location, float r, float g, float b, float a) {
        // P6：句柄缺失时 Reflect.call 空转，uniform 保持旧值画错；直接跳过。
        if (location < 0 || uniform4f == null) {
            return;
        }
        Reflect.call(uniform4f, null, location, r, g, b, a);
    }

    /**
     * 上传 4x4 矩阵 uniform。
     *
     * @param matrix 列主序的 16 个元素（GL 的默认布局）
     */
    public void uniformMatrix4fv(int location, float[] matrix) {
        if (location < 0 || uniformMatrix4fv == null) {
            return;
        }
        Reflect.call(uniformMatrix4fv, null, location, false, matrix);
    }

    /** 启用指定的着色器程序；{@code program} 为 0 时解绑当前程序。 */
    public void useProgram(int program) {
        Reflect.call(useProgram, null, program);
    }

    /** @return 新建的顶点数组对象 id；失败时为 0 */
    public int genVertexArray() {
        int[] ids = new int[1];
        Reflect.call(genVertexArrays, null, ids);
        return ids[0];
    }

    /** 绑定顶点数组对象；{@code id} 为 0 时解绑。 */
    public void bindVertexArray(int id) {
        Reflect.call(bindVertexArray, null, id);
    }

    /** 启用指定索引的顶点属性数组。 */
    public void enableVertexAttrib(int index) {
        Reflect.call(enableVertexAttribArray, null, index);
    }

    /**
     * 描述顶点属性的布局。
     *
     * @param index  属性索引
     * @param size   每个顶点的分量数
     * @param stride 相邻顶点的字节步长
     * @param offset 在步长内的字节偏移
     */
    public void vertexAttribPointer(int index, int size, int stride, int offset) {
        Reflect.call(vertexAttribPointer, null, index, size, GL_FLOAT, false, stride, (long) offset);
    }

    // ------------------------------------------------------------- 状态开关

    /** 开启混合并使用常规的 src-alpha 混合因子，同时返回下发前的因子对（还原时用）。 */
    public int[] enableBlendAndReadPrevious() {
        int[] previous = null;
        if (getIntegervBuffer != null && viewportStaging != null) {
            // GL_BLEND_SRC_RGB / GL_BLEND_DST_RGB / GL_BLEND_SRC_ALPHA / GL_BLEND_DST_ALPHA
            // 一次 getIntegerv 只能查一个平面；这里用四个单独查询（视口以内）。查不到就返回 null。
            // GL_BLEND_SRC_RGB=0x80C9, GL_BLEND_DST_RGB=0x80C8, GL_BLEND_SRC_ALPHA=0x80CB,
            // GL_BLEND_DST_ALPHA=0x80CA。旧值 0x0C30-0x0C33 是 pre-1.4 的 token（核心 profile 下
            // glGetIntegerv 直接 GL_INVALID_ENUM 且不写值），会让还原用到垃圾因子。
            int[][] queries = {{0x80C9, 0x80C8}, {0x80CB, 0x80CA}};
            int[] prev = new int[4];
            for (int i = 0; i < 4; i++) {
                int[] read = getInteger(queries[i / 2][i % 2], 1);
                if (read == null) {
                    prev = null;
                    break;
                }
                prev[i] = read[0];
            }
            // P7：GL_ZERO==0 是合法因子，不能拿 ">0" 判成功——四个查询都返回非 null 即读到了真实值。
            previous = prev;
        }
        enableCap(GL_BLEND);
        Reflect.call(blendFunc, null, GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        return previous;
    }

    /**
     * 还原 enableBlendAndReadPrevious 读到的混合因子。
     *
     * <p>读到 4 因子就用 glBlendFuncSeparate 全还（只还 RGB 对会让 ALPHA 对永久残留）；
     * separate 不可用时退化为 RGB 对。
     */
    public void restoreBlendFunc(int[] previous) {
        if (previous == null) {
            return;
        }
        if (previous.length >= 4 && blendFuncSeparate != null) {
            Reflect.call(blendFuncSeparate, null, previous[0], previous[1], previous[2], previous[3]);
            return;
        }
        if (blendFunc != null) {
            Reflect.call(blendFunc, null, previous[0], previous[1]);
        }
    }

    /** 关闭深度测试；UI 与游戏共用同一缓冲时避免被世界几何遮挡。 */
    public void disableDepthTest() {
        disableCap(GL_DEPTH_TEST);
    }

    /** 关闭背面剔除；本后端的投影含 Y 翻转，所有三角形都是「背面」。 */
    public void disableCullFace() {
        disableCap(GL_CULL_FACE);
    }

    /** 开启裁剪测试。 */
    public void enableScissorTest() {
        enableCap(GL_SCISSOR_TEST);
    }

    /** 关闭裁剪测试。 */
    public void disableScissorTest() {
        disableCap(GL_SCISSOR_TEST);
    }

    /** 开启某项 GL 能力；{@code glEnable} 不可用时为空操作。 */
    public void enableCap(int cap) {
        Reflect.call(enable, null, cap);
    }

    /** 关闭某项 GL 能力；{@code glDisable} 不可用时为空操作。 */
    public void disableCap(int cap) {
        Reflect.call(disable, null, cap);
    }

    /** @return 某项 GL 能力当前是否开启；无法查询时返回 {@code false} */
    public boolean capabilityEnabled(int cap) {
        Object status = Reflect.call(isEnabled, null, cap);
        return Boolean.TRUE.equals(status);
    }

    /** @return 是否具备查询 GL 开关状态的能力（保存/还原状态的前提） */
    public boolean hasIsEnabled() {
        return isEnabled != null;
    }

    /** 设置裁剪矩形（{@code glScissor}），参数为窗口像素、原点在左下角。 */
    public void scissor(int x, int y, int width, int height) {
        Reflect.call(scissor, null, x, y, width, height);
    }

    /** @return 是否具备 {@code glScissor} 与开关能力（本后端实现裁剪的前提） */
    public boolean hasScissor() {
        return scissor != null && enable != null && disable != null;
    }
}
