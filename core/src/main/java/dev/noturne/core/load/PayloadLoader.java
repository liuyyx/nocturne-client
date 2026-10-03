package dev.noturne.core.load;

import dev.noturne.core.pack.PayloadPack;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Reads an encrypted payload from wherever it was shipped — a classpath resource, a jar
 * entry, or an arbitrary stream — decrypts it, and turns it into a class loader.
 */
public final class PayloadLoader {

    private PayloadLoader() {
    }

    /** Decrypts a payload from a stream. The stream is fully consumed and not closed. */
    public static Map<String, byte[]> read(InputStream in, byte[] key) throws IOException {
        return PayloadPack.unpack(readFully(in), key);
    }

    /** Decrypts a payload shipped as a classpath resource (path is absolute, e.g. {@code /assets/payload.bin}). */
    public static Map<String, byte[]> readResource(String resource, byte[] key) throws IOException {
        InputStream in = PayloadLoader.class.getResourceAsStream(resource);
        if (in == null) {
            throw new IOException("payload resource not found: " + resource);
        }
        try {
            return read(in, key);
        } finally {
            in.close();
        }
    }

    /** Decrypts a payload shipped as an entry inside a jar (for example the loader's own jar). */
    public static Map<String, byte[]> readJarEntry(File jar, String entry, byte[] key) throws IOException {
        ZipFile zip = new ZipFile(jar);
        try {
            ZipEntry zipEntry = zip.getEntry(entry);
            if (zipEntry == null) {
                throw new IOException("payload entry not found: " + entry + " in " + jar);
            }
            InputStream in = zip.getInputStream(zipEntry);
            try {
                return read(in, key);
            } finally {
                in.close();
            }
        } finally {
            zip.close();
        }
    }

    /** Decrypts a payload from a plain file (used by tooling / tests). */
    public static Map<String, byte[]> readFile(File file, byte[] key) throws IOException {
        InputStream in = new FileInputStream(file);
        try {
            return read(in, key);
        } finally {
            in.close();
        }
    }

    public static MemoryClassLoader newLoader(Map<String, byte[]> classes, ClassLoader parent) {
        return new MemoryClassLoader(parent, classes);
    }

    /** Creates the loader and resolves (without initialising) the payload's entry class. */
    public static Class<?> entryClass(Map<String, byte[]> classes, ClassLoader parent, String mainClass)
            throws ClassNotFoundException {
        return new MemoryClassLoader(parent, classes).loadClass(mainClass);
    }

    private static byte[] readFully(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }
}
