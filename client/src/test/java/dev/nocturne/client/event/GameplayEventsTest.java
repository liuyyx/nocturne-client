package dev.nocturne.client.event;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.TestSupport;
import dev.nocturne.client.bootstrap.GameplayHook;
import dev.nocturne.client.game.FakeMinecraft;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.game.TestMapping;
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
    void screenOpenPostsScreenEventForOpenAndClose() {
        NocturneClient client = NocturneClient.boot(null);
        AtomicReference<ScreenEvent> seen = new AtomicReference<ScreenEvent>();
        client.events().subscribe(ScreenEvent.class, event -> seen.set(event));

        Object screen = new Object();
        assertTrue(GameplayHook.screenOpen(screen), "an uncancelled screen event must allow the switch");
        assertSame(screen, seen.get().screen);

        assertTrue(GameplayHook.screenOpen(null), "closing a screen (null) must also be delivered");
        assertNull(seen.get().screen);
    }

    @Test
    void cancelledScreenEventBlocksTheGate() {
        NocturneClient client = NocturneClient.boot(null);
        client.events().subscribe(ScreenEvent.class, event -> event.cancelled = true);

        assertFalse(GameplayHook.screenOpen(new Object()));
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

    @Test
    void playerTickPreCancellationReachesTheGate() {
        NocturneClient client = NocturneClient.boot(null);
        FakePlayer player = new FakePlayer();
        FakeMinecraft.reset();
        FakeMinecraft.instance.player = player;
        client.setGameBridge(new GameBridge(null, new TestMapping(FakeMinecraft.class)));
        client.events().subscribe(PlayerTickEvent.Pre.class, event -> event.cancelled = true);

        assertFalse(GameplayHook.playerTickPre(player));
    }

    @Test
    void playerTickPostUsesTheSharedEventType() {
        NocturneClient client = NocturneClient.boot(null);
        FakePlayer player = new FakePlayer();
        FakeMinecraft.reset();
        FakeMinecraft.instance.player = player;
        client.setGameBridge(new GameBridge(null, new TestMapping(FakeMinecraft.class)));
        AtomicReference<PlayerTickEvent.Post> seen = new AtomicReference<PlayerTickEvent.Post>();
        client.events().subscribe(PlayerTickEvent.Post.class, seen::set);

        GameplayHook.playerTickPost(player);

        assertSame(PlayerTickEvent.Post.INSTANCE, seen.get());
    }

    @Test
    void deferredAttackPostUsesCapturedTargetAndDropsCancelledAttack() {
        NocturneClient client = NocturneClient.boot(null);
        Object player = new Object();
        Object target = new Object();
        AtomicReference<AttackEvent> post = new AtomicReference<AttackEvent>();
        java.util.concurrent.atomic.AtomicBoolean cancel = new java.util.concurrent.atomic.AtomicBoolean(true);
        client.events().subscribe(AttackEvent.class, event -> {
            if (event.post) {
                post.set(event);
            } else if (cancel.get()) {
                event.cancelled = true;
            }
        });

        GameplayHook.attackCapture(player, target);
        assertFalse(GameplayHook.attack(player, target));
        GameplayHook.attackPost();
        assertNull(post.get());

        cancel.set(false);
        GameplayHook.attackCapture(player, target);
        assertTrue(GameplayHook.attack(player, target));
        GameplayHook.attackPost();
        assertSame(target, post.get().target);
        assertTrue(post.get().post);
    }

    /** SDL（26.3）的 MouseButtonInfo 1/2/3 必须归一为契约 0/1/2。 */
    @Test
    void sdlMouseButtonsAreNormalisedToTheContract() {
        NocturneClient client = NocturneClient.boot(null);
        client.setGameBridge(new GameBridge(null, new TestMapping(FakeMinecraft.class)));
        AtomicReference<Integer> seen = new AtomicReference<Integer>();
        client.events().subscribe(MouseButtonEvent.class, event -> seen.set(event.button));

        assertTrue(GameplayHook.mouse(0L, new FakeMouseButtonInfo(1), 1));
        assertEquals(0, seen.get().intValue(), "SDL left (1) must arrive as contract left (0)");

        assertTrue(GameplayHook.mouse(0L, new FakeMouseButtonInfo(3), 1));
        assertEquals(1, seen.get().intValue(), "SDL right (3) must arrive as contract right (1)");

        assertTrue(GameplayHook.mouse(0L, new FakeMouseButtonInfo(2), 1));
        assertEquals(2, seen.get().intValue(), "SDL middle (2) stays contract middle (2)");
    }

    /** GLFW 世代（onPress 的 int 按钮）本来就符合契约，不得再被归一。 */
    @Test
    void glfwMouseButtonsArePassedThroughUnchanged() {
        NocturneClient client = NocturneClient.boot(null);
        AtomicReference<Integer> seen = new AtomicReference<Integer>();
        client.events().subscribe(MouseButtonEvent.class, event -> seen.set(event.button));

        assertTrue(GameplayHook.mouse(0L, 1, 1, 0));
        assertEquals(1, seen.get().intValue(), "GLFW right button (1) must stay 1");
    }

    /** 26.x {@code MouseButtonInfo} 的最小替身：只有 button()。 */
    public static final class FakeMouseButtonInfo {
        private final int button;

        public FakeMouseButtonInfo(int button) {
            this.button = button;
        }

        public int button() {
            return button;
        }
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
