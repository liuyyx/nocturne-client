package dev.noturne.client.mapping;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ObfuscatedMapping} 的单元测试：验证 1.8.9 混淆表的加载（版本、规模、描述串）、
 * 已知类/方法/字段的翻译结果，以及表外条目原样透传的降级契约。
 */
class ObfuscatedMappingTest {

    /** 各测试共用的 1.8.9 混淆映射，从 classpath 资源加载。 */
    private static ObfuscatedMapping minecraft189() {
        return ObfuscatedMapping.load("/mappings-1.8.9.json");
    }

    /** 验证表能正常加载：版本号正确、非恒等映射、覆盖规模足够、描述串包含版本号。 */
    @Test
    void loadsTheTable() {
        ObfuscatedMapping mapping = minecraft189();
        assertEquals("1.8.9", mapping.version());
        assertFalse(mapping.isIdentity());
        assertTrue(mapping.classCount() > 10, "table should cover a useful surface");
        assertTrue(mapping.describe().contains("1.8.9"));
    }

    /** 验证表内条目被翻译为混淆名，且方法描述符会按混淆后的类名重写。 */
    @Test
    void translatesKnownClassesFieldsAndMethods() {
        ObfuscatedMapping mapping = minecraft189();

        assertEquals("ave", mapping.className(ClassType.MINECRAFT));
        assertEquals("A", mapping.methodName(ClassType.MINECRAFT, "getInstance", "()Lave;"));
        assertEquals("()Lave;", mapping.methodDescriptor(ClassType.MINECRAFT, "getInstance"));
        assertEquals("h", mapping.fieldName(ClassType.MINECRAFT, "player"));
        assertEquals("f", mapping.fieldName(ClassType.MINECRAFT, "level"));
    }

    /**
     * 验证降级契约：表外的类/方法/字段一律原样返回可读名称，
     * 这样在 SRG/Forge 等未混淆构建上仍能按规范名反射。
     */
    @Test
    void unknownMembersFallThroughToTheCanonicalName() {
        ObfuscatedMapping mapping = minecraft189();

        // 表中不存在：必须保持可读名称，SRG/Forge 构建才能继续按规范名反射。
        assertEquals("com.example.NotInTable", mapping.className("com.example.NotInTable"));
        assertEquals("somethingElse",
                mapping.fieldName(ClassType.MINECRAFT, "somethingElse"));
        assertEquals("unknownMethod",
                mapping.methodName(ClassType.MINECRAFT, "unknownMethod", "()V"));
    }
}
