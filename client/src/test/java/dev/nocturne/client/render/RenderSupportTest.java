package dev.nocturne.client.render;

import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.mapping.Mapping;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 使用真实反射桥与假映射验证公共视觉工具，不为测试扩张生产接口。 */
class RenderSupportTest {
    private GameBridge bridge;

    @BeforeEach
    void setUp() {
        FakeMinecraft.instance = new FakeMinecraft();
        bridge = new GameBridge(null, new EntityMapping());
        assertTrue(bridge.resolve());
    }

    @AfterEach
    void tearDown() {
        FakeMinecraft.instance = null;
    }

    @Test
    void alphaMultiplierClampsAndPreservesRgb() {
        assertEquals(0x00123456, RenderSupport.withAlpha(0x80123456, 0f));
        assertEquals(0x80123456, RenderSupport.withAlpha(0x80123456, 1f));
        assertEquals(0x40123456, RenderSupport.withAlpha(0x80123456, 0.5f));
        assertEquals(0x00123456, RenderSupport.withAlpha(0x80123456, -2f));
        assertEquals(0x80123456, RenderSupport.withAlpha(0x80123456, 2f));
        assertEquals(0x00123456, RenderSupport.withAlpha(0x00123456, 1f));
        assertEquals(0xFF123456, RenderSupport.withAlpha(0xFF123456, 1f));
    }

    @Test
    void blendInterpolatesEveryChannelAndClampsEndpoints() {
        int from = 0x10203040;
        int to = 0x90A0B0C0;
        assertEquals(from, RenderSupport.blend(from, to, 0f));
        assertEquals(to, RenderSupport.blend(from, to, 1f));
        assertEquals(from, RenderSupport.blend(from, to, -1f));
        assertEquals(to, RenderSupport.blend(from, to, 2f));
        assertEquals(0x50607080, RenderSupport.blend(from, to, 0.5f));
        assertEquals(0x80808080, RenderSupport.blend(0, 0xFFFFFFFF, 0.5f));
    }

    @Test
    void hsvKeepsCallerAlphaAndProducesPrimaryColors() {
        assertEquals(0x34FF0000, RenderSupport.hsv(0x34, 0f, 1f, 1f));
        assertEquals(0x5600FF00, RenderSupport.hsv(0x56, 1f / 3f, 1f, 1f));
        assertEquals(0x780000FF, RenderSupport.hsv(0x78, 2f / 3f, 1f, 1f));
        assertEquals(0x00FFFFFF, RenderSupport.hsv(-1, 0f, 0f, 1f));
        assertEquals(0xFF000000, RenderSupport.hsv(300, 0f, 1f, 0f));
    }

    @Test
    void classificationUsesMappedTypesWithPlayerAndMonsterPriority() {
        // 假继承链故意重叠，防止把玩家或怪物提前归入动物。
        assertEquals(RenderSupport.Kind.PLAYER, RenderSupport.kind(bridge, new FakePlayer()));
        assertEquals(RenderSupport.Kind.MONSTER, RenderSupport.kind(bridge, new FakeMonster()));
        assertEquals(RenderSupport.Kind.ANIMAL, RenderSupport.kind(bridge, new FakeAnimal()));
        assertEquals(RenderSupport.Kind.ANIMAL, RenderSupport.kind(bridge, new FakeWaterMob()));
        assertEquals(RenderSupport.Kind.ANIMAL, RenderSupport.kind(bridge, new FakeVillager()));
        assertEquals(RenderSupport.Kind.AMBIENT, RenderSupport.kind(bridge, new FakeAmbient()));
        assertEquals(RenderSupport.Kind.OTHER, RenderSupport.kind(bridge, new Object()));
        assertEquals(RenderSupport.Kind.OTHER, RenderSupport.kind(bridge, null));
        assertEquals(RenderSupport.Kind.OTHER, RenderSupport.kind(null, new FakePlayer()));
    }

    @Test
    void absentMappedClassesFallBackToOther() {
        GameBridge absent = new GameBridge(null, new EntityMapping() {
            @Override
            public String className(ClassType type) {
                return type == ClassType.MINECRAFT ? super.className(type)
                        : "missing.visual.Entity" + type.name();
            }
        });
        assertEquals(RenderSupport.Kind.OTHER, RenderSupport.kind(absent, new FakePlayer()));
    }

    @Test
    void entityTraversalSupportsIterableAndArrayWithoutVisitingNulls() {
        Object first = new FakePlayer();
        Object second = new FakeAnimal();
        List<Object> visited = new ArrayList<Object>();
        FakeMinecraft.instance.level.entities = Arrays.asList(first, null, second);
        RenderSupport.forEachEntity(bridge, visited::add);
        assertEquals(Arrays.asList(first, second), visited);

        visited.clear();
        FakeMinecraft.instance.level.entities = new Object[]{second, null, first};
        RenderSupport.forEachEntity(bridge, visited::add);
        assertEquals(Arrays.asList(second, first), visited);
    }

    @Test
    void scalarEntityIsVisitedOnceAndUnavailableWorldIsEmpty() {
        Object entity = new FakePlayer();
        List<Object> visited = new ArrayList<Object>();
        FakeMinecraft.instance.level.entities = entity;
        RenderSupport.forEachEntity(bridge, visited::add);
        assertEquals(Arrays.asList(entity), visited);

        visited.clear();
        FakeMinecraft.instance.level.entities = null;
        RenderSupport.forEachEntity(bridge, visited::add);
        FakeMinecraft.instance.level = null;
        RenderSupport.forEachEntity(bridge, visited::add);
        RenderSupport.forEachEntity(null, visited::add);
        RenderSupport.forEachEntity(bridge, null);
        assertTrue(visited.isEmpty());
    }

    @Test
    void selfDetectionUsesIdentityRatherThanEquals() {
        FakePlayer player = new FakePlayer();
        FakeMinecraft.instance.player = player;
        assertTrue(RenderSupport.isSelf(bridge, player));
        assertFalse(RenderSupport.isSelf(bridge, new FakePlayer()));
        assertFalse(RenderSupport.isSelf(bridge, null));
        assertFalse(RenderSupport.isSelf(null, player));
        FakeMinecraft.instance.player = null;
        assertFalse(RenderSupport.isSelf(bridge, null));
    }

    public static final class FakeMinecraft {
        public static FakeMinecraft instance;
        public Object player;
        public FakeWorld level = new FakeWorld();

        public static FakeMinecraft getInstance() {
            return instance;
        }
    }

    public static final class FakeWorld {
        public Object entities;

        public Object entitiesForRendering() {
            return entities;
        }
    }

    public static class FakeAnimal { }
    public static class FakeMonster extends FakeAnimal { }
    public static final class FakePlayer extends FakeMonster {
        @Override
        public boolean equals(Object other) {
            return other instanceof FakePlayer;
        }

        @Override
        public int hashCode() {
            return 1;
        }
    }
    public static final class FakeWaterMob { }
    public static final class FakeVillager { }
    public static final class FakeAmbient { }

    private static class EntityMapping implements Mapping {
        @Override
        public String describe() {
            return "render-support-test";
        }

        @Override
        public String className(ClassType type) {
            switch (type) {
                case MINECRAFT: return FakeMinecraft.class.getName();
                case CLIENT_LEVEL:
                case MULTIPLAYER_LEVEL:
                case LEVEL: return FakeWorld.class.getName();
                case PLAYER: return FakePlayer.class.getName();
                case MONSTER: return FakeMonster.class.getName();
                case ANIMAL: return FakeAnimal.class.getName();
                case WATER_MOB: return FakeWaterMob.class.getName();
                case VILLAGER: return FakeVillager.class.getName();
                case AMBIENT: return FakeAmbient.class.getName();
                default: return type.canonicalName();
            }
        }

        @Override
        public String methodName(ClassType owner, String name, String descriptor) {
            return name;
        }

        @Override
        public String fieldName(ClassType owner, String name) {
            return name;
        }

        @Override
        public boolean isIdentity() {
            return false;
        }
    }
}
