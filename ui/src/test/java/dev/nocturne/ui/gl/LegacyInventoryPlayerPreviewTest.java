package dev.nocturne.ui.gl;

import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.mapping.IdentityMapping;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 旧版游戏原生 helper 即便抛错也不许把本地玩家的镜头/身体朝向改掉。 */
class LegacyInventoryPlayerPreviewTest {
    public static final class Player {
        public float xRot = 11f;
        public float yRot = 22f;
        public float yBodyRot = 33f;
        public float yHeadRot = 44f;
        public float yHeadRotO = 55f;
    }

    public static final class Screen {
        static boolean fail;
        static int calls;

        public static void drawEntityOnScreen(int x, int y, int size, float dx, float dy, Player player) {
            calls++;
            player.xRot = player.yRot = player.yBodyRot = player.yHeadRot = player.yHeadRotO = -99f;
            if (fail) {
                throw new IllegalStateException("render interrupted");
            }
        }
    }

    public static final class PoseStack {
    }

    public static final class PoseScreen {
        static int calls;

        public static void renderEntityInInventory(PoseStack pose, int x, int y, int size,
                float dx, float dy, Player player) {
            calls++;
            player.yHeadRot = -100f;
        }
    }

    @Test
    void poseStackSignatureUsesCurrentFrameAndRestoresRotations() {
        LegacyInventoryPlayerPreview preview = LegacyInventoryPlayerPreview.forScreen(
                new IdentityMapping(), PoseScreen.class);
        assertNotNull(preview);
        GameBridge bridge = new GameBridge(null, new IdentityMapping());
        Player player = new Player();
        PoseScreen.calls = 0;
        assertFalse(preview.draw(bridge, null, player, 40, 100, 30, 0f, 0f));
        assertEquals(0, PoseScreen.calls);
        assertTrue(preview.draw(bridge, new PoseStack(), player, 40, 100, 30, 0f, 0f));
        assertEquals(1, PoseScreen.calls);
        assertRestored(player);
    }

    @Test
    void alwaysRestoresAllRotationsAndKeepsRenderingAfterNativeFailure() {
        LegacyInventoryPlayerPreview preview = LegacyInventoryPlayerPreview.forScreen(
                new IdentityMapping(), Screen.class);
        assertNotNull(preview);
        GameBridge bridge = new GameBridge(null, new IdentityMapping());
        Player player = new Player();
        Screen.calls = 0;
        Screen.fail = true;
        assertFalse(preview.draw(bridge, player, 40, 100, 30, 0f, 0f));
        assertRestored(player);
        Screen.fail = false;
        assertTrue(preview.draw(bridge, player, 40, 100, 30, 0f, 0f));
        assertRestored(player);
        assertEquals(2, Screen.calls);
    }

    private static void assertRestored(Player player) {
        assertEquals(11f, player.xRot);
        assertEquals(22f, player.yRot);
        assertEquals(33f, player.yBodyRot);
        assertEquals(44f, player.yHeadRot);
        assertEquals(55f, player.yHeadRotO);
    }
}
