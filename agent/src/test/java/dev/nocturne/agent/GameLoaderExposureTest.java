package dev.nocturne.agent;

import dev.nocturne.client.mapping.ObfuscatedMapping;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;

import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.net.URL;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** agent jar 必须暴露给映射命中的游戏加载器，不能另用硬编码类名扫描。 */
class GameLoaderExposureTest {
    private static final class GameLoader extends ClassLoader {
        private URL exposed;

        public void addUrlFwd(URL url) {
            exposed = url;
        }

        Class<?> minecraft() {
            ClassWriter writer = new ClassWriter(0);
            writer.visit(Opcodes.V1_8, Opcodes.ACC_PUBLIC, "net/minecraft/class_310",
                    null, "java/lang/Object", null);
            writer.visitEnd();
            byte[] bytes = writer.toByteArray();
            return defineClass("net.minecraft.class_310", bytes, 0, bytes.length);
        }
    }

    @Test
    void exposesThroughFabricMappingCandidates() throws Exception {
        GameLoader loader = new GameLoader();
        Class<?> minecraft = loader.minecraft();
        Instrumentation instrumentation = (Instrumentation) Proxy.newProxyInstance(
                Instrumentation.class.getClassLoader(), new Class<?>[]{Instrumentation.class},
                (proxy, method, args) -> {
                    if ("getAllLoadedClasses".equals(method.getName())) {
                        return new Class<?>[]{minecraft};
                    }
                    throw new AssertionError(method.getName());
                });
        expose(loader, instrumentation, false);
    }

    @Test
    void exposesThroughCachedLoaderWithoutInstrumentation() throws Exception {
        expose(new GameLoader(), null, true);
    }

    private static void expose(GameLoader loader, Instrumentation instrumentation,
                               boolean cached) throws Exception {
        Field mapping = NocturneAgent.class.getDeclaredField("selectedMapping");
        Field gameLoader = NocturneAgent.class.getDeclaredField("cachedGameLoader");
        mapping.setAccessible(true);
        gameLoader.setAccessible(true);
        Object previousMapping = mapping.get(null);
        Object previousLoader = gameLoader.get(null);
        try {
            mapping.set(null, ObfuscatedMapping.load("/mappings-1.21.1.json"));
            gameLoader.set(null, cached ? loader : null);
            Method expose = NocturneAgent.class.getDeclaredMethod(
                    "exposeAgentJarToGameLoader", Instrumentation.class);
            expose.setAccessible(true);
            expose.invoke(null, instrumentation);
            assertEquals(NocturneAgent.class.getProtectionDomain().getCodeSource().getLocation(),
                    loader.exposed, "agent jar 必须真正转发到游戏加载器");
        } finally {
            mapping.set(null, previousMapping);
            gameLoader.set(null, previousLoader);
        }
    }
}
