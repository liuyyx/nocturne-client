package dev.nocturne.client.game;

import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.mapping.IdentityMapping;
import dev.nocturne.client.mapping.Mapping;
import dev.nocturne.client.mapping.ObfuscatedMapping;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GameBridge} 的单元测试：验证反射桥按映射表生成候选类名、类路径上没有游戏类时
 * 降级为「未解析」而不抛异常、字段读取严格走映射表，以及恒等映射下带参调用按实参推导形参。
 */
class GameBridgeTest {

    /**
     * 字段名重命名映射：把 {@code player} 映射成 {@code localPlayer}。
     *
     * <p>用于证明字段读取确实经过映射表——硬编码 {@code "player"} 的实现会读到诱饵字段的值。
     */
    static final class RenamingMapping implements Mapping {
        @Override
        public String describe() {
            return "renaming";
        }

        @Override
        public String className(ClassType type) {
            return type.canonicalName();
        }

        @Override
        public String methodName(ClassType owner, String canonicalName, String descriptor) {
            return canonicalName;
        }

        @Override
        public String fieldName(ClassType owner, String canonicalName) {
            return "player".equals(canonicalName) ? "localPlayer" : canonicalName;
        }

        @Override
        public boolean isIdentity() {
            return false;
        }
    }

    /** 同时持有诱饵字段 {@code player} 与真实映射字段 {@code localPlayer} 的宿主 */
    static final class Holder {
        @SuppressWarnings("unused")
        private final String player = "hardcoded-decoy";
        @SuppressWarnings("unused")
        private final String localPlayer = "mapped-value";
    }

    /** 带多个同名重载的宿主，用于验证恒等映射下按实参推导形参 */
    static final class Overloaded {
        @SuppressWarnings("unused")
        String pick() {
            return "none";
        }

        @SuppressWarnings("unused")
        String pick(int value) {
            return "int:" + value;
        }

        @SuppressWarnings("unused")
        String pick(String value) {
            return "str:" + value;
        }

        @SuppressWarnings("unused")
        String respawnPlayer(int times) {
            return "respawn:" + times;
        }
    }

    /**
     * 验证混淆映射下候选类名把映射名排在首位：反编译产物的混淆名优先尝试，
     * 规范名作为兜底，保证不同打包方式的客户端都能命中。
     */
    @Test
    void obfuscatedCandidatesPreferTheMappedName() {
        GameBridge bridge = new GameBridge(null, ObfuscatedMapping.load("/mappings-1.8.9.json"));
        assertArrayEquals(
                new String[]{"ave", "net.minecraft.client.Minecraft"},
                bridge.minecraftClassCandidates());
    }

    /** 验证恒等映射下候选类名只有规范名一个（无混淆名可试）。 */
    @Test
    void identityCandidatesAreJustTheCanonicalName() {
        GameBridge bridge = new GameBridge(null, new IdentityMapping());
        assertArrayEquals(
                new String[]{"net.minecraft.client.Minecraft"},
                bridge.minecraftClassCandidates());
    }

    /** 验证解析不到游戏类时的降级契约：isResolved/resolve 为假，各访问器返回 null 而非抛异常。 */
    @Test
    void unresolvedBridgeDegradesToNullInsteadOfThrowing() {
        GameBridge bridge = new GameBridge(null, new IdentityMapping());
        assertFalse(bridge.isResolved());
        assertFalse(bridge.resolve(), "no game on this classpath");
        assertNull(bridge.minecraft());
        assertNull(bridge.player());
        assertFalse(bridge.inWorld());
        assertNull(bridge.readField(null, ClassType.MINECRAFT, "player"));
    }

    /**
     * L-08 回归：字段名必须真正来自映射表。
     *
     * <p>宿主同时有 {@code player}（诱饵）与 {@code localPlayer}（映射目标）；若实现硬编码
     * {@code "player"}，读到的就是诱饵值，本用例必失败。
     */
    @Test
    void readsFieldsThroughTheMappingNotByHardcodedName() {
        GameBridge bridge = new GameBridge(null, new RenamingMapping());
        Object value = bridge.readField(new Holder(), ClassType.MINECRAFT, "player");
        assertEquals("mapped-value", value, "field lookup must honour the mapping table");
        assertNull(bridge.readField(new Holder(), ClassType.MINECRAFT, "nothing"));
    }

    /**
     * H-10 回归：恒等映射（无描述符）下带参 {@code callMapped} 必须按实参推导形参，
     * 命中带参重载；硬编码零参查找会让所有带参调用返回 null。
     */
    @Test
    void identityMappingInfersParametersFromArguments() {
        GameBridge bridge = new GameBridge(null, new IdentityMapping());
        Overloaded target = new Overloaded();

        assertEquals("int:5", bridge.callMapped(target, ClassType.LOCAL_PLAYER, "pick", 5));
        assertEquals("str:x", bridge.callMapped(target, ClassType.LOCAL_PLAYER, "pick", "x"));
        assertEquals("none", bridge.callMapped(target, ClassType.LOCAL_PLAYER, "pick"));
        assertEquals("respawn:3", bridge.callMapped(target, ClassType.LOCAL_PLAYER, "respawnPlayer", 3));
    }

    /** 恒等映射下无法推导（名字不存在）时返回 null，而不是抛异常 */
    @Test
    void identityMappingReturnsNullForUnknownMethods() {
        GameBridge bridge = new GameBridge(null, new IdentityMapping());
        assertNull(bridge.callMapped(new Overloaded(), ClassType.LOCAL_PLAYER, "doesNotExist"));
        assertNull(bridge.callMapped(null, ClassType.LOCAL_PLAYER, "pick", 1));
    }

    /** 通过替身游戏类验证解析链路：resolve 成功、player 读取经映射、带参调用可命中 */
    @Test
    void resolvesAgainstAFakeGameClassAndReadsItsFields() {
        ClassLoader saved = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(getClass().getClassLoader());
        try {
            FakeMinecraft.reset();
            FakePlayer player = new FakePlayer();
            FakeMinecraft.instance.player = player;
            GameBridge bridge = new GameBridge(null, new TestMapping(FakeMinecraft.class));

            assertTrue(bridge.resolve(), "fake Minecraft must resolve");
            assertTrue(bridge.isResolved());
            assertEquals(FakeMinecraft.class, bridge.minecraftClass());
            assertEquals(player, bridge.player());
            assertTrue(bridge.inWorld());

            player.isDead = true;
            assertEquals(Boolean.TRUE, bridge.readField(player, ClassType.ENTITY, "isDead"));
        } finally {
            Thread.currentThread().setContextClassLoader(saved);
        }
    }
}
