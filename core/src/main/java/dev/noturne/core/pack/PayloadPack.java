package dev.noturne.core.pack;

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
 * Self-contained payload container: {@code deflate} + {@code AES-256-GCM}.
 *
 * <p>Layout: {@code MAGIC(4) | VERSION(1) | NONCE(12) | ciphertext+tag}.
 * The plaintext is {@code int count} followed by {@code (UTF name | int length | bytes)} records.
 *
 * <p>Everything is in-memory: the payload never touches disk.
 */
public final class PayloadPack {

    private static final byte[] MAGIC = {'N', 'T', 'P', 'K'};
    private static final byte VERSION = 1;
    private static final int NONCE_LEN = 12;
    private static final int TAG_BITS = 128;
    private static final int MAX_ENTRY = 64 * 1024 * 1024;

    private PayloadPack() {
    }

    public static byte[] pack(Map<String, byte[]> entries, byte[] key) throws IOException {
        byte[] plain = serialize(entries);
        byte[] compressed = deflate(plain);
        byte[] sealed = seal(compressed, key);   // [ nonce(12) | ciphertext + tag ]

        ByteArrayOutputStream out = new ByteArrayOutputStream(sealed.length + 5);
        out.write(MAGIC);
        out.write(VERSION);
        out.write(sealed);
        return out.toByteArray();
    }

    public static Map<String, byte[]> unpack(byte[] packed, byte[] key) throws IOException {
        if (packed == null || packed.length < 4 + 1 + NONCE_LEN + TAG_BITS / 8) {
            throw new IOException("payload too short");
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (packed[i] != MAGIC[i]) {
                throw new IOException("bad magic");
            }
        }
        if (packed[4] != VERSION) {
            throw new IOException("unsupported payload version " + packed[4]);
        }
        byte[] body = new byte[packed.length - 5];
        System.arraycopy(packed, 5, body, 0, body.length);

        byte[] compressed = open(body, key);
        return deserialize(inflate(compressed));
    }

    // ------------------------------------------------------------------ crypto

    private static byte[] seal(byte[] data, byte[] key) throws IOException {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            byte[] nonce = new byte[NONCE_LEN];
            new java.security.SecureRandom().nextBytes(nonce);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce));
            byte[] cipherText = cipher.doFinal(data);
            byte[] out = new byte[NONCE_LEN + cipherText.length];
            System.arraycopy(nonce, 0, out, 0, NONCE_LEN);
            System.arraycopy(cipherText, 0, out, NONCE_LEN, cipherText.length);
            return out;
        } catch (Exception e) {
            throw new IOException("seal failed", e);
        }
    }

    private static byte[] open(byte[] data, byte[] key) throws IOException {
        if (data.length <= NONCE_LEN) {
            throw new IOException("ciphertext too short");
        }
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            byte[] nonce = new byte[NONCE_LEN];
            System.arraycopy(data, 0, nonce, 0, NONCE_LEN);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(TAG_BITS, nonce));
            return cipher.doFinal(data, NONCE_LEN, data.length - NONCE_LEN);
        } catch (Exception e) {
            throw new IOException("open failed", e);
        }
    }

    // ------------------------------------------------------------- compression

    private static byte[] deflate(byte[] data) {
        Deflater deflater = new Deflater(Deflater.BEST_COMPRESSION);
        try {
            deflater.setInput(data);
            deflater.finish();
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, data.length / 2));
            byte[] buf = new byte[8192];
            while (!deflater.finished()) {
                int n = deflater.deflate(buf);
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            deflater.end();
        }
    }

    private static byte[] inflate(byte[] data) throws IOException {
        Inflater inflater = new Inflater();
        try {
            inflater.setInput(data);
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(64, data.length * 2));
            byte[] buf = new byte[8192];
            while (!inflater.finished()) {
                int n = inflater.inflate(buf);
                if (n == 0) {
                    if (inflater.needsInput() || inflater.needsDictionary()) {
                        throw new IOException("truncated payload");
                    }
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

    private static byte[] serialize(Map<String, byte[]> entries) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(buffer);
        out.writeInt(entries.size());
        for (Map.Entry<String, byte[]> e : entries.entrySet()) {
            byte[] value = e.getValue();
            if (value == null || value.length > MAX_ENTRY) {
                throw new IOException("entry out of range: " + e.getKey());
            }
            out.writeUTF(e.getKey());
            out.writeInt(value.length);
            out.write(value);
        }
        out.flush();
        return buffer.toByteArray();
    }

    private static Map<String, byte[]> deserialize(byte[] plain) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(plain));
        int count = in.readInt();
        if (count < 0 || count > 1 << 20) {
            throw new IOException("bad entry count: " + count);
        }
        Map<String, byte[]> out = new LinkedHashMap<String, byte[]>(Math.max(16, count * 2));
        for (int i = 0; i < count; i++) {
            String name = in.readUTF();
            int length = in.readInt();
            if (length < 0 || length > MAX_ENTRY) {
                throw new IOException("bad entry length for " + name + ": " + length);
            }
            byte[] value = new byte[length];
            in.readFully(value);
            out.put(name, value);
        }
        return out;
    }
}
