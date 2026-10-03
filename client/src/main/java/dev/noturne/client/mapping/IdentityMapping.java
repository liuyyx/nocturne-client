package dev.noturne.client.mapping;

/**
 * Mapping for unobfuscated builds (Minecraft 26.1+): canonical names are the runtime names.
 *
 * <p>Method and field signatures still matter to JNI, but they are discovered lazily through
 * {@code java.lang.Class} reflection rather than a mapping file, so nothing needs translating here.
 */
public final class IdentityMapping implements Mapping {

    @Override
    public String describe() {
        return "identity (unobfuscated build)";
    }

    @Override
    public String className(ClassType type) {
        return type.canonicalName();
    }

    @Override
    public String methodName(ClassType owner, String canonicalName, String descriptor) {
        return canonicalName;
    }

    @Override
    public String fieldName(ClassType owner, String canonicalName) {
        return canonicalName;
    }

    @Override
    public boolean isIdentity() {
        return true;
    }
}
