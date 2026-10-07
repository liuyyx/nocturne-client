package dev.nocturne.client.mapping;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link Mapping} 的单元测试：验证 {@link IdentityMapping} 原样透传规范名，
 * 并保证 {@link ClassType} 覆盖核心游戏 API 面且全部为全限定名。
 */
class MappingTest {

    /** 验证恒等映射：类名、方法名、字段名均原样返回，且 {@code isIdentity} 为真。 */
    @Test
    void identityMappingReturnsCanonicalNames() {
        Mapping mapping = new IdentityMapping();

        assertTrue(mapping.isIdentity());
        assertEquals("net.minecraft.client.Minecraft", mapping.className(ClassType.MINECRAFT));
        assertEquals("net.minecraft.client.player.LocalPlayer", mapping.className(ClassType.LOCAL_PLAYER));

        assertEquals("getInstance", mapping.methodName(ClassType.MINECRAFT, "getInstance", "()Lnet/minecraft/client/Minecraft;"));
        assertEquals("player", mapping.fieldName(ClassType.MINECRAFT, "player"));
    }

    /**
     * 验证接口默认实现：候选列表退化为单个规范名/方法名，描述符无记录。
     *
     * <p>{@link IdentityMapping} 不覆写这两个新方法，依赖的正是这里的默认行为。
     */
    @Test
    void defaultCandidatesDegradeToTheSingleCanonicalName() {
        Mapping mapping = new IdentityMapping();

        assertEquals(java.util.Collections.singletonList(mapping.className(ClassType.MINECRAFT)),
                mapping.classNameCandidates(ClassType.MINECRAFT));

        java.util.List<MethodCandidate> candidates =
                mapping.methodCandidates(ClassType.MINECRAFT, "getInstance");
        assertEquals(1, candidates.size());
        assertEquals("getInstance", candidates.get(0).name());
        assertEquals(null, candidates.get(0).descriptor());
        assertTrue(candidates.get(0).equals(new MethodCandidate("getInstance", null)));

        assertEquals(java.util.Collections.singletonList("player"),
                mapping.fieldNameCandidates(ClassType.MINECRAFT, "player"));
        assertEquals(java.util.Collections.singletonList("getInstance"),
                mapping.methodNameCandidates(ClassType.MINECRAFT, "getInstance", null));
    }

    /** 验证 {@link ClassType} 枚举中每个类型的规范名都是全限定（含包名），并抽查一个具体取值。 */
    @Test
    void classTypesCoverTheCoreGameSurface() {
        for (ClassType type : ClassType.values()) {
            assertTrue(type.canonicalName().contains("."), type + " must be fully qualified");
        }
        assertEquals("net.minecraft.client.multiplayer.ClientLevel", ClassType.CLIENT_LEVEL.canonicalName());
    }
}
