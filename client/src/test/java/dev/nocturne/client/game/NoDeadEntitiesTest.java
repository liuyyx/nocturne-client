package dev.nocturne.client.game;

import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.mapping.Mapping;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 渲染期死亡判定：非 LivingEntity 不参与；读不到 ≠ 已死；只有能读到且 ≤0 才算尸体。
 * 旧实现把"读不到"当 0，导致 1.21.5+ 所有实体（含玩家）被 No Dead Entities 删掉。
 */
class NoDeadEntitiesTest {

    public static final class FakeLiving {
        private final float health;

        FakeLiving(float health) {
            this.health = health;
        }

        public float getHealth() {
            return health;
        }
    }

    public static final class FakeUnreadableLiving {
    }

    static class LivingMapping implements Mapping {
        @Override
        public String describe() {
            return "living-test-mapping";
        }

        @Override
        public String className(ClassType type) {
            return type == ClassType.LIVING_ENTITY
                    ? FakeUnreadableLiving.class.getName() : type.canonicalName();
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
            return false;
        }
    }

    static final class ReadableLivingMapping extends LivingMapping {
        @Override
        public String className(ClassType type) {
            return type == ClassType.LIVING_ENTITY
                    ? FakeLiving.class.getName() : type.canonicalName();
        }
    }

    @Test
    void nonLivingEntityIsNeverTreatedAsDead() {
        GameBridge bridge = new GameBridge(null, new ReadableLivingMapping());
        assertFalse(bridge.isDeadForRendering(new Object()));
        assertFalse(bridge.isDeadForRendering(null));
        assertNull(bridge.healthOrNull(null));
    }

    @Test
    void unreadableHealthIsNotDeath() {
        GameBridge bridge = new GameBridge(null, new LivingMapping());
        Object entity = new FakeUnreadableLiving();
        assertTrue(bridge.isInstance(entity, ClassType.LIVING_ENTITY));
        assertNull(bridge.healthOrNull(entity));
        assertFalse(bridge.isDeadForRendering(entity), "unknown health must not hide the entity");
    }

    @Test
    void readableZeroHealthIsDeath() {
        GameBridge bridge = new GameBridge(null, new ReadableLivingMapping());
        assertTrue(bridge.isDeadForRendering(new FakeLiving(0.0f)));
        assertTrue(bridge.isDeadForRendering(new FakeLiving(-1.0f)));
        assertFalse(bridge.isDeadForRendering(new FakeLiving(20.0f)));
    }
}
