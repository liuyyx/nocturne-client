package dev.nocturne.client.action;

import dev.nocturne.client.TestSupport;
import dev.nocturne.client.bootstrap.GameplayHook;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.game.TestMapping;
import dev.nocturne.client.mapping.ClassType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code ActionApi.attack} 的返回值必须反映 AttackEvent 门控。
 *
 * <p>回归：反射调用成功只说明方法进入了；HitSelect/其它订阅者取消后原版攻击体根本没执行，
 * 但调用方仍被告知 success，于是照常推进 nextAttack、扣 MultiAura 配额——表现为“一直打但
 * 没伤害、真正的攻击窗口反而被推迟”。
 */
class AttackGateTest {

    static final class GateEntity {
    }

    static final class GatePlayer {
    }

    /** 模拟被织入的 MultiPlayerGameMode.attack：先过 GameplayHook 门，再执行原版逻辑。 */
    static final class GateGameMode {
        int calls;

        public void attack(GatePlayer player, GateEntity target) {
            calls++;
            GameplayHook.attack(player, target);
        }
    }

    static final class GateMinecraft {
        static GateMinecraft instance;
        public Object player;
        public Object gameMode;

        public static GateMinecraft getInstance() {
            return instance;
        }
    }

    private GateGameMode gameMode;
    private ActionApi api;

    @BeforeEach
    void setUp() {
        TestSupport.resetClient();
        GameplayHook.install(null);
        GameplayHook.resetAttackAllowed();
        GateMinecraft.instance = new GateMinecraft();
        GateMinecraft.instance.player = new GatePlayer();
        gameMode = new GateGameMode();
        GateMinecraft.instance.gameMode = gameMode;

        TestMapping mapping = new TestMapping(GateMinecraft.class)
                .classOverride(ClassType.MULTI_PLAYER_GAME_MODE, GateGameMode.class);
        GameBridge bridge = new GameBridge(null, mapping);
        List<String> messages = new ArrayList<String>();
        ActionBinder binder = new ActionBinder(contract(), mapping,
                AttackGateTest.class.getClassLoader(), messages::add);
        binder.bind();
        api = binder.api(bridge);
    }

    @AfterEach
    void tearDown() {
        GameplayHook.install(null);
        GameplayHook.resetAttackAllowed();
        GateMinecraft.instance = null;
        TestSupport.resetClient();
    }

    @Test
    void cancelledAttackReturnsFalseToTheCaller() {
        GameplayHook.install(sink(false));

        assertFalse(api.attack(new GateEntity()),
                "a cancelled attack must not look successful to the module");
        assertEquals(1, gameMode.calls, "the game method was still entered");
        assertFalse(GameplayHook.lastAttackAllowed());
    }

    @Test
    void allowedAttackReturnsTrue() {
        GameplayHook.install(sink(true));

        assertTrue(api.attack(new GateEntity()));
        assertEquals(1, gameMode.calls);
        assertTrue(GameplayHook.lastAttackAllowed());
    }

    /** 门控未接线（无 sink）时按放行处理，不能误报失败。 */
    @Test
    void missingGateDefaultsToAllowed() {
        GameplayHook.install(null);

        assertTrue(api.attack(new GateEntity()));
        assertEquals(1, gameMode.calls);
    }

    private static GameplayHook.Sink sink(final boolean allow) {
        return new GameplayHook.Sink() {
            @Override
            public boolean attack(Object player, Object target) {
                return allow;
            }
        };
    }

    private static ActionContract contract() {
        String json = "{\"version\":1,\"actions\":[{"
                + "\"id\":\"attack\",\"signature\":\"boolean attack(Object target)\","
                + "\"receiver\":\"gameMode\",\"operation\":\"method\",\"degrade\":\"disable\","
                + "\"candidates\":[{\"owner\":\"MULTI_PLAYER_GAME_MODE\",\"method\":\"attack\","
                + "\"parameters\":[\"" + GatePlayer.class.getName() + "\","
                + "\"" + GateEntity.class.getName() + "\"]}]}]}";
        return ActionContract.parse(new StringReader(json));
    }
}
