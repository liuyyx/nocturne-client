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
 * {@link GlWorld3DBackend} 的契约：相机变换、图元顶点数、选项状态与矩阵/属性栈的成对还原。
 *
 * <p>用 {@link FakeGl} 替身跑，不需要真实 GL 上下文。关键断言都是「有序」的：旋转顺序错了、
 * 属性栈漏 pop、深度/混合没生效，症状在真机上分别是「框方向相反」「HUD 串色」「框不可见」，
 * 只看计数抓不到。
 */
class GlWorld3DBackendTest {

    private static final int GL_DEPTH_TEST = 0x0B71;
    private static final int GL_CULL_FACE = 0x0B44;
    private static final int GL_BLEND = 0x0BE2;
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
        assertTrue(gl.hasProjection3D(), "FakeGl must look like a fixed pipeline");
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
    void beginAppliesCameraTransformAndState() {
        assertTrue(backend.begin(camera));

        // 属性栈 + 客户态栈各压一次；矩阵先切投影、再切模型视图。
        assertEquals(1, FakeGl.pushAttribCalls);
        assertEquals(1, FakeGl.pushClientAttribCalls);
        assertEquals(GlApi.GL_ALL_ATTRIB_BITS, FakeGl.lastAttribMask);
        assertEquals(2, FakeGl.matrixModeSequence.size());
        assertEquals(GlApi.GL_PROJECTION, FakeGl.matrixModeSequence.get(0).intValue());
        assertEquals(GlApi.GL_MODELVIEW, FakeGl.matrixModeSequence.get(1).intValue());

        // 90° 竖直 FOV、16:9 视口：top = near * tan(45°) = 0.05，right = top * aspect。
        assertEquals(1, FakeGl.frustumCalls);
        assertEquals(0.05d, FakeGl.lastFrustum[3], 1e-9d);
        assertEquals(0.05d * 1920.0d / 1080.0d, FakeGl.lastFrustum[1], 1e-9d);

        // 旋转顺序：先 pitch（绕 X），再 yaw+180（绕 Y）。
        assertEquals(2, FakeGl.rotateCalls);
        assertEquals(30f, FakeGl.rotateSequence.get(0)[0], 1e-4f);
        assertEquals(1f, FakeGl.rotateSequence.get(0)[1], 1e-4f);
        assertEquals(300f, FakeGl.rotateSequence.get(1)[0], 1e-4f);
        assertEquals(1f, FakeGl.rotateSequence.get(1)[2], 1e-4f);

        // 平移是眼位取负。
        assertArrayEquals(new double[]{-100.0d, -64.0d, 200.0d}, FakeGl.lastTranslate, 1e-9d);

        // 基线状态：深度开、混合/剔除关、深度可写、纹理关闭。
        assertTrue(FakeGl.isCapEnabled(GL_DEPTH_TEST));
        assertFalse(FakeGl.isCapEnabled(GL_BLEND));
        assertFalse(FakeGl.isCapEnabled(GL_CULL_FACE));
        assertEquals(1, FakeGl.depthMaskCalls);
        assertTrue(FakeGl.lastDepthMask);
        assertTrue(FakeGl.disableCount(GlApi.GL_TEXTURE_2D) >= 1);
    }

    @Test
    void endRestoresMatricesAndStacks() {
        assertTrue(backend.begin(camera));
        backend.end();
        assertEquals(2, FakeGl.pushMatrixCalls);
        assertEquals(2, FakeGl.popMatrixCalls);
        assertEquals(1, FakeGl.popAttribCalls);
        assertEquals(1, FakeGl.popClientAttribCalls);
        // 归零后没有批次可用。
        assertEquals(0, backend.viewportWidth());
        assertEquals(0, backend.viewportHeight());
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
        // 最后一个顶点在角度 2π*31/32 处，Y 必须仍是圆心高度。
        assertEquals(64f, FakeGl.lastVertex3[1], 1e-4f);
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

        // 改回默认值后再画一次：状态必须随之回退。
        batch.depthTest(true).blend(false).cull(false);
        batch.line(0, 0, 0, 1, 1, 1);
        assertTrue(FakeGl.isCapEnabled(GL_DEPTH_TEST));
        assertFalse(FakeGl.isCapEnabled(GL_BLEND));
        assertFalse(FakeGl.isCapEnabled(GL_CULL_FACE));
        backend.end();
    }

    @Test
    void refusesToDrawWithInvalidCameraOrViewport() {
        assertFalse(backend.begin(null));
        assertFalse(backend.begin(new WorldCamera()), "invalid camera must not begin");
        assertEquals(0, FakeGl.pushAttribCalls);

        WorldCamera invalidFov = new WorldCamera();
        invalidFov.set(0d, 0d, 0d, 0f, 0f, 0f, 0f);
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
