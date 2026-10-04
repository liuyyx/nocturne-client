package dev.noturne.core.pack;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link PayloadPack} 容器格式的测试：往返一致性、明文不泄漏、以及错误密钥/篡改字节被拒绝。
 */
class PayloadPackTest {

    /** 测试用 AES-256 密钥（全 0）。 */
    private static final byte[] KEY = new byte[32];

    /** 构造一组固定的样本条目，覆盖文本类名、含高位字节的二进制内容与非类名文件。 */
    private static Map<String, byte[]> sample() {
        Map<String, byte[]> entries = new LinkedHashMap<String, byte[]>();
        entries.put("dev/noturne/client/Bootstrap", "class-bytes-here".getBytes(Charset.forName("UTF-8")));
        entries.put("dev/noturne/client/Module", new byte[]{0, 1, 2, 3, (byte) 0xFF});
        entries.put("metadata.json", "{\"version\":1}".getBytes(Charset.forName("UTF-8")));
        return entries;
    }

    /** 打包再解包后，条目集合与每个条目的字节都应与原始完全一致。 */
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

    /** 密文中不应出现明文条目名（说明名称确实被加密，而非仅压缩）。 */
    @Test
    void packedBytesDoNotLeakPlainNames() throws IOException {
        byte[] packed = PayloadPack.pack(sample(), KEY);
        String asText = new String(packed, Charset.forName("ISO-8859-1"));
        org.junit.jupiter.api.Assertions.assertFalse(asText.contains("Bootstrap"));
        org.junit.jupiter.api.Assertions.assertFalse(asText.contains("metadata.json"));
    }

    /** 使用错误密钥解密必须失败（GCM 认证标签校验不通过）。 */
    @Test
    void wrongKeyIsRejected() throws IOException {
        byte[] packed = PayloadPack.pack(sample(), KEY);
        byte[] otherKey = new byte[32];
        otherKey[0] = 1;
        assertThrows(IOException.class, () -> PayloadPack.unpack(packed, otherKey));
    }

    /** 篡改密文最后一个字节必须被 GCM 认证标签检测到并失败。 */
    @Test
    void tamperedBytesAreRejected() throws IOException {
        byte[] packed = PayloadPack.pack(sample(), KEY);
        packed[packed.length - 1] ^= 0x01;
        assertThrows(IOException.class, () -> PayloadPack.unpack(packed, KEY));
    }
}
