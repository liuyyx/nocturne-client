package dev.nocturne.client.mapping;

import com.google.gson.Gson;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 混淆构建（1.8.9 – 1.21.x）的表驱动映射。
 *
 * <p>数据格式（schema v2，由映射工具产出）：同一份表同时记录四种运行期命名空间，使同一份代码
 * 在 vanilla / Fabric / Forge / NeoForge 上都能按名反射：
 * <pre>
 * {
 *   "version": "1.20.1",
 *   "classes": {
 *     "net/minecraft/client/Minecraft": {          // 键恒为规范（Mojmap）内部名
 *       "names": {                                  // 命名空间 → 运行期类名
 *         "vanilla": "enn",
 *         "fabric": "net/minecraft/class_1657",
 *         "forge": "net.minecraft.client.Minecraft",
 *         "neoforge": "net.minecraft.client.Minecraft"
 *       },
 *       "methods": {
 *         "getInstance": {
 *           "names": { "vanilla": "N", "fabric": "method_1551", ... },
 *           "signatures": { "vanilla": "()Lenn;", ... }   // JNI 描述符，与同名命名空间配套
 *         },
 *         "someRemovedMethod": { "absent": true }         // 该版本根本没有这个方法
 *       },
 *       "fields": {
 *         "player": {
 *           "names": { "vanilla": "h", ... },
 *           "descriptors": { "vanilla": "Lavs;", ... }    // 字段描述符（当前 Java 侧不使用）
 *         }
 *       }
 *     }
 *   }
 * }
 * </pre>
 *
 * <p>类的键是使用 {@code /} 分隔的规范 Mojmap 名；查询时 {@code /} 与 {@code .} 两种写法
 * 均可。某个命名空间在目标版本不存在时，该键直接缺省（不写空串）。未收录的条目回退为规范名，
 * 因此即使表覆盖不完整，反射仍可尝试可读名。
 *
 * <p><b>候选顺序</b>：命名空间按固定优先级 {@code vanilla → fabric → forge → neoforge} 展开，
 * 每个命名空间的空/缺省值跳过，重名去重，末尾追加规范名兜底。因此 {@link #className}（即候选首项）
 * 在 1.8.9 表上仍是原版混淆名，而在缺表的版本上自动落回 Mojmap 名。
 *
 * <p><b>键的语义</b>：键始终是<b>规范（Mojmap）名</b>，而非某个版本运行期真实存在的类名。当某个类
 * 在目标版本里已被重命名时，值填的是该版本中<b>语义等价</b>的类——例如规范名
 * {@code net/minecraft/client/renderer/GameRenderer} 在 1.8.9 上的等价物是 {@code EntityRenderer}
 * （{@code bfk}，1.13 才更名为 GameRenderer），{@code net/minecraft/network/chat/Component} 对应
 * 1.8.9 的 {@code IChatComponent}（{@code eu}）。因此同一个规范名在不同版本的表里可以指向不同类名，
 * 多个规范名也可以指向同一个运行期类（如基础包与其“仅状态”子包在 1.8.9 是同一个类）。
 *
 * <p>若某类型在目标版本里<b>根本不存在</b>（如 1.8.9 没有 Blaze3D 的 Window），条目以
 * {@code "absent": true} 显式标记：查询会打印一次性诊断日志并回退为规范名，而不是把规范名
 * 静默当作混淆名使用（L-106）。
 */
public final class ObfuscatedMapping implements Mapping {

    /**
     * 命名空间优先级（唯一权威顺序）：原版混淆名 → Fabric intermediary → Forge SRG → NeoForge。
     *
     * <p>NeoForge 从 1.20.2 起运行期即 Mojmap 恒等名，放在最后；规范名兜底再追加在其后。
     * 顺序即尝试顺序，改动它会改变 {@link #className} 等“取首项”方法的返回值。
     */
    private static final String[] NAMESPACES = {"vanilla", "fabric", "forge", "neoforge"};

    /** 映射表原始 JSON 结构中的一条成员记录（方法或字段共用）。 */
    static final class MemberData {
        /**
         * 命名空间 → 运行期成员名。
         *
         * <p>缺省/空串表示该命名空间下没有这个名字（例如 1.8.9 没有 Fabric 命名空间）。
         * 全部为空则回退规范名。
         */
        Map<String, String> names;
        /**
         * 命名空间 → 该方法的 JNI 描述符（仅方法使用）。
         *
         * <p>描述符与名字<b>必须同命名空间配套</b>：SRG 名的描述符里是 Mojmap 类名，原版混淆名的
         * 描述符里是混淆类名，混用会导致参数类型解析失败。
         */
        Map<String, String> signatures;
        /**
         * 命名空间 → 该字段的 JNI 描述符（仅字段使用）。
         *
         * <p>当前 Java 侧没有按描述符访问字段的入口，此表仅为 schema 完整性而保留。
         */
        Map<String, String> descriptors;
        /**
         * 该成员在<b>本表对应的版本里不存在</b>（例如 {@code Entity#isDead} 在 26.2/26.3 已被改名）。
         *
         * <p>为 true 时各 {@code names} 表均为空：查询会打印一次性诊断并回退为规范名，
         * 让「这个版本没有这个成员」在日志里可见，而不是等反射抛异常或被静默吞掉。
         */
        boolean absent;
    }

    /** 单个类的映射记录。 */
    static final class ClassData {
        /**
         * 命名空间 → 运行期类名。
         *
         * <p>标记为 {@link #absent} 时为空表；全部为空则回退规范名。
         */
        Map<String, String> names;
        /** 规范方法名 → 成员记录。 */
        Map<String, MemberData> methods;
        /** 规范字段名 → 成员记录。 */
        Map<String, MemberData> fields;
        /**
         * 该规范类在<b>本表对应的版本里不存在</b>（例如 1.8.9 没有 Blaze3D 的
         * {@code com.mojang.blaze3d.platform.Window}）。为 true 时 {@link #names} 必须为空，
         * 查询会记录一次性诊断日志并回退为规范名，而不是把规范名当作混淆名静默使用（L-106）。
         */
        boolean absent;
    }

    /** 整个映射表的 JSON 文档。 */
    static final class Document {
        /** 目标游戏版本号，例如 {@code "1.8.9"}；缺失时为 {@code "unknown"}。 */
        String version;
        /** 规范类名（斜杠形式）→ 类记录。 */
        Map<String, ClassData> classes;
    }

    /** 表中记录的版本号；构造时已把 {@code null} 归一化为 {@code "unknown"}。 */
    private final String version;
    /** 规范类名（斜杠形式）→ 类记录；构造时已把 {@code null} 归一化为空表。 */
    private final Map<String, ClassData> classes;

    /** 已成功解析的映射表，按资源路径缓存：同一路径只解析一次（L-107）。 */
    private static final ConcurrentHashMap<String, ObfuscatedMapping> CACHE =
            new ConcurrentHashMap<String, ObfuscatedMapping>();
    /**
     * 解析失败的资源路径；失败同样缓存，避免逐帧重试反复打开流、反复解析（L-107）。
     * 资源随 jar 打包且 {@code getResourceAsStream} 用的是本类的加载器，故同一次类加载内失败是永久的。
     */
    private static final Set<String> FAILED = ConcurrentHashMap.newKeySet();

    /**
     * 构造映射实例；私有，强制经 {@link #load(String)} 创建。
     *
     * <p>对 {@code null} 做归一化，保证后续查询不必再判空。
     */
    private ObfuscatedMapping(String version, Map<String, ClassData> classes) {
        this.version = version == null ? "unknown" : version;
        this.classes = classes == null ? new HashMap<String, ClassData>() : classes;
    }

    /**
     * 从 classpath 资源加载映射表，例如 {@code /mappings-1.8.9.json}。
     *
     * <p>资源按 UTF-8 解码（映射文件可能含非 ASCII 字符），并用 Gson 直接反序列化为
     * {@link Document}。解析结果按资源路径缓存：同一路径只解析一次，失败同样被缓存，
     * 因此调用方在逐帧重试路径上重复调用不会反复打开流、反复解析（L-107）。
     *
     * @param resource 资源的 classpath 绝对路径
     * @return 加载完成的映射实例（同一路径多次调用返回同一实例）
     * @throws IllegalStateException 资源不存在，或解析结果为 {@code null}（文件为空）时抛出
     */
    public static ObfuscatedMapping load(String resource) {
        ObfuscatedMapping cached = CACHE.get(resource);
        if (cached != null) {
            return cached;
        }
        if (FAILED.contains(resource)) {
            // 之前已判定失败：直接抛同一语义的异常，不再触碰 classpath。
            throw new IllegalStateException("mapping resource unavailable: " + resource);
        }
        ObfuscatedMapping parsed;
        try {
            parsed = parse(resource);
        } catch (RuntimeException failure) {
            FAILED.add(resource);
            throw failure;
        }
        // 并发下可能已被别的线程放入同值实例；统一返回缓存中的那一个，保证全进程唯一。
        ObfuscatedMapping existing = CACHE.putIfAbsent(resource, parsed);
        return existing != null ? existing : parsed;
    }

    /**
     * 直接从 JSON 文本构造映射实例，绕过 classpath 资源查找与全局缓存。
     *
     * <p>包可见：供测试用内联 fixture 验证候选顺序与降级契约。{@link #load(String)} 的缓存按资源
     * 路径键控且成功/失败都永久驻留，直接用本入口可以避免测试之间互相污染。
     *
     * @param json 映射表 JSON 文本
     * @return 解析出的映射实例
     * @throws IllegalStateException JSON 为空白或 {@code null} 时抛出
     */
    static ObfuscatedMapping fromJson(String json) {
        Document document = new Gson().fromJson(json, Document.class);
        if (document == null) {
            throw new IllegalStateException("mapping json is empty");
        }
        return new ObfuscatedMapping(document.version, document.classes);
    }

    /** 实际的资源读取与反序列化；仅由 {@link #load(String)} 在缓存未命中时调用。 */
    private static ObfuscatedMapping parse(String resource) {
        InputStream in = ObfuscatedMapping.class.getResourceAsStream(resource);
        if (in == null) {
            throw new IllegalStateException("mapping resource not found: " + resource);
        }
        try {
            InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8);
            // 必须显式指定 UTF-8：默认字符集随平台变化，会让含非 ASCII 的映射表解析失败。
            Document document = new Gson().fromJson(reader, Document.class);
            // Gson 直接填充这些包可见字段，无需额外的反序列化配置。
            if (document == null) {
                throw new IllegalStateException("mapping resource is empty: " + resource);
            }
            return new ObfuscatedMapping(document.version, document.classes);
        } finally {
            // 映射表随 jar 打包且生命周期与类加载器一致，关闭失败不应掩盖真正的加载结果。
            try {
                in.close();
            } catch (Exception ignored) {
                // 关闭流失败无补救手段，吞掉即可。
            }
        }
    }

    /** 表中记录的版本号。 */
    public String version() {
        return version;
    }

    /** 表中收录的类数量。 */
    public int classCount() {
        return classes.size();
    }

    /** 返回“obfuscated <版本> (N classes)”形式的描述。 */
    @Override
    public String describe() {
        return "obfuscated " + version + " (" + classes.size() + " classes)";
    }

    /** 恒为 false。 */
    @Override
    public boolean isIdentity() {
        return false;
    }

    /**
     * 查表得到运行期类名；即候选列表首项（命名空间优先级的第一项，缺表时退化为规范名）。
     */
    @Override
    public String className(ClassType type) {
        return classCandidates(type.canonicalName()).get(0);
    }

    /** {@inheritDoc}（表未覆盖或该版本不存在时只剩规范名一项）。 */
    @Override
    public List<String> classNameCandidates(ClassType type) {
        return classCandidates(type.canonicalName());
    }

    /**
     * 组装某个规范类的候选名：命名空间优先级 → 空值跳过 → 去重 → 末尾追加规范名。
     *
     * <p>表未收录或标记 {@code absent} 时只返回规范名一项（后者附带一次性诊断日志）。
     */
    private List<String> classCandidates(String canonicalName) {
        ClassData data = classes.get(key(canonicalName));
        if (data == null) {
            // 表根本没覆盖这个类型：回退为规范名，SRG/Forge 等未混淆构建仍可按可读名反射。
            return Collections.singletonList(canonicalName);
        }
        if (data.absent) {
            reportAbsent(canonicalName);
            return Collections.singletonList(canonicalName);
        }
        LinkedHashSet<String> names = pickNames(data.names);
        names.add(canonicalName);
        return new ArrayList<String>(names);
    }

    /** 一次性诊断日志集合：同一个「本版本不存在」的类只提示一次，避免逐帧刷屏。 */
    private static final Set<String> ABSENT_REPORTED = ConcurrentHashMap.newKeySet();

    /** 记录一次「该规范类在本表对应版本里不存在」的诊断，使回退不再是静默行为（L-106）。 */
    private static void reportAbsent(String canonicalName) {
        if (ABSENT_REPORTED.add(canonicalName)) {
            System.out.println("[nocturne] mapping: " + canonicalName
                    + " has no counterpart in this version; reflection will use the canonical name");
        }
    }

    /**
     * 查表得到运行期方法名；即候选列表首项（命名空间优先级的第一项，缺表时退化为规范名）。
     *
     * <p>{@code descriptor} 不参与查找：表中以规范名为键，每个命名空间的描述符在
     * {@link #methodCandidates} 里与名字成对给出。
     */
    @Override
    public String methodName(ClassType owner, String canonicalName, String descriptor) {
        return methodCandidates(owner, canonicalName).get(0).name();
    }

    /** 查表得到运行期字段名；即候选列表首项（命名空间优先级的第一项，缺表时退化为规范名）。 */
    @Override
    public String fieldName(ClassType owner, String canonicalName) {
        MemberData member = fieldMember(owner, canonicalName);
        if (member != null && member.absent) {
            reportAbsentMember(owner.canonicalName(), canonicalName, "field");
            return canonicalName;
        }
        if (member != null) {
            for (String namespace : NAMESPACES) {
                String name = memberName(member, namespace);
                if (name != null) {
                    return name;
                }
            }
        }
        return canonicalName;
    }

    /**
     * {@inheritDoc}
     *
     * <p>顺序即尝试顺序：**原版混淆名 → Fabric intermediary → Forge SRG → NeoForge → 规范名**。
     * 末项是为未混淆/兜底环境准备的——那里的成员用的是可读名。
     */
    @Override
    public List<String> fieldNameCandidates(ClassType owner, String canonicalName) {
        MemberData member = fieldMember(owner, canonicalName);
        if (member != null && member.absent) {
            reportAbsentMember(owner.canonicalName(), canonicalName, "field");
            return Collections.singletonList(canonicalName);
        }
        LinkedHashSet<String> names = pickNames(member == null ? null : member.names);
        names.add(canonicalName);
        return new ArrayList<String>(names);
    }

    /** {@inheritDoc}（顺序同 {@link #methodCandidates}，此处只保留名字）。 */
    @Override
    public List<String> methodNameCandidates(ClassType owner, String canonicalName,
                                            String descriptor) {
        List<MethodCandidate> candidates = methodCandidates(owner, canonicalName);
        List<String> names = new ArrayList<String>(candidates.size());
        for (MethodCandidate candidate : candidates) {
            names.add(candidate.name());
        }
        return names;
    }

    /**
     * {@inheritDoc}
     *
     * <p>每条候选都带上<b>该命名空间自己的</b>描述符（同名命名空间缺描述符时为 {@code null}，
     * 调用方按实参类型推断形参）；末尾追加的规范名没有命名空间，描述符恒为 {@code null}。
     * 名字按命名空间优先级去重：两个命名空间同名时以优先级更高者的描述符为准。
     */
    @Override
    public List<MethodCandidate> methodCandidates(ClassType owner, String canonicalName) {
        MemberData member = methodMember(owner, canonicalName);
        if (member != null && member.absent) {
            reportAbsentMember(owner.canonicalName(), canonicalName, "method");
            return Collections.singletonList(new MethodCandidate(canonicalName, null));
        }
        // LinkedHashMap：保序 + 以名字为键天然去重；value 为该名字配套的描述符。
        LinkedHashMap<String, String> picked = new LinkedHashMap<String, String>();
        if (member != null) {
            for (String namespace : NAMESPACES) {
                String name = memberName(member, namespace);
                if (name != null) {
                    picked.put(name, memberSignature(member, namespace));
                }
            }
        }
        if (!picked.containsKey(canonicalName)) {
            picked.put(canonicalName, null);
        }
        List<MethodCandidate> candidates = new ArrayList<MethodCandidate>(picked.size());
        for (Map.Entry<String, String> entry : picked.entrySet()) {
            candidates.add(new MethodCandidate(entry.getKey(), entry.getValue()));
        }
        return candidates;
    }

    /**
     * 按固定命名空间优先级取出表中全部非空名（去重、保序）。
     *
     * @param names 命名空间 → 名 的表，可为 {@code null}
     * @return 保序去重后的非空名集合（可能为空）
     */
    private static LinkedHashSet<String> pickNames(Map<String, String> names) {
        LinkedHashSet<String> picked = new LinkedHashSet<String>(NAMESPACES.length + 1);
        if (names != null) {
            for (String namespace : NAMESPACES) {
                String value = names.get(namespace);
                if (value != null && !value.isEmpty()) {
                    picked.add(value);
                }
            }
        }
        return picked;
    }

    /** 取某成员在指定命名空间下的名；缺省或空串时返回 {@code null}。 */
    private static String memberName(MemberData member, String namespace) {
        if (member.names == null) {
            return null;
        }
        String value = member.names.get(namespace);
        return value == null || value.isEmpty() ? null : value;
    }

    /** 取某方法在指定命名空间下的 JNI 描述符；缺省或空串时返回 {@code null}。 */
    private static String memberSignature(MemberData member, String namespace) {
        if (member.signatures == null) {
            return null;
        }
        String value = member.signatures.get(namespace);
        return value == null || value.isEmpty() ? null : value;
    }

    /** 取某类的方法成员记录；类或方法表缺失时返回 {@code null}。 */
    private MemberData methodMember(ClassType owner, String canonicalName) {
        ClassData data = classes.get(key(owner.canonicalName()));
        return data == null || data.methods == null ? null : data.methods.get(canonicalName);
    }

    /** 取某类的字段成员记录；类或字段表缺失时返回 {@code null}。 */
    private MemberData fieldMember(ClassType owner, String canonicalName) {
        ClassData data = classes.get(key(owner.canonicalName()));
        return data == null || data.fields == null ? null : data.fields.get(canonicalName);
    }

    /** 一次性诊断集合：同一个「本版本不存在」的成员只提示一次。 */
    private static final Set<String> ABSENT_MEMBERS_REPORTED = ConcurrentHashMap.newKeySet();

    /** 记录一次「该规范成员在本表对应版本里不存在」的诊断，使成员级回退不再静默。 */
    private static void reportAbsentMember(String owner, String member, String kind) {
        String key = owner + "#" + member;
        if (ABSENT_MEMBERS_REPORTED.add(key)) {
            System.out.println("[nocturne] mapping: " + kind + " " + key
                    + " does not exist in this version; callers will see the canonical name"
                    + " (module behaviour for it is unavailable here)");
        }
    }

    /** 按规范类名查运行期类名，供不便构造 {@link ClassType} 的调用方使用。 */
    public String className(String canonicalName) {
        return classCandidates(canonicalName).get(0);
    }

    /**
     * 取表中为某方法记录的 JNI 描述符；即候选列表首项的描述符（与首项名字同命名空间配套）。
     *
     * <p>未记录、该版本不存在、或首项是末尾兜底的规范名时返回 {@code null}。本查询<b>不</b>打
     * absent 诊断日志（诊断只由 {@link #methodName} / {@link #methodCandidates} 触发），
     * 与旧契约的「纯查询」定位一致。
     */
    @Override
    public String methodDescriptor(ClassType owner, String canonicalName) {
        return firstMethodDescriptor(methodMember(owner, canonicalName));
    }

    /**
     * {@inheritDoc}
     *
     * <p>实现与 {@link #methodDescriptor} 同一口径：首个非空命名空间名存在且其签名为非空即返回
     * {@code true}。这样「表里有零参方法（签名为 {@code ()...}）」与「表里没有这个方法」在调用方
     * 视角下不再混同（H-35）。同为纯查询，不触发诊断日志。
     */
    @Override
    public boolean hasMethodDescriptor(ClassType owner, String canonicalName) {
        return firstMethodDescriptor(methodMember(owner, canonicalName)) != null;
    }

    /**
     * 方法候选首项的描述符：命名空间优先级里第一个非空名字所配套的签名。
     *
     * <p>与 {@link #methodCandidates} 的首项口径严格一致；成员缺失或 absent 时返回 {@code null}。
     */
    private static String firstMethodDescriptor(MemberData member) {
        if (member == null || member.absent) {
            return null;
        }
        for (String namespace : NAMESPACES) {
            if (memberName(member, namespace) != null) {
                return memberSignature(member, namespace);
            }
        }
        return null;
    }

    /** 统一键形式：把点号分隔的规范名转换为斜杠分隔，与 JSON 键一致。 */
    private static String key(String canonicalName) {
        return canonicalName.replace('.', '/');
    }
}
