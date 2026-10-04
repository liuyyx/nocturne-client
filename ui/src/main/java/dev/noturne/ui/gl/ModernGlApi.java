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
    /** 2D 纹理目标 / 纹理开关。 */
    public static final int GL_TEXTURE_2D = 0x0DE1;
    /** glGetIntegerv 查询项：当前视口。 */
    public static final int GL_VIEWPORT = 0x0BA2;

    // 以下为已解析的 GL 入口点。标记「可选」的句柄在老版本 LWJGL 上可能缺失，
    // 此时对应包装方法会安全地跳过调用；未标记的则由 bind 保证非 null。
    /** {@code glGenBuffers}（必需）。 */
    private final Method genBuffers;
    /** {@code glBindBuffer}（必需）。 */
    private final Method bindBuffer;
    /** {@code glBufferData}（必需）。 */
    private final Method bufferData;
    /** {@code glDeleteBuffers}（可选）。 */
    private final Method deleteBuffers;
    /** {@code glCreateShader}（必需）。 */
    private final Method createShader;
    /** {@code glShaderSource}（必需）。 */
    private final Method shaderSource;
    /** {@code glCompileShader}（必需）。 */
    private final Method compileShader;
    /** {@code glGetShaderi}（可选，缺失时 {@link #compileOk} 只会返回 false）。 */
    private final Method getShaderi;
    /** {@code glGetShaderInfoLog}（可选，缺失时日志为空）。 */
    private final Method getShaderInfoLog;
    /** {@code glDeleteShader}（可选）。 */
    private final Method deleteShader;
    /** {@code glCreateProgram}（必需）。 */
    private final Method createProgram;
    /** {@code glAttachShader}（可选）。 */
    private final Method attachShader;
    /** {@code glLinkProgram}（可选）。 */
    private final Method linkProgram;
    /** {@code glDeleteProgram}（可选）。 */
    private final Method deleteProgram;
    /** {@code glUseProgram}（必需）。 */
    private final Method useProgram;
    /** {@code glGetUniformLocation}（可选）。 */
    private final Method getUniformLocation;
    /** {@code glUniformMatrix4fv}（可选）。 */
    private final Method uniformMatrix4fv;
    /** {@code glUniform4f}（可选）。 */
    private final Method uniform4f;
    /** {@code glGenVertexArrays}（必需）。 */
    private final Method genVertexArrays;
    /** {@code glBindVertexArray}（必需）。 */
    private final Method bindVertexArray;
    /** {@code glEnableVertexAttribArray}（必需）。 */
    private final Method enableVertexAttribArray;
    /** {@code glVertexAttribPointer}（必需）。 */
    private final Method vertexAttribPointer;
    /** {@code glDrawArrays}（必需）。 */
    private final Method drawArrays;
    /** {@code glEnable}（可选）。 */
    private final Method enable;
    /** {@code glDisable}（可选）。 */
    private final Method disable;
    /** {@code glBlendFunc}（可选）。 */
    private final Method blendFunc;
    /** {@code glViewport}（可选）。 */
    private final Method viewport;
    /** {@code glScissor}（可选）。 */
    private final Method scissor;
    /** {@code BufferUtils.createFloatBuffer}（可选，缺失时无法上传顶点）。 */
    private final Method newFloatBuffer;
    /** {@code glGetIntegerv}（可选，缺失时 {@link #getInteger} 返回 {@code null}）。 */
    private final Method getIntegerv;

    /**
     * 保存已解析的入口点；仅由 {@link #bind(ClassLoader)} 调用。
     *
     * @param genBuffers 等为 {@link #bind} 解析出的方法句柄，允许为 {@code null}
     */
    private ModernGlApi(Method genBuffers, Method bindBuffer, Method bufferData, Method deleteBuffers,
                        Method createShader, Method shaderSource, Method compileShader, Method getShaderi,
                        Method getShaderInfoLog, Method deleteShader, Method createProgram,
                        Method attachShader, Method linkProgram, Method deleteProgram, Method useProgram,
                        Method getUniformLocation, Method uniformMatrix4fv, Method uniform4f,
                        Method genVertexArrays, Method bindVertexArray, Method enableVertexAttribArray,
                        Method vertexAttribPointer, Method drawArrays, Method enable, Method disable,
                        Method blendFunc, Method viewport, Method scissor, Method newFloatBuffer,
                        Method getIntegerv) {
        this.genBuffers = genBuffers;
        this.bindBuffer = bindBuffer;
        this.bufferData = bufferData;
        this.deleteBuffers = deleteBuffers;
        this.createShader = createShader;
        this.shaderSource = shaderSource;
        this.compileShader = compileShader;
        this.getShaderi = getShaderi;
        this.getShaderInfoLog = getShaderInfoLog;
        this.deleteShader = deleteShader;
        this.createProgram = createProgram;
        this.attachShader = attachShader;
        this.linkProgram = linkProgram;
        this.deleteProgram = deleteProgram;
        this.useProgram = useProgram;
        this.getUniformLocation = getUniformLocation;
        this.uniformMatrix4fv = uniformMatrix4fv;
        this.uniform4f = uniform4f;
        this.genVertexArrays = genVertexArrays;
        this.bindVertexArray = bindVertexArray;
        this.enableVertexAttribArray = enableVertexAttribArray;
        this.vertexAttribPointer = vertexAttribPointer;
        this.drawArrays = drawArrays;
        this.enable = enable;
        this.disable = disable;
        this.blendFunc = blendFunc;
        this.viewport = viewport;
        this.scissor = scissor;
        this.newFloatBuffer = newFloatBuffer;
        this.getIntegerv = getIntegerv;
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

        return new ModernGlApi(
                genBuffers, bindBuffer, bufferData,
                find(loader, "glDeleteBuffers", int.class, int[].class),
                createShader, shaderSource, compileShader,
                find(loader, "glGetShaderi", int.class, int.class),
                find(loader, "glGetShaderInfoLog", int.class),
                find(loader, "glDeleteShader", int.class),
                createProgram,
                find(loader, "glAttachShader", int.class, int.class),
                find(loader, "glLinkProgram", int.class),
                find(loader, "glDeleteProgram", int.class),
                useProgram,
                find(loader, "glGetUniformLocation", int.class, CharSequence.class),
                find(loader, "glUniformMatrix4fv", int.class, boolean.class, float[].class),
                find(loader, "glUniform4f", int.class, float.class, float.class, float.class, float.class),
                genVertexArrays, bindVertexArray, enableVertexAttribArray, vertexAttribPointer, drawArrays,
                find(loader, "glEnable", int.class),
                find(loader, "glDisable", int.class),
                find(loader, "glBlendFunc", int.class, int.class),
                find(loader, "glViewport", int.class, int.class, int.class, int.class),
                find(loader, "glScissor", int.class, int.class, int.class, int.class),
                findBufferUtils(loader),
                find(loader, "glGetIntegerv", int.class, int[].class));
    }

    /**
     * 读取整型 GL 参数（如 {@link #GL_VIEWPORT}）。
     *
     * @param name  参数名
     * @param count 输出数组长度（如视口为 4）
     * @return 读到的值；{@code glGetIntegerv} 不可用时返回 {@code null}
     */
    public int[] getInteger(int name, int count) {
        if (getIntegerv == null) {
            return null;
        }
        int[] out = new int[count];
        Reflect.call(getIntegerv, null, name, out);
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
            Method method = Reflect.method(Reflect.load(owner, loader), name, parameters);
            if (method != null) {
                return method;
            }
        }
        return null;
    }

    /** 解析 {@code BufferUtils.createFloatBuffer}，用于把 float 数组包成直接缓冲区。 */
    private static Method findBufferUtils(ClassLoader loader) {
        return Reflect.method(Reflect.load("org.lwjgl.BufferUtils", loader),
                "createFloatBuffer", int.class);
    }

    // ------------------------------------------------------------- 原始包装方法

    /** @return 新建的顶点缓冲对象 id */
    public int genBuffer() {
        int[] ids = new int[1];
        Reflect.call(genBuffers, null, 1, ids);
        return ids[0];
    }

    /** 绑定顶点数组缓冲。 */
    public void bindArrayBuffer(int id) {
        Reflect.call(bindBuffer, null, GL_ARRAY_BUFFER, id);
    }

    /**
     * 把 float 数组上传到当前绑定的数组缓冲。
     *
     * <p>缺失 {@code createFloatBuffer} 或 {@code glBufferData} 时静默跳过，
     * 绘制层另有就绪判断兜底。
     */
    public void uploadArrayBuffer(float[] data) {
        if (bufferData == null || newFloatBuffer == null) {
            return;
        }
        Object buffer = Reflect.call(newFloatBuffer, null, data.length);
        if (!(buffer instanceof FloatBuffer)) {
            return;
        }
        FloatBuffer floatBuffer = (FloatBuffer) buffer;
        floatBuffer.clear();
        floatBuffer.put(data);
        floatBuffer.flip();
        Reflect.call(bufferData, null, GL_ARRAY_BUFFER, floatBuffer, GL_DYNAMIC_DRAW);
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

    /** @return 编译/链接日志；无可用日志时返回空串 */
    public String shaderLog(int shader) {
        Object log = Reflect.call(getShaderInfoLog, null, shader);
        return log == null ? "" : log.toString();
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

    /** 链接着色器程序。 */
    public void linkProgram(int program) {
        Reflect.call(linkProgram, null, program);
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

    /** 设置 vec4 颜色 uniform，取值 0–1。 */
    public void uniform4f(int location, float r, float g, float b, float a) {
        Reflect.call(uniform4f, null, location, r, g, b, a);
    }

    /**
     * 上传 4x4 矩阵 uniform。
     *
     * @param matrix 列主序的 16 个元素（GL 的默认布局）
     */
    public void uniformMatrix4fv(int location, float[] matrix) {
        Reflect.call(uniformMatrix4fv, null, location, false, matrix);
    }

    /** 启用指定的着色器程序。 */
    public void useProgram(int program) {
        Reflect.call(useProgram, null, program);
    }

    /** @return 新建的顶点数组对象 id */
    public int genVertexArray() {
        int[] ids = new int[1];
        Reflect.call(genVertexArrays, null, ids);
        return ids[0];
    }

    /** 绑定顶点数组对象。 */
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

    /** 开启混合并使用常规的 src-alpha 混合因子。 */
    public void enableBlend() {
        Reflect.call(enable, null, GL_BLEND);
        Reflect.call(blendFunc, null, GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
    }

    /** 关闭混合。 */
    public void disableBlend() {
        Reflect.call(disable, null, GL_BLEND);
    }

    /** 关闭 2D 纹理——UI 绘制纯色时必须关掉，否则会采样到游戏纹理。 */
    public void disableTexture() {
        Reflect.call(disable, null, GL_TEXTURE_2D);
    }
}
