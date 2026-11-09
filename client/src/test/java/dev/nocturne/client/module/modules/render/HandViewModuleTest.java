package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.TestSupport;
import dev.nocturne.client.game.FakeMinecraft;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.game.TestMapping;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 26.x 的换手抑制：{@code Minecraft#itemInHandRenderer} 已不存在，手部状态在
 * {@code LocalPlayer#firstPersonHandsAndItems()} 的四个字段上。
 */
class HandViewModuleTest {

    private ClassLoader savedTccl;

    @BeforeEach
    void setUp() {
        savedTccl = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(getClass().getClassLoader());
        TestSupport.resetClient();
        FakeMinecraft.reset();
    }

    @AfterEach
    void tearDown() {
        TestSupport.resetClient();
        Thread.currentThread().setContextClassLoader(savedTccl);
    }

    @Test
    void writesHandsStateWhenTheVersionHasNoItemInHandRenderer() {
        FakePlayer player = new FakePlayer();
        FakeMinecraft.instance.player = player;
        NocturneClient.boot(null).setGameBridge(new GameBridge(null, new TestMapping(FakeMinecraft.class)));

        HandViewModule module = new HandViewModule();
        module.setEnabled(true);
        module.onTick();

        assertEquals(1.0f, player.hands.mainHandHeight, "main hand height must be pinned");
        assertEquals(1.0f, player.hands.offHandHeight, "off hand height must be pinned");
        assertEquals(player.mainHandItem, player.hands.mainHandItem, "main hand item must be pinned");
        assertEquals(player.offHandItem, player.hands.offHandItem, "off hand item must be pinned");
    }

    /** 26.x 的第一人称手部状态替身。 */
    public static final class FakeHands {
        public float mainHandHeight;
        public float offHandHeight;
        public Object mainHandItem;
        public Object offHandItem;
    }

    /** 持有手部状态与双手物品的本地玩家替身。 */
    public static final class FakePlayer {
        public final FakeHands hands = new FakeHands();
        public final Object mainHandItem = new Object();
        public final Object offHandItem = new Object();

        public FakeHands firstPersonHandsAndItems() {
            return hands;
        }

        public Object getMainHandItem() {
            return mainHandItem;
        }

        public Object getOffhandItem() {
            return offHandItem;
        }
    }
}
