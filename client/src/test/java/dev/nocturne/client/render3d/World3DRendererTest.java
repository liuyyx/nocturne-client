package dev.nocturne.client.render3d;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.TestSupport;
import dev.nocturne.client.bootstrap.GameplayHook;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link World3DRenderer} 的契约：能力诚实、钩子/后端异常不外泄、模块异常隔离、
 * 事件载荷字段完整、没有工作量时不碰 GL。
 */
class World3DRendererTest {

    private NocturneClient client;

    @BeforeEach
    void isolate() {
        TestSupport.resetClient();
        client = NocturneClient.boot(null);
    }

    @AfterEach
    void cleanUp() {
        TestSupport.resetClient();
    }

    /** 相机快照：眼位 (1,2,3)、yaw/pitch 0、FOV 70、partialTicks 0.5。 */
    private static WorldCamera camera() {
        WorldCamera camera = new WorldCamera();
        camera.set(1.0d, 2.0d, 3.0d, 0f, 0f, 70f, 0.5f);
        return camera;
    }

    /** 记录调用并可选地在各阶段抛异常。 */
    private static final class FakeBackend implements World3DBackend {
        boolean available = true;
        boolean failBegin;
        boolean throwBegin;
        boolean throwEnd;
        boolean nullBatch;
        int begins;
        int ends;
        int captures;
        WorldCamera seen;
        final RecordingBatch batch = new RecordingBatch();

        @Override
        public String backendName() {
            return "fake";
        }

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public String unavailableReason() {
            return available ? null : "fake unavailable";
        }

        @Override
        public boolean begin(WorldCamera camera) {
            begins++;
            seen = camera;
            if (throwBegin) {
                throw new IllegalStateException("begin boom");
            }
            return !failBegin;
        }

        @Override
        public void end() {
            ends++;
            if (throwEnd) {
                throw new IllegalStateException("end boom");
            }
        }

        @Override
        public void captureMatrices() {
            captures++;
        }

        @Override
        public World3DBatch batch() {
            return nullBatch ? null : batch;
        }

        @Override
        public int viewportWidth() {
            return 1280;
        }

        @Override
        public int viewportHeight() {
            return 720;
        }
    }

    /** 只记录图元数量的批次。 */
    private static final class RecordingBatch implements World3DBatch {
        int boxes;
        int rings;
        int lines;
        int strips;

        @Override
        public World3DBatch color(int argb) {
            return this;
        }

        @Override
        public World3DBatch lineWidth(float width) {
            return this;
        }

        @Override
        public World3DBatch depthTest(boolean enabled) {
            return this;
        }

        @Override
        public World3DBatch cull(boolean enabled) {
            return this;
        }

        @Override
        public World3DBatch blend(boolean enabled) {
            return this;
        }

        @Override
        public void line(double x1, double y1, double z1, double x2, double y2, double z2) {
            lines++;
        }

        @Override
        public void box(double minX, double minY, double minZ,
                        double maxX, double maxY, double maxZ) {
            boxes++;
        }

        @Override
        public void ring(double centerX, double centerY, double centerZ, double radius,
                         int segments) {
            rings++;
        }

        @Override
        public void triangleStrip(float[] xyz, int vertexCount) {
            strips++;
        }

        @Override
        public void flush() {
        }
    }

    /** 测试模块：实现 3D 覆盖层，可选地在绘制时抛异常。 */
    static final class OverlayModule extends GameModule implements World3DOverlay {
        int draws;
        boolean throwOnDraw;
        World3DContext lastContext;

        OverlayModule(String name) {
            super(name, Category.RENDER);
        }

        @Override
        public void drawWorld3D(World3DContext context) {
            draws++;
            lastContext = context;
            if (throwOnDraw) {
                throw new IllegalStateException("overlay boom");
            }
        }
    }

    @Test
    void unavailableCapabilityCarriesReason() {
        World3DRenderer renderer = client.world3d();
        assertNotNull(renderer);
        renderer.markUnavailable("gl-core", "core profile has no immediate mode");

        World3DCapability capability = renderer.capability();
        assertFalse(capability.available());
        assertEquals("gl-core", capability.backend());
        assertEquals("core profile has no immediate mode", capability.reason());
        assertTrue(capability.describe().contains("core profile"));
        assertFalse(renderer.renderCamera(camera()));
    }

    @Test
    void captureDelegatesToBackendAndSwallowsErrors() {
        FakeBackend backend = new FakeBackend();
        client.world3d().setBackend(backend);
        client.world3d().captureMatrices();
        assertEquals(1, backend.captures);

        // 没有后端时也必须安全。
        client.world3d().markUnavailable("gl-core", "no immediate mode");
        client.world3d().captureMatrices();
        assertEquals(1, backend.captures);
    }

    @Test
    void capabilityRequiresWiredHook() {
        FakeBackend backend = new FakeBackend();
        World3DRenderer renderer = client.world3d();
        renderer.setBackend(backend);
        World3DCapability notWired = renderer.capability();
        assertFalse(notWired.available(), "backend alone must not claim availability");
        assertEquals("world3d hook not wired yet", notWired.reason());

        GameplayHook.markWired("world3d");
        assertTrue(renderer.capability().available());
    }

    @Test
    void capabilityReflectsHookState() {
        FakeBackend backend = new FakeBackend();
        World3DRenderer renderer = client.world3d();
        renderer.setBackend(backend);
        GameplayHook.markWired("world3d");
        assertTrue(renderer.capability().available());

        GameplayHook.markUnavailable("world3d", "EntityRenderer#renderWorld absent");
        World3DCapability capability = renderer.capability();
        assertFalse(capability.available());
        assertEquals("EntityRenderer#renderWorld absent", capability.reason());
    }

    @Test
    void drawsEnabledOverlayAndPostsEvent() {
        FakeBackend backend = new FakeBackend();
        World3DRenderer renderer = client.world3d();
        renderer.setBackend(backend);

        OverlayModule module = new OverlayModule("Test 3D");
        client.modules().register(module);
        module.setEnabled(true);

        final AtomicReference<WorldRenderEvent> seen = new AtomicReference<WorldRenderEvent>();
        client.events().subscribe(WorldRenderEvent.class, seen::set);

        WorldCamera cam = camera();
        assertTrue(renderer.renderCamera(cam));
        assertEquals(1, module.draws);
        assertNotNull(module.lastContext);
        assertSame(backend.batch, module.lastContext.batch());
        assertSame(cam, module.lastContext.camera());
        assertEquals(1, backend.begins);
        assertEquals(1, backend.ends);

        WorldRenderEvent event = seen.get();
        assertNotNull(event, "world render event must be posted");
        assertEquals(0.5f, event.partialTicks, 1e-6f);
        assertEquals(1.0d, event.cameraX, 1e-9d);
        assertEquals(2.0d, event.cameraY, 1e-9d);
        assertEquals(3.0d, event.cameraZ, 1e-9d);
        assertEquals(70f, event.fov, 1e-6f);
        assertEquals(1280, event.viewportWidth);
        assertEquals(720, event.viewportHeight);
        assertSame(module.lastContext, event.context);
    }

    @Test
    void disabledOverlayIsNotCalled() {
        FakeBackend backend = new FakeBackend();
        client.world3d().setBackend(backend);
        OverlayModule module = new OverlayModule("Disabled 3D");
        client.modules().register(module);

        assertFalse(client.world3d().renderCamera(camera()));
        assertEquals(0, module.draws);
        assertEquals(0, backend.begins, "no enabled overlay and no subscribers -> no GL work");
    }

    @Test
    void moduleFailureIsIsolatedAndLaterModulesStillDraw() {
        FakeBackend backend = new FakeBackend();
        client.world3d().setBackend(backend);
        OverlayModule bad = new OverlayModule("Bad 3D");
        bad.throwOnDraw = true;
        OverlayModule good = new OverlayModule("Good 3D");
        client.modules().register(bad);
        client.modules().register(good);
        bad.setEnabled(true);
        good.setEnabled(true);

        assertTrue(client.world3d().renderCamera(camera()));
        assertEquals(1, bad.draws);
        assertEquals(1, good.draws, "a throwing module must not stop the rest");
        assertEquals(1, backend.ends, "end() must run even when a module throws");
    }

    @Test
    void beginFailureOrExceptionNeverCallsEnd() {
        FakeBackend backend = new FakeBackend();
        client.world3d().setBackend(backend);
        OverlayModule module = new OverlayModule("Test 3D");
        client.modules().register(module);
        module.setEnabled(true);

        backend.failBegin = true;
        assertFalse(client.world3d().renderCamera(camera()));
        assertEquals(0, backend.ends);

        backend.failBegin = false;
        backend.throwBegin = true;
        assertFalse(client.world3d().renderCamera(camera()), "begin exception must be swallowed");
        assertEquals(0, backend.ends);
    }

    @Test
    void endExceptionIsSwallowed() {
        FakeBackend backend = new FakeBackend();
        backend.throwEnd = true;
        client.world3d().setBackend(backend);
        OverlayModule module = new OverlayModule("Test 3D");
        client.modules().register(module);
        module.setEnabled(true);

        assertTrue(client.world3d().renderCamera(camera()), "end exception must not change the result");
        assertEquals(1, backend.ends);
    }

    @Test
    void nullBatchFallsBackToNoOpSoModulesNeverNpe() {
        FakeBackend backend = new FakeBackend();
        backend.nullBatch = true;
        client.world3d().setBackend(backend);
        OverlayModule module = new OverlayModule("Null batch 3D");
        client.modules().register(module);
        module.setEnabled(true);

        assertTrue(client.world3d().renderCamera(camera()));
        assertNotNull(module.lastContext.batch());
        // 空实现必须吞掉所有调用。
        module.lastContext.batch().box(0, 0, 0, 1, 1, 1);
        module.lastContext.batch().ring(0, 0, 0, 1, 8);
        module.lastContext.batch().flush();
    }

    @Test
    void invalidCameraSkipsEverything() {
        FakeBackend backend = new FakeBackend();
        client.world3d().setBackend(backend);
        OverlayModule module = new OverlayModule("Test 3D");
        client.modules().register(module);
        module.setEnabled(true);

        assertFalse(client.world3d().renderCamera(null));
        assertFalse(client.world3d().renderCamera(new WorldCamera()));
        assertEquals(0, backend.begins);
    }
}
