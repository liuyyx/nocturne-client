package dev.noturne.core.load;

import java.util.Map;

/**
 * 直接从内存中的 {@code 类名 -> 字节码} 表加载类。
 *
 * <p>解析顺序是「内存优先」：只要名字在表里，就在这里定义，即便父加载器也能提供同名类，
 * 从而允许 payload 遮蔽自己的类；表里没有的名字则委派给父加载器（JDK 类、游戏类）。
 */
public final class MemoryClassLoader extends ClassLoader {

    /** 类全名到字节码的映射；构造后不应再被修改。 */
    private final Map<String, byte[]> definitions;

    /**
     * 构造一个内存类加载器。
     *
     * @param parent      父加载器；{@code null} 表示使用引导加载器（bootstrap）
     * @param definitions 类全名到字节码的映射，不会被复制
     */
    public MemoryClassLoader(ClassLoader parent, Map<String, byte[]> definitions) {
        super(parent);
        this.definitions = definitions;
    }

    /** @return 构造时传入的字节码表本身（非副本） */
    public Map<String, byte[]> definitions() {
        return definitions;
    }

    /**
     * 覆盖 {@link ClassLoader#loadClass(String, boolean)}：先查内存表，未命中再委派父加载器。
     *
     * <p>{@code synchronized} 沿用父类语义，保证同一名字只被定义一次（类加载器的线程安全约束）。
     *
     * @param name    类的全名（点分形式）
     * @param resolve 是否立即解析该类
     */
    @Override
    protected synchronized Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        Class<?> loaded = findLoadedClass(name);
        if (loaded == null) {
            byte[] bytes = definitions.get(name);
            if (bytes == null) {
                // 表中没有：交给父加载器（JDK 类、游戏类）
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
