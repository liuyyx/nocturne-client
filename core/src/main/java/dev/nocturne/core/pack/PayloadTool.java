package dev.nocturne.core.pack;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * 构建期把依赖 jar 转成加密载荷的命令行工具（dist 打包 ASM 载荷时调用）。
 *
 * <p>之所以做成 {@code main} 而不是在 Gradle 里内联加密逻辑：加密必须与运行期
 * （{@link PayloadPack#unpack} + {@code EmbeddedAsmLoader}）用的是同一份实现，
 * 否则格式一改两处不同步，直到真机跑起来才会发现帧钩子静默失效。
 *
 * <p>用法：
 * <pre>
 *   java -cp core.jar dev.nocturne.core.pack.PayloadTool asm-pack &lt;asm.jar&gt; &lt;out.pack&gt;
 * </pre>
 * 生成后会立刻用同一密钥解包并比对条目表，格式或密钥不一致时以非 0 退出——构建期失败，
 * 而不是留到目标 JVM 里。
 */
public final class PayloadTool {

    /** 只收 ASM 自己的类；jar 里可能混有模块描述符等无关条目。 */
    private static final String ASM_ENTRY_PREFIX = "org/objectweb/asm/";

    /** 工具类，禁止实例化。 */
    private PayloadTool() {
    }

    /**
     * 命令行入口。
     *
     * @param args {@code asm-pack <asm.jar> <out.pack>}
     * @throws Exception 参数错误、读 jar 失败、加解密失败或校验不通过时抛出（进程以非 0 退出）
     */
    public static void main(String[] args) throws Exception {
        if (args.length != 3 || !"asm-pack".equals(args[0])) {
            System.err.println("usage: PayloadTool asm-pack <asm.jar> <out.pack>");
            System.exit(2);
            return;
        }
        int code = packAsm(new File(args[1]), new File(args[2]));
        if (code != 0) {
            System.exit(code);
        }
    }

    /**
     * 把 ASM jar 加密打包成载荷文件（{@link #main} 的实际逻辑，单独暴露以便测试）。
     *
     * @param source 含 {@code org/objectweb/asm/**.class} 的 jar
     * @param target 目标 {@code .pack} 文件（父目录会自动创建）
     * @return 退出码：0 成功；1 内容或往返校验失败；2 输入不可用
     * @throws IOException 读 jar 或写文件失败
     */
    static int packAsm(File source, File target) throws IOException {
        if (!source.isFile()) {
            System.err.println("input jar not found: " + source);
            return 2;
        }
        Map<String, byte[]> classes = readClasses(source);
        if (classes.isEmpty()) {
            System.err.println("no " + ASM_ENTRY_PREFIX + " entries in " + source);
            return 1;
        }
        byte[] key = PayloadKey.asmPayload();
        byte[] packed = PayloadPack.pack(classes, key);
        // 立刻自校验：格式/密钥/reflate 任何一环不一致都在这里暴露，而不是在目标 JVM 里。
        Map<String, byte[]> roundTrip = PayloadPack.unpack(packed, key);
        if (roundTrip.size() != classes.size()) {
            System.err.println("payload round-trip mismatch: " + roundTrip.size()
                    + " entries vs " + classes.size() + " packed");
            return 1;
        }
        File parent = target.getParentFile();
        if (parent != null) {
            parent.mkdirs();
        }
        try (OutputStream out = Files.newOutputStream(target.toPath())) {
            out.write(packed);
        }
        System.out.println("packed " + classes.size() + " ASM classes -> " + target
                + " (" + packed.length + " bytes)");
        return 0;
    }

    /** 从 jar 里读出 {@code org/objectweb/asm/**.class}，返回「类名（点号）→ 字节码」。 */
    private static Map<String, byte[]> readClasses(File jar) throws IOException {
        Map<String, byte[]> classes = new LinkedHashMap<String, byte[]>();
        try (InputStream raw = Files.newInputStream(jar.toPath());
             ZipInputStream zip = new ZipInputStream(raw)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName();
                if (entry.isDirectory() || !name.startsWith(ASM_ENTRY_PREFIX)
                        || !name.endsWith(".class")) {
                    continue;
                }
                String className = name.substring(0, name.length() - ".class".length())
                        .replace('/', '.');
                classes.put(className, readAll(zip));
            }
        }
        return classes;
    }

    /** 读尽一个 zip 条目（条目在流结束前不会关闭，因此读到 EOF 即可）。 */
    private static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(4096);
        byte[] buffer = new byte[8192];
        int read;
        while ((read = in.read(buffer)) != -1) {
            out.write(buffer, 0, read);
        }
        return out.toByteArray();
    }
}
