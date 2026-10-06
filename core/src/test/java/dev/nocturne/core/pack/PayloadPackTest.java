package dev.nocturne.core.pack;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PayloadPack} 容器格式的测试：往返一致性、明文不泄漏、错误密钥/篡改字节被拒绝，
 * 以及畸形输入（密钥长度、重复条目、伪造条目数、损坏的 deflate 流）必须被拒绝而不是静默接受。
 */
class PayloadPackTest {

    /** 测试用 AES-256 密钥（全 0）。 */
    private static final byte[] KEY = new byte[32];

    /** 构造一组固定的样本条目，覆盖文本类名、含高位字节的二进制内容与非类名文件。 */
    private static Map<String, byte[]> sample() {
        Map<String, byte[]> entries = new LinkedHashMap<String, byte[]>();
        entries.put("dev/nocturne/client/Bootstrap", "class-bytes-here".getBytes(Charset.forName("UTF-8")));
        entries.put("dev/nocturne/client/Module", new byte[]{0, 1, 2, 3, (byte) 0xFF});
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

    /**
     * L-22 回归：AES 密钥必须是 32 字节。
     *
     * <p>传入 16/24 字节时若被静默降级为 AES-128/192，就违反了容器的「AES-256」契约。
     */
    @Test
    void nonAes256KeysAreRejected() throws IOException {
        byte[] packed = PayloadPack.pack(sample(), KEY);
        assertThrows(IOException.class, () -> PayloadPack.pack(sample(), new byte[16]));
        assertThrows(IOException.class, () -> PayloadPack.pack(sample(), new byte[24]));
        assertThrows(IOException.class, () -> PayloadPack.pack(sample(), null));
        assertThrows(IOException.class, () -> PayloadPack.unpack(packed, new byte[16]));
    }

    /** 空条目名 / null 条目值必须被拒绝（否则要么 NPE 要么写出无法解析的载荷） */
    @Test
    void malformedEntriesAreRejected() throws IOException {
        assertThrows(IOException.class, () -> PayloadPack.pack(null, KEY));

        Map<String, byte[]> nullValue = new LinkedHashMap<String, byte[]>();
        nullValue.put("x", null);
        assertThrows(IOException.class, () -> PayloadPack.pack(nullValue, KEY));

        Map<String, byte[]> nullName = new LinkedHashMap<String, byte[]>();
        nullName.put(null, new byte[]{1});
        assertThrows(IOException.class, () -> PayloadPack.pack(nullName, KEY));
    }

    /**
     * L-22 回归：明文里重复的条目名必须被拒绝，而不是静默覆盖。
     *
     * <p>正确性要求「实际条目数 == count」，重复名会让两者不一致。
     */
    @Test
    void duplicateEntryNamesAreRejected() throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(buffer);
        out.writeInt(2);
        out.writeUTF("dup");
        out.writeInt(1);
        out.write(11);
        out.writeUTF("dup");
        out.writeInt(1);
        out.write(22);
        out.flush();

        byte[] packed = wrap(seal(deflate(buffer.toByteArray()), KEY));
        assertThrows(IOException.class, () -> PayloadPack.unpack(packed, KEY));
    }

    /**
     * L-22 回归：伪造超大的条目数必须在预分配之前被拒绝（否则 4 字节输入即可强制约 16 MiB 分配）。
     */
    @Test
    void absurdEntryCountIsRejectedBeforePreallocation() throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(buffer);
        out.writeInt((1 << 20) + 1);   // 超过上限
        out.flush();

        byte[] packed = wrap(seal(deflate(buffer.toByteArray()), KEY));
        assertThrows(IOException.class, () -> PayloadPack.unpack(packed, KEY));
    }

    /** 声称有很多条目但正文缺失时，因数据截断而失败（而不是 OOM 或静默返回半截表） */
    @Test
    void truncatedEntryTableIsRejected() throws Exception {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(buffer);
        out.writeInt(1000);
        out.flush();

        byte[] packed = wrap(seal(deflate(buffer.toByteArray()), KEY));
        assertThrows(IOException.class, () -> PayloadPack.unpack(packed, KEY));
    }

    /**
     * L-22 回归：inflate 主循环遇损坏流必须抛 IOException（而不是自旋不退或静默返回空）。
     */
    @Test
    void corruptDeflateStreamIsRejected() throws Exception {
        Method inflate = PayloadPack.class.getDeclaredMethod("inflate", byte[].class);
        inflate.setAccessible(true);

        InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                () -> inflate.invoke(null, (Object) new byte[]{1, 2, 3}));
        assertTrue(failure.getCause() instanceof IOException, "corrupt deflate must raise IOException");
    }

    /** 被截断的合法 deflate 流也必须抛 IOException */
    @Test
    void truncatedDeflateStreamIsRejected() throws Exception {
        Method deflate = PayloadPack.class.getDeclaredMethod("deflate", byte[].class);
        deflate.setAccessible(true);
        Method inflate = PayloadPack.class.getDeclaredMethod("inflate", byte[].class);
        inflate.setAccessible(true);

        byte[] payload = new byte[4096];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i * 31);
        }
        byte[] compressed = (byte[]) deflate.invoke(null, (Object) payload);
        byte[] truncated = Arrays.copyOf(compressed, Math.max(1, compressed.length / 2));

        InvocationTargetException failure = assertThrows(InvocationTargetException.class,
                () -> inflate.invoke(null, (Object) truncated));
        assertTrue(failure.getCause() instanceof IOException, "truncated deflate must raise IOException");
    }

    // ------------------------------------------------------------- 测试辅助（最小反射宿主）

    /** 调用私有的 {@code deflate(byte[])} */
    private static byte[] deflate(byte[] plain) throws Exception {
        Method method = PayloadPack.class.getDeclaredMethod("deflate", byte[].class);
        method.setAccessible(true);
        return (byte[]) method.invoke(null, (Object) plain);
    }

    /** 调用私有的 {@code seal(byte[], byte[])} */
    private static byte[] seal(byte[] data, byte[] key) throws Exception {
        Method method = PayloadPack.class.getDeclaredMethod("seal", byte[].class, byte[].class);
        method.setAccessible(true);
        return (byte[]) method.invoke(null, data, key);
    }

    /** 按 {@code MAGIC(4) | VERSION(1) | 密文} 组装完整 payload */
    private static byte[] wrap(byte[] sealed) {
        byte[] magic = {'N', 'T', 'P', 'K'};
        byte[] out = new byte[magic.length + 1 + sealed.length];
        System.arraycopy(magic, 0, out, 0, magic.length);
        out[magic.length] = 1;
        System.arraycopy(sealed, 0, out, magic.length + 1, sealed.length);
        return out;
    }
}
