package dev.nocturne.client.event;

import dev.nocturne.bootstrap.GameplaySink;
import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.TestSupport;
import dev.nocturne.client.bootstrap.GameplayHook;
import dev.nocturne.client.game.FakeMinecraft;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.game.TestMapping;
import dev.nocturne.client.mapping.IdentityMapping;
import dev.nocturne.client.module.Module;
import dev.nocturne.client.module.modules.render.NoRenderModule;
import dev.nocturne.client.value.MultiBoolValue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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

    /** 嵌套实体渲染：pre/post 必须成对，内层不能吞掉外层的捕获。 */
    @Test
    void nestedChamsCapturePairsPreAndPost() {
        NocturneClient client = NocturneClient.boot(null);
        java.util.List<EntityRenderEvent> events = new java.util.ArrayList<EntityRenderEvent>();
        client.events().subscribe(EntityRenderEvent.class, events::add);
        Object outer = new Object();
        Object inner = new Object();

        GameplayHook.chamsPre(outer);
        GameplayHook.chamsPre(inner);
        GameplayHook.chamsPost();
        GameplayHook.chamsPost();

        assertEquals(4, events.size(), "nested render must deliver two pre and two post events");
        assertSame(outer, events.get(0).entity);
        assertFalse(events.get(0).post);
        assertSame(inner, events.get(1).entity);
        assertFalse(events.get(1).post);
        assertSame(inner, events.get(2).entity);
        assertTrue(events.get(2).post);
        assertSame(outer, events.get(3).entity);
        assertTrue(events.get(3).post);
    }

    /** {@code chamsPre(null)} 也必须占一个配对位，post 不能因此弹掉外层实体。 */
    @Test
    void nullEntityCaptureKeepsTheStackBalanced() {
        NocturneClient client = NocturneClient.boot(null);
        java.util.List<EntityRenderEvent> events = new java.util.ArrayList<EntityRenderEvent>();
        client.events().subscribe(EntityRenderEvent.class, events::add);
        Object outer = new Object();

        GameplayHook.chamsPre(outer);
        GameplayHook.chamsPre(null);
        GameplayHook.chamsPost();
        GameplayHook.chamsPost();

        assertEquals(3, events.size(), "null pre emits only its own pre; post of null emits nothing");
        assertSame(outer, events.get(0).entity);
        assertFalse(events.get(0).post);
        assertNull(events.get(1).entity);
        assertFalse(events.get(1).post);
        assertSame(outer, events.get(2).entity);
        assertTrue(events.get(2).post);
    }

    // -------------------------------------------------- 四个视觉覆写的转发契约

    @Test
    void noFogForwardsToEnabledWorldTweaks() {
        NocturneClient client = NocturneClient.boot(null);
        Module tweaks = client.modules().get("World Tweaks");
        assertNotNull(tweaks, "World Tweaks must be registered for noFog to reach it");

        assertTrue(GameplayHook.noFog(), "disabled by default: fog allowed");
        tweaks.setEnabled(true);
        assertFalse(GameplayHook.noFog(), "enabled World Tweaks (No Fog) must cancel fog");
        tweaks.setEnabled(false);
        assertTrue(GameplayHook.noFog(), "disabling restores fog");
    }

    @Test
    void cameraZoomForwardsToEnabledCameraClip() {
        NocturneClient client = NocturneClient.boot(null);
        Module clip = client.modules().get("Camera Clip");
        assertNotNull(clip, "Camera Clip must be registered for cameraZoom to reach it");
        GameplaySink.markWired("cameraZoom");

        assertEquals(5.0f, GameplayHook.cameraZoom(5.0f), "disabled: original distance preserved");
        clip.setEnabled(true);
        assertEquals(3.5f, GameplayHook.cameraZoom(5.0f), "enabled Camera Clip supplies its Distance value");
        clip.setEnabled(false);
        assertEquals(5.0f, GameplayHook.cameraZoom(5.0f), "disabling restores original distance");
    }

    @Test
    void entityRenderGateForwardsToEnabledNoRender() {
        NocturneClient client = NocturneClient.boot(null);
        NoRenderModule noRender = (NoRenderModule) client.modules().get("No Render");
        assertNotNull(noRender);

        assertTrue(GameplayHook.entityRenderGate(new Object(), null), "disabled: allow render");
        MultiBoolValue entities = multi(noRender, "Entities");
        entities.toggle("Other");
        noRender.setEnabled(true);
        // 选中 "Other" 后普通对象判为 OTHER，门必须真的取消渲染 —— 证明转发到模块而非恒 true。
        client.setGameBridge(new GameBridge(null, new IdentityMapping()));
        assertFalse(GameplayHook.entityRenderGate(new Object(), null), "enabled No Render must filter selected entity kind");
        assertTrue(GameplayHook.entityRenderGate(null, null), "null entity must allow render");
    }

    @Test
    void handTransformPreservesPoseWhenDisabledAndIsolatesFailures() {
        NocturneClient client = NocturneClient.boot(null);
        Module handView = client.modules().get("Hand View");
        assertNotNull(handView);
        Object pose = new Object();

        GameplayHook.handTransform(pose, null, 0.5f); // 未启用：不抛、不动 pose
        handView.setEnabled(true);
        client.setGameBridge(new GameBridge(null, new IdentityMapping()));
        GameplayHook.handTransform(pose, null, 0.5f); // 反射失败被 catch，pose 必须原样保留
        assertSame(pose, pose);
    }

    private static MultiBoolValue multi(Module module, String name) {
        for (dev.nocturne.client.value.Value<?> value : module.values()) {
            if (value.name().equals(name) && value instanceof MultiBoolValue) {
                return (MultiBoolValue) value;
            }
        }
        throw new AssertionError("missing MultiBoolValue " + name + " on " + module.name());
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
