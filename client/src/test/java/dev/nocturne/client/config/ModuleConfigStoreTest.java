package dev.nocturne.client.config;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.TestSupport;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.Module;
import dev.nocturne.client.module.ModuleRegistry;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.ModeValue;
import dev.nocturne.client.value.MultiBoolValue;
import dev.nocturne.client.value.NumberValue;
import dev.nocturne.client.value.TextValue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 模块配置持久化：结构、去抖/原子写、损坏文件隔离与 panic 语义。
 *
 * <p>测试全部落在 {@link TempDir} 下；通过系统属性 {@code nocturne.config.dir} 注入路径，
 * 避免污染源码树或真实用户目录。
 */
class ModuleConfigStoreTest {

    /** 覆盖全部需要 round-trip 的类型；模块不注册进客户端，避免真实模块的 onStart 副作用。 */
    static final class ProbeModule extends Module {
        final BooleanValue toggle = add(new BooleanValue("Toggle", false));
        final NumberValue amount = add(new NumberValue("Amount", 5, 0, 10, 0.5));
        final ModeValue mode = add(new ModeValue("Mode", "A", "A", "B", "C"));
        final MultiBoolValue flags = add(new MultiBoolValue("Flags",
                Arrays.asList("x", "y", "z"), Arrays.asList("x")));
        final TextValue text = add(new TextValue("Text", "default", 16));

        @Override
        public String name() {
            return "Probe";
        }

        @Override
        public Category category() {
            return Category.MISC;
        }
    }

    @TempDir
    Path tempDir;

    private Path path;

    @BeforeEach
    void isolate() {
        TestSupport.resetClient();
        path = tempDir.resolve("config").resolve(ModuleConfigStore.FILE_NAME);
    }

    @AfterEach
    void cleanUp() {
        TestSupport.resetClient();
    }

    private ModuleRegistry registryWithProbe(ProbeModule module) {
        ModuleRegistry registry = new ModuleRegistry();
        registry.register(module);
        return registry;
    }

    /** enabled/bind/Boolean/Number/Mode/MultiBool/Text 全部 round-trip。 */
    @Test
    void roundTripsEnabledBindAndTypedValues() {
        ProbeModule original = new ProbeModule();
        ModuleConfigStore store = new ModuleConfigStore(registryWithProbe(original), path);
        store.load();

        original.setEnabled(true);
        original.bind().set(82);
        original.toggle.set(true);
        original.amount.set(7.5);
        original.mode.set("C");
        original.text.set("hello");
        Set<String> selected = new LinkedHashSet<String>(Arrays.asList("y", "z"));
        original.flags.set(selected);
        assertTrue(store.flush(), "flush must write the config file");

        ProbeModule restored = new ProbeModule();
        ModuleConfigStore reloaded = new ModuleConfigStore(registryWithProbe(restored), path);
        reloaded.load();

        assertTrue(restored.isEnabled());
        assertEquals(82, restored.bind().get());
        assertTrue(restored.toggle.get());
        assertEquals(7.5, restored.amount.get());
        assertEquals("C", restored.mode.get());
        assertEquals("hello", restored.text.get());
        assertEquals(selected, restored.flags.get());
    }

    /** 默认值（含 bind=0）不写出：配置文件只保留真正的用户改动。 */
    @Test
    void defaultValuesAreNotWritten() throws IOException {
        ProbeModule module = new ProbeModule();
        ModuleConfigStore store = new ModuleConfigStore(registryWithProbe(module), path);
        store.load();
        module.amount.set(8.0);
        assertTrue(store.flush());

        JsonObject node = readModuleNode("Probe");
        assertFalse(node.get("enabled").getAsBoolean());
        assertFalse(node.has("bind"), "unset bind must be omitted");
        assertTrue(node.has("values"));
        JsonObject values = node.getAsJsonObject("values");
        assertEquals(1, values.size(), "only the changed value is written");
        assertEquals(8.0, values.get("Amount").getAsDouble());
        assertFalse(values.has("Toggle"));
        assertFalse(values.has("Mode"));
        assertFalse(values.has("Flags"));
        assertFalse(values.has("Text"));
    }

    /** 坏 JSON：保留默认值并把文件改名 .corrupt，绝不覆盖现场。 */
    @Test
    void corruptJsonIsQuarantinedAndDefaultsSurvive() throws IOException {
        Files.createDirectories(path.getParent());
        Files.write(path, "{this is not json".getBytes(StandardCharsets.UTF_8));

        ProbeModule module = new ProbeModule();
        ModuleConfigStore store = new ModuleConfigStore(registryWithProbe(module), path);
        store.load();

        assertFalse(module.isEnabled());
        assertEquals(5.0, module.amount.get());
        assertEquals("A", module.mode.get());
        assertFalse(Files.exists(path), "corrupt file must not be overwritten in place");
        assertNotNull(findQuarantine(), "corrupt file must be kept for diagnosis");
    }

    /** 未知模块/未知设置项/类型不符：忽略该项并保留默认，不影响同一文件里的其它项。 */
    @Test
    void unknownEntriesAndTypeMismatchAreIgnored() throws IOException {
        Files.createDirectories(path.getParent());
        String json = "{\"version\":1,\"modules\":{"
                + "\"Probe\":{\"enabled\":true,\"bind\":70,\"values\":{"
                + "\"Amount\":\"not-a-number\",\"Mode\":\"C\",\"Nope\":123}},"
                + "\"Unknown Module\":{\"enabled\":true}}}";
        Files.write(path, json.getBytes(StandardCharsets.UTF_8));

        ProbeModule module = new ProbeModule();
        ModuleConfigStore store = new ModuleConfigStore(registryWithProbe(module), path);
        store.load();

        assertTrue(module.isEnabled());
        assertEquals(70, module.bind().get());
        assertEquals(5.0, module.amount.get(), "type mismatch keeps the default");
        assertEquals("C", module.mode.get(), "valid neighbours still load");
    }

    /** 去抖：连续修改在窗口结束后只写一次文件。 */
    @Test
    void changesAreSavedAfterTheDebounceWindow() throws Exception {
        ProbeModule module = new ProbeModule();
        ModuleConfigStore store = new ModuleConfigStore(registryWithProbe(module), path);
        store.load();
        module.amount.set(6.0);
        module.amount.set(7.0);
        module.amount.set(8.0);
        store.requestSave();
        assertFalse(Files.exists(path), "save is debounced, not synchronous");

        long deadline = System.currentTimeMillis() + 2000L;
        while (!Files.exists(path) && System.currentTimeMillis() < deadline) {
            Thread.sleep(25L);
        }
        assertTrue(Files.exists(path), "debounced save must eventually land");
        assertEquals(8.0, readModuleNode("Probe").getAsJsonObject("values")
                .get("Amount").getAsDouble());
        store.close();
    }

    /** 原子写：临时文件不残留，第二次写盘留下上一版备份。 */
    @Test
    void writesAreAtomicAndKeepABackup() throws IOException {
        ProbeModule module = new ProbeModule();
        ModuleConfigStore store = new ModuleConfigStore(registryWithProbe(module), path);
        store.load();
        module.amount.set(6.0);
        assertTrue(store.flush());
        Path temp = path.resolveSibling(path.getFileName() + ".tmp");
        Path backup = path.resolveSibling(path.getFileName() + ".bak");
        assertFalse(Files.exists(temp), "temp file must be moved, not left behind");
        assertFalse(Files.exists(backup), "no previous file means no backup");

        module.amount.set(9.0);
        assertTrue(store.flush());
        assertFalse(Files.exists(temp));
        assertTrue(Files.exists(backup), "second write must back up the previous file");
        assertEquals(9.0, readModuleNode("Probe").getAsJsonObject("values")
                .get("Amount").getAsDouble());
        // 备份是上一版，仍然是完整可解析的 JSON。
        JsonParser.parseString(new String(Files.readAllBytes(backup), StandardCharsets.UTF_8));
    }

    /** panic：先落盘“禁用前”的开关与设置，再关闭存储；全关状态不得写进配置。 */
    @Test
    void panicPersistsTheStateBeforeDisabling() throws IOException {
        Path panicDir = tempDir.resolve("panic");
        Files.createDirectories(panicDir);
        TestSupport.resetClient();
        System.setProperty(ModuleConfigStore.CONFIG_DIR_PROPERTY, panicDir.toString());

        NocturneClient client = NocturneClient.boot(null);
        Module blink = client.modules().get("Blink");
        assertNotNull(blink, "precondition: Blink is registered");
        blink.setEnabled(true);
        blink.bind().set(82);

        client.panic();
        assertFalse(blink.isEnabled(), "panic still disables modules in memory");

        JsonObject root = readJson(panicDir.resolve(ModuleConfigStore.FILE_NAME));
        JsonObject modules = root.getAsJsonObject("modules");
        JsonObject blinkNode = modules.getAsJsonObject("Blink");
        assertTrue(blinkNode.get("enabled").getAsBoolean(),
                "panic must persist the pre-disable state, not the all-off state");
        assertEquals(82, blinkNode.get("bind").getAsInt());
        assertTrue(modules.getAsJsonObject("Better Chat").get("enabled").getAsBoolean(),
                "default-enabled modules must not be persisted as off after panic");
    }

    /** 默认开启只在配置缺键时生效：显式关闭后重启必须保持关闭。 */
    @Test
    void defaultsOnlyApplyToMissingKeys() {
        Path configDir = tempDir.resolve("defaults");
        NocturneClient first = bootWithConfigDir(configDir);
        Module betterChat = first.modules().get("Better Chat");
        assertNotNull(betterChat);
        assertTrue(betterChat.isEnabled(), "first run applies the default");

        betterChat.setEnabled(false);
        first.moduleConfig().flush();

        NocturneClient second = bootWithConfigDir(configDir);
        Module reloaded = second.modules().get("Better Chat");
        assertNotNull(reloaded);
        assertFalse(reloaded.isEnabled(), "explicit user choice must beat the default");
        assertTrue(second.modules().get("Better Scoreboard").isEnabled(),
                "untouched default stays on");
    }

    private NocturneClient bootWithConfigDir(Path configDir) {
        TestSupport.resetClient();
        try {
            Files.createDirectories(configDir);
        } catch (IOException error) {
            throw new IllegalStateException(error);
        }
        System.setProperty(ModuleConfigStore.CONFIG_DIR_PROPERTY, configDir.toString());
        return NocturneClient.boot(null);
    }

    private JsonObject readModuleNode(String moduleName) throws IOException {
        return readJson(path).getAsJsonObject("modules").getAsJsonObject(moduleName);
    }

    private static JsonObject readJson(Path file) throws IOException {
        String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        return JsonParser.parseString(text).getAsJsonObject();
    }

    private Path findQuarantine() throws IOException {
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(path.getParent())) {
            for (Path file : stream) {
                if (file.getFileName().toString().startsWith(ModuleConfigStore.FILE_NAME + ".corrupt")) {
                    return file;
                }
            }
        }
        return null;
    }
}
