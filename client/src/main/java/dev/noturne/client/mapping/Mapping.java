package dev.noturne.client.mapping;

/**
 * Translates canonical (Mojang) names into the names the running JVM actually exposes.
 *
 * <p>Two implementations are planned: an identity mapping for unobfuscated builds (26.1+), and a
 * table-driven mapping for obfuscated builds (1.8.9 – 1.21.x). Modules are written against the
 * canonical names only, so they run unchanged on either.
 */
public interface Mapping {

    /** Human-readable description of the active mapping, e.g. {@code identity} or {@code mojmap-1.21.4}. */
    String describe();

    /** Runtime class name for a known game class. */
    String className(ClassType type);

    /** Runtime name for a method, given the canonical name and JNI descriptor. */
    String methodName(ClassType owner, String canonicalName, String descriptor);

    /** Runtime name for a field on a known game class. */
    String fieldName(ClassType owner, String canonicalName);

    /**
     * JNI descriptor recorded for a method, when the table has one.
     *
     * <p>Needed because obfuscated builds reuse short method names; the descriptor is what
     * disambiguates them. Identity mappings return {@code null}.
     */
    default String methodDescriptor(ClassType owner, String canonicalName) {
        return null;
    }

    /** True when canonical names are already the runtime names. */
    boolean isIdentity();
}
