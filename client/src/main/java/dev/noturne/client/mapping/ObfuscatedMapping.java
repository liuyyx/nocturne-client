package dev.noturne.client.mapping;

import com.google.gson.Gson;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

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
 */
public final class ObfuscatedMapping implements Mapping {

    /** 映射表原始 JSON 结构中的一条成员记录（方法或字段共用）。 */
    static final class MemberData {
        /** 运行期成员名。 */
        String name;
        /** 该成员的 JNI 描述符；表未记录时为 {@code null}。 */
        String signature;
    }

    /** 单个类的映射记录。 */
    static final class ClassData {
        /** 运行期类名。 */
        String name;
        /** 规范方法名 → 成员记录。 */
        Map<String, MemberData> methods;
        /** 规范字段名 → 成员记录。 */
        Map<String, MemberData> fields;
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
     * {@link Document}。
     *
     * @param resource 资源的 classpath 绝对路径
     * @return 加载完成的映射实例
     * @throws IllegalStateException 资源不存在，或解析结果为 {@code null}（文件为空）时抛出
     */
    public static ObfuscatedMapping load(String resource) {
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
        return data == null || data.name == null ? type.canonicalName() : data.name;
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
            if (member != null && member.name != null) {
                return member.name;
            }
        }
        return canonicalName;
    }

    /** 按规范类名查运行期类名，供不便构造 {@link ClassType} 的调用方使用。 */
    public String className(String canonicalName) {
        ClassData data = classes.get(key(canonicalName));
        return data == null || data.name == null ? canonicalName : data.name;
    }

    /** 取表中为某方法记录的 JNI 描述符；未记录时返回 {@code null}。 */
    @Override
    public String methodDescriptor(ClassType owner, String canonicalName) {
        ClassData data = classes.get(key(owner.canonicalName()));
        if (data != null && data.methods != null) {
            MemberData member = data.methods.get(canonicalName);
            if (member != null) {
                return member.signature;
            }
        }
        return null;
    }

    /** 统一键形式：把点号分隔的规范名转换为斜杠分隔，与 JSON 键一致。 */
    private static String key(String canonicalName) {
        return canonicalName.replace('.', '/');
    }
}
