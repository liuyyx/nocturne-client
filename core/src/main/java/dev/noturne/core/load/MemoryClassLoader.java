package dev.noturne.core.load;

import java.util.Map;

/**
 * Loads classes straight from an in-memory {@code name -> bytecode} table.
 *
 * <p>Resolution is memory-first: a name present in the table is defined here even if the
 * parent could also load it, so the payload can shadow its own classes; everything else
 * falls through to the parent loader (JDK classes, game classes).
 */
public final class MemoryClassLoader extends ClassLoader {

    private final Map<String, byte[]> definitions;

    public MemoryClassLoader(ClassLoader parent, Map<String, byte[]> definitions) {
        super(parent);
        this.definitions = definitions;
    }

    public Map<String, byte[]> definitions() {
        return definitions;
    }

    @Override
    protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        Class<?> loaded = findLoadedClass(name);
        if (loaded == null) {
            byte[] bytes = definitions.get(name);
            if (bytes == null) {
                return super.loadClass(name, resolve);
            }
            loaded = defineClass(name, bytes, 0, bytes.length);
        }
        if (resolve) {
            resolveClass(loaded);
        }
        return loaded;
    }
}
