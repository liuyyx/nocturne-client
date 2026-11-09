package dev.nocturne.client.runtime;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.TestSupport;
import dev.nocturne.client.control.PacketController;
import dev.nocturne.client.event.PacketEvent;
import dev.nocturne.client.game.FakeMinecraft;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.game.TestMapping;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 「世界已就绪」的判定。
 *
 * <p>回归：旧实现用 {@code bridge.isResolved()}，主菜单/死亡界面同样能解析出 Minecraft 类，
 * 于是模块闸门永远打开，onSuspend/onResume 从不触发。这里用替身「游戏主类」构造真实
 * {@link GameBridge}，验证只有 player 与 level 同时存在才算在世界里。
 */
class ModuleTickerWorldTest {

    private ClassLoader savedLoader;

    @BeforeEach
    void setUp() {
        // GameBridge 用上下文加载器解析替身「游戏类」；不设置会解析不到 FakeMinecraft。
        savedLoader = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(getClass().getClassLoader());
        FakeMinecraft.reset();
        TestSupport.resetClient();
    }

    @AfterEach
    void tearDown() {
        Thread.currentThread().setContextClassLoader(savedLoader);
        FakeMinecraft.reset();
        TestSupport.resetClient();
    }

    @Test
    void mainMenuAndDeathScreenAreNotWorldReady() {
        GameBridge bridge = new GameBridge(null, new TestMapping(FakeMinecraft.class));
        assertTrue(bridge.resolve(), "fake minecraft class is resolvable");
        assertTrue(bridge.isResolved());
        assertFalse(ModuleTicker.worldReady(bridge), "player == null (main menu)");

        FakeMinecraft.getInstance().player = new Object();
        assertFalse(ModuleTicker.worldReady(bridge), "level == null (dying / switching)");

        FakeMinecraft.getInstance().level = new Object();
        assertTrue(ModuleTicker.worldReady(bridge), "player + level present");
    }

    @Test
    void nullBridgeIsNotWorldReady() {
        assertFalse(ModuleTicker.worldReady(null));
    }

    /** 死亡界面：player/level 都非空但血量 ≤0，闸门必须关闭（AutoRespawn 自行豁免）。 */
    @Test
    void deadPlayerIsNotWorldReady() {
        FakeMinecraft.reset();
        FakeMinecraft.instance.level = new Object();
        FakeLiving player = new FakeLiving(0.0f);
        FakeMinecraft.instance.player = player;
        GameBridge bridge = new GameBridge(null, new TestMapping(FakeMinecraft.class));

        assertFalse(ModuleTicker.worldReady(bridge), "health == 0 must close the gate");

        player.health = 20.0f;
        assertTrue(ModuleTicker.worldReady(bridge), "respawned player reopens the gate");
    }

    /** 血量读不到（非 LivingEntity / 映射缺失）时保持存活，不能误判成尸体。 */
    @Test
    void unreadableHealthIsTreatedAsAlive() {
        FakeMinecraft.reset();
        FakeMinecraft.instance.level = new Object();
        FakeMinecraft.instance.player = new Object();
        GameBridge bridge = new GameBridge(null, new TestMapping(FakeMinecraft.class));

        assertTrue(ModuleTicker.worldReady(bridge));
    }

    /**
     * 世界切换顺序：先清闸门/队列（reset），再投递 WorldChangeEvent，监听器随后重新 hold 必须生效。
     */
    @Test
    void worldChangeResetsPacketsBeforeTheEvent() {
        NocturneClient client = NocturneClient.boot(null);
        FakeMinecraft.instance.player = new FakeLiving(20.0f);
        FakeMinecraft.instance.level = new Object();
        client.setGameBridge(new GameBridge(null, new TestMapping(FakeMinecraft.class)));
        ModuleTicker ticker = new ModuleTicker(client);
        PacketController packets = client.packets();
        packets.noteMainThread();

        // 首帧只建立世界基线。
        ticker.onFrame();

        Object owner = new Object();
        assertTrue(packets.hold(PacketEvent.Direction.SEND, owner));
        assertTrue(packets.capture(PacketEvent.Direction.SEND, new Object()));
        boolean[] sawEmptyQueue = new boolean[1];
        boolean[] rearmed = new boolean[1];
        client.events().subscribe(dev.nocturne.client.event.WorldChangeEvent.class, event -> {
            sawEmptyQueue[0] = packets.queued(PacketEvent.Direction.SEND) == 0
                    && !packets.isHolding(PacketEvent.Direction.SEND);
            rearmed[0] = packets.hold(PacketEvent.Direction.SEND, owner);
        });

        FakeMinecraft.instance.level = new Object();
        ticker.onFrame();

        assertTrue(sawEmptyQueue[0], "事件投递时旧世界积压与所有权必须已经清空");
        assertTrue(rearmed[0], "事件内重新 hold 不得被随后的 reset 清掉");
        assertTrue(packets.isHolding(PacketEvent.Direction.SEND, owner));
    }

    /** 最小 LivingEntity 替身：只需要 getHealth。 */
    public static final class FakeLiving {
        public float health;

        public FakeLiving(float health) {
            this.health = health;
        }

        public float getHealth() {
            return health;
        }
    }
}
