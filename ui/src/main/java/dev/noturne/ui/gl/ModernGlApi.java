package dev.noturne.ui.gl;

import dev.noturne.client.game.Reflect;

import java.lang.reflect.Method;
import java.nio.FloatBuffer;

/**
 * Reflective binding to the OpenGL 3.2 core subset the modern renderer needs.
 *
 * <p>Minecraft 26.x runs a <em>core profile</em>: the fixed-function calls used by {@link GlApi}
 * ({@code glBegin}, {@code glVertex2f}, …) were removed. The UI therefore needs its own VBO +
 * shader path. As with {@link GlApi}, nothing is linked at compile time — entry points are resolved
 * against the game's class loader. LWJGL3 ships both legacy ({@code GL15}) and core ({@code GL15C})
 * variants, so each function is looked up in both spellings.
 */
public final class ModernGlApi {

    // OpenGL enums: part of the ABI, never change.
    public static final int GL_FLOAT = 0x1406;
    public static final int GL_TRIANGLES = 0x0004;
    public static final int GL_ARRAY_BUFFER = 0x8892;
    public static final int GL_DYNAMIC_DRAW = 0x88E8;
    public static final int GL_BLEND = 0x0BE2;
    public static final int GL_SRC_ALPHA = 0x0302;
    public static final int GL_ONE_MINUS_SRC_ALPHA = 0x0303;
    public static final int GL_VERTEX_SHADER = 0x8B31;
    public static final int GL_FRAGMENT_SHADER = 0x8B30;
    public static final int GL_COMPILE_STATUS = 0x8B81;
    public static final int GL_LINK_STATUS = 0x8B82;
    public static final int GL_SCISSOR_TEST = 0x0C11;
    public static final int GL_TEXTURE_2D = 0x0DE1;
    public static final int GL_VIEWPORT = 0x0BA2;

    private final Method genBuffers;
    private final Method bindBuffer;
    private final Method bufferData;
    private final Method deleteBuffers;
    private final Method createShader;
    private final Method shaderSource;
    private final Method compileShader;
    private final Method getShaderi;
    private final Method getShaderInfoLog;
    private final Method deleteShader;
    private final Method createProgram;
    private final Method attachShader;
    private final Method linkProgram;
    private final Method deleteProgram;
    private final Method useProgram;
    private final Method getUniformLocation;
    private final Method uniformMatrix4fv;
    private final Method uniform4f;
    private final Method genVertexArrays;
    private final Method bindVertexArray;
    private final Method enableVertexAttribArray;
    private final Method vertexAttribPointer;
    private final Method drawArrays;
    private final Method enable;
    private final Method disable;
    private final Method blendFunc;
    private final Method viewport;
    private final Method scissor;
    private final Method newFloatBuffer;
    private final Method getIntegerv;

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

    /** Binds the core-profile entry points, or returns {@code null} if the subset is incomplete. */
    public static ModernGlApi bind(ClassLoader loader) {
        Method genBuffers = find(loader, "glGenBuffers", int.class, int[].class);
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

        if (genBuffers == null || bindBuffer == null || bufferData == null || createShader == null
                || shaderSource == null || compileShader == null || createProgram == null
                || useProgram == null || genVertexArrays == null || bindVertexArray == null
                || enableVertexAttribArray == null || vertexAttribPointer == null || drawArrays == null) {
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

    /** Reads an integer GL parameter (e.g. {@link #GL_VIEWPORT}), or {@code null}. */
    public int[] getInteger(int name, int count) {
        if (getIntegerv == null) {
            return null;
        }
        int[] out = new int[count];
        Reflect.call(getIntegerv, null, name, out);
        return out;
    }

    /** Convenience wrapper that assumes the program is already in use. */
    public void drawTriangles(int first, int count) {
        if (drawArrays != null) {
            Reflect.call(drawArrays, null, GL_TRIANGLES, first, count);
        }
    }

    /**
     * Finds a function by name and signature across the LWJGL core/legacy class spellings.
     *
     * <p>Returns {@code null} when absent — callers treat that as "this capability is unavailable".
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

    private static Method findBufferUtils(ClassLoader loader) {
        return Reflect.method(Reflect.load("org.lwjgl.BufferUtils", loader),
                "createFloatBuffer", int.class);
    }

    // ------------------------------------------------------------- raw wrappers

    public int genBuffer() {
        int[] ids = new int[1];
        Reflect.call(genBuffers, null, 1, ids);
        return ids[0];
    }

    public void bindArrayBuffer(int id) {
        Reflect.call(bindBuffer, null, GL_ARRAY_BUFFER, id);
    }

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

    public int createShader(int type) {
        Object id = Reflect.call(createShader, null, type);
        return id instanceof Number ? ((Number) id).intValue() : 0;
    }

    public void shaderSource(int shader, String source) {
        Reflect.call(shaderSource, null, shader, source);
    }

    public void compileShader(int shader) {
        Reflect.call(compileShader, null, shader);
    }

    public boolean compileOk(int shader) {
        Object status = Reflect.call(getShaderi, null, shader, GL_COMPILE_STATUS);
        return status instanceof Number && ((Number) status).intValue() != 0;
    }

    public String shaderLog(int shader) {
        Object log = Reflect.call(getShaderInfoLog, null, shader);
        return log == null ? "" : log.toString();
    }

    public int createProgram() {
        Object id = Reflect.call(createProgram, null);
        return id instanceof Number ? ((Number) id).intValue() : 0;
    }

    public void attachShader(int program, int shader) {
        Reflect.call(attachShader, null, program, shader);
    }

    public void linkProgram(int program) {
        Reflect.call(linkProgram, null, program);
    }

    public int uniformLocation(int program, String name) {
        Object location = Reflect.call(getUniformLocation, null, program, name);
        return location instanceof Number ? ((Number) location).intValue() : -1;
    }

    public void uniform4f(int location, float r, float g, float b, float a) {
        Reflect.call(uniform4f, null, location, r, g, b, a);
    }

    public void uniformMatrix4fv(int location, float[] matrix) {
        Reflect.call(uniformMatrix4fv, null, location, false, matrix);
    }

    public void useProgram(int program) {
        Reflect.call(useProgram, null, program);
    }

    public int genVertexArray() {
        int[] ids = new int[1];
        Reflect.call(genVertexArrays, null, ids);
        return ids[0];
    }

    public void bindVertexArray(int id) {
        Reflect.call(bindVertexArray, null, id);
    }

    public void enableVertexAttrib(int index) {
        Reflect.call(enableVertexAttribArray, null, index);
    }

    public void vertexAttribPointer(int index, int size, int stride, int offset) {
        Reflect.call(vertexAttribPointer, null, index, size, GL_FLOAT, false, stride, (long) offset);
    }

    public void enableBlend() {
        Reflect.call(enable, null, GL_BLEND);
        Reflect.call(blendFunc, null, GL_SRC_ALPHA, GL_ONE_MINUS_SRC_ALPHA);
    }

    public void disableBlend() {
        Reflect.call(disable, null, GL_BLEND);
    }

    public void disableTexture() {
        Reflect.call(disable, null, GL_TEXTURE_2D);
    }
}
