package org.lwjgl.opengl;

import java.nio.FloatBuffer;

/**
 * 测试专用替身：模拟 LWJGL3 的 {@code GL30}（核心 profile 入口点集合），
 * 使 {@code ModernGlApi.bind} 能在无原生 GL 的单元测试里解析到一个完整绑定。
 *
 * <p>放在 {@code org.lwjgl.opengl} 包下是因为生产代码按该包名反射查找 LWJGL 类；
 * 测试 classpath 没有真实 LWJGL，因此不会冲突。
 *
 * <p>通过静态开关 {@link #failIds} 模拟「缓冲对象生成失败」以验证就绪判定的兜底路径。
 */
public final class GL30 {

    /** 为 true 时 {@code glGenBuffers}/{@code glGenVertexArrays} 写入 0（模拟创建失败） */
    public static boolean failIds;
    /** {@code glGetIntegerv(int, IntBuffer)} 被调用次数 */
    public static int getIntegervBufferCalls;
    /** {@code glGenBuffers} 被调用次数 */
    public static int genBufferCalls;
    /** 最近一次 {@code glGenBuffers} 收到的数组长度；用于钉死「单参签名」 */
    public static int lastGenBufferArrayLength = -1;
    /** {@code glGenVertexArrays} 被调用次数 */
    public static int genVertexArrayCalls;
    /** {@code glDeleteBuffers} 收到的 id */
    public static int deletedBuffer = -1;
    /** {@code glDeleteVertexArrays} 收到的 id */
    public static int deletedVertexArray = -1;

    /** 重置全部观察状态 */
    public static void reset() {
        failIds = false;
        getIntegervBufferCalls = 0;
        genBufferCalls = 0;
        lastGenBufferArrayLength = -1;
        genVertexArrayCalls = 0;
        deletedBuffer = -1;
        deletedVertexArray = -1;
        genTextureCalls = 0;
        lastGenTexture = 0;
        deletedTexture = -1;
        lastTexImage = null;
        lastTexParameter = null;
        lastActiveTexture = -1;
        lastUniform1i = null;
    }

    private GL30() {
    }

    public static void glGenBuffers(int[] ids) {
        genBufferCalls++;
        lastGenBufferArrayLength = ids == null ? -1 : ids.length;
        if (ids != null && ids.length > 0) {
            ids[0] = failIds ? 0 : 7;
        }
    }

    public static void glGenVertexArrays(int[] ids) {
        genVertexArrayCalls++;
        if (ids != null && ids.length > 0) {
            ids[0] = failIds ? 0 : 9;
        }
    }

    public static void glBindBuffer(int target, int id) {
    }

    public static void glBufferData(int target, FloatBuffer data, int usage) {
    }

    public static void glDeleteBuffers(int[] ids) {
        if (ids != null && ids.length > 0) {
            deletedBuffer = ids[0];
        }
    }

    public static void glDeleteVertexArrays(int[] ids) {
        if (ids != null && ids.length > 0) {
            deletedVertexArray = ids[0];
        }
    }

    public static int glCreateShader(int type) {
        return 100 + type;
    }

    public static void glShaderSource(int shader, CharSequence source) {
    }

    public static void glCompileShader(int shader) {
    }

    public static int glGetShaderi(int shader, int name) {
        return 1;   // 编译/链接总是成功
    }

    public static String glGetShaderInfoLog(int shader) {
        return "";
    }

    public static void glDeleteShader(int shader) {
    }

    public static int glCreateProgram() {
        return 42;
    }

    public static void glAttachShader(int program, int shader) {
    }

    public static void glBindAttribLocation(int program, int index, CharSequence name) {
    }

    public static void glLinkProgram(int program) {
    }

    public static int glGetProgrami(int program, int name) {
        return 1;
    }

    public static String glGetProgramInfoLog(int program) {
        return "";
    }

    public static void glDeleteProgram(int program) {
    }

    public static void glUseProgram(int program) {
    }

    public static int glGetUniformLocation(int program, CharSequence name) {
        return 0;   // 非负：视为 uniform 存在
    }

    public static void glUniformMatrix4fv(int location, boolean transpose, float[] value) {
    }

    public static void glUniform4f(int location, float r, float g, float b, float a) {
    }

    public static void glBindVertexArray(int id) {
    }

    public static void glEnableVertexAttribArray(int index) {
    }

    public static void glVertexAttribPointer(int index, int size, int type, boolean normalized,
                                             int stride, long offset) {
    }

    public static void glDrawArrays(int mode, int first, int count) {
    }

    public static void glEnable(int cap) {
    }

    public static void glDisable(int cap) {
    }

    public static boolean glIsEnabled(int cap) {
        return false;
    }

    public static void glBlendFunc(int src, int dst) {
    }

    public static void glScissor(int x, int y, int width, int height) {
    }

    public static void glGetIntegerv(int name, int[] out) {
        if (out != null && out.length >= 4) {
            out[0] = 0;
            out[1] = 0;
            out[2] = 100;
            out[3] = 100;
        }
    }

    /**
     * LWJGL3 的真实形态：{@code glGetIntegerv(int, IntBuffer)}。
     *
     * <p>写入时用绝对下标而不是 {@code put(...)}：真实 GL 是按原生地址写缓冲区的，Java 侧的
     * position 始终停在 0；替身若用相对写入，被测代码"读 position 之后的元素"就会假成功。
     */
    public static void glGetIntegerv(int name, java.nio.IntBuffer out) {
        getIntegervBufferCalls++;
        if (out == null || out.limit() < 4) {
            return;
        }
        out.put(0, 0);
        out.put(1, 0);
        out.put(2, 100);
        out.put(3, 100);
    }

    // ---- 纹理支路（供 ModernGlApi 的纹理绑定与 ModernRenderer 的纹理回归使用）----

    /** {@code glGenTextures(int[])} 被调用次数 */
    public static int genTextureCalls;
    /** 最近一次生成的纹理句柄 */
    public static int lastGenTexture;
    /** {@code glDeleteTextures(int[])} 收到的句柄；未删除为 -1 */
    public static int deletedTexture = -1;
    /** 最近一次 {@code glTexImage2D} 的参数：width, height, internalFormat, format, type, 字节数 */
    public static int[] lastTexImage;
    /** 最近一次 {@code glTexParameteri} 的参数：target, pname, param */
    public static int[] lastTexParameter;
    /** 最近一次 {@code glActiveTexture} 的纹理单元 */
    public static int lastActiveTexture = -1;
    /** 最近一次 {@code glUniform1i} 的参数：location, value */
    public static int[] lastUniform1i;

    /** 记录一次纹理句柄生成。 */
    public static void glGenTextures(int[] ids) {
        genTextureCalls++;
        if (ids != null && ids.length > 0) {
            ids[0] = 55;
            lastGenTexture = ids[0];
        }
    }

    /** 记录一次纹理删除。 */
    public static void glDeleteTextures(int[] ids) {
        if (ids != null && ids.length > 0) {
            deletedTexture = ids[0];
        }
    }

    /** 绑定纹理；无操作 */
    public static void glBindTexture(int target, int texture) {
    }

    /** 记录一次纹理上传。 */
    public static void glTexImage2D(int target, int level, int internalFormat, int width, int height,
                                    int border, int format, int type, java.nio.ByteBuffer pixels) {
        lastTexImage = new int[]{width, height, internalFormat, format, type,
                pixels == null ? 0 : pixels.remaining()};
    }

    /** 记录一次采样参数设置。 */
    public static void glTexParameteri(int target, int pname, int param) {
        lastTexParameter = new int[]{target, pname, param};
    }

    /** 像素存储模式；无操作。 */
    public static void glPixelStorei(int pname, int param) {
    }

    /** 记录一次纹理单元选择。 */
    public static void glActiveTexture(int unit) {
        lastActiveTexture = unit;
    }

    /** 记录一次整型 uniform 下发（{@code uTexture} 开关与采样器绑定）。 */
    public static void glUniform1i(int location, int value) {
        lastUniform1i = new int[]{location, value};
    }
}
