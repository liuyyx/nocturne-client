package dev.noturne.client.game;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class JniTypesTest {

    private static final ClassLoader LOADER = JniTypesTest.class.getClassLoader();

    @Test
    void parsesPrimitivesAndObjects() {
        Class<?>[] params = JniTypes.parameterTypes("(Ljava/lang/String;III)I", LOADER);
        assertArrayEquals(new Class<?>[]{String.class, int.class, int.class, int.class}, params);
    }

    @Test
    void parsesNoArgAndMixedDescriptors() {
        assertArrayEquals(new Class<?>[0], JniTypes.parameterTypes("()V", LOADER));
        assertArrayEquals(new Class<?>[]{float.class, boolean.class, long.class},
                JniTypes.parameterTypes("(FZJ)D", LOADER));
    }

    @Test
    void parsesArrays() {
        Class<?>[] params = JniTypes.parameterTypes("([B[Ljava/lang/String;)V", LOADER);
        assertEquals(2, params.length);
        assertEquals(byte[].class, params[0]);
        assertEquals(String[].class, params[1]);
    }

    @Test
    void resolvesReturnTypes() {
        assertEquals(int.class, JniTypes.returnType("(Ljava/lang/String;III)I", LOADER));
        assertEquals(void.class, JniTypes.returnType("()V", LOADER));
    }

    @Test
    void rejectsMalformedDescriptorsOrUnknownClasses() {
        assertNull(JniTypes.parameterTypes(null, LOADER));
        assertNull(JniTypes.parameterTypes("I)I", LOADER));
        assertNull(JniTypes.parameterTypes("(Ljava/lang/String", LOADER));
        assertNull(JniTypes.parameterTypes("(Ldoes/not/Exist;)V", LOADER));
    }

    @Test
    void disambiguatesOverloadsSharingAName() throws Exception {
        // FontRenderer in 1.8.9 exposes drawString/getStringWidth both as "a" — descriptor wins.
        Class<?>[] drawString = JniTypes.parameterTypes("(Ljava/lang/String;III)I", LOADER);
        Class<?>[] width = JniTypes.parameterTypes("(Ljava/lang/String;)I", LOADER);
        assertEquals(4, drawString.length);
        assertEquals(1, width.length);

        List<String> unused = java.util.Collections.emptyList();
        assertEquals(0, unused.size());
    }
}
