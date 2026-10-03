package dev.noturne.core.load;

import dev.noturne.core.pack.PayloadPack;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PayloadLoaderTest {

    private static final byte[] KEY = new byte[32];

    /** A class shipped inside the payload; must be simple enough to link from the loader. */
    public static class Greeter {
        public String greet() {
            return "hello from memory";
        }
    }

    private static byte[] greeterBytes() throws Exception {
        String resource = "/" + Greeter.class.getName().replace('.', '/') + ".class";
        InputStream in = PayloadLoaderTest.class.getResourceAsStream(resource);
        if (in == null) {
            throw new IllegalStateException("fixture class not found: " + resource);
        }
        try {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    @Test
    void loadsClassFromDecryptedPack() throws Exception {
        Map<String, byte[]> classes = new LinkedHashMap<String, byte[]>();
        classes.put(Greeter.class.getName(), greeterBytes());

        byte[] packed = PayloadPack.pack(classes, KEY);
        Map<String, byte[]> decrypted = PayloadLoader.read(new ByteArrayInputStream(packed), KEY);

        Class<?> loaded = PayloadLoader.entryClass(decrypted, getClass().getClassLoader(), Greeter.class.getName());
        assertNotSame(Greeter.class, loaded, "payload class must be defined by the memory loader, not the app loader");

        Object instance = loaded.getDeclaredConstructor().newInstance();
        Method greet = loaded.getMethod("greet");
        assertEquals("hello from memory", greet.invoke(instance));
    }

    @Test
    void missingClassFallsThroughToParent() throws Exception {
        MemoryClassLoader loader = PayloadLoader.newLoader(new LinkedHashMap<String, byte[]>(), getClass().getClassLoader());
        assertEquals(String.class, loader.loadClass("java.lang.String"));
        assertThrows(ClassNotFoundException.class, () -> loader.loadClass("does.not.Exist"));
    }
}
