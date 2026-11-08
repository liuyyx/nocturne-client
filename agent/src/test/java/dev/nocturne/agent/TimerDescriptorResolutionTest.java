package dev.nocturne.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * 26.x 的表只记名字（描述符为空），timer 钩子必须从**已加载类**反射出真实描述符。
 *
 * <p>这里不启动 JVM，只验证 {@code NocturneAgent.resolveDescriptor} 的两个结论：
 * 命中时给出 {@code ()F}，没有该方法时返回 {@code null}（钩子据此显式标记不可用，
 * 而不是静默 continue 让开关亮着却不生效）。
 */
class TimerDescriptorResolutionTest {

    /** DeltaTracker 的最小替身：0 参、返回 float。 */
    static final class DeltaTracker {
        float getGameTimeDeltaTicks() {
            return 1.0F;
        }
    }

    /** 没有任何方法可用的类。 */
    static final class Empty {
    }

    private static Instrumentation instrumentation(Class<?>... loaded) {
        final List<Class<?>> classes = Arrays.asList(loaded);
        return (Instrumentation) Proxy.newProxyInstance(
                TimerDescriptorResolutionTest.class.getClassLoader(),
                new Class<?>[] {Instrumentation.class},
                (proxy, method, args) -> {
                    if ("getAllLoadedClasses".equals(method.getName())) {
                        return classes.toArray(new Class<?>[0]);
                    }
                    return null;
                });
    }

    private static String resolve(Class<?> type, String methodName) throws Exception {
        Method resolve = NocturneAgent.class.getDeclaredMethod("resolveDescriptor",
                Instrumentation.class, String.class, String.class, String.class);
        resolve.setAccessible(true);
        return (String) resolve.invoke(null, instrumentation(type), type.getName(), methodName,
                "()F|()D");
    }

    @Test
    void floatGetterYieldsDescriptor() throws Exception {
        assertEquals("()F", resolve(DeltaTracker.class, "getGameTimeDeltaTicks"));
    }

    @Test
    void missingMethodYieldsNull() throws Exception {
        assertNull(resolve(Empty.class, "getGameTimeDeltaTicks"));
    }
}
