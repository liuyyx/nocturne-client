package dev.noturne.client.mapping;

import com.google.gson.Gson;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Table-driven mapping for obfuscated builds (1.8.9 – 1.21.x).
 *
 * <p>Data format (produced by the mapping tooling):
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
 * <p>Class keys are canonical Mojmap names with {@code /} separators; lookups accept either
 * {@code /} or {@code .}. Unknown entries fall through to the canonical name, so a partially
 * covered table still lets reflection try the readable name (which is what Forge/SRG builds need).
 */
public final class ObfuscatedMapping implements Mapping {

    /** Raw JSON shape. */
    static final class MemberData {
        String name;
        String signature;
    }

    static final class ClassData {
        String name;
        Map<String, MemberData> methods;
        Map<String, MemberData> fields;
    }

    static final class Document {
        String version;
        Map<String, ClassData> classes;
    }

    private final String version;
    private final Map<String, ClassData> classes;

    private ObfuscatedMapping(String version, Map<String, ClassData> classes) {
        this.version = version == null ? "unknown" : version;
        this.classes = classes == null ? new HashMap<String, ClassData>() : classes;
    }

    /** Loads a mapping table from a classpath resource, e.g. {@code /mappings-1.8.9.json}. */
    public static ObfuscatedMapping load(String resource) {
        InputStream in = ObfuscatedMapping.class.getResourceAsStream(resource);
        if (in == null) {
            throw new IllegalStateException("mapping resource not found: " + resource);
        }
        try {
            InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8);
            Document document = new Gson().fromJson(reader, Document.class);
            if (document == null) {
                throw new IllegalStateException("mapping resource is empty: " + resource);
            }
            return new ObfuscatedMapping(document.version, document.classes);
        } finally {
            try {
                in.close();
            } catch (Exception ignored) {
                // nothing useful to do
            }
        }
    }

    public String version() {
        return version;
    }

    public int classCount() {
        return classes.size();
    }

    @Override
    public String describe() {
        return "obfuscated " + version + " (" + classes.size() + " classes)";
    }

    @Override
    public boolean isIdentity() {
        return false;
    }

    @Override
    public String className(ClassType type) {
        ClassData data = classes.get(key(type.canonicalName()));
        return data == null || data.name == null ? type.canonicalName() : data.name;
    }

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

    /** Runtime class name lookup by canonical name rather than {@link ClassType}. */
    public String className(String canonicalName) {
        ClassData data = classes.get(key(canonicalName));
        return data == null || data.name == null ? canonicalName : data.name;
    }

    /** JNI descriptor recorded for a method, when the table has one. */
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

    private static String key(String canonicalName) {
        return canonicalName.replace('.', '/');
    }
}
