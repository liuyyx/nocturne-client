package dev.nocturne.ui.gl;

import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.game.Reflect;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.DoubleBuffer;
import java.nio.IntBuffer;

/**
 * 1.13–1.14.4 的无上下文库存人物绘制。游戏 helper 使用固定管线，因此这里只隔离
 * 投影、模型视图和原生 helper 会碰到的状态，绝不在核心 profile 上调用 glPushAttrib。
 */
final class NativeInventoryPlayerPreview {
    private static final int GL_MATRIX_MODE = 0x0BA0;
    private static final int GL_DEPTH_RANGE = 0x0B70;
    private static final int GL_ACTIVE_TEXTURE = 0x84E0;
    private static final int GL_TEXTURE_BINDING_2D = 0x8069;
    private static final int GL_LIGHTING = 0x0B50;
    private static final int GL_RESCALE_NORMAL = 0x803A;
    private static final int GL_COLOR_MATERIAL = 0x0B57;
    private static final int GL_ALPHA_TEST = 0x0BC0;
    private static final int[] CAPABILITIES = {
            GlApi.GL_DEPTH_TEST, GlApi.GL_TEXTURE_2D, GlApi.GL_BLEND, GlApi.GL_CULL_FACE,
            GlApi.GL_SCISSOR_TEST, GL_LIGHTING, GL_RESCALE_NORMAL, GL_COLOR_MATERIAL, GL_ALPHA_TEST
    };

    private final LegacyInventoryPlayerPreview preview;
    private final GlApi gl;
    private final Method depthRange;
    private final Method getDoublev;
    private final Method getDoublevBuffer;
    private final Method getIntegerv;
    private final Method getIntegervBuffer;
    private final boolean available;
    private boolean failureLogged;

    /** 查询复用缓冲区；绘制只在游戏渲染线程发生，不能把它们共享到别的渲染器实例。 */
    private final DoubleBuffer doubleBuffer = ByteBuffer.allocateDirect(16 * Double.BYTES)
            .order(ByteOrder.nativeOrder()).asDoubleBuffer();
    private final IntBuffer intBuffer = ByteBuffer.allocateDirect(16 * Integer.BYTES)
            .order(ByteOrder.nativeOrder()).asIntBuffer();
    private final double[] doubles = new double[2];
    private final int[] ints = new int[1];
    private final boolean[] capabilities = new boolean[CAPABILITIES.length];
    private int queriedInt;

    static NativeInventoryPlayerPreview bind(GameBridge bridge) {
        if (bridge == null) {
            return null;
        }
        ClassLoader loader = bridge.gameLoader();
        Class<?> glType = Reflect.loadWithoutInit("org.lwjgl.opengl.GL11", loader);
        if (glType == null) {
            return null;
        }
        LegacyInventoryPlayerPreview preview = LegacyInventoryPlayerPreview.bind(bridge.mapping(), loader);
        NativeInventoryPlayerPreview nativePreview = new NativeInventoryPlayerPreview(preview,
                GlApi.bind(glType), glType);
        return nativePreview.available ? nativePreview : null;
    }

    /** 测试可传入带 GL11 签名的替身；生产仍由 {@link #bind(GameBridge)} 从游戏加载器解析。 */
    NativeInventoryPlayerPreview(LegacyInventoryPlayerPreview preview, GlApi gl, Class<?> glType) {
        this.preview = preview;
        this.gl = gl;
        this.depthRange = Reflect.method(glType, "glDepthRange", double.class, double.class);
        this.getDoublev = Reflect.method(glType, "glGetDoublev", int.class, double[].class);
        this.getDoublevBuffer = Reflect.method(glType, "glGetDoublev", int.class, DoubleBuffer.class);
        this.getIntegerv = Reflect.method(glType, "glGetIntegerv", int.class, int[].class);
        this.getIntegervBuffer = Reflect.method(glType, "glGetIntegerv", int.class, IntBuffer.class);
        this.available = preview != null && gl != null && gl.hasMatrixControl()
                && depthRange != null && (getDoublev != null || getDoublevBuffer != null)
                && (getIntegerv != null || getIntegervBuffer != null)
                && Reflect.method(glType, "glPushMatrix") != null
                && Reflect.method(glType, "glPopMatrix") != null;
    }

    boolean draw(GameBridge bridge, Object player, float left, float top, float right, float bottom,
                 float mouseX, float mouseY, int width, int height) {
        if (!available || bridge == null || player == null || width <= 0 || height <= 0) {
            return false;
        }
        if (!queryInt(GL_MATRIX_MODE)) {
            return false;
        }
        int matrixMode = queriedInt;
        if (!queryInt(GL_ACTIVE_TEXTURE)) {
            return false;
        }
        int activeTexture = queriedInt;
        if (!queryInt(GL_TEXTURE_BINDING_2D)) {
            return false;
        }
        int textureBinding = queriedInt;
        if (!queryDepthRange()) {
            return false;
        }
        double near = doubles[0];
        double far = doubles[1];
        for (int i = 0; i < CAPABILITIES.length; i++) {
            capabilities[i] = gl.isCapEnabled(CAPABILITIES[i]);
        }

        int center = Math.round((left + right) * 0.5f);
        int feet = Math.round(bottom - 3.0f);
        int size = Math.max(1, Math.round(Math.min((right - left) * 0.52f, (bottom - top) * 0.43f)));
        boolean projectionPushed = false;
        boolean modelViewPushed = false;
        boolean drawn = false;
        gl.flushBatch();
        try {
            gl.matrixMode(GlApi.GL_PROJECTION);
            gl.pushMatrix();
            projectionPushed = true;
            gl.loadIdentity();
            gl.ortho(0d, width, height, 0d, -1000d, 1000d);
            gl.matrixMode(GlApi.GL_MODELVIEW);
            gl.pushMatrix();
            modelViewPushed = true;
            gl.loadIdentity();
            setDepthRange(0d, 1d);
            gl.enable(GlApi.GL_DEPTH_TEST);
            gl.enable(GlApi.GL_TEXTURE_2D);
            drawn = preview.draw(bridge, null, player, center, feet, size,
                    center - mouseX, (feet - size) - mouseY);
        } catch (Throwable failure) {
            logFailure(failure);
        } finally {
            if (modelViewPushed) {
                gl.matrixMode(GlApi.GL_MODELVIEW);
                gl.popMatrix();
            }
            if (projectionPushed) {
                gl.matrixMode(GlApi.GL_PROJECTION);
                gl.popMatrix();
            }
            gl.matrixMode(matrixMode);
            for (int i = 0; i < CAPABILITIES.length; i++) {
                restore(CAPABILITIES[i], capabilities[i]);
            }
            try {
                setDepthRange(near, far);
            } catch (Throwable failure) {
                drawn = false;
                logFailure(failure);
            }
            gl.activeTexture(activeTexture);
            if (textureBinding >= 0) {
                gl.bindTexture2D(textureBinding);
            }
            // ModernRenderer 的帧内混合口径固定；经 GlApi 恢复可同时同步游戏缓存和真实 GL。
            gl.blendFunc(GlApi.GL_SRC_ALPHA, GlApi.GL_ONE_MINUS_SRC_ALPHA);
            gl.color(1f, 1f, 1f, 1f);
        }
        return drawn;
    }

    private void restore(int capability, boolean enabled) {
        if (enabled) {
            gl.enable(capability);
        } else {
            gl.disable(capability);
        }
    }

    private boolean queryInt(int name) {
        try {
            if (getIntegerv != null) {
                getIntegerv.invoke(null, name, ints);
                queriedInt = ints[0];
                return true;
            }
            intBuffer.clear();
            getIntegervBuffer.invoke(null, name, intBuffer);
            queriedInt = intBuffer.get(0);
            return true;
        } catch (Throwable failure) {
            return false;
        }
    }

    private boolean queryDepthRange() {
        try {
            if (getDoublev != null) {
                getDoublev.invoke(null, GL_DEPTH_RANGE, doubles);
            } else {
                doubleBuffer.clear();
                getDoublevBuffer.invoke(null, GL_DEPTH_RANGE, doubleBuffer);
                doubles[0] = doubleBuffer.get(0);
                doubles[1] = doubleBuffer.get(1);
            }
            return true;
        } catch (Throwable failure) {
            return false;
        }
    }

    private void setDepthRange(double near, double far) throws Exception {
        depthRange.invoke(null, near, far);
    }

    private void logFailure(Throwable failure) {
        if (!failureLogged) {
            failureLogged = true;
            System.out.println("[nocturne] fixed-function native player preview failed: " + failure);
        }
    }
}
