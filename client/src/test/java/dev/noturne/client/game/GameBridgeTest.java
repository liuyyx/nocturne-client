package dev.noturne.client.game;

import dev.noturne.client.mapping.IdentityMapping;
import dev.noturne.client.mapping.ObfuscatedMapping;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link GameBridge} 的单元测试：验证反射桥按映射表生成候选类名、类路径上没有游戏类时
 * 降级为「未解析」而不抛异常，以及字段读取严格走映射表（而非硬编码混淆名）。
 */
class GameBridgeTest {

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
        assertNull(bridge.readField(null, dev.noturne.client.mapping.ClassType.MINECRAFT, "player"));
    }

    /**
     * 验证字段名来自映射表：传入持有 {@code player} 字段的替身对象即可读到值，
     * 而表中不存在的字段名返回 null（不向调用方抛出反射异常）。
     */
    @Test
    void readsFieldsThroughTheMapping() {
        GameBridge bridge = new GameBridge(null, new IdentityMapping());
        // 尚未持有游戏对象，先用替身对象验证字段读取链路本身。
        Object holder = new Holder();
        // 读取应命中 Holder.player 的值，说明字段名确实由映射表给出。
        Object value = bridge.readField(holder, dev.noturne.client.mapping.ClassType.MINECRAFT, "player");
        assertTrue(value instanceof String);
        assertNull(bridge.readField(holder, dev.noturne.client.mapping.ClassType.MINECRAFT, "nothing"));
    }

    /** 游戏对象的替身：仅暴露一个由映射表命名的字段，用于验证字段读取链路。 */
    static final class Holder {
        @SuppressWarnings("unused")
        private final String player = "local-player";
    }
}
