package dev.nocturne.core.load;

import dev.nocturne.core.pack.PayloadPack;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link PayloadLoader} 与 {@link MemoryClassLoader} 的行为测试：
 * 验证加密 payload 能被解密成类字节码、由内存加载器定义（而非应用加载器），
 * 且表外的类名会正确委派给父加载器。
 */
class PayloadLoaderTest {

    /** 测试用 AES-256 密钥（全 0），payload 只用于往返，不涉及真实安全性。 */
    private static final byte[] KEY = new byte[32];

    /** 随 payload 一起投递的示例类；必须足够简单，才能从内存加载器链接成功。 */
    public static class Greeter {
        /** @return 固定字符串，供断言比对 */
        public String greet() {
            return "hello from memory";
        }
    }

    /**
     * 读取本测试自身编译出的 {@code Greeter.class} 字节，作为 payload 的测试素材。
     *
     * @throws Exception 资源不存在或读取失败
     */
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

    /** 端到端：打包 → 解密 → 加载的类必须由内存加载器定义，并且能正常调用。 */
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

    /** 空表时表外类名委派父加载器；不存在的类仍抛 {@link ClassNotFoundException}。 */
    @Test
    void missingClassFallsThroughToParent() throws Exception {
        MemoryClassLoader loader = PayloadLoader.newLoader(new LinkedHashMap<String, byte[]>(), getClass().getClassLoader());
        assertEquals(String.class, loader.loadClass("java.lang.String"));
        assertThrows(ClassNotFoundException.class, () -> loader.loadClass("does.not.Exist"));
    }
}
