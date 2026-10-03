package dev.noturne.client.mapping;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MappingTest {

    @Test
    void identityMappingReturnsCanonicalNames() {
        Mapping mapping = new IdentityMapping();

        assertTrue(mapping.isIdentity());
        assertEquals("net.minecraft.client.Minecraft", mapping.className(ClassType.MINECRAFT));
        assertEquals("net.minecraft.client.player.LocalPlayer", mapping.className(ClassType.LOCAL_PLAYER));

        assertEquals("getInstance", mapping.methodName(ClassType.MINECRAFT, "getInstance", "()Lnet/minecraft/client/Minecraft;"));
        assertEquals("player", mapping.fieldName(ClassType.MINECRAFT, "player"));
    }

    @Test
    void classTypesCoverTheCoreGameSurface() {
        for (ClassType type : ClassType.values()) {
            assertTrue(type.canonicalName().contains("."), type + " must be fully qualified");
        }
        assertEquals("net.minecraft.client.multiplayer.ClientLevel", ClassType.CLIENT_LEVEL.canonicalName());
    }
}
