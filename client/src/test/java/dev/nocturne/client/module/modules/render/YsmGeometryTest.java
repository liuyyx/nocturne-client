package dev.nocturne.client.module.modules.render;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class YsmGeometryTest {
    @Test void readsAllGeometryBonesAndCubes() {
        YsmGeometry model = YsmGeometry.parse("{\"minecraft:geometry\":[{\"bones\":["
                + "{\"name\":\"head\",\"cubes\":[{\"origin\":[-4,24,-4],\"size\":[8,8,8]}]},"
                + "{\"name\":\"empty\"}]},{\"bones\":[{\"name\":\"arm\",\"cubes\":["
                + "{\"origin\":[1.5,2,3],\"size\":[4,5,6]}]}]}]}");
        assertEquals(2, model.cubes.length);
        assertEquals("head", model.cubes[0].bone);
        assertEquals(-4, model.cubes[0].x, 0);
        assertEquals(8, model.cubes[0].height, 0);
        assertEquals(1.5, model.cubes[1].x, 0);
        assertEquals(0xFF000000 | ("arm".hashCode() & 0xFFFFFF), model.cubes[1].color);
    }

    @Test void rejectsInvalidGeometryInsteadOfReturningPartialModel() {
        assertThrows(IllegalArgumentException.class, () -> YsmGeometry.parse("{}"));
        assertThrows(IllegalArgumentException.class, () -> YsmGeometry.parse("{\"minecraft:geometry\":[]}"));
        assertThrows(IllegalArgumentException.class, () -> YsmGeometry.parse("{\"minecraft:geometry\":[{\"bones\":[{\"name\":\"a\",\"cubes\":[{\"origin\":[0,0],\"size\":[1,1,1]}]}]}]}"));
        assertThrows(IllegalArgumentException.class, () -> YsmGeometry.parse("{\"minecraft:geometry\":[{\"bones\":[{\"name\":\"a\",\"cubes\":[{\"origin\":[0,0,0],\"size\":[-1,1,1]}]}]}]}"));
        assertThrows(IllegalArgumentException.class, () -> YsmGeometry.parse("not json"));
    }

    @Test void builtinsScaleAndAddHair() {
        YsmGeometry auto = YsmGeometry.builtin("Auto");
        assertEquals(6, auto.cubes.length);
        assertEquals(8, auto.cubes[0].width, 0);
        assertEquals(11.2, YsmGeometry.builtin("ToyotaAe86").cubes[0].width, 1e-9);
        assertEquals(7.2, YsmGeometry.builtin("Boqijiang").cubes[0].width, 1e-9);
        assertEquals(7, YsmGeometry.builtin("Alice").cubes.length);
        assertEquals(7, YsmGeometry.builtin("Yatuoya").cubes.length);
        assertEquals("hair", YsmGeometry.builtin("Alice").cubes[6].bone);
    }

    @Test void filtersBySubstringOrExclusion() {
        assertTrue(YsmGeometry.matches("left_arm", "", false, true));
        assertTrue(YsmGeometry.matches("left_arm", "arm", false, false));
        assertFalse(YsmGeometry.matches("left_arm", "leg", false, false));
        assertFalse(YsmGeometry.matches("left_arm", "arm", true, false));
        assertTrue(YsmGeometry.matches("left_arm", "leg", true, false));
        assertFalse(YsmGeometry.matches("left_arm", "", true, false));
    }

}
