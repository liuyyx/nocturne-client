package dev.noturne.ui.gl;

import dev.noturne.client.game.Reflect;

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
    /** {@code glScissor}（可选）。 */
    private Method scissor;
    /** {@code BufferUtils.createFloatBuffer}（可选，缺失时无法上传顶点）。 */
    private Method newFloatBuffer;
    /** {@code glGetIntegerv}（可选，缺失时 {@link #getInteger} 返回 {@code null}）。 */
    private Method getIntegerv;

    /** 复用的直接缓冲区，避免每次上传都 {@code memAlloc}；不足时按需扩容。 */
    private FloatBuffer staging;
    /** 复用的直接 IntBuffer，供 {@link #getIntegerv} 读取 GL 参数（如视口）。 */
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
            System.out.println("[noturne] core profile bind miss:"
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
        api.blendFunc = find(loader, "glBlendFunc", int.class, int.class);
        api.scissor = find(loader, "glScissor", int.class, int.class, int.class, int.class);
        api.newFloatBuffer = findBufferUtils(loader);
        // LWJGL3 只暴露 glGetIntegerv(int, IntBuffer)；没有 (int, int[]) 重载。
        // 修复前试图查找 (int, int[]) 结果必然 null → 视口读不出来 → 26.x 上 GUI 一个像素都画不出。
        // LWJGL 的 checkBuffer 需要 buffer 有余量，所以容量给 32 元素（大于视口实际 4）。
        api.getIntegerv = find(loader, "glGetIntegerv", int.class, java.nio.IntBuffer.class);
        if (api.getIntegerv == null) {
            // 兜底：老版本 LWJGL3 可能有 legacy (int, int[]) 形态，继续尝试
            api.getIntegerv = find(loader, "glGetIntegerv", int.class, int[].class);
        }
        if (api.getIntegerv != null) {
            api.viewportStaging = java.nio.ByteBuffer
                    .allocateDirect(32 * 4)
                    .order(java.nio.ByteOrder.nativeOrder())
                    .asIntBuffer();
        }
        return api;
    }

    /**
     * 读取整型 GL 参数（如 {@link #GL_VIEWPORT}）。
     *
     * @param name  参数名
     * @param count 输出数组长度（如视口为 4）
     * @return 读到的值；{@code glGetIntegerv} 不可用或调用失败时返回 {@code null}
     */
    public int[] getInteger(int name, int count) {
        if (getIntegerv == null) {
            return null;
        }
        // IntBuffer 形态优先（LWJGL3 是 (int, IntBuffer)）
        if (viewportStaging != null) {
            viewportStaging.clear();
            viewportStaging.limit(count);
            try {
                getIntegerv.invoke(null, name, viewportStaging);
            } catch (Throwable t) {
                return null;
            }
            int[] out = new int[count];
            viewportStaging.flip();
            viewportStaging.get(out, 0, count);
            return out;
        }
        // 数组形态兜底（老版本 LWJGL3）
        int[] out = new int[count];
        try {
            // 不走 Reflect.call：后者把失败一律变成 null，而 void 方法成功时也是 null，
            // 结果就是「句柄存在但调用抛异常」被伪装成 {0,0,0,0}。
            getIntegerv.invoke(null, name, (Object) out);
        } catch (Throwable t) {
            return null;
        }
        return out;
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
     * 结果是属性未被描述、绘制出随机三角形。{@code glBindAttribLocation} 缺失时为空操作。
     */
    public void bindAttribLocation(int program, int index, String name) {
        Reflect.call(bindAttribLocation, null, program, index, name);
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
        if (location < 0) {
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
        if (location < 0) {
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
        if (getIntegerv != null && viewportStaging != null) {
            // GL_BLEND_SRC_RGB / GL_BLEND_DST_RGB / GL_BLEND_SRC_ALPHA / GL_BLEND_DST_ALPHA
            // 一次 getIntegerv 只能查一个平面；这里用四个单独查询（视口以内）。查不到就返回 null。
            int[][] queries = {{0x0C30, 0x0C31}, {0x0C32, 0x0C33}};
            int[] prev = new int[4];
            for (int i = 0; i < 4; i++) {
                int[] read = getInteger(queries[i / 2][i % 2], 1);
                if (read == null) {
                    prev = null;
                    break;
                }
                prev[i] = read[0];
            }
            if (prev != null && prev[0] > 0 && prev[2] > 0) {
                // GL 常量 GL_ONE==1；值 >0 说明读到了真实因子
                previous = prev;
            }
        }
        enableCap(GL_BLEND);
        Reflect.call(blendFunc, null, GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
        return previous;
    }

    /** 还原 enableBlendAndReadPrevious 读到的混合因子对；读不到时不做（读本后端自己每帧下发）。 */
    public void restoreBlendFunc(int[] previous) {
        if (previous != null && blendFunc != null) {
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
