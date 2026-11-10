package dev.nocturne.client.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import dev.nocturne.client.module.Module;
import dev.nocturne.client.module.ModuleRegistry;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.BoundsNumberValue;
import dev.nocturne.client.value.ColorValue;
import dev.nocturne.client.value.GroupValue;
import dev.nocturne.client.value.KeyValue;
import dev.nocturne.client.value.ModeValue;
import dev.nocturne.client.value.MultiBoolValue;
import dev.nocturne.client.value.NumberBounds;
import dev.nocturne.client.value.NumberValue;
import dev.nocturne.client.value.SlotValue;
import dev.nocturne.client.value.TextValue;
import dev.nocturne.client.value.Value;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.channels.FileChannel;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 模块开关、热键与设置项的持久化。
 *
 * <p>文件结构（{@code modules.json}）：
 * <pre>
 * {"version":1,"modules":{"Blink":{"enabled":true,"bind":82,"values":{"Tick":42.0}}}}
 * </pre>
 * 键名直接用 {@code module.name()} 与 {@code value.name()}，不依赖反射字段名，因此重命名字段
 * 不会破坏配置；未知模块/未知设置项在加载时静默忽略（保留构造函数里的默认值）。
 *
 * <p><b>路径</b>：优先游戏实例目录（{@code user.dir}，Fabric/原版启动时即实例目录）下的
 * {@code .nocturne/modules.json}，不可写时回退 {@code ~/.nocturne/modules.json}；
 * {@code -Dnocturne.config.dir=<dir>} 可显式覆盖（测试与自定义启动器用）。
 *
 * <p><b>写盘</b>：所有变更只投递一个去抖任务（{@value #DEBOUNCE_MS}ms 内的连续修改合并成一次），
 * 落盘走「同目录临时文件 + fsync + 备份旧文件 + ATOMIC_MOVE」，进程在写入中途被杀也不会留下
 * 半截 JSON。读取到损坏文件时改名 {@code modules.json.corrupt[.n]} 保留现场，绝不静默覆盖。
 *
 * <p><b>panic/shutdown 语义</b>：{@link #flushAndClose()} 在模块被全部禁用<b>之前</b>写出当前快照，
 * 随后拒绝任何新写入——否则「自销毁」会把全关状态持久化，下次启动看起来像用户主动关掉了一切。
 */
public final class ModuleConfigStore {

    /** 配置文件格式版本；结构不兼容变更时递增，读到更高版本时保留旧文件不加载。 */
    public static final int FORMAT_VERSION = 1;
    /** 文件名（含扩展名）。 */
    public static final String FILE_NAME = "modules.json";
    /** 游戏实例目录 / 用户目录下的配置子目录。 */
    public static final String DIR_NAME = ".nocturne";
    /** 显式配置目录的系统属性（测试与自定义启动器）。 */
    public static final String CONFIG_DIR_PROPERTY = "nocturne.config.dir";

    /** 去抖窗口：连续修改合并为一次写盘。 */
    private static final long DEBOUNCE_MS = 300L;

    private final ModuleRegistry modules;
    private final Path path;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    /** 本次加载中出现的模块键；用于判断「配置缺键」以决定是否套用默认开启。 */
    private final Set<String> configuredModules = new LinkedHashSet<String>();
    private final Set<String> warnings = ConcurrentHashMap.newKeySet();

    /** 保护去抖调度状态（序号/工作线程）。 */
    private final Object schedulerLock = new Object();
    /** 串行化真正写盘，避免去抖线程与 flush 并发写同一份文件。 */
    private final Object writeLock = new Object();

    private long mutationSeq;
    private long savedSeq;
    private long pendingAt;
    private boolean workerRunning;
    private boolean closed;
    private Thread worker;
    /** 加载/默认值套用期间为 true：写入不回标脏，避免「读配置」反向触发保存。 */
    private volatile boolean loading;

    public ModuleConfigStore(ModuleRegistry modules) {
        this(modules, defaultPath());
    }

    public ModuleConfigStore(ModuleRegistry modules, Path path) {
        if (modules == null) {
            throw new IllegalArgumentException("modules == null");
        }
        if (path == null) {
            throw new IllegalArgumentException("path == null");
        }
        this.modules = modules;
        this.path = path.toAbsolutePath();
    }

    /** @return 实际使用的配置文件路径 */
    public Path path() {
        return path;
    }

    /**
     * 解析默认路径：显式属性 → 游戏实例目录 → 用户目录回退。
     *
     * <p>用 {@code user.dir} 而不是硬编码 {@code ~/.minecraft}：不同启动器把工作目录设成各自的
     * 版本/实例目录，写在该目录下自然实现「每个实例独立配置」。
     */
    public static Path defaultPath() {
        String override = System.getProperty(CONFIG_DIR_PROPERTY);
        if (override != null && !override.trim().isEmpty()) {
            return Paths.get(override.trim()).resolve(FILE_NAME);
        }
        String userDir = System.getProperty("user.dir");
        if (userDir != null && !userDir.isEmpty()) {
            Path instance = Paths.get(userDir).toAbsolutePath();
            if (Files.isDirectory(instance) && Files.isWritable(instance)) {
                return instance.resolve(DIR_NAME).resolve(FILE_NAME);
            }
        }
        return Paths.get(System.getProperty("user.home", "."), DIR_NAME, FILE_NAME);
    }

    // ------------------------------------------------------------------ load

    /**
     * 读取配置文件并应用到已注册模块。
     *
     * <p>损坏 JSON / 根不是对象：把文件改名 {@code modules.json.corrupt[.n]} 后返回，模块保持
     * 构造默认值；单个模块/设置项类型不符时只跳过该项，不影响其它项。
     */
    public void load() {
        loading = true;
        try {
            if (!Files.isRegularFile(path)) {
                return;
            }
            JsonObject root;
            try {
                String text = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
                JsonElement parsed = new JsonParser().parse(text);
                if (parsed == null || !parsed.isJsonObject()) {
                    throw new JsonParseException("module config root is not an object");
                }
                root = parsed.getAsJsonObject();
            } catch (IOException | RuntimeException error) {
                quarantine("unreadable module config: " + error);
                return;
            }
            int version = readVersion(root);
            if (version > FORMAT_VERSION) {
                warn("version", "module config version " + version + " is newer than "
                        + FORMAT_VERSION + "; keeping defaults and leaving the file untouched");
                return;
            }
            JsonObject modulesNode = asObject(root.get("modules"));
            if (modulesNode == null) {
                return;
            }
            for (Module module : modules.all()) {
                JsonObject node = asObject(modulesNode.get(module.name()));
                if (node == null) {
                    continue;
                }
                configuredModules.add(module.name());
                applyModule(module, node);
            }
        } finally {
            loading = false;
        }
    }

    /**
     * 仅对配置里「缺键」的模块套用默认开启。
     *
     * <p>替换旧实现里无条件 {@code setEnabled(true)}：已有配置（哪怕显式写着 false）必须尊重；
     * 只有首次运行或新版本新增模块时才落到默认值。
     */
    public void applyDefaults(String... moduleNames) {
        if (moduleNames == null || moduleNames.length == 0) {
            return;
        }
        loading = true;
        try {
            for (String name : moduleNames) {
                if (name == null || configuredModules.contains(name)) {
                    continue;
                }
                Module module = modules.get(name);
                if (module == null || module.isEnabled()) {
                    continue;
                }
                try {
                    module.setEnabled(true);
                } catch (Throwable error) {
                    warn("default:" + name, "failed to apply default for module '" + name + "': " + error);
                }
            }
        } finally {
            loading = false;
        }
    }

    /** 在回调期间禁止把变更标记为待保存（用于启动参数等非用户意图的临时切换）。 */
    public void withSavingSuppressed(Runnable action) {
        if (action == null) {
            return;
        }
        boolean previous = loading;
        loading = true;
        try {
            action.run();
        } finally {
            loading = previous;
        }
    }

    private int readVersion(JsonObject root) {
        JsonElement version = root.get("version");
        if (version == null || !version.isJsonPrimitive() || !version.getAsJsonPrimitive().isNumber()) {
            return FORMAT_VERSION;
        }
        try {
            return version.getAsInt();
        } catch (RuntimeException error) {
            return FORMAT_VERSION;
        }
    }

    private void applyModule(Module module, JsonObject node) {
        // 先恢复设置/热键，再恢复启用状态：模块的 onStart 可能读取自身设置（例如 Blink 的
        // Fake Player），顺序反过来会让它按默认值装上副作用，直到下一次生命周期变化才自愈。
        JsonElement bind = node.get("bind");
        if (isInteger(bind)) {
            try {
                module.bind().set(bind.getAsInt());
            } catch (RuntimeException error) {
                warn("bind:" + module.name(), "invalid bind for '" + module.name() + "': " + error);
            }
        }
        JsonObject values = asObject(node.get("values"));
        if (values != null) {
            for (Map.Entry<String, JsonElement> entry : values.entrySet()) {
                Value<?> value = findValue(module, entry.getKey());
                if (value == null || value == module.bind()) {
                    continue;
                }
                try {
                    applyValue(value, entry.getValue());
                } catch (RuntimeException error) {
                    warn("value:" + module.name() + "#" + entry.getKey(),
                            "invalid value for '" + module.name() + "." + entry.getKey() + "': " + error);
                }
            }
        }
        JsonElement enabled = node.get("enabled");
        if (enabled != null && enabled.isJsonPrimitive()
                && enabled.getAsJsonPrimitive().isBoolean()) {
            try {
                module.setEnabled(enabled.getAsBoolean());
            } catch (Throwable error) {
                warn("enable:" + module.name(),
                        "failed to restore enabled state for '" + module.name() + "': " + error);
            }
        }
    }

    private static Value<?> findValue(Module module, String name) {
        if (name == null) {
            return null;
        }
        List<Value<?>> values = module.values();
        for (Value<?> value : values) {
            if (name.equals(value.name())) {
                return value;
            }
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private static void applyValue(Value<?> value, JsonElement raw) {
        if (value instanceof BooleanValue) {
            if (isBoolean(raw)) {
                ((BooleanValue) value).set(raw.getAsBoolean());
            }
            return;
        }
        if (value instanceof NumberValue) {
            if (isNumber(raw)) {
                ((NumberValue) value).set(raw.getAsDouble());
            }
            return;
        }
        if (value instanceof ModeValue) {
            if (isString(raw)) {
                ((ModeValue) value).set(raw.getAsString());
            }
            return;
        }
        if (value instanceof TextValue) {
            if (isString(raw)) {
                ((TextValue) value).set(raw.getAsString());
            }
            return;
        }
        if (value instanceof ColorValue) {
            if (isInteger(raw)) {
                ((ColorValue) value).set(raw.getAsInt());
            }
            return;
        }
        if (value instanceof SlotValue) {
            if (isInteger(raw)) {
                ((SlotValue) value).set(raw.getAsInt());
            }
            return;
        }
        if (value instanceof KeyValue) {
            if (isInteger(raw)) {
                ((KeyValue) value).set(raw.getAsInt());
            }
            return;
        }
        if (value instanceof MultiBoolValue) {
            if (raw != null && raw.isJsonArray()) {
                Set<String> selected = new LinkedHashSet<String>();
                for (JsonElement element : raw.getAsJsonArray()) {
                    if (isString(element)) {
                        selected.add(element.getAsString());
                    }
                }
                ((MultiBoolValue) value).set(selected);
            }
            return;
        }
        if (value instanceof BoundsNumberValue) {
            NumberBounds bounds = readBounds(raw);
            if (bounds != null) {
                ((BoundsNumberValue) value).set(bounds);
            }
            return;
        }
        if (value instanceof GroupValue) {
            if (isBoolean(raw)) {
                ((GroupValue) value).set(raw.getAsBoolean());
            }
        }
    }

    private static NumberBounds readBounds(JsonElement raw) {
        if (raw == null || !raw.isJsonArray()) {
            return null;
        }
        JsonArray array = raw.getAsJsonArray();
        if (array.size() != 2 || !isNumber(array.get(0)) || !isNumber(array.get(1))) {
            return null;
        }
        return new NumberBounds(array.get(0).getAsDouble(), array.get(1).getAsDouble());
    }

    // ------------------------------------------------------------------ save

    /** 请求一次去抖保存；加载期间、关闭后与无变更时为空操作。 */
    public void requestSave() {
        if (closed || loading) {
            return;
        }
        synchronized (schedulerLock) {
            if (closed) {
                return;
            }
            mutationSeq++;
            pendingAt = System.currentTimeMillis() + DEBOUNCE_MS;
            if (workerRunning) {
                schedulerLock.notifyAll();
                return;
            }
            workerRunning = true;
            worker = new Thread(new Runnable() {
                @Override
                public void run() {
                    debounceLoop();
                }
            }, "nocturne-config-save");
            worker.setDaemon(true);
            worker.start();
        }
    }

    private void debounceLoop() {
        while (true) {
            synchronized (schedulerLock) {
                if (closed) {
                    workerRunning = false;
                    worker = null;
                    return;
                }
                if (savedSeq == mutationSeq) {
                    workerRunning = false;
                    worker = null;
                    return;
                }
                long now = System.currentTimeMillis();
                if (now < pendingAt) {
                    try {
                        schedulerLock.wait(pendingAt - now);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        workerRunning = false;
                        worker = null;
                        return;
                    }
                    continue;
                }
            }
            long sequence;
            synchronized (schedulerLock) {
                sequence = mutationSeq;
            }
            saveNow();
            synchronized (schedulerLock) {
                if (sequence > savedSeq) {
                    savedSeq = sequence;
                }
            }
        }
    }

    /** 立即写盘（忽略去抖窗口）；返回是否成功。 */
    public boolean flush() {
        long sequence;
        synchronized (schedulerLock) {
            if (closed) {
                return false;
            }
            sequence = mutationSeq;
        }
        boolean saved = saveNow();
        synchronized (schedulerLock) {
            if (sequence > savedSeq) {
                savedSeq = sequence;
            }
        }
        return saved;
    }

    /**
     * 写出当前快照后关闭存储：之后所有 {@link #requestSave()} 都是空操作。
     *
     * <p>调用顺序必须是「flushAndClose() → 禁用模块」，否则自销毁会把全关状态写进配置。
     * 若去抖线程正在写盘会先等它结束，保证最后落盘的是调用这一刻的状态。
     */
    public void flushAndClose() {
        Thread pending;
        boolean changed;
        synchronized (schedulerLock) {
            closed = true;
            pending = worker;
            changed = mutationSeq > savedSeq;
            schedulerLock.notifyAll();
        }
        joinQuietly(pending);
        // 从未落过盘且没有任何用户变更时保持「没注入过」：panic 不应凭空创建配置文件。
        if (changed || Files.isRegularFile(path)) {
            saveNow();
        }
    }

    /** 关闭存储但<b>不</b>写盘（测试隔离/放弃未落盘变更时使用）。 */
    public void close() {
        Thread pending;
        synchronized (schedulerLock) {
            closed = true;
            pending = worker;
            schedulerLock.notifyAll();
        }
        joinQuietly(pending);
    }

    /** @return 是否已关闭（不再接受写盘请求） */
    public boolean isClosed() {
        synchronized (schedulerLock) {
            return closed;
        }
    }

    private void joinQuietly(Thread thread) {
        if (thread == null || thread == Thread.currentThread()) {
            return;
        }
        try {
            thread.join(1000L);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private boolean saveNow() {
        synchronized (writeLock) {
            try {
                JsonObject root = new JsonObject();
                root.addProperty("version", FORMAT_VERSION);
                JsonObject modulesNode = new JsonObject();
                for (Module module : modules.all()) {
                    modulesNode.add(module.name(), snapshotModule(module));
                }
                root.add("modules", modulesNode);
                writeAtomically(gson.toJson(root).getBytes(StandardCharsets.UTF_8));
                return true;
            } catch (Throwable error) {
                warn("save", "failed to save module config to " + path + ": " + error);
                return false;
            }
        }
    }

    private JsonObject snapshotModule(Module module) {
        JsonObject node = new JsonObject();
        node.addProperty("enabled", module.isEnabled());
        int bind = module.bind().get();
        if (bind != KeyValue.NONE) {
            node.addProperty("bind", bind);
        }
        JsonObject valuesNode = new JsonObject();
        for (Value<?> value : module.values()) {
            if (value == module.bind() || value.isDefault()) {
                continue;
            }
            JsonElement raw = rawValue(value);
            if (raw != null) {
                valuesNode.add(value.name(), raw);
            }
        }
        if (!valuesNode.entrySet().isEmpty()) {
            node.add("values", valuesNode);
        }
        return node;
    }

    /** 把设置项当前值转成 JSON；无法识别的自定义 Value 不持久化（返回 null）。 */
    private JsonElement rawValue(Value<?> value) {
        if (value instanceof BooleanValue) {
            return new com.google.gson.JsonPrimitive(Boolean.valueOf((Boolean) value.get()));
        }
        if (value instanceof NumberValue) {
            return new com.google.gson.JsonPrimitive((Double) value.get());
        }
        if (value instanceof ModeValue || value instanceof TextValue) {
            return new com.google.gson.JsonPrimitive((String) value.get());
        }
        if (value instanceof ColorValue || value instanceof SlotValue || value instanceof KeyValue) {
            return new com.google.gson.JsonPrimitive((Integer) value.get());
        }
        if (value instanceof MultiBoolValue) {
            JsonArray array = new JsonArray();
            for (String selected : ((MultiBoolValue) value).get()) {
                array.add(selected);
            }
            return array;
        }
        if (value instanceof BoundsNumberValue) {
            NumberBounds bounds = (NumberBounds) value.get();
            if (bounds == null) {
                return null;
            }
            JsonArray array = new JsonArray();
            array.add(bounds.min());
            array.add(bounds.max());
            return array;
        }
        if (value instanceof GroupValue) {
            return new com.google.gson.JsonPrimitive(Boolean.valueOf((Boolean) value.get()));
        }
        return null;
    }

    private void writeAtomically(byte[] bytes) throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temp = path.resolveSibling(path.getFileName() + ".tmp");
        Files.write(temp, bytes);
        force(temp);
        if (Files.isRegularFile(path)) {
            Path backup = path.resolveSibling(path.getFileName() + ".bak");
            Files.copy(path, backup, StandardCopyOption.REPLACE_EXISTING);
        }
        try {
            Files.move(temp, path, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException unsupported) {
            // 极少数文件系统（网络盘/老 Windows 共享）不支持原子移动：退化为普通替换。
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static void force(Path file) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    /**
     * 把损坏文件改名保留现场（{@code modules.json.corrupt}，重名时追加序号），不覆盖旧证据。
     */
    private void quarantine(String reason) {
        try {
            Path corrupt = path.resolveSibling(path.getFileName() + ".corrupt");
            int suffix = 1;
            while (Files.exists(corrupt)) {
                corrupt = path.resolveSibling(path.getFileName() + ".corrupt." + suffix);
                suffix++;
            }
            Files.move(path, corrupt);
            warn("corrupt", reason + "; kept as " + corrupt.getFileName());
        } catch (Throwable error) {
            warn("corrupt-keep", reason + "; also failed to quarantine " + path + ": " + error);
        }
    }

    // ------------------------------------------------------------------ helpers

    private static JsonObject asObject(JsonElement element) {
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static boolean isBoolean(JsonElement element) {
        return element != null && element.isJsonPrimitive()
                && element.getAsJsonPrimitive().isBoolean();
    }

    private static boolean isString(JsonElement element) {
        return element != null && element.isJsonPrimitive()
                && element.getAsJsonPrimitive().isString();
    }

    private static boolean isNumber(JsonElement element) {
        return element != null && element.isJsonPrimitive()
                && element.getAsJsonPrimitive().isNumber();
    }

    private static boolean isInteger(JsonElement element) {
        if (!isNumber(element)) {
            return false;
        }
        double value = element.getAsDouble();
        return value == Math.rint(value) && value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE;
    }

    private void warn(String key, String message) {
        if (warnings.add(key)) {
            System.out.println("[nocturne] " + message);
        }
    }
}
