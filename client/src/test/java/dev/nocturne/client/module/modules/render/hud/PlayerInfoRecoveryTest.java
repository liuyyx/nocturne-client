package dev.nocturne.client.module.modules.render.hud;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.TestSupport;
import dev.nocturne.client.game.FakeMinecraft;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.game.TestMapping;
import dev.nocturne.client.mapping.ClassType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 实体遍历异常后，PlayerInfo 下一 tick 必须恢复主扫描。 */
class PlayerInfoRecoveryTest {
    @BeforeEach
    void setUp() {
        TestSupport.resetClient();
        FakeMinecraft.reset();
    }

    @AfterEach
    void tearDown() {
        TestSupport.resetClient();
        FakeMinecraft.reset();
    }

    @Test
    void failedPendingLookupDoesNotPoisonFollowingTicks() throws Exception {
        NocturneClient client = NocturneClient.boot(null);
        PlayerInfoElementModule module = (PlayerInfoElementModule) client.modules().get("PlayerInfo");
        assertTrue(module != null);
        FakePlayer player = new FakePlayer();
        FakeMinecraft.instance.player = player;
        FakeWorld world = new FakeWorld();
        FakeMinecraft.instance.level = world;
        client.setGameBridge(new GameBridge(null, new TestMapping(FakeMinecraft.class)
                .classOverride(ClassType.PLAYER, FakePlayer.class)
                .classOverride(ClassType.LEVEL, FakeWorld.class)));
        world.entities = new FailingEntities(player);
        set(module, "pendingId", 99);

        try {
            module.onTick();
        } catch (IllegalStateException expected) {
            // 模拟实体遍历/访问异常；生产注册表会记录并隔离它。
        }
        assertEquals(-1, getInt(module, "pendingId"), "异常路径必须复位 pendingId");

        world.entities = Collections.singletonList(player);
        module.onTick();
        assertTrue((Boolean) get(module, "available"), "下一 tick 必须恢复列表可用状态");
        assertEquals(0, getInt(module, "count"), "默认隐藏自己时主扫描仍应正常完成");
    }

    public static final class FakePlayer {
        public double x;
        public double y;
        public double z;

        public FakePosition position() {
            return new FakePosition();
        }
    }

    public static final class FakePosition {
        public double x;
        public double y;
        public double z;
    }

    public static final class FakeWorld {
        public Object entities;
    }

    public static final class FailingEntities implements Iterable<Object> {
        private final Object entity;

        FailingEntities(Object entity) {
            this.entity = entity;
        }

        @Override
        public java.util.Iterator<Object> iterator() {
            throw new IllegalStateException("entity list changed during traversal");
        }
    }

    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Object get(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(target);
    }

    private static int getInt(Object target, String name) throws Exception {
        return ((Integer) get(target, name)).intValue();
    }
}
