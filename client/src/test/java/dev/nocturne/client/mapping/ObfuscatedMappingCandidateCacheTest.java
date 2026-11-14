package dev.nocturne.client.mapping;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * {@link ObfuscatedMapping} 候选查询记忆化（M-73）的单元测试：映射表加载后不可变，
 * 同一（类/成员）的候选列表必须返回同一实例，避免扫描类模块的每格热循环重建候选集合。
 */
class ObfuscatedMappingCandidateCacheTest {

    /** 最小可用 fixture：一个类、一个带描述符的方法、一个 absent 方法、一个字段。 */
    private static ObfuscatedMapping fixture() {
        return ObfuscatedMapping.fromJson("{\"version\":\"1.8.9\",\"classes\":{"
                + "\"net/minecraft/client/Minecraft\":{"
                + "\"names\":{\"vanilla\":\"ave\"},"
                + "\"methods\":{"
                + "\"getInstance\":{\"names\":{\"vanilla\":\"N\"},\"signatures\":{\"vanilla\":\"()Lave;\"}},"
                + "\"gone\":{\"absent\":true}"
                + "},"
                + "\"fields\":{\"player\":{\"names\":{\"vanilla\":\"h\"}}}"
                + "}}}");
    }

    @Test
    void repeatedQueriesReturnTheSameInstance() {
        ObfuscatedMapping mapping = fixture();
        assertSame(mapping.methodCandidates(ClassType.MINECRAFT, "getInstance"),
                mapping.methodCandidates(ClassType.MINECRAFT, "getInstance"));
        assertSame(mapping.fieldNameCandidates(ClassType.MINECRAFT, "player"),
                mapping.fieldNameCandidates(ClassType.MINECRAFT, "player"));
        assertSame(mapping.classNameCandidates(ClassType.MINECRAFT),
                mapping.classNameCandidates(ClassType.MINECRAFT));
    }

    @Test
    void absentQueriesAreMemoizedAndStillFallbackToCanonicalName() {
        ObfuscatedMapping mapping = fixture();
        assertSame(mapping.methodCandidates(ClassType.MINECRAFT, "gone"),
                mapping.methodCandidates(ClassType.MINECRAFT, "gone"));
        assertEquals("gone", mapping.methodName(ClassType.MINECRAFT, "gone", null));
        assertEquals(java.util.Collections.singletonList("gone"),
                mapping.methodNameCandidates(ClassType.MINECRAFT, "gone", null));
    }

    @Test
    void uncoveredClassesAreMemoizedAndStillFallbackToCanonicalName() {
        ObfuscatedMapping mapping = fixture();
        assertSame(mapping.classNameCandidates(ClassType.LEVEL),
                mapping.classNameCandidates(ClassType.LEVEL));
        assertEquals(java.util.Collections.singletonList(ClassType.LEVEL.canonicalName()),
                mapping.classNameCandidates(ClassType.LEVEL));
    }
}
