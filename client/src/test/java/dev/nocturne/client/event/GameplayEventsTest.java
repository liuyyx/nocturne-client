package dev.nocturne.client.event;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.TestSupport;
import dev.nocturne.client.bootstrap.GameplayHook;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.mapping.IdentityMapping;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** {@link GameplayEvents} 的汇级行为测试。 */
class GameplayEventsTest {

    @BeforeEach
    void isolate() {
        TestSupport.resetClient();
        GameplayHook.install(null);
    }

    @AfterEach
    void cleanUp() {
        GameplayHook.install(null);
        TestSupport.resetClient();
    }

    @Test
    void packetEventReplacementReachesTheOriginalCall() {
        NocturneClient client = NocturneClient.boot(null);
        Object original = new Object();
        Object replacement = new Object();
        client.events().subscribe(PacketEvent.class, event -> event.setPacket(replacement));

        assertSame(replacement, GameplayHook.packetSend(original));
    }

    @Test
    void cancelledPacketReturnsNullToTheTransformer() {
        NocturneClient client = NocturneClient.boot(null);
        client.events().subscribe(PacketEvent.class, event -> event.cancelled = true);

        assertNull(GameplayHook.packetSend(new Object()));
    }

    @Test
    void keyEventUsesAwtCodesAndControlsTheGate() {
        NocturneClient client = NocturneClient.boot(null);
        AtomicReference<KeyEvent> seen = new AtomicReference<KeyEvent>();
        client.events().subscribe(KeyEvent.class, event -> {
            seen.set(event);
            event.cancelled = true;
        });

        assertFalse(GameplayHook.key(0L, 65, 30, 1, 0));
        assertEquals(65, seen.get().keyCode);
        assertTrue(seen.get().pressed);
    }

    @Test
    void motionPreWritesSubscriberChangesBackToThePlayer() {
        NocturneClient client = NocturneClient.boot(null);
        client.setGameBridge(new GameBridge(null, new IdentityMapping()));
        FakePlayer player = new FakePlayer();
        client.events().subscribe(MotionEvent.class, event -> {
            event.x = 4.0;
            event.yaw = 90.0f;
            event.onGround = true;
        });

        GameplayHook.motionPre(player);

        assertEquals(4.0, player.position.x);
        assertEquals(90.0f, player.yRot);
        assertTrue(player.onGround);
    }

    private static final class FakeVector {
        private double x;
        private final double y;
        private final double z;

        private FakeVector(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    public static final class FakePlayer {
        private FakeVector position = new FakeVector(1.0, 2.0, 3.0);
        private float yRot = 10.0f;
        private float xRot = 20.0f;
        private boolean onGround;

        public FakeVector position() {
            return position;
        }

        public void setPos(double x, double y, double z) {
            position = new FakeVector(x, y, z);
        }
    }
}
