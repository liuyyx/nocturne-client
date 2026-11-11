package dev.nocturne.agent;

import dev.nocturne.agent.transform.CallbackHookTransformer;
import dev.nocturne.agent.transform.EntityRenderGateTransformer;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * attach（目标类已加载）路径的三条回归：
 *
 * <ul>
 *   <li>Chams 的 woven 判定必须按真实 hook 名 {@code chamsPre} 检查，而不是 {@code chams}；</li>
 *   <li>同一类的 shouldRender 门织入后，render 门仍必须能织入（方法级去重）；</li>
 *   <li>正确织入后要 redefine 并把状态登记为 {@code HOOK_WOVEN}。</li>
 * </ul>
 */
class AttachRegistrationTest {

    private static final String HOOK_OWNER = "dev/nocturne/client/bootstrap/GameplayHook";

    public static final class Subject {
        public void shouldRender(Object entity) {
        }

        public void render(Object entity) {
        }
    }

    @AfterEach
    void cleanUp() throws Exception {
        maps("GAMEPLAY_BYTES").clear();
        maps("GAMEPLAY_TRANSFORMERS").clear();
    }

    @Test
    void chamsUsesTheRealHookNameAndDualGatesBothWeave() throws Exception {
        byte[] original = subjectBytes();
        maps("GAMEPLAY_BYTES").put(Subject.class, original);

        int gate = register(new EntityRenderGateTransformer(Subject.class.getName(),
                "shouldRender", "(Ljava/lang/Object;)V", HOOK_OWNER),
                "shouldRender", "(Ljava/lang/Object;)V", "entityRenderGate");
        assertEquals(2, gate, "shouldRender gate must weave in attach mode");

        int renderGate = register(new EntityRenderGateTransformer(Subject.class.getName(),
                "render", "(Ljava/lang/Object;)V", HOOK_OWNER),
                "render", "(Ljava/lang/Object;)V", "entityRenderGate");
        assertEquals(2, renderGate,
                "render gate must not be skipped just because shouldRender already has the hook");

        int chams = register(new CallbackHookTransformer(Subject.class.getName(), "render",
                        "(Ljava/lang/Object;)V", HOOK_OWNER, "chamsPre", false, "chamsPost"),
                "render", "(Ljava/lang/Object;)V", "chamsPre");
        assertEquals(2, chams,
                "chams must be reported woven when chamsPre/chamsPost are actually inserted");

        byte[] patched = (byte[]) maps("GAMEPLAY_BYTES").get(Subject.class);
        assertTrue(dev.nocturne.agent.transform.TransformGuard.containsHookInMethod(patched,
                        Subject.class.getName(), "shouldRender", "(Ljava/lang/Object;)V",
                        HOOK_OWNER, "entityRenderGate"),
                "shouldRender gate must be present in the final bytes");
        assertTrue(dev.nocturne.agent.transform.TransformGuard.containsHookInMethod(patched,
                        Subject.class.getName(), "render", "(Ljava/lang/Object;)V",
                        HOOK_OWNER, "entityRenderGate"),
                "render gate must be present in the final bytes");
        assertTrue(dev.nocturne.agent.transform.TransformGuard.containsHookInMethod(patched,
                        Subject.class.getName(), "render", "(Ljava/lang/Object;)V",
                        HOOK_OWNER, "chamsPre"),
                "chams pre must be present in the final bytes");
    }

    @Test
    void entityFirstFallbackFindsRealRenderMethodsWhenTheTableIsEmpty() throws Exception {
        Method helper = NocturneAgent.class.getDeclaredMethod("entityFirstRenderCandidates",
                Instrumentation.class, dev.nocturne.client.mapping.Mapping.class, String.class);
        helper.setAccessible(true);
        dev.nocturne.client.mapping.Mapping mapping = new dev.nocturne.client.mapping.Mapping() {
            @Override
            public String describe() {
                return "attach-fallback";
            }

            @Override
            public String className(dev.nocturne.client.mapping.ClassType type) {
                return type == dev.nocturne.client.mapping.ClassType.ENTITY
                        ? Object.class.getName() : type.canonicalName();
            }

            @Override
            public String methodName(dev.nocturne.client.mapping.ClassType owner,
                                     String canonicalName, String descriptor) {
                return canonicalName;
            }

            @Override
            public String fieldName(dev.nocturne.client.mapping.ClassType owner,
                                    String canonicalName) {
                return canonicalName;
            }

            @Override
            public boolean isIdentity() {
                return false;
            }
        };
        @SuppressWarnings("unchecked")
        java.util.List<dev.nocturne.client.mapping.MethodCandidate> found =
                (java.util.List<dev.nocturne.client.mapping.MethodCandidate>) helper.invoke(null,
                        instrumentation(), mapping, Subject.class.getName());
        java.util.Set<String> names = new java.util.HashSet<String>();
        for (dev.nocturne.client.mapping.MethodCandidate candidate : found) {
            names.add(candidate.name() + candidate.descriptor());
        }
        assertTrue(names.contains("shouldRender(Ljava/lang/Object;)V"), names.toString());
        assertTrue(names.contains("render(Ljava/lang/Object;)V"), names.toString());
    }

    private static int register(ClassFileTransformer transformer, String method, String descriptor,
                                String hookMethod) throws Exception {
        Method register = NocturneAgent.class.getDeclaredMethod("registerGameplayTransformerState",
                Instrumentation.class, dev.nocturne.agent.transform.EmbeddedAsmLoader.class,
                ClassFileTransformer.class, String.class, String.class,
                String.class, String.class);
        register.setAccessible(true);
        return ((Integer) register.invoke(null, instrumentation(), null, transformer,
                Subject.class.getName(), method, descriptor, hookMethod)).intValue();
    }

    private static byte[] subjectBytes() throws Exception {
        String resource = Subject.class.getName().replace('.', '/') + ".class";
        try (InputStream in = Subject.class.getClassLoader().getResourceAsStream(resource)) {
            if (in == null) {
                throw new IllegalStateException("missing " + resource);
            }
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) >= 0) {
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    private static Map<Object, Object> maps(String name) throws Exception {
        java.lang.reflect.Field field = NocturneAgent.class.getDeclaredField(name);
        field.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<Object, Object> map = (Map<Object, Object>) field.get(null);
        return map;
    }

    /** 只实现注册路径需要的四个方法；其余默认返回。 */
    private static Instrumentation instrumentation() {
        InvocationHandler handler = new InvocationHandler() {
            @Override
            public Object invoke(Object proxy, Method method, Object[] args) {
                String name = method.getName();
                if ("getAllLoadedClasses".equals(name)) {
                    return new Class<?>[]{Subject.class};
                }
                if ("redefineClasses".equals(name) || "addTransformer".equals(name)
                        || "retransformClasses".equals(name)) {
                    return null;
                }
                Class<?> type = method.getReturnType();
                if (type == boolean.class) {
                    return Boolean.FALSE;
                }
                if (type == int.class) {
                    return Integer.valueOf(0);
                }
                if (type == long.class) {
                    return Long.valueOf(0L);
                }
                return null;
            }
        };
        return (Instrumentation) Proxy.newProxyInstance(AttachRegistrationTest.class.getClassLoader(),
                new Class<?>[]{Instrumentation.class}, handler);
    }
}
