package dev.nocturne.client.control;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.TestSupport;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.game.TestMapping;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Module;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 目标选择器内建的好友/视线/NPC 过滤。
 *
 * <p>回归：好友过滤只存在于 AimBot 内部、视线由各模块自己判断，KillAura/MultiAura 等会
 * 直接攻击好友；AntiBot 又对动物/怪物也求值，在 Default 模式下把它们全部当成“不在 Tab 的
 * 玩家”误杀。
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

    static class Living {
        public String name = "Target";
        public double health = 20.0;
        public boolean invisible;
        /** hasLineOfSight 的返回值。 */
        public boolean visible = true;
        public Vec position = new Vec(0.0, 0.0, 2.0);
        public float yRot;
        public boolean onGround = true;

        public Vec position() {
            return position;
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
        TestMapping mapping = new TestMapping(Minecraft.class)
                .classOverride(ClassType.LIVING_ENTITY, Living.class)
                .classOverride(ClassType.PLAYER, Player.class);
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

    /** AntiBot 的 Default（不在 Tab 列表）只淘汰玩家，动物/怪物照常可作为目标。 */
    @Test
    void antiBotOnlyFiltersPlayers() {
        Module antiBot = client.modules().get("Anti Bot");
        assertNotNull(antiBot);
        antiBot.setEnabled(true);

        Player npcPlayer = addPlayer("FakePlayer");
        Living cow = addMob("Cow");

        List<TargetManager.Target> found = targets(true, true);

        assertEquals(1, found.size(), "player filtered as bot, mob must survive");
        assertTrue(found.get(0).entity() == cow, "mob must not be judged by AntiBot");
        assertTrue(npcPlayer != found.get(0).entity());
    }
}
