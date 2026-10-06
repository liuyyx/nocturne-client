package dev.nocturne.core.pack;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * 自包含的 payload 容器格式：{@code deflate} 压缩 + {@code AES-256-GCM} 加密。
 *
 * <p>布局为 {@code MAGIC(4) | VERSION(1) | NONCE(12) | 密文+认证标签}。
 * 明文则是 {@code int 条目数} 后跟若干 {@code (UTF 名 | int 长度 | 字节)} 记录。
 *
 * <p>全流程在内存中完成：payload 不落盘。
 */
public final class PayloadPack {

    /** 文件头魔数 {@code NTPK}，用于快速识别与拒绝非 payload 数据。 */
    private static final byte[] MAGIC = {'N', 'T', 'P', 'K'};
    /** 格式版本；不匹配时 {@link #unpack} 直接拒绝。 */
    private static final byte VERSION = 1;
    /** GCM nonce 长度（字节）；AES-GCM 推荐 12 字节。 */
    private static final int NONCE_LEN = 12;
    /** GCM 认证标签长度（位）。 */
    private static final int TAG_BITS = 128;
    /** 单个条目允许的最大字节数（64 MiB），用于防御畸形长度字段。 */
    private static final int MAX_ENTRY = 64 * 1024 * 1024;
    /** 解压后明文允许的最大字节数（512 MiB），用于防御解压炸弹。 */
    private static final long MAX_PLAIN = 512L * 1024 * 1024;

    /**
     * 交付 payload 上限：整个 payload（含 5 字节头 + nonce + 密文）的最大字节数。
     * 缺这一层，真正防线在 PayloadLoader.readFully（仅流入口），绕过它直接调
     * {@link #unpack(byte[], byte[])} 就没有任何最大长度门禁。
     * 与 PayloadLoader.readFully 的 MAX_PAYLOAD_BYTES 对齐：压缩态 256 MiB。
     */
    public static final long MAX_PACKED_BYTES = 256L * 1024 * 1024;

    /** 要求的 AES 密钥长度（字节）——必须是 32 字节的 AES-256。 */
    public static final int KEY_LENGTH = 32;

    /** 工具类，禁止实例化。 */
    private PayloadPack() {
    }

    /**
     * 把条目表打包成一个加密 payload。
     *
     * @param entries 名称到字节内容的映射；值不可为 {@code null} 且不超过 64 MiB
     * @param key     AES-256 密钥，长度必须为 32 字节
     * @return 完整的 payload 字节
     * @throws IOException 序列化失败（条目越界/为空）或加密失败
     */
    public static byte[] pack(Map<String, byte[]> entries, byte[] key) throws IOException {
        requireKey(key);
        byte[] plain = serialize(entries);
        byte[] compressed = deflate(plain);
        // seal 的输出布局为 [ nonce(12) | 密文 + 认证标签 ]
        byte[] sealed = seal(compressed, key);
        ByteArrayOutputStream out = new ByteArrayOutputStream(sealed.length + 5);
        out.write(MAGIC);
        out.write(VERSION);
        out.write(sealed);
        return out.toByteArray();
    }

    /**
     * 校验头部并解密、解压出条目表。
     *
     * @param packed 完整 payload 字节
     * @param key    AES-256 密钥
     * @return 名称到字节内容的映射（保持打包时的顺序）
     * @throws IOException 长度不足、魔数/版本不符、密钥错误、密文被篡改或解压失败
     */
    public static Map<String, byte[]> unpack(byte[] packed, byte[] key) throws IOException {
        requireKey(key);
        if (packed == null || packed.length < 4 + 1 + NONCE_LEN + TAG_BITS / 8) {
            throw new IOException("payload too short");
        }
        if (packed.length > MAX_PACKED_BYTES) {
            throw new IOException("payload too large: " + packed.length + " bytes (limit "
                    + MAX_PACKED_BYTES + ")");
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (packed[i] != MAGIC[i]) {
                throw new IOException("bad magic");
            }
        }
        if (packed[4] != VERSION) {
            throw new IOException("unsupported payload version " + packed[4]);
        }
        // 直接把密文段以偏移形式交给 open，避免为跳过 5 字节头而整份复制一个大数组。
        byte[] compressed = open(packed, 5, packed.length - 5, key);
        return deserialize(inflate(compressed));
    }

    // ------------------------------------------------------------------ crypto

    /** 校验密钥必须为 32 字节；否则 AES 会静默降级成 AES-128/192，违反容器契约。 */
    private static void requireKey(byte[] key) throws IOException {
        if (key == null || key.length != KEY_LENGTH) {
            throw new IOException("AES-256 key must be " + KEY_LENGTH + " bytes, got "
                    + (key == null ? "null" : Integer.toString(key.length)));
        }
    }

    /**
     * 供 GCM 认证用的 AAD：文件头（魔数 + 版本）。头部由此纳入完整性保护——
     * 修改 MAGIC 或 VERSION 的任何一点都会变成 AEADBadTagException，而不是被静默放过
     * （AEADBadTagException 之前不触发，反而被当作「不支持的版本」拒收）。
     */
    private static byte[] headerAad() {
        byte[] aad = new byte[MAGIC.length + 1];
        System.arraycopy(MAGIC, 0, aad, 0, MAGIC.length);
        aad[MAGIC.length] = VERSION;
        return aad;
    }

    private static byte[] seal(byte[] data, byte[] key) throws IOException {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            byte[] nonce = new byte[NONCE_LEN];
            new java.security.SecureRandom().nextBytes(nonce);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(headerAad());
            byte[] cipherText = cipher.doFinal(data);
            byte[] out = new byte[NONCE_LEN + cipherText.length];
            System.arraycopy(nonce, 0, out, 0, NONCE_LEN);
            System.arraycopy(cipherText, 0, out, NONCE_LEN, cipherText.length);
            return out;
        } catch (Exception e) {
            throw new IOException("seal failed", e);
        }
    }

    /**
     * 解密一段 {@code [ nonce | 密文+标签 ]} 数据。
     *
     * @throws IOException 密文过短（非唯一路径），或密钥错误 / 数据被篡改 / 头部被改写
     *   （后三种统一表现为 AEADBadTagException，形如 "open failed"）
     */
    private static byte[] open(byte[] data, byte[] key) throws IOException {
        return open(data, 0, data.length, key);
    }

    /**
     * 解密 {@code data[offset .. offset+length)} 里的 {@code [ nonce | 密文+标签 ]}。
     *
     * <p>提供 offset/length 重载是给 {@link #unpack} 直接传密文段用的——修复前会把
     * {@code packed} 整个复制一份 body 再传给单数的 open，峰值内存放大一整份密文数组。
     *
     * @throws IOException 密文过短，或密钥错误 / 数据被篡改 / 头部被改写
     */
    private static byte[] open(byte[] data, int offset, int length, byte[] key) throws IOException {
        // 仅有 nonce 而没有密文+标签（16 字节）时不可能是合法的 GCM 输出
        if (length < NONCE_LEN + TAG_BITS / 8) {
            throw new IOException("ciphertext too short");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            byte[] nonce = new byte[NONCE_LEN];
            System.arraycopy(data, offset, nonce, 0, NONCE_LEN);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce));
            cipher.updateAAD(headerAad());
            return cipher.doFinal(data, offset + NONCE_LEN, length - NONCE_LEN);
        } catch (javax.crypto.AEADBadTagException e) {
            // 认证失败可能来自：错误密钥 / 数据被篡改 / 头部改写（含降级到未来 v2）
            throw new IOException("authentication failed (bad key, tampered payload, or header tweak)", e);
        } catch (Exception e) {
            throw new IOException("open failed", e);
        }
    }

    /** 用 {@code Deflater.BEST_COMPRESSION} 压缩数据。 */
    private static byte[] deflate(byte[] data) throws IOException {
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        try {
            deflater.setInput(data);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, data.length / 8));
            byte[] buf = new byte[8192];
            while (!deflater.finished()) {
                int n = deflater.deflate(buf);
                if (n == 0) {
                    // 与 inflate 侧对称的「无进展即失败」守卫：finish() 保证正常时 n > 0，
                    // 若未来改为流式喂 deflate() 不再 finish()，一次 Pat 滞回就是死循环
                    throw new IOException("deflate made no progress");
                }
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    /**
     * 解压 {@code deflate} 数据。
     *
     * @throws IOException 数据被截断或不是合法的 deflate 流
     */
    private static byte[] inflate(byte[] data) throws IOException {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(data);
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(1 << 20, Math.max(64, data.length * 2)));
            byte[] buf = new byte[8192];
            long total = 0;
            while (!inflater.finished()) {
                int n = inflater.inflate(buf);
                if (n == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) {
                        throw new IOException("truncated payload");
                    }
                    // 既不产出数据、又不缺输入/字典：流已损坏，继续循环只会自旋不退。
                    throw new IOException("corrupt payload: inflater made no progress");
                }
                total += n;
                if (total > MAX_PLAIN) {
                    throw new IOException("payload expands beyond " + MAX_PLAIN + " bytes");
                }
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } catch (java.util.zip.DataFormatException e) {
            throw new IOException("corrupt payload", e);
        } finally {
            inflater.end();
        }
    }

    // ------------------------------------------------------------- serialization

    /**
     * 序列化条目表为明文字节。
     *
     * @throws IOException 条目表或其名称/值为 {@code null}，条目值超过 {@link #MAX_ENTRY}
     */
    private static byte[] serialize(Map<String, byte[]> entries) throws IOException {
        if (entries == null) {
            throw new IOException("entries must not be null");
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(buffer);
        out.writeInt(entries.size());
        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            byte[] value = e.getValue();
            String name = e.getKey();
            if (name == null) {
                throw new IOException("entry name must not be null");
            }
            if (value == null || value.length > MAX_ENTRY) {
                throw new IOException("entry out of range: " + name);
            }
            out.writeUTF(name);
            out.writeInt(value.length);
            out.write(value);
        }
        out.flush();
        return buffer.toByteArray();
    }

    /**
     * 反序列化明文字节为条目表。
     *
     * @throws IOException 条目数或长度字段越界、出现重复条目名，或数据被截断
     */
    private static Map<String, byte[]> deserialize(byte[] plain) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(plain));
        int count = in.readInt();
        // 上限用于防御恶意 payload 伪造超大的 count 触发巨量预分配
        if (count < 0 || count > 1 << 20) {
            throw new IOException("bad entry count: " + count);
        }
        // 预分配容量与 count 脱钩并封顶：否则 4 字节输入即可强制约 16 MiB 的初始分配。
        Map<String, byte[]> out = new LinkedHashMap<String, byte[]>(Math.min(Math.max(16, count), 256));
        for (int i = 0; i < count; i++) {
            String name = in.readUTF();
            int length = in.readInt();
            if (length < 0 || length > MAX_ENTRY) {
                throw new IOException("bad entry length for entry #" + i + ": " + length);
            }
            // 先校验剩余字节，再分配：6 字节明文即可强制服务端预分配一个 64 MiB 的数组
            // （修复前是先 new byte[length] 再 readFully 抛 EOF，浪费堆）。
            if (length > in.available()) {
                throw new IOException("entry #" + i + " claims " + length
                        + " bytes but only " + in.available() + " remain");
            }
            byte[] value = new byte[length];
            in.readFully(value);
            if (out.put(name, value) != null) {
                // 重复条目名会静默覆盖，使实际条目数与 count 不符；这里显式拒绝。
                throw new IOException("duplicate entry: " + name);
            }
        }
        // 明文必须是 canonical 的：非规范形会让「未来版本加大端元数据」被静默忽略
        // （返回「成功」），也使得 count/长度/重名三项不变性只在无尾随数据时成立。
        if (in.available() != 0) {
            throw new IOException("trailing data after entry table: " + in.available() + " bytes; "
                    + "payload is not canonical");
        }
        return out;
    }
}
