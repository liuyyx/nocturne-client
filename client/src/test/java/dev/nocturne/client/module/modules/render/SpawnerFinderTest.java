package dev.nocturne.client.module.modules.render;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** SpawnerFinder 的命名空间归一化与白名单匹配。 */
class SpawnerFinderTest {

    @Test
    void normalizeDropsNamespaceAndCase() {
        assertEquals("zombie", SpawnerFinderModule.normalize("minecraft:Zombie"));
        assertEquals("spider", SpawnerFinderModule.normalize(" Spider "));
        assertEquals("", SpawnerFinderModule.normalize(null));
    }

    @Test
    void emptyWhitelistMatchesEveryKnownSpawner() {
        assertTrue(SpawnerFinderModule.matches("minecraft:zombie", Collections.<String>emptyList()));
        assertFalse(SpawnerFinderModule.matches(null, Collections.<String>emptyList()));
    }

    @Test
    void whitelistMatchesNormalizedEntityIds() {
        assertTrue(SpawnerFinderModule.matches("minecraft:zombie",
                Arrays.asList("zombie", "skeleton")));
        assertTrue(SpawnerFinderModule.matches("Zombie",
                Collections.singletonList("minecraft:zombie")));
        assertFalse(SpawnerFinderModule.matches("minecraft:creeper",
                Collections.singletonList("zombie")));
    }
}
