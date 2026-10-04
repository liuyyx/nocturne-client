package dev.noturne.core.load;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLStreamHandler;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 直接从内存中的 {@code 类名 -> 字节码} 表加载类。
 *
 * <p>解析顺序是「内存优先」：只要名字在表里，就在这里定义，即便父加载器也能提供同名类，
 * 从而允许 payload 遮蔽自己的类；表里没有的名字则委派给父加载器（JDK 类、游戏类）。
 *
 * <p>字节码表在构造时被防御性复制，且不对外暴露可变的内部状态：表本身包装为不可修改视图
 * 由 {@link #definitions()} 返回副本，字节数组永远是本加载器私有的，从而与类加载锁之间不存在数据竞争。
 */
public final class MemoryClassLoader extends ClassLoader {

    /** 类全名到字节码的映射；构造时复制，此后不再变化。 */
    private final Map<String, byte[]> definitions;

    /**
     * 构造一个内存类加载器。
     *
     * @param parent      父加载器；{@code null} 表示使用引导加载器（bootstrap）
     * @param definitions 类全名到字节码的映射，会被防御性复制（包括每个字节数组）
     */
    public MemoryClassLoader(ClassLoader parent, Map<String, byte[]> definitions) {
        super(parent);
        Map<String, byte[]> copy = new LinkedHashMap<String, byte[]>();
        if (definitions != null) {
            for (Map.Entry<String, byte[]> entry : definitions.entrySet()) {
                byte[] value = entry.getValue();
                copy.put(entry.getKey(), value == null ? null : value.clone());
            }
        }
        this.definitions = Collections.unmodifiableMap(copy);
    }

    /**
     * 取得字节码表的只读快照。
     *
     * <p>返回新的 Map，字节数组也被复制，因此调用方修改结果既不影响本加载器，也不构成数据竞争。
     *
     * @return 类全名到字节码副本的不可修改映射
     */
    public Map<String, byte[]> definitions() {
        Map<String, byte[]> copy = new LinkedHashMap<String, byte[]>(definitions.size());
        for (Map.Entry<String, byte[]> entry : definitions.entrySet()) {
            byte[] value = entry.getValue();
            copy.put(entry.getKey(), value == null ? null : value.clone());
        }
        return Collections.unmodifiableMap(copy);
    }

    /**
     * 覆盖 {@link ClassLoader#loadClass(String, boolean)}：先查内存表，未命中再委派父加载器。
     *
     * <p>仅对「在内存表中定义」这一步持有按类名分配的可重入锁（{@link #getClassLoadingLock}），
     * 委派父加载器的过程不持有本加载器的锁——避免退化成全类加载串行，也避免与父加载器形成锁顺序反转。
     *
     * @param name    类的全名（点分形式）
     * @param resolve 是否立即解析该类
     */
    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        synchronized (getClassLoadingLock(name)) {
            Class<?> loaded = findLoadedClass(name);
            if (loaded != null) {
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
            byte[] bytes = definitions.get(name);
            if (bytes != null) {
                try {
                    loaded = defineClass(name, bytes, 0, bytes.length);
                } catch (LinkageError e) {
                    // 表的 key 与字节码内声明的类名不符时，defineClass 抛 NoClassDefFoundError；
                    // 按 loadClass 的签名契约转成 ClassNotFoundException。
                    throw new ClassNotFoundException("cannot define class " + name + " from memory", e);
                }
                if (resolve) {
                    resolveClass(loaded);
                }
                return loaded;
            }
        }
        // 表中没有：交给父加载器（JDK 类、游戏类），此时不持有本加载器的名字锁
        return super.loadClass(name, resolve);
    }

    /**
     * 覆写资源查找：pack 中的非类条目（如 {@code assets/...}）也要能被 payload 读取。
     *
     * @param name 资源路径（斜杠分隔），也接受点分类名形式
     */
    @Override
    public URL getResource(String name) {
        byte[] bytes = resourceBytes(name);
        if (bytes != null) {
            return inMemoryUrl(name, bytes);
        }
        return super.getResource(name);
    }

    /**
     * 覆写资源流查找：命中内存表时返回内存字节流。
     *
     * @param name 资源路径（斜杠分隔），也接受点分类名形式
     */
    @Override
    public InputStream getResourceAsStream(String name) {
        byte[] bytes = resourceBytes(name);
        if (bytes != null) {
            return new ByteArrayInputStream(bytes);
        }
        return super.getResourceAsStream(name);
    }

    /** 按资源路径查表；同时兼容斜杠路径与点分类名两种键风格。 */
    private byte[] resourceBytes(String name) {
        if (name == null) {
            return null;
        }
        byte[] bytes = definitions.get(name);
        if (bytes == null) {
            bytes = definitions.get(name.replace('/', '.'));
        }
        return bytes;
    }

    /** 用内存字节构造一个一次性 URL（协议为 {@code memory}），供 {@link #getResource} 使用。 */
    private static URL inMemoryUrl(final String name, final byte[] bytes) {
        URLStreamHandler handler = new URLStreamHandler() {
            @Override
            protected URLConnection openConnection(URL url) throws IOException {
                return new URLConnection(url) {
                    @Override
                    public void connect() {
                        // 内存资源，无需建立连接
                    }

                    @Override
                    public InputStream getInputStream() {
                        return new ByteArrayInputStream(bytes);
                    }
                };
            }

            @Override
            protected synchronized InetAddress getHostAddress(URL url) {
                return null;
            }
        };
        try {
            return new URL("memory", null, -1, "/" + name, handler);
        } catch (MalformedURLException e) {
            return null;
        }
    }
}
