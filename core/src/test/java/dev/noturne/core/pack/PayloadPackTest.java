package dev.noturne.core.pack;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PayloadPackTest {

    private static final byte[] KEY = new byte[32];

    private static Map<String, byte[]> sample() {
        Map<String, byte[]> entries = new LinkedHashMap<String, byte[]>();
        entries.put("dev/noturne/client/Bootstrap", "class-bytes-here".getBytes(Charset.forName("UTF-8")));
        entries.put("dev/noturne/client/Module", new byte[]{0, 1, 2, 3, (byte) 0xFF});
        entries.put("metadata.json", "{\"version\":1}".getBytes(Charset.forName("UTF-8")));
        return entries;
    }

    @Test
    void roundTripPreservesEntries() throws IOException {
        Map<String, byte[]> original = sample();
        byte[] packed = PayloadPack.pack(original, KEY);
        Map<String, byte[]> restored = PayloadPack.unpack(packed, KEY);

        assertEquals(original.keySet(), restored.keySet());
        for (String name : original.keySet()) {
            assertArrayEquals(original.get(name), restored.get(name), name);
        }
    }

    @Test
    void packedBytesDoNotLeakPlainNames() throws IOException {
        byte[] packed = PayloadPack.pack(sample(), KEY);
        String asText = new String(packed, Charset.forName("ISO-8859-1"));
        org.junit.jupiter.api.Assertions.assertFalse(asText.contains("Bootstrap"));
        org.junit.jupiter.api.Assertions.assertFalse(asText.contains("metadata.json"));
    }

    @Test
    void wrongKeyIsRejected() throws IOException {
        byte[] packed = PayloadPack.pack(sample(), KEY);
        byte[] otherKey = new byte[32];
        otherKey[0] = 1;
        assertThrows(IOException.class, () -> PayloadPack.unpack(packed, otherKey));
    }

    @Test
    void tamperedBytesAreRejected() throws IOException {
        byte[] packed = PayloadPack.pack(sample(), KEY);
        packed[packed.length - 1] ^= 0x01;
        assertThrows(IOException.class, () -> PayloadPack.unpack(packed, KEY));
    }
}
