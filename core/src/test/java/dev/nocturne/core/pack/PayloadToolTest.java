package dev.nocturne.core.pack;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link PayloadTool} 的打包契约：只收 ASM 条目、产物能用运行期同一密钥解回、坏输入以退出码拒绝。
 *
 * <p>这三条一旦破坏，dist 打出来的 jar 会带着坏载荷分发，而症状要到目标 JVM 里帧钩子静默失效
 * 才出现——所以在构建工具这一侧就把它钉住。
 */
class PayloadToolTest {

    @TempDir
    Path temp;

    /** 造一个含指定条目的 zip。 */
    private File jar(String name, String[] entries, byte[][] contents) throws Exception {
        File file = temp.resolve(name).toFile();
        try (OutputStream raw = Files.newOutputStream(file.toPath());
             ZipOutputStream zip = new ZipOutputStream(raw)) {
            for (int i = 0; i < entries.length; i++) {
                zip.putNextEntry(new ZipEntry(entries[i]));
                zip.write(contents[i]);
                zip.closeEntry();
            }
        }
        return file;
    }

    @Test
    void packsAsmEntriesAndRoundTripsWithRuntimeKey() throws Exception {
        byte[] foo = {1, 2, 3, 4};
        byte[] bar = {9, 8};
        File source = jar("asm.jar",
                new String[]{"org/objectweb/asm/Foo.class", "org/objectweb/asm/tree/Bar.class",
                        "META-INF/MANIFEST.MF", "module-info.class"},
                new byte[][]{foo, bar, {7}, {6}});
        File target = temp.resolve("out/asm.pack").toFile();

        assertEquals(0, PayloadTool.packAsm(source, target));
        assertTrue(target.isFile());

        // 运行期用的是 PayloadKey.asmPayload()：必须能解回，且只有 ASM 条目。
        Map<String, byte[]> unpacked = PayloadPack.unpack(Files.readAllBytes(target.toPath()),
                PayloadKey.asmPayload());
        assertEquals(2, unpacked.size());
        assertArrayEquals(foo, unpacked.get("org.objectweb.asm.Foo"));
        assertArrayEquals(bar, unpacked.get("org.objectweb.asm.tree.Bar"));
    }

    @Test
    void rejectsJarWithoutAsmEntries() throws Exception {
        File source = jar("nope.jar", new String[]{"other/Thing.class"}, new byte[][]{{1}});
        File target = temp.resolve("none.pack").toFile();
        assertEquals(1, PayloadTool.packAsm(source, target));
        assertTrue(!target.exists(), "must not leave a pack file behind on failure");
    }

    @Test
    void rejectsMissingInput() throws Exception {
        File target = temp.resolve("missing.pack").toFile();
        assertEquals(2, PayloadTool.packAsm(temp.resolve("absent.jar").toFile(), target));
    }

    @Test
    void keyIsStableAcrossCalls() {
        assertArrayEquals(PayloadKey.asmPayload(), PayloadKey.asmPayload());
        assertEquals(PayloadPack.KEY_LENGTH, PayloadKey.asmPayload().length);
    }
}
