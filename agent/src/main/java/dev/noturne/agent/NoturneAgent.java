package dev.noturne.agent;

import dev.noturne.agent.transform.FrameHookTransformer;
import dev.noturne.client.NoturneClient;

import java.lang.instrument.Instrumentation;

/**
 * The agent entry points.
 *
 * <p>{@code premain} runs when the jar is passed via {@code -javaagent} or loaded by an attach;
 * {@code agentmain} runs when the jar is attached to an already-running JVM. Both hand off to
 * {@link NoturneClient} on a dedicated thread: doing real work here would block class loading of
 * the very JVM we are trying to instrument.
 */
public final class NoturneAgent {

    private NoturneAgent() {
    }

    public static void premain(String agentArgs, Instrumentation instrumentation) {
        start("premain", agentArgs, instrumentation);
    }

    public static void agentmain(String agentArgs, Instrumentation instrumentation) {
        start("agentmain", agentArgs, instrumentation);
    }

    private static void start(String via, final String agentArgs, final Instrumentation instrumentation) {
        log("agent loaded via " + via
                + (agentArgs == null || agentArgs.isEmpty() ? "" : " args=[" + agentArgs + "]"));

        Thread init = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    NoturneClient.boot(instrumentation);
                    installFrameHook(instrumentation);
                    installOverlay(instrumentation);
                } catch (Throwable t) {
                    log("client initialisation failed: " + t);
                    t.printStackTrace();
                }
            }
        }, "noturne-init");
        init.setDaemon(true);
        init.start();
    }

    /**
     * Patches the frame swap point so {@code NoturneRuntime.onFrame()} runs once per frame.
     *
     * <p>Two render paths exist across the supported range: LWJGL2's {@code Display.update()}
     * (Minecraft ≤ 1.12) and LWJGL3's {@code GLFW.glfwSwapBuffers(long)} (1.13+). Whichever class is
     * actually loaded decides which one gets patched. The swap point is used because drawing there
     * survives to the screen — drawing at the start of the frame would be overwritten.
     */
    private static void installFrameHook(Instrumentation instrumentation) {
        if (instrumentation == null) {
            log("no Instrumentation: frame hook skipped");
            return;
        }
        ClassLoader loader = ClassLoader.getSystemClassLoader();
        String targetClass;
        FrameHookTransformer transformer;

        if (dev.noturne.client.game.Reflect.load("org.lwjgl.opengl.Display", loader) != null) {
            targetClass = "org.lwjgl.opengl.Display";
            transformer = new FrameHookTransformer(targetClass, "update", "()V",
                    "dev/noturne/client/runtime/NoturneRuntime", "onFrame");
        } else if (dev.noturne.client.game.Reflect.load("org.lwjgl.glfw.GLFW", loader) != null) {
            targetClass = "org.lwjgl.glfw.GLFW";
            transformer = new FrameHookTransformer(targetClass, "glfwSwapBuffers", "(J)V",
                    "dev/noturne/client/runtime/NoturneRuntime", "onFrame");
        } else {
            log("no LWJGL render entry point found; frame hook skipped");
            return;
        }

        try {
            instrumentation.addTransformer(transformer, true);
        } catch (Throwable t) {
            log("could not register transformer: " + t);
            return;
        }
        for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
            if (targetClass.equals(loaded.getName())) {
                try {
                    instrumentation.retransformClasses(loaded);
                    log("frame hook installed on " + targetClass);
                } catch (Throwable t) {
                    log("retransform failed: " + t);
                }
                return;
            }
        }
        log(targetClass + " not loaded yet; frame hook will apply when it loads");
    }

    /** Binds GL and attaches the click-GUI overlay to the frame hook. */
    private static void installOverlay(Instrumentation instrumentation) {
        try {
            // Resolve GL through the classes the game actually loaded: with Fabric/Forge the game
            // runs under an isolated class loader, so the system loader cannot see LWJGL.
            Class<?> gl11 = findLoadedClass(instrumentation, "org.lwjgl.opengl.GL11");
            if (gl11 == null) {
                log("GL11 not loaded yet; GUI overlay disabled");
                return;
            }
            dev.noturne.ui.gl.GlApi gl = dev.noturne.ui.gl.GlApi.bind(gl11);
            if (gl == null) {
                log("GL11 has no usable entry points; GUI overlay disabled");
                return;
            }

            dev.noturne.client.mapping.Mapping mapping = selectMapping(instrumentation);
            dev.noturne.client.game.GameBridge bridge =
                    new dev.noturne.client.game.GameBridge(instrumentation, mapping);
            dev.noturne.ui.gl.TextRenderer font = dev.noturne.ui.gl.MinecraftTextRenderer.bind(bridge);
            NoturneClient.get().setGameBridge(bridge);
            log("mapping: " + mapping.describe() + "; font: " + (font == null ? "none" : "game"));

            dev.noturne.ui.gl.UiBackend backend = selectBackend(gl, gl11, font);
            dev.noturne.ui.gl.GuiOverlay overlay = new dev.noturne.ui.gl.GuiOverlay(
                    NoturneClient.get().modules(), backend, gl11.getClassLoader());
            dev.noturne.client.runtime.NoturneRuntime.addListener(overlay);
            dev.noturne.client.runtime.NoturneRuntime.trace(true);
            log("GUI overlay attached; backend=" + backend.backendName()
                    + "; input=" + overlay.keyBackend() + "; Right Shift toggles it");
        } catch (Throwable t) {
            log("overlay attach failed: " + t);
        }
    }

    /**
     * Chooses the draw backend for this runtime.
     *
     * <p>Binding the core-profile entry points succeeding is what tells us the game is 1.13+; on
     * 1.8.9 those functions do not exist and the fixed-function renderer is the only option.
     */
    private static dev.noturne.ui.gl.UiBackend selectBackend(
            dev.noturne.ui.gl.GlApi fixed, Class<?> gl11, dev.noturne.ui.gl.TextRenderer font) {
        dev.noturne.ui.gl.ModernGlApi modern =
                dev.noturne.ui.gl.ModernGlApi.bind(gl11.getClassLoader());
        if (modern != null) {
            return new dev.noturne.ui.gl.ModernRenderer(modern, font);
        }
        return new dev.noturne.ui.gl.GlRenderer(fixed, font);
    }

    private static Class<?> findLoadedClass(Instrumentation instrumentation, String className) {
        if (instrumentation == null) {
            return null;
        }
        for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
            if (className.equals(loaded.getName())) {
                return loaded;
            }
        }
        return null;
    }

    /** Picks the mapping table for the running build; falls back to identity. */
    private static dev.noturne.client.mapping.Mapping selectMapping(Instrumentation instrumentation) {
        // 26.1+ ships unobfuscated: the canonical class name is loaded as-is, so the mapping is the
        // identity. Only older builds need the 1.8.9 table.
        if (isLoaded(instrumentation, dev.noturne.client.mapping.ClassType.MINECRAFT.canonicalName())) {
            return new dev.noturne.client.mapping.IdentityMapping();
        }
        try {
            return dev.noturne.client.mapping.ObfuscatedMapping.load("/mappings-1.8.9.json");
        } catch (Throwable t) {
            return new dev.noturne.client.mapping.IdentityMapping();
        }
    }

    private static boolean isLoaded(Instrumentation instrumentation, String className) {
        if (instrumentation == null) {
            return false;
        }
        for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
            if (className.equals(loaded.getName())) {
                return true;
            }
        }
        return false;
    }

    private static void log(String message) {
        System.out.println("[noturne] " + message);
    }
}
