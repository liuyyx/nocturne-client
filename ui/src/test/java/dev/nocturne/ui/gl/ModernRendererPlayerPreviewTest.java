package dev.nocturne.ui.gl;

import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.mapping.Mapping;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ModernRenderer 的版本分派、帧生命周期和原生 GL 状态隔离行为。 */
class ModernRendererPlayerPreviewTest {
    public static final class Player {
        public float xRot = 11f;
        public float yRot = 22f;
        public float yBodyRot = 33f;
        public float yHeadRot = 44f;
        public float yHeadRotO = 55f;
    }

    public static final class Graphics {
        int calls;
    }

    public static final class PoseStack {
    }

    public static final class RegionScreen {
        public static void renderEntityInInventoryFollowsMouse(Graphics graphics,
                int left, int top, int right, int bottom, int size, float offset,
                float mouseX, float mouseY, Player player) {
            graphics.calls++;
        }
    }

    public static final class PoseScreen {
        static int calls;

        public static void renderEntityInInventory(PoseStack pose, int x, int y, int size,
                float lookX, float lookY, Player player) {
            calls++;
            player.xRot = player.yRot = player.yBodyRot = player.yHeadRot = player.yHeadRotO = -90f;
        }
    }

    public static final class NativeScreen {
        static int calls;
        static int programAtCall;
        static int vaoAtCall;

        public static void drawEntityOnScreen(int x, int y, int size, float lookX, float lookY,
                Player player) {
            programAtCall = GL30.lastUseProgram;
            vaoAtCall = GL30.lastBindVertexArray;
            calls++;
            player.xRot = player.yRot = player.yBodyRot = player.yHeadRot = player.yHeadRotO = -90f;
            GL11.glEnable(0x0B50);
            GL11.glDisable(0x0BE2);
            GL11.glDepthRange(0.2, 0.8);
            GL11.glActiveTexture(GL11.GL_TEXTURE0 + 1);
            GL11.glBindTexture(0x0DE1, 99);
            GL30.glUseProgram(777);
            GL30.glBindVertexArray(888);
            GL30.glBindBuffer(0x8892, 999);
            GL30.glDisable(0x0BE2);
            GL30.glEnable(0x0B71);
            GL30.glEnable(0x0B44);
        }
    }

    public static final class MissingScreen {
    }

    @BeforeEach
    void reset() {
        GL30.reset();
        GL11.reset();
        PoseScreen.calls = 0;
        NativeScreen.calls = 0;
    }

    @Test
    void guiGraphicsRequiresAnActiveFrameAndClearedContextIsNotReused() {
        ModernRenderer renderer = renderer(new TestMapping(RegionScreen.class));
        Graphics graphics = new Graphics();
        Player player = new Player();
        renderer.setDrawContext(graphics);

        assertFalse(renderer.drawPlayer(player, 10, 10, 90, 100, 20, 20));
        assertEquals(0, graphics.calls, "beginFrame 前不得提交游戏模型");

        renderer.beginFrame();
        assertTrue(renderer.drawPlayer(player, 10, 10, 90, 100, 20, 20));
        assertEquals(1, graphics.calls);
        assertFalse(renderer.drawPlayer(player, 10, 10, 12, 100, 20, 20));
        assertEquals(1, graphics.calls, "无效区域不得进入原生 helper");

        renderer.clearDrawContext();
        assertFalse(renderer.drawPlayer(player, 10, 10, 90, 100, 20, 20));
        renderer.endFrame();
        assertFalse(renderer.drawPlayer(player, 10, 10, 90, 100, 20, 20));
        assertEquals(1, graphics.calls, "清除上下文或 endFrame 后不得重放上一帧");
    }

    @Test
    void poseStackPathRestoresRotationsAndNeverDrawsWithoutTheCurrentPose() {
        ModernRenderer renderer = renderer(new TestMapping(PoseScreen.class));
        Player player = new Player();
        renderer.beginFrame();

        assertFalse(renderer.drawPlayer(player, 10, 10, 90, 100, 20, 20));
        assertEquals(0, PoseScreen.calls, "1.15+ 缺少 PoseStack 时不能退回六参调用");

        renderer.setDrawContext(new PoseStack());
        assertTrue(renderer.drawPlayer(player, 10, 10, 90, 100, 20, 20));
        assertEquals(1, PoseScreen.calls);
        assertRotationsRestored(player);
        renderer.endFrame();
    }

    @Test
    void fixedFunctionPathRestoresMatricesDepthAndModernUiBindings() {
        ModernRenderer renderer = renderer(new TestMapping(NativeScreen.class));
        Player player = new Player();
        renderer.beginFrame();

        assertTrue(renderer.drawPlayer(player, 10, 10, 90, 100, 20, 20));

        assertEquals(1, NativeScreen.calls);
        assertRotationsRestored(player);
        assertEquals(0, NativeScreen.programAtCall, "fixed-function helper 前必须解绑 UI shader");
        assertEquals(0, NativeScreen.vaoAtCall, "fixed-function helper 前必须解绑 UI VAO");
        assertEquals(2, GL11.pushMatrixCalls);
        assertEquals(GL11.pushMatrixCalls, GL11.popMatrixCalls, "投影和模型视图栈必须配对");
        assertEquals(GL11.GL_MODELVIEW, GL11.matrixMode, "矩阵模式不得泄漏");
        assertEquals(0.1, GL11.depthNear);
        assertEquals(0.9, GL11.depthFar);
        assertEquals(-1000.0, GL11.lastOrtho[4]);
        assertEquals(1000.0, GL11.lastOrtho[5]);
        assertTrue(GL11.isEnabled(0x0BE2));
        assertFalse(GL11.isEnabled(0x0B50));
        assertEquals(GL11.GL_TEXTURE0, GL11.activeTexture);
        assertEquals(17, GL11.boundTexture);

        assertEquals(42, GL30.lastUseProgram, "原生绘制后必须重新绑定 ModernRenderer program");
        assertEquals(9, GL30.lastBindVertexArray, "原生绘制后必须重新绑定 ModernRenderer VAO");
        assertEquals(7, GL30.lastBindArrayBuffer);
        assertTrue(GL30.glIsEnabled(0x0BE2));
        assertFalse(GL30.glIsEnabled(0x0B71));
        assertFalse(GL30.glIsEnabled(0x0B44));
        renderer.endFrame();
    }

    @Test
    void missingFixedFunctionHelperFailsExplicitly() {
        ModernRenderer renderer = renderer(new TestMapping(MissingScreen.class));
        renderer.beginFrame();
        assertFalse(renderer.drawPlayer(new Player(), 10, 10, 90, 100, 20, 20));
        assertEquals(0, GL11.pushMatrixCalls, "helper 缺失时不得先接管矩阵再假装绘制");
        renderer.endFrame();
    }

    private static ModernRenderer renderer(Mapping mapping) {
        ModernRenderer renderer = new ModernRenderer(ModernGlApi.bind(
                ModernRendererPlayerPreviewTest.class.getClassLoader()), null,
                new GameBridge(null, mapping));
        assertTrue(renderer.initialise());
        return renderer;
    }

    private static void assertRotationsRestored(Player player) {
        assertEquals(11f, player.xRot);
        assertEquals(22f, player.yRot);
        assertEquals(33f, player.yBodyRot);
        assertEquals(44f, player.yHeadRot);
        assertEquals(55f, player.yHeadRotO);
    }

    private static final class TestMapping implements Mapping {
        private final String inventoryScreen;

        TestMapping(Class<?> inventoryScreen) {
            this.inventoryScreen = inventoryScreen.getName();
        }

        @Override
        public String describe() {
            return "modern-player-preview-test";
        }

        @Override
        public String className(ClassType type) {
            return type == ClassType.INVENTORY_SCREEN ? inventoryScreen : type.canonicalName();
        }

        @Override
        public String methodName(ClassType owner, String canonicalName, String descriptor) {
            return canonicalName;
        }

        @Override
        public String fieldName(ClassType owner, String canonicalName) {
            return canonicalName;
        }

        @Override
        public boolean isIdentity() {
            return true;
        }
    }
}
