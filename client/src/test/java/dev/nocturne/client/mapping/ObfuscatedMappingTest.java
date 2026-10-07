package dev.nocturne.client.mapping;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ObfuscatedMapping} 的单元测试：验证 1.8.9 混淆表的加载（版本、规模、描述串）、
 * 已知类/方法/字段的翻译结果、表外条目原样透传的降级契约，
 * 以及表内混淆类名在真实 1.8.9 客户端 jar 中确实存在（L-20）。
 */
class ObfuscatedMappingTest {

    /** 各测试共用的 1.8.9 混淆映射，从 classpath 资源加载。 */
    private static ObfuscatedMapping minecraft189() {
        return ObfuscatedMapping.load("/mappings-1.8.9.json");
    }

    /** 验证表能正常加载：版本号正确、非恒等映射、覆盖规模足够、描述串包含版本号。 */
    @Test
    void loadsTheTable() {
        ObfuscatedMapping mapping = minecraft189();
        assertEquals("1.8.9", mapping.version());
        assertFalse(mapping.isIdentity());
        assertTrue(mapping.classCount() > 10, "table should cover a useful surface");
        assertTrue(mapping.describe().contains("1.8.9"));
    }

    /** 验证表内条目被翻译为混淆名，且方法描述符会按混淆后的类名重写。 */
    @Test
    void translatesKnownClassesFieldsAndMethods() {
        ObfuscatedMapping mapping = minecraft189();

        assertEquals("ave", mapping.className(ClassType.MINECRAFT));
        assertEquals("A", mapping.methodName(ClassType.MINECRAFT, "getInstance", "()Lave;"));
        assertEquals("()Lave;", mapping.methodDescriptor(ClassType.MINECRAFT, "getInstance"));
        assertEquals("h", mapping.fieldName(ClassType.MINECRAFT, "player"));
        assertEquals("f", mapping.fieldName(ClassType.MINECRAFT, "level"));
    }

    /**
     * 验证降级契约：表外的类/方法/字段一律原样返回可读名称，
     * 这样在 SRG/Forge 等未混淆构建上仍能按规范名反射。
     */
    @Test
    void unknownMembersFallThroughToTheCanonicalName() {
        ObfuscatedMapping mapping = minecraft189();

        // 表中不存在：必须保持可读名称，SRG/Forge 构建才能继续按规范名反射。
        assertEquals("com.example.NotInTable", mapping.className("com.example.NotInTable"));
        assertEquals("somethingElse",
                mapping.fieldName(ClassType.MINECRAFT, "somethingElse"));
        assertEquals("unknownMethod",
                mapping.methodName(ClassType.MINECRAFT, "unknownMethod", "()V"));
    }

    /**
     * L-20 回归：映射表里的每一个混淆类名都必须能在真实 1.8.9 客户端 jar 中找到对应条目。
     *
     * <p>这是 1.8.9 路径上所有反射调用的前提——表里写错一个名字，对应功能就静默失效。
     * 若本机没有 {@code analysis/client-1.8.9.jar}，用例按「假设不成立」跳过（并留痕），
     * 不作为失败。
     */
    @Test
    void mappedClassNamesExistInTheReal189Jar() throws Exception {
        File jar = locateClientJar();
        Assumptions.assumeTrue(jar != null,
                "analysis/client-1.8.9.jar 不可用，跳过真实混淆名校验（需要 1.8.9 客户端 jar）");

        Set<String> entries = listClassEntries(jar);
        assertTrue(entries.contains("ave.class"), "sanity: 1.8.9 Minecraft must be ave");
        assertTrue(entries.contains("bfk.class"), "sanity: 1.8.9 EntityRenderer must be bfk");

        JsonObject classes = readClasses();
        int checked = 0;
        for (Map.Entry<String, JsonElement> entry : classes.entrySet()) {
            JsonObject info = entry.getValue().getAsJsonObject();
            JsonObject names = info.getAsJsonObject("names");
            if (names == null || !names.has("vanilla")) {
                // 标记为 absent 的条目（该版本不存在此类）没有运行期名，跳过
                continue;
            }
            String canonical = entry.getKey();
            // vanilla 命名空间即该版本的原版混淆名，必须能在真实 jar 里找到。
            String mapped = names.get("vanilla").getAsString();
            assertTrue(entries.contains(mapped + ".class"),
                    "mapping " + canonical + " -> " + mapped
                            + " has no matching entry in the real 1.8.9 jar");
            checked++;
        }
        assertTrue(checked > 10, "table should contain a non-trivial number of classes");
    }

    /** 从 classpath 资源解析映射 JSON 的 classes 节点。 */
    private static JsonObject readClasses() throws Exception {
        InputStream in = ObfuscatedMappingTest.class.getResourceAsStream("/mappings-1.8.9.json");
        if (in == null) {
            throw new IllegalStateException("mappings-1.8.9.json not found on classpath");
        }
        try {
            InputStreamReader reader = new InputStreamReader(in, Charset.forName("UTF-8"));
            JsonObject root = JsonParser.parseReader(reader).getAsJsonObject();
            return root.getAsJsonObject("classes");
        } finally {
            in.close();
        }
    }

    /** 在若干候选相对/绝对位置中寻找真实 1.8.9 客户端 jar。 */
    private static File locateClientJar() {
        String explicit = System.getProperty("nocturne.analysis.jar");
        String[] candidates = {
                explicit,
                "analysis/client-1.8.9.jar",
                "../analysis/client-1.8.9.jar",
                "../../analysis/client-1.8.9.jar",
        };
        for (String candidate : candidates) {
            if (candidate == null) {
                continue;
            }
            File file = new File(candidate);
            if (file.isFile()) {
                return file;
            }
        }
        return null;
    }

    /** 列出 jar 中全部 {@code .class} 条目名。 */
    private static Set<String> listClassEntries(File jar) throws Exception {
        Set<String> entries = new HashSet<String>();
        ZipFile zip = new ZipFile(jar);
        try {
            Enumeration<? extends ZipEntry> it = zip.entries();
            while (it.hasMoreElements()) {
                ZipEntry entry = it.nextElement();
                if (entry.getName().endsWith(".class")) {
                    entries.add(entry.getName());
                }
            }
        } finally {
            zip.close();
        }
        return entries;
    }

    /**
     * 成员级 {@code absent}：该版本没有这个成员时回退为规范名，并且**只提示一次**。
     *
     * <p>用真实生成的 26.3 表：{@code Entity#isDead} 与 {@code LocalPlayer#respawnPlayer} 在 26.x 已被改名，
     * 生成器把它们标成 {@code "absent": true}。旧实现只写标记不打日志，模块会静默失效（例如自动重生）；
     * 这里钉住「回退 + 一次诊断 + 描述符按不存在处理」。
     */
    @Test
    void absentMembersFallBackToCanonicalNameAndAreReportedOnce() throws Exception {
        ObfuscatedMapping mapping = ObfuscatedMapping.load("/mappings-26.3.json");
        java.io.PrintStream original = System.out;
        java.io.ByteArrayOutputStream captured = new java.io.ByteArrayOutputStream();
        String log;
        try {
            System.setOut(new java.io.PrintStream(captured, true, "UTF-8"));
            // 字段：回退为规范名，且第二次查询不再打印
            assertEquals("isDead", mapping.fieldName(ClassType.ENTITY, "isDead"));
            assertEquals("isDead", mapping.fieldName(ClassType.ENTITY, "isDead"));
            // 方法：回退为规范名；描述符按「该版本不存在」处理
            assertEquals("respawnPlayer",
                    mapping.methodName(ClassType.LOCAL_PLAYER, "respawnPlayer", "()V"));
            assertFalse(mapping.hasMethodDescriptor(ClassType.LOCAL_PLAYER, "respawnPlayer"));
            assertEquals(null, mapping.methodDescriptor(ClassType.LOCAL_PLAYER, "respawnPlayer"));
        } finally {
            System.setOut(original);
            log = captured.toString("UTF-8");
        }
        assertEquals(1, countOccurrences(log, "net.minecraft.world.entity.Entity#isDead"),
                "absent member must be reported exactly once; log=" + log);
        assertTrue(log.contains("does not exist in this version"), "log=" + log);
    }

    /**
     * 内联 schema v2 fixture：覆盖四种命名空间齐全、缺命名空间、表外条目、absent 类与成员。
     *
     * <p>经 {@link ObfuscatedMapping#fromJson(String)} 直接构造，不碰 classpath 与全局缓存，
     * 因此断言不受真实资源重新生成的影响。
     */
    private static final String FIXTURE_JSON =
            "{"
                    + "\"version\":\"fixture-1.20.1\","
                    + "\"classes\":{"
                    + "  \"net/minecraft/client/Minecraft\":{"
                    + "    \"names\":{"
                    + "      \"vanilla\":\"ave\","
                    + "      \"fabric\":\"net/minecraft/class_1657\","
                    + "      \"forge\":\"net.minecraft.client.Minecraft\","
                    + "      \"neoforge\":\"net.minecraft.client.Minecraft\""
                    + "    },"
                    + "    \"methods\":{"
                    + "      \"getInstance\":{"
                    + "        \"names\":{"
                    + "          \"vanilla\":\"A\","
                    + "          \"fabric\":\"method_1551\","
                    + "          \"forge\":\"m_91087_\","
                    + "          \"neoforge\":\"getInstance\""
                    + "        },"
                    + "        \"signatures\":{"
                    + "          \"vanilla\":\"()Lave;\","
                    + "          \"fabric\":\"()Lnet/minecraft/class_1657;\","
                    + "          \"forge\":\"()Lnet/minecraft/client/Minecraft;\","
                    + "          \"neoforge\":\"()Lnet/minecraft/client/Minecraft;\""
                    + "        }"
                    + "      },"
                    + "      \"forgeOnly\":{"
                    + "        \"names\":{\"forge\":\"m_0000_\"},"
                    + "        \"signatures\":{\"forge\":\"()V\"}"
                    + "      },"
                    + "      \"removed\":{\"absent\":true}"
                    + "    },"
                    + "    \"fields\":{"
                    + "      \"player\":{"
                    + "        \"names\":{"
                    + "          \"vanilla\":\"h\","
                    + "          \"fabric\":\"field_1724\","
                    + "          \"forge\":\"f_91073_\","
                    + "          \"neoforge\":\"player\""
                    + "        },"
                    + "        \"descriptors\":{\"vanilla\":\"Lavs;\"}"
                    + "      }"
                    + "    }"
                    + "  },"
                    + "  \"com/mojang/blaze3d/platform/Window\":{"
                    + "    \"names\":{"
                    + "      \"vanilla\":\"bss\","
                    + "      \"neoforge\":\"com.mojang.blaze3d.platform.Window\""
                    + "    }"
                    + "  },"
                    + "  \"net/minecraft/world/entity/Entity\":{"
                    + "    \"absent\":true,"
                    + "    \"names\":{},"
                    + "    \"methods\":{},\"fields\":{}"
                    + "  }"
                    + "}"
                    + "}";

    /** 从内联 fixture 构造映射，避免复用全局缓存。 */
    private static ObfuscatedMapping fixture() {
        return ObfuscatedMapping.fromJson(FIXTURE_JSON);
    }

    /**
     * 类候选顺序：vanilla → fabric → forge → neoforge → 规范名（去重）；缺命名空间跳过；表外条目只剩规范名。
     */
    @Test
    void classNameCandidatesFollowNamespacePriorityAndFallBackToCanonical() {
        ObfuscatedMapping mapping = fixture();

        // 四种命名空间齐全；neoforge 名恰好等于规范名，去重后留在原位。
        assertEquals(Arrays.asList(
                        "ave",
                        "net/minecraft/class_1657",
                        "net.minecraft.client.Minecraft"),
                mapping.classNameCandidates(ClassType.MINECRAFT));
        // 老契约：className 返回候选首项（vanilla 优先）。
        assertEquals("ave", mapping.className(ClassType.MINECRAFT));

        // 只有 vanilla/neoforge：缺的 fabric/forge 被跳过，规范名兜底追加（与 neoforge 同名去重）。
        assertEquals(Arrays.asList("bss", "com.mojang.blaze3d.platform.Window"),
                mapping.classNameCandidates(ClassType.WINDOW));
        assertEquals("bss", mapping.className(ClassType.WINDOW));

        // 表未覆盖的类：只剩规范名一项。
        assertEquals(Collections.singletonList(ClassType.OPTIONS.canonicalName()),
                mapping.classNameCandidates(ClassType.OPTIONS));
    }

    /** 方法候选：每个名字带上**同命名空间**的描述符；缺描述符的候选为 null；末尾追加规范名。 */
    @Test
    void methodCandidatesPairEachNameWithItsOwnNamespaceDescriptor() {
        ObfuscatedMapping mapping = fixture();

        List<MethodCandidate> candidates =
                mapping.methodCandidates(ClassType.MINECRAFT, "getInstance");
        assertEquals(4, candidates.size());
        assertEquals("A", candidates.get(0).name());
        assertEquals("()Lave;", candidates.get(0).descriptor());
        assertEquals("method_1551", candidates.get(1).name());
        assertEquals("()Lnet/minecraft/class_1657;", candidates.get(1).descriptor());
        assertEquals("m_91087_", candidates.get(2).name());
        assertEquals("()Lnet/minecraft/client/Minecraft;", candidates.get(2).descriptor());
        // neoforge 名与规范名相同：去重后保留在末尾，描述符仍是 neoforge 的。
        assertEquals("getInstance", candidates.get(3).name());
        assertEquals("()Lnet/minecraft/client/Minecraft;", candidates.get(3).descriptor());

        // 名字候选与之一一对应，顺序一致。
        assertEquals(Arrays.asList("A", "method_1551", "m_91087_", "getInstance"),
                mapping.methodNameCandidates(ClassType.MINECRAFT, "getInstance", null));
        // 老契约：methodName / methodDescriptor 取候选首项。
        assertEquals("A", mapping.methodName(ClassType.MINECRAFT, "getInstance", "()Lave;"));
        assertEquals("()Lave;", mapping.methodDescriptor(ClassType.MINECRAFT, "getInstance"));
        assertTrue(mapping.hasMethodDescriptor(ClassType.MINECRAFT, "getInstance"));
    }

    /** 只覆盖一个命名空间的方法：该候选带自己的描述符，末尾规范名候选无描述符。 */
    @Test
    void singleNamespaceMethodCandidatesKeepTheirDescriptor() {
        ObfuscatedMapping mapping = fixture();

        List<MethodCandidate> forgeOnly =
                mapping.methodCandidates(ClassType.MINECRAFT, "forgeOnly");
        assertEquals(2, forgeOnly.size());
        assertEquals("m_0000_", forgeOnly.get(0).name());
        assertEquals("()V", forgeOnly.get(0).descriptor());
        assertEquals("forgeOnly", forgeOnly.get(1).name());
        assertEquals(null, forgeOnly.get(1).descriptor());
        // 首项带描述符，故 hasMethodDescriptor 为真。
        assertTrue(mapping.hasMethodDescriptor(ClassType.MINECRAFT, "forgeOnly"));
        assertEquals("()V", mapping.methodDescriptor(ClassType.MINECRAFT, "forgeOnly"));
    }

    /** 字段候选顺序同命名空间优先级；表外字段只剩规范名。 */
    @Test
    void fieldCandidatesFollowNamespacePriorityAndFallBackToCanonical() {
        ObfuscatedMapping mapping = fixture();

        assertEquals(Arrays.asList("h", "field_1724", "f_91073_", "player"),
                mapping.fieldNameCandidates(ClassType.MINECRAFT, "player"));
        assertEquals("h", mapping.fieldName(ClassType.MINECRAFT, "player"));
        assertEquals(Collections.singletonList("unknownField"),
                mapping.fieldNameCandidates(ClassType.MINECRAFT, "unknownField"));
        assertEquals("unknownField", mapping.fieldName(ClassType.MINECRAFT, "unknownField"));
    }

    /** absent 类与 absent 成员都回退为规范名（候选列表只剩规范名一项）。 */
    @Test
    void absentClassAndMemberFallBackToCanonicalCandidates() {
        ObfuscatedMapping mapping = fixture();

        assertEquals(Collections.singletonList(ClassType.ENTITY.canonicalName()),
                mapping.classNameCandidates(ClassType.ENTITY));
        assertEquals(ClassType.ENTITY.canonicalName(), mapping.className(ClassType.ENTITY));

        List<MethodCandidate> removed =
                mapping.methodCandidates(ClassType.MINECRAFT, "removed");
        assertEquals(1, removed.size());
        assertEquals("removed", removed.get(0).name());
        assertEquals(null, removed.get(0).descriptor());
        assertEquals("removed", mapping.methodName(ClassType.MINECRAFT, "removed", "()V"));
        assertFalse(mapping.hasMethodDescriptor(ClassType.MINECRAFT, "removed"));

        // 表外方法同样只剩规范名。
        List<MethodCandidate> unknown = mapping.methodCandidates(ClassType.MINECRAFT, "unknown");
        assertEquals(1, unknown.size());
        assertEquals("unknown", unknown.get(0).name());
        assertEquals(null, unknown.get(0).descriptor());
    }

    /** 统计子串出现次数。 */
    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int from = 0;
        while (true) {
            int at = haystack.indexOf(needle, from);
            if (at < 0) {
                return count;
            }
            count++;
            from = at + needle.length();
        }
    }
}
