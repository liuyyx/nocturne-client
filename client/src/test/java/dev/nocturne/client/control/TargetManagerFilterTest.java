package dev.nocturne.client.control;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.TestSupport;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.game.TestMapping;
import dev.nocturne.client.mapping.ClassType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 目标选择器内建的好友/视线过滤。
 *
 * <p>回归：好友过滤只存在于 AimBot 内部、视线由各模块自己判断，KillAura/MultiAura 等会
 * 直接攻击好友。两者现已集中到 {@link TargetManager}，模块不再各自过滤。
 */
class TargetManagerFilterTest {

    static final class Vec {
        public double x;
        public double y;
        public double z;

        Vec(double x, double y, double z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }
    }

    static final class Box {
        public double minX;
        public double minY;
        public double minZ;
        public double maxX;
        public double maxY;
        public double maxZ;
    }

    static class Living {
        public String name = "Target";
        public double health = 20.0;
        public boolean invisible;
        /** hasLineOfSight 的返回值。 */
        public boolean visible = true;
        public Vec position = new Vec(0.0, 0.0, 2.0);
        public Vec eye;
        public Object box;
        public float yRot;
        public boolean onGround = true;

        public Vec position() {
            return position;
        }

        public Vec getEyePosition(float partialTicks) {
            return eye;
        }

        public Vec getEyePosition() {
            return eye;
        }

        public Object getBoundingBox() {
            return box;
        }

        public float getHealth() {
            return (float) health;
        }

        public boolean isInvisible() {
            return invisible;
        }

        public boolean hasLineOfSight(Living other) {
            // 真实语义：接收者（玩家）能否看见 other；被遮挡的是 other 自己的 visible。
            return other != null && other.visible;
        }

        public String getName() {
            return name;
        }

        public java.util.UUID getUUID() {
            // null → AntiBot(Default) 视为“不在 Tab 列表”
            return null;
        }
    }

    static final class Player extends Living {
    }

    static final class Villager extends Living {
    }

    static class AnimalMob extends Living {
    }

    /** 同时是 Animal，用于验证 Water 的分类优先级。 */
    static final class WaterMob extends AnimalMob {
    }

    static final class Ambient extends Living {
    }

    static final class ArmorStand extends Living {
    }

    static final class World {
        final List<Object> entities = new ArrayList<Object>();

        public Iterable<Object> entitiesForRendering() {
            return entities;
        }
    }

    static final class Minecraft {
        static Minecraft instance;
        public Object player;
        public Object level;

        public static Minecraft getInstance() {
            return instance;
        }
    }

    private GameBridge bridge;
    private TestMapping mapping;
    private World world;
    private Player self;
    private TargetManager manager;

    private NocturneClient client;

    @BeforeEach
    void setUp() {
        TestSupport.resetClient();
        Friends.clear();
        Minecraft.instance = new Minecraft();
        self = new Player();
        self.name = "Self";
        self.position = new Vec(0.0, 0.0, 0.0);
        self.yRot = 0.0f;
        world = new World();
        Minecraft.instance.player = self;
        Minecraft.instance.level = world;
        mapping = new TestMapping(Minecraft.class)
                .classOverride(ClassType.LIVING_ENTITY, Living.class)
                .classOverride(ClassType.PLAYER, Player.class)
                .classOverride(ClassType.MOB, Living.class);
        bridge = new GameBridge(null, mapping);
        client = NocturneClient.boot(null);
        client.setGameBridge(bridge);
        manager = client.targets();
    }

    @AfterEach
    void tearDown() {
        Friends.clear();
        Minecraft.instance = null;
        TestSupport.resetClient();
    }

    private List<TargetManager.Target> targets(boolean players, boolean mobs) {
        TargetManager.Options options = new TargetManager.Options();
        options.range = 5.0;
        options.maxAngle = 180.0;
        options.players = players;
        options.mobs = mobs;
        options.sort = TargetManager.Sort.DISTANCE;
        return manager.list(client, options);
    }

    private Player addPlayer(String name) {
        Player player = new Player();
        player.name = name;
        world.entities.add(player);
        return player;
    }

    private Living addMob(String name) {
        Living mob = new Living();
        mob.name = name;
        world.entities.add(mob);
        return mob;
    }

    @Test
    void friendsAreNeverTargetedButMobsAreUnaffected() {
        Player enemy = addPlayer("Enemy");
        Living cow = addMob("Cow");
        Friends.toggle("Enemy");

        List<TargetManager.Target> found = targets(true, true);

        assertEquals(1, found.size(), "friend must be filtered, mob must stay");
        assertTrue(found.get(0).entity() == cow);
        // 解除好友后立刻可见（好友表是全局共享状态）。
        Friends.toggle("Enemy");
        List<TargetManager.Target> after = targets(true, true);
        assertEquals(2, after.size());
        assertTrue(after.get(0).entity() == enemy || after.get(1).entity() == enemy);
    }

    @Test
    void lineOfSightIsOnByDefaultAndCanBeDisabledPerQuery() {
        Player behindWall = addPlayer("BehindWall");
        behindWall.visible = false;

        assertEquals(0, targets(true, false).size(),
                "default line-of-sight must hide occluded targets");

        TargetManager.Options options = new TargetManager.Options();
        options.range = 5.0;
        options.maxAngle = 180.0;
        options.players = true;
        options.mobs = false;
        options.lineOfSight = false;
        List<TargetManager.Target> throughWall = manager.list(client, options);
        assertEquals(1, throughWall.size());
        assertTrue(throughWall.get(0).entity() == behindWall);
    }

    @Test
    void villagersAnimalsWaterAmbientObeyTheirOwnSwitches() {
        Villager villager = new Villager();
        AnimalMob animal = new AnimalMob();
        WaterMob waterMob = new WaterMob();
        Ambient ambient = new Ambient();
        world.entities.add(villager);
        world.entities.add(animal);
        world.entities.add(waterMob);
        world.entities.add(ambient);
        mapping.classOverride(ClassType.VILLAGER, Villager.class)
                .classOverride(ClassType.ANIMAL, AnimalMob.class)
                .classOverride(ClassType.WATER_MOB, WaterMob.class)
                .classOverride(ClassType.AMBIENT, Ambient.class);

        assertEquals(0, manager.list(client, new TargetManager.Options()).size(),
                "only the default player switch is on");

        TargetManager.Options villagerOptions = new TargetManager.Options();
        villagerOptions.villagers = true;
        List<TargetManager.Target> villagers = manager.list(client, villagerOptions);
        assertEquals(1, villagers.size());
        assertTrue(villagers.get(0).entity() == villager);

        TargetManager.Options animalOptions = new TargetManager.Options();
        animalOptions.animals = true;
        List<TargetManager.Target> animals = manager.list(client, animalOptions);
        assertEquals(1, animals.size());
        assertTrue(animals.get(0).entity() == animal,
                "a WaterMob that is also an Animal must not be controlled by Animals");

        TargetManager.Options waterOptions = new TargetManager.Options();
        waterOptions.water = true;
        List<TargetManager.Target> water = manager.list(client, waterOptions);
        assertEquals(1, water.size());
        assertTrue(water.get(0).entity() == waterMob);

        TargetManager.Options ambientOptions = new TargetManager.Options();
        ambientOptions.ambient = true;
        List<TargetManager.Target> ambientTargets = manager.list(client, ambientOptions);
        assertEquals(1, ambientTargets.size());
        assertTrue(ambientTargets.get(0).entity() == ambient);
    }

    @Test
    void armorStandsAreNeverTargets() {
        ArmorStand armorStand = new ArmorStand();
        world.entities.add(armorStand);
        mapping.classOverride(ClassType.ARMOR_STAND, ArmorStand.class);
        TargetManager.Options options = new TargetManager.Options();
        options.mobs = true;

        assertEquals(0, manager.list(client, options).size());
    }

    @Test
    void distanceUsesEyeToBoundingBox() {
        mapping.classOverride(ClassType.AABB, Box.class);
        self.eye = new Vec(0.0, 1.62, 0.0);
        Living target = addMob("Boxed");
        target.position = new Vec(3.0, 0.0, 0.0);
        Box box = new Box();
        box.minX = 2.5;
        box.minY = 0.0;
        box.minZ = -0.3;
        box.maxX = 3.5;
        box.maxY = 1.8;
        box.maxZ = 0.3;
        target.box = box;
        TargetManager.Options options = new TargetManager.Options();
        options.mobs = true;
        options.maxAngle = 360.0;

        List<TargetManager.Target> found = manager.list(client, options);

        assertEquals(1, found.size());
        assertEquals(2.5, found.get(0).distance(), 1.0e-9,
                "eye-to-AABB distance differs from the 3.0 feet-center distance");
        assertTrue(found.get(0).aimY() >= 0.0 && found.get(0).aimY() <= 1.62);
    }
}
