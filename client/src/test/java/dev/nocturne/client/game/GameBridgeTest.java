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
     * 验证混淆映射下候选类名把映射名排在首位、规范名压尾：四套加载器命名空间按
     * {@code vanilla → fabric → forge → neoforge} 展开（1.8.9 无 NeoForge），
     * 规范名作为最后的兜底，保证不同加载方式下的客户端都能命中。
     */
    @Test
    void obfuscatedCandidatesPreferTheMappedName() {
        GameBridge bridge = new GameBridge(null, ObfuscatedMapping.load("/mappings-1.8.9.json"));
        assertArrayEquals(
                new String[]{"ave", "net.minecraft.class_310", "net.minecraft.client.Minecraft"},
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

    /** 把 {@code pick} 记成"零参"描述符的映射：钉住描述符 arity 与实参不符时的降级。 */
    static final class ZeroArityDescriptorMapping implements Mapping {
        @Override
        public String describe() {
            return "zero-arity-descriptor";
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
            return canonicalName;
        }

        @Override
        public String methodDescriptor(ClassType owner, String canonicalName) {
            return "pick".equals(canonicalName) ? "()Ljava/lang/String;" : null;
        }

        @Override
        public boolean isIdentity() {
            return false;
        }
    }

    /** 仿 {@code Component}：取文本入口是无参的 {@code getString()}。 */
    public static final class FakeComponent {
        private final String text;

        FakeComponent(String text) {
            this.text = text;
        }

        /** 与真实 {@code Component#getString()} 同名同形（无参、返回 String）。 */
        public String getString() {
            return text;
        }
    }

    /**
     * 回归（High-8）：{@code Entity#getName} 返回的是聊天组件而不是字符串，必须经映射表读出文本。
     *
     * <p>原实现用 {@code instanceof String} 判定，于是 NameTags / ItemEsp / Esp 在任何版本都判为
     * "读不到名字"——标签一个字符都画不出来，而计数器照常增长（看起来像渲染坏了）。
     */
    @Test
    void readsChatComponentsAsPlainText() {
        GameBridge bridge = new GameBridge(null, new TestMapping(FakeMinecraft.class));

        assertEquals("Steve", bridge.componentText(new FakeComponent("Steve")),
                "a component must be read through its text accessor");
        assertEquals("plain", bridge.componentText("plain"),
                "a plain String passes through unchanged (unobfuscated builds)");
        assertNull(bridge.componentText(null));
        assertNull(bridge.componentText(new Object()),
                "an object without the component shape must yield null, not a bogus name");
    }

    /**
     * 回归（High-3）：表里记的描述符只覆盖该规范方法的一种签名。本次实参个数与它不符时，必须退化为
     * 按实参推断——否则会找一个 arity 不匹配的重载、调用失败后静默返回 {@code null}。
     *
     * <p>真机症状：{@code Entity#getEyePosition} 在 1.8.9–1.16.5 的签名是 {@code (F)}，调用方若按
     * 26.x 的零参形态调用，世界覆盖层就整帧不画，日志里只有一句"读不到 getEyePosition"。
     */
    @Test
    void fallsBackToArgumentInferenceWhenStoredDescriptorArityDiffers() {
        GameBridge bridge = new GameBridge(null, new ZeroArityDescriptorMapping());
        Overloaded target = new Overloaded();

        assertEquals("none", bridge.callMapped(target, ClassType.LOCAL_PLAYER, "pick"),
                "a zero-arg call still matches the stored zero-arg descriptor");
        assertEquals("int:5", bridge.callMapped(target, ClassType.LOCAL_PLAYER, "pick", 5),
                "arity mismatch must fall back to argument inference instead of returning null");
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
