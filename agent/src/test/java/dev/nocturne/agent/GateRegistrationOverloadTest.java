package dev.nocturne.agent;

import dev.nocturne.agent.transform.EmbeddedAsmLoader;
import dev.nocturne.client.bootstrap.GameplayHook;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.mapping.Mapping;
import dev.nocturne.client.mapping.MethodCandidate;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 混淆名相同不代表同一事件；实际定义并执行插桩后的所有重载。 */
class GateRegistrationOverloadTest {
    public static final class Subject {
        public Object screen;

        public void a(String screen) {
            this.screen = screen;
        }

        public void a(Object value) {
            screen = value;
        }

        public float a(float value) {
            return value + 1f;
        }

        public String a(Object a, Object b, Object c, Object d, Object e) {
            return "five";
        }
    }

    @Test
    void mappedSignatureDoesNotExpandIntoUnrelatedOverloads() throws Exception {
        exercise("(Ljava/lang/String;)V");
    }

    @Test
    void explicitFallbackDoesNotInferOtherOverloads() throws Exception {
        exercise(null);
    }

    private static void exercise(final String descriptor) throws Exception {
        Field selected = field("selectedMapping");
        Object previousMapping = selected.get(null);
        Map<Object, Object> bytes = map("GAMEPLAY_BYTES");
        Map<Object, Object> transformers = map("GAMEPLAY_TRANSFORMERS");
        Map<Object, Object> previousBytes = new HashMap<Object, Object>(bytes);
        Map<Object, Object> previousTransformers = new HashMap<Object, Object>(transformers);
        final boolean[] allow = {true};
        final int[] calls = {0};
        try {
            selected.set(null, new Mapping() {
                @Override public String describe() { return "overloaded-screen"; }
                @Override public String className(ClassType type) { return Subject.class.getName(); }
                @Override public String methodName(ClassType owner, String name, String desc) { return "a"; }
                @Override public String fieldName(ClassType owner, String name) { return name; }
                @Override public boolean isIdentity() { return false; }
                @Override public List<MethodCandidate> methodCandidates(ClassType owner, String name) {
                    return Collections.singletonList(new MethodCandidate("a", descriptor));
                }
            });
            bytes.clear();
            transformers.clear();
            bytes.put(Subject.class, originalBytes());
            Constructor<EmbeddedAsmLoader> constructor = EmbeddedAsmLoader.class.getDeclaredConstructor(
                    ClassLoader.class, Map.class);
            constructor.setAccessible(true);
            EmbeddedAsmLoader loader = constructor.newInstance(
                    Subject.class.getClassLoader(), Collections.<String, byte[]>emptyMap());
            Instrumentation instrumentation = (Instrumentation) Proxy.newProxyInstance(
                    Instrumentation.class.getClassLoader(), new Class<?>[]{Instrumentation.class},
                    (proxy, method, args) -> {
                        if ("getAllLoadedClasses".equals(method.getName())) {
                            return new Class<?>[]{Subject.class};
                        }
                        if ("addTransformer".equals(method.getName())
                                || "redefineClasses".equals(method.getName())) {
                            return null;
                        }
                        throw new AssertionError(method.getName());
                    });
            Method register = NocturneAgent.class.getDeclaredMethod("registerGate",
                    Instrumentation.class, EmbeddedAsmLoader.class, ClassType.class,
                    String.class, String.class, String.class, String.class, boolean.class, boolean.class);
            register.setAccessible(true);
            assertTrue((Boolean) register.invoke(null, instrumentation, loader, ClassType.MINECRAFT,
                    "setScreen", "(Ljava/lang/String;)V", "screenOpen", "Z", false, false));
            final byte[] patched = (byte[]) bytes.get(Subject.class);
            Class<?> type = new ClassLoader(Subject.class.getClassLoader()) {
                Class<?> define() {
                    return defineClass(Subject.class.getName(), patched, 0, patched.length);
                }
            }.define();
            Object subject = type.getConstructor().newInstance();
            GameplayHook.install(new GameplayHook.Sink() {
                @Override public boolean screenOpen(Object screen) {
                    calls[0]++;
                    return allow[0];
                }
            });
            type.getMethod("a", String.class).invoke(subject, "allowed");
            assertEquals("allowed", type.getField("screen").get(subject));
            allow[0] = false;
            type.getMethod("a", String.class).invoke(subject, "cancelled");
            assertEquals("allowed", type.getField("screen").get(subject), "取消必须只影响目标方法");
            Object unrelated = new Object();
            type.getMethod("a", Object.class).invoke(subject, unrelated);
            assertSame(unrelated, type.getField("screen").get(subject), "同形引用重载也不能被门控取消");
            assertEquals(3f, type.getMethod("a", float.class).invoke(subject, 2f));
            assertEquals("five", type.getMethod("a", Object.class, Object.class, Object.class,
                    Object.class, Object.class).invoke(subject, null, null, null, null, null));
            assertEquals(2, calls[0], "只有两次真正的屏幕切换可以触发事件");
        } finally {
            GameplayHook.install(null);
            selected.set(null, previousMapping);
            bytes.clear();
            bytes.putAll(previousBytes);
            transformers.clear();
            transformers.putAll(previousTransformers);
        }
    }

    private static byte[] originalBytes() throws Exception {
        String resource = Subject.class.getName().replace('.', '/') + ".class";
        try (InputStream in = Subject.class.getClassLoader().getResourceAsStream(resource)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buffer = new byte[4096];
            int read;
            while ((read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    private static Field field(String name) throws Exception {
        Field field = NocturneAgent.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Object> map(String name) throws Exception {
        return (Map<Object, Object>) field(name).get(null);
    }
}
