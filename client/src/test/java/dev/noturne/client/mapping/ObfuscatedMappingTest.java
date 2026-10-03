package dev.noturne.client.mapping;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ObfuscatedMappingTest {

    private static ObfuscatedMapping minecraft189() {
        return ObfuscatedMapping.load("/mappings-1.8.9.json");
    }

    @Test
    void loadsTheTable() {
        ObfuscatedMapping mapping = minecraft189();
        assertEquals("1.8.9", mapping.version());
        assertFalse(mapping.isIdentity());
        assertTrue(mapping.classCount() > 10, "table should cover a useful surface");
        assertTrue(mapping.describe().contains("1.8.9"));
    }

    @Test
    void translatesKnownClassesFieldsAndMethods() {
        ObfuscatedMapping mapping = minecraft189();

        assertEquals("ave", mapping.className(ClassType.MINECRAFT));
        assertEquals("A", mapping.methodName(ClassType.MINECRAFT, "getInstance", "()Lave;"));
        assertEquals("()Lave;", mapping.methodDescriptor(ClassType.MINECRAFT, "getInstance"));
        assertEquals("h", mapping.fieldName(ClassType.MINECRAFT, "player"));
        assertEquals("f", mapping.fieldName(ClassType.MINECRAFT, "level"));
    }

    @Test
    void unknownMembersFallThroughToTheCanonicalName() {
        ObfuscatedMapping mapping = minecraft189();

        // Not in the table: must stay readable so SRG/Forge builds can still reflect on it.
        assertEquals("com.example.NotInTable", mapping.className("com.example.NotInTable"));
        assertEquals("somethingElse",
                mapping.fieldName(ClassType.MINECRAFT, "somethingElse"));
        assertEquals("unknownMethod",
                mapping.methodName(ClassType.MINECRAFT, "unknownMethod", "()V"));
    }
}
