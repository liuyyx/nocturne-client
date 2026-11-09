package dev.nocturne.ui.gl;

import dev.nocturne.client.render3d.World3DBatch;
import dev.nocturne.client.render3d.WorldCamera;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GlWorld3DBackend} 的契约：复用游戏投影/模型视图、相机相对顶点精度、图元顶点数、
 * 选项状态、残留状态清理、重入保护与矩阵/属性栈成对还原。
 *
 * <p>用 {@link FakeGl} 替身跑。关键断言是有序/带数值的：矩阵用自造 1024 远平面、顶点不减相机、
 * 重入压两次栈，症状在真机上分别是「远处深度错位穿墙」「大坐标抖动」「栈溢出/HUD 串色」。
 */
class GlWorld3DBackendTest {

    private static final int GL_DEPTH_TEST = 0x0B71;
    private static final int GL_CULL_FACE = 0x0B44;
    private static final int GL_BLEND = 0x0BE2;
    private static final int GL_TEXTURE_2D = 0x0DE1;
    private static final int GL_ALPHA_TEST = 0x0BC0;
    private static final int GL_FOG = 0x0B60;
    private static final int GL_LINES = 1;
    private static final int GL_LINE_LOOP = 2;
    private static final int GL_TRIANGLE_STRIP = 5;

    private GlWorld3DBackend backend;
    private WorldCamera camera;

    @BeforeEach
    void setUp() {
        FakeGl.reset();
        GlApi gl = GlApi.bind(FakeGl.class);
        assertNotNull(gl, "FakeGl must expose the GL11 subset");
        assertTrue(gl.hasProjection3D(), "FakeGl must look like a fixed pipeline with matrix access");
        assertTrue(gl.hasFallbackProjection(), "FakeGl must expose the fallback path");
        backend = new GlWorld3DBackend(gl);
        camera = new WorldCamera();
        camera.set(100.0d, 64.0d, -200.0d, 120f, 30f, 90f, 0.25f);
    }

    @Test
    void reportsFixedPipelineAndViewport() {
        assertTrue(backend.available());
        assertEquals("gl-fixed", backend.backendName());
        assertTrue(backend.begin(camera));
        assertEquals(1920, backend.viewportWidth());
        assertEquals(1080, backend.viewportHeight());
        backend.end();
    }

    @Test
    void beginReusesGameMatricesAndDrawsCameraRelative() {
        assertTrue(backend.begin(camera));

        // 投影/模型视图都读到了：不再自建 frustum/rotate，而是载入“仅旋转”的模型视图。
        assertEquals(0, FakeGl.frustumCalls, "game projection must be reused, not rebuilt");
        assertEquals(0, FakeGl.rotateCalls, "game modelview rotation must be reused");
        assertEquals(1, FakeGl.loadMatrixdCalls);
        assertNotNull(FakeGl.lastLoadedMatrix);
        assertEquals(0.0d, FakeGl.lastLoadedMatrix[12], 1e-9d);
        assertEquals(0.0d, FakeGl.lastLoadedMatrix[13], 1e-9d);
        assertEquals(0.0d, FakeGl.lastLoadedMatrix[14], 1e-9d);

        assertEquals(2, FakeGl.matrixModeSequence.size());
        assertEquals(GlApi.GL_PROJECTION, FakeGl.matrixModeSequence.get(0).intValue());
        assertEquals(GlApi.GL_MODELVIEW, FakeGl.matrixModeSequence.get(1).intValue());

        // 残留状态：两个纹理单元都关、ALPHA_TEST/FOG 关、活动单元回到 0。
        assertTrue(FakeGl.disableCount(GL_TEXTURE_2D) >= 2);
        assertTrue(FakeGl.disableCount(GL_ALPHA_TEST) >= 1);
        assertTrue(FakeGl.disableCount(GL_FOG) >= 1);
        assertEquals(GlApi.GL_TEXTURE0, FakeGl.lastActiveTexture);

        // 顶点是世界坐标减去矩阵解出的相机原点：盒子上角 (101,65,-199) -> (1,1,1)。
        assertTrue(FakeGl.disableCount(GlApi.GL_TEXTURE_2D) >= 2);
        FakeGl.lastVertex3 = null;
        backend.batch().box(99.0d, 63.0d, -201.0d, 101.0d, 65.0d, -199.0d);
        assertArrayEquals(new float[]{1f, 1f, 1f}, FakeGl.lastVertex3, 1e-4f);
        backend.end();
    }

    @Test
    void endRestoresMatricesAndStacks() {
        assertTrue(backend.begin(camera));
        backend.end();
        assertEquals(2, FakeGl.pushMatrixCalls);
        assertEquals(2, FakeGl.popMatrixCalls);
        assertEquals(1, FakeGl.popAttribCalls);
        assertEquals(1, FakeGl.popClientAttribCalls);
        assertEquals(0, backend.viewportWidth());
        assertEquals(0, backend.viewportHeight());
        // 退出后活动纹理单元也回到 0。
        assertEquals(GlApi.GL_TEXTURE0, FakeGl.lastActiveTexture);
    }

    @Test
    void secondBeginIsRejectedWithoutPushingAgain() {
        assertTrue(backend.begin(camera));
        assertFalse(backend.begin(camera), "re-entrant begin must be refused");
        assertEquals(1, FakeGl.pushAttribCalls);
        backend.end();
    }

    @Test
    void boxEmitsTwelveEdgesInOneBatch() {
        assertTrue(backend.begin(camera));
        World3DBatch batch = backend.batch();
        assertNotNull(batch);
        batch.color(0xFF112233).lineWidth(3f);
        batch.box(0.0d, 64.0d, 0.0d, 1.0d, 65.0d, 1.0d);

        assertEquals(GL_LINES, FakeGl.lastBeginMode);
        assertEquals(1, FakeGl.beginCalls, "a box must be one glBegin/glEnd batch");
        assertEquals(1, FakeGl.endCalls);
        assertEquals(24, FakeGl.vertex3Calls);
        assertEquals(1, FakeGl.colorCalls);
        assertEquals(3f, FakeGl.lastLineWidth, 1e-4f);
        backend.end();
    }

    @Test
    void ringUsesLineLoopAndSegmentCount() {
        assertTrue(backend.begin(camera));
        int before = FakeGl.vertex3Calls;
        backend.batch().ring(0.0d, 64.0d, 0.0d, 2.5d, 32);
        assertEquals(GL_LINE_LOOP, FakeGl.lastBeginMode);
        assertEquals(32, FakeGl.vertex3Calls - before);
        // 相对相机原点 (100,64,-200)，Y 分量应为 0。
        assertEquals(0f, FakeGl.lastVertex3[1], 1e-4f);
        backend.end();
    }

    @Test
    void triangleStripIgnoresDegenerateAndOutOfRangeInput() {
        assertTrue(backend.begin(camera));
        int before = FakeGl.vertex3Calls;
        backend.batch().triangleStrip(null, 3);
        backend.batch().triangleStrip(new float[]{0f, 0f, 0f}, 3);
        assertEquals(before, FakeGl.vertex3Calls, "degenerate input must draw nothing");

        float[] quad = {0f, 0f, 0f, 1f, 0f, 0f, 1f, 1f, 0f, 0f, 1f, 0f};
        backend.batch().triangleStrip(quad, 999);
        assertEquals(GL_TRIANGLE_STRIP, FakeGl.lastBeginMode);
        assertEquals(4, FakeGl.vertex3Calls - before, "vertexCount must clamp to the array");
        backend.end();
    }

    @Test
    void optionsAreStickyAndAppliedBeforePrimitives() {
        assertTrue(backend.begin(camera));
        World3DBatch batch = backend.batch();
        batch.depthTest(false).blend(true).cull(true);
        batch.line(0, 0, 0, 1, 1, 1);

        assertFalse(FakeGl.isCapEnabled(GL_DEPTH_TEST));
        assertTrue(FakeGl.isCapEnabled(GL_BLEND));
        assertTrue(FakeGl.isCapEnabled(GL_CULL_FACE));
        assertTrue(FakeGl.hasEvent("blendFunc:770,771"));

        batch.depthTest(true).blend(false).cull(false);
        batch.line(0, 0, 0, 1, 1, 1);
        assertTrue(FakeGl.isCapEnabled(GL_DEPTH_TEST));
        assertFalse(FakeGl.isCapEnabled(GL_BLEND));
        assertFalse(FakeGl.isCapEnabled(GL_CULL_FACE));
        backend.end();
    }

    @Test
    void unusableModelViewFallsBackToCameraRotationAndEye() {
        // 平移为 0 的模型视图解出原点 (0,0,0)，与相机眼位相差约 224 格 -> 判定不是世界矩阵。
        FakeGl.modelViewMatrix = FakeGl.worldModelView(0.0d, 0.0d, 0.0d);
        assertTrue(backend.begin(camera));
        assertEquals(0, FakeGl.loadMatrixdCalls);
        assertEquals(2, FakeGl.rotateCalls, "fallback must rebuild pitch + yaw");

        // 回退路径以眼位为原点：盒子上角 (101,65,-199) - (100,64,-200) = (1,1,1)。
        backend.batch().box(99.0d, 63.0d, -201.0d, 101.0d, 65.0d, -199.0d);
        assertArrayEquals(new float[]{1f, 1f, 1f}, FakeGl.lastVertex3, 1e-4f);
        backend.end();
    }

    @Test
    void unusableProjectionUsesRealFarPlane() {
        FakeGl.projectionMatrix = new double[16];
        camera.setFarPlane(256f);
        assertTrue(backend.begin(camera));
        assertEquals(1, FakeGl.frustumCalls);
        assertEquals(256.0d, FakeGl.lastFrustum[5], 1e-9d, "far must come from farPlaneDistance");
        assertTrue(FakeGl.lastFrustum[4] > 0.0d);
        backend.end();
    }

    @Test
    void readsMatricesThroughBufferFormToo() {
        // FakeGl 同时提供数组与 Buffer 形态；GlApi 优先数组，这里只验证 buffer 绑定存在时仍工作。
        assertTrue(GlApi.bind(FakeGl.class).readMatrix(GlApi.GL_PROJECTION_MATRIX, new double[16]));
    }

    @Test
    void vertexPrecisionAtLargeWorldCoordinates() {
        double origin = 30000000.0d;
        WorldCamera far = new WorldCamera();
        far.set(origin, 70.0d, -origin, 0f, 0f, 70f, 0f);
        FakeGl.modelViewMatrix = FakeGl.worldModelView(-origin, -70.0d, origin);
        assertTrue(backend.begin(far));

        backend.batch().box(origin + 0.25d, 70.25d, -origin + 0.25d,
                origin + 0.75d, 70.75d, -origin + 0.75d);
        // 最后提交的是 max 角，double 减完后应为 0.75；若先转 float 再减会得到 0 或 2。
        assertArrayEquals(new float[]{0.75f, 0.75f, 0.75f}, FakeGl.lastVertex3, 1e-4f);
        backend.end();
    }

    @Test
    void capturedHeadMatricesWinOverTailState() {
        // 头部：世界相机矩阵（平移 = -eye）。
        FakeGl.projectionMatrix = FakeGl.perspective();
        FakeGl.modelViewMatrix = FakeGl.worldModelView(-100.0d, -64.0d, 200.0d);
        backend.captureMatrices();
        // 尾部：模拟手部渲染把模型视图改成带缩放的手空间。
        FakeGl.modelViewMatrix = FakeGl.worldModelView(3.0d, 4.0d, 5.0d);
        FakeGl.modelViewMatrix[0] = 0.5d;

        assertTrue(backend.begin(camera));
        assertEquals(1, FakeGl.loadMatrixdCalls);
        assertNotNull(FakeGl.lastLoadedMatrix);
        assertEquals(1.0d, FakeGl.lastLoadedMatrix[0], 1e-9d, "captured world rotation wins");
        backend.end();
    }

    @Test
    void readsLwjgl2MatrixMethodNames() {
        GlApi gl = GlApi.bind(Lwjgl2Gl.class);
        assertNotNull(gl);
        assertTrue(gl.hasProjection3D(), "LWJGL2 names glGetDouble/glLoadMatrix must bind");
        GlWorld3DBackend lwjgl2 = new GlWorld3DBackend(gl);
        assertTrue(lwjgl2.available());
        lwjgl2.captureMatrices();
        assertTrue(lwjgl2.begin(camera));
        lwjgl2.end();
    }

    /** 只暴露 LWJGL2 公开方法名（无 v/d 后缀）的 GL11 替身。 */
    public static final class Lwjgl2Gl {
        public static void glColor4f(float r, float g, float b, float a) {
        }

        public static void glBegin(int mode) {
        }

        public static void glEnd() {
        }

        public static void glVertex2f(float x, float y) {
        }

        public static void glVertex3f(float x, float y, float z) {
        }

        public static void glEnable(int cap) {
        }

        public static void glDisable(int cap) {
        }

        public static void glBlendFunc(int src, int dst) {
        }

        public static void glPushMatrix() {
        }

        public static void glPopMatrix() {
        }

        public static void glPushAttrib(int mask) {
        }

        public static void glPopAttrib() {
        }

        public static void glPushClientAttrib(int mask) {
        }

        public static void glPopClientAttrib() {
        }

        public static void glTranslatef(float x, float y, float z) {
        }

        public static void glLineWidth(float width) {
        }

        public static void glMatrixMode(int mode) {
        }

        public static void glLoadIdentity() {
        }

        public static void glFrustum(double l, double r, double b, double t, double n, double f) {
        }

        public static void glRotatef(float angle, float x, float y, float z) {
        }

        public static void glDepthMask(boolean flag) {
        }

        public static void glGetInteger(int target, java.nio.IntBuffer out) {
            if (out != null && out.remaining() >= 4) {
                out.put(0, 0);
                out.put(1, 0);
                out.put(2, 1280);
                out.put(3, 720);
            }
        }

        public static void glGetDouble(int target, java.nio.DoubleBuffer out) {
            double[] source = target == GlApi.GL_PROJECTION_MATRIX ? FakeGl.perspective()
                    : FakeGl.worldModelView(-100.0d, -64.0d, 200.0d);
            for (int i = 0; i < 16 && i < out.remaining(); i++) {
                out.put(i, source[i]);
            }
        }

        public static void glLoadMatrix(java.nio.DoubleBuffer matrix) {
        }

        public static void glDisableClientState(int cap) {
        }

        public static void glClientActiveTexture(int unit) {
        }

        public static void glActiveTexture(int unit) {
        }
    }

    @Test
    void refusesToDrawWithInvalidCameraOrViewport() {
        assertFalse(backend.begin(null));
        assertFalse(backend.begin(new WorldCamera()), "invalid camera must not begin");
        assertEquals(0, FakeGl.pushAttribCalls);

        WorldCamera invalidFov = new WorldCamera();
        invalidFov.set(0d, 0d, 0d, 0f, 0f, 0f, 0f);
        FakeGl.projectionMatrix = new double[16];
        assertTrue(backend.begin(invalidFov), "invalid FOV falls back to 70 degrees");
        // 70° 的 top = 0.05 * tan(35°)
        assertEquals(0.05d * Math.tan(Math.toRadians(35.0d)), FakeGl.lastFrustum[3], 1e-9d);
        backend.end();
    }

    @Test
    void unavailableWhenFixedPipelineEntriesAreMissing() {
        GlApi coreOnly = GlApi.bind(FakeGlBufferOnly.class);
        assertNotNull(coreOnly);
        GlWorld3DBackend core = new GlWorld3DBackend(coreOnly);
        assertFalse(core.available());
        assertNotNull(core.unavailableReason());
        assertFalse(core.begin(camera));
    }
}
