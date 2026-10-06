package dev.nocturne.client.mapping;

import com.google.gson.Gson;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 混淆构建（1.8.9 – 1.21.x）的表驱动映射。
 *
 * <p>数据格式（由映射工具产出）：
 * <pre>
 * {
 *   "version": "1.8.9",
 *   "classes": {
 *     "net/minecraft/client/Minecraft": {
 *       "name": "ave",
 *       "methods": { "getInstance": { "name": "A", "signature": "()Lave;" } },
 *       "fields":  { "player": { "name": "h" } }
 *     }
 *   }
 * }
 * </pre>
 *
 * <p>类的键是使用 {@code /} 分隔的规范 Mojmap 名；查询时 {@code /} 与 {@code .} 两种写法
 * 均可。未收录的条目回退为规范名，因此即使表覆盖不完整，反射仍可尝试可读名
 * （Forge/SRG 构建正是这种情形）。
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

    /** 映射表原始 JSON 结构中的一条成员记录（方法或字段共用）。 */
    static final class MemberData {
        /** 运行期成员名。 */
        String name;
        /** 该成员的 JNI 描述符；表未记录时为 {@code null}。 */
        String signature;
        /**
         * 该成员在<b>本表对应的版本里不存在</b>（例如 {@code Entity#isDead} 在 26.2/26.3 已被改名）。
         *
         * <p>为 true 时 {@link #name} 为 {@code null}：查询会打印一次性诊断并回退为规范名，
         * 让「这个版本没有这个成员」在日志里可见，而不是等反射抛异常或被静默吞掉。
         */
        boolean absent;
    }

    /** 单个类的映射记录。 */
    static final class ClassData {
        /** 运行期类名；标记为 {@link #absent} 时为 {@code null}。 */
        String name;
        /** 规范方法名 → 成员记录。 */
        Map<String, MemberData> methods;
        /** 规范字段名 → 成员记录。 */
        Map<String, MemberData> fields;
        /**
         * 该规范类在<b>本表对应的版本里不存在</b>（例如 1.8.9 没有 Blaze3D 的
         * {@code com.mojang.blaze3d.platform.Window}）。为 true 时 {@link #name} 必须为 {@code null}，
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

    /** 查表得到运行期类名；表未收录时回退为规范名。 */
    @Override
    public String className(ClassType type) {
        ClassData data = classes.get(key(type.canonicalName()));
        if (data == null) {
            // 表根本没覆盖这个类型：回退为规范名，SRG/Forge 等未混淆构建仍可按可读名反射。
            return type.canonicalName();
        }
        if (data.absent) {
            reportAbsent(type.canonicalName());
            return type.canonicalName();
        }
        return data.name == null ? type.canonicalName() : data.name;
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
     * 查表得到运行期方法名；任一层缺失时回退为规范名。
     *
     * <p>{@code descriptor} 当前不参与查找：表中以规范名为键，描述符仅在需要消歧时
     * 通过 {@link #methodDescriptor(ClassType, String)} 单独取用。
     */
    @Override
    public String methodName(ClassType owner, String canonicalName, String descriptor) {
        ClassData data = classes.get(key(owner.canonicalName()));
        if (data != null && data.methods != null) {
            MemberData member = data.methods.get(canonicalName);
            if (member != null && member.absent) {
                reportAbsentMember(owner.canonicalName(), canonicalName, "method");
                return canonicalName;
            }
            if (member != null && member.name != null) {
                return member.name;
            }
        }
        return canonicalName;
    }

    /** 查表得到运行期字段名；任一层缺失时回退为规范名。 */
    @Override
    public String fieldName(ClassType owner, String canonicalName) {
        ClassData data = classes.get(key(owner.canonicalName()));
        if (data != null && data.fields != null) {
            MemberData member = data.fields.get(canonicalName);
            if (member != null && member.absent) {
                reportAbsentMember(owner.canonicalName(), canonicalName, "field");
                return canonicalName;
            }
            if (member != null && member.name != null) {
                return member.name;
            }
        }
        return canonicalName;
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
        ClassData data = classes.get(key(canonicalName));
        if (data == null) {
            return canonicalName;
        }
        if (data.absent) {
            reportAbsent(canonicalName);
            return canonicalName;
        }
        return data.name == null ? canonicalName : data.name;
    }

    /** 取表中为某方法记录的 JNI 描述符；未记录或该版本不存在时返回 {@code null}。 */
    @Override
    public String methodDescriptor(ClassType owner, String canonicalName) {
        ClassData data = classes.get(key(owner.canonicalName()));
        if (data != null && data.methods != null) {
            MemberData member = data.methods.get(canonicalName);
            if (member != null && !member.absent) {
                return member.signature;
            }
        }
        return null;
    }

    /**
     * 表中是否确实为该规范方法记录了 JNI 描述符。
     *
     * <p>与 {@link #methodDescriptor} 同一口径：只有方法条目存在且其 {@code signature} 非空时返回
     * {@code true}。这样「表里有零参方法（签名为 {@code ()...}）」与「表里没有这个方法」在调用方
     * 视角下不再混同（H-35）。
     */
    @Override
    public boolean hasMethodDescriptor(ClassType owner, String canonicalName) {
        ClassData data = classes.get(key(owner.canonicalName()));
        if (data != null && data.methods != null) {
            MemberData member = data.methods.get(canonicalName);
            return member != null && !member.absent && member.signature != null;
        }
        return false;
    }

    /** 统一键形式：把点号分隔的规范名转换为斜杠分隔，与 JSON 键一致。 */
    private static String key(String canonicalName) {
        return canonicalName.replace('.', '/');
    }
}
