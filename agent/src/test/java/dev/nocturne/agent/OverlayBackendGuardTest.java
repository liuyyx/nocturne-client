package dev.nocturne.agent;

import org.junit.jupiter.api.Test;

import java.lang.instrument.Instrumentation;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 强制 GL 不得绕过 SDL / extractor 的上下文所有权。 */
class OverlayBackendGuardTest {

    private static final class NamedLoader extends ClassLoader {
        private final boolean sdlPresent;
        private final boolean extractorPresent;

        NamedLoader(boolean sdlPresent, boolean extractorPresent) {
            super(OverlayBackendGuardTest.class.getClassLoader());
            this.sdlPresent = sdlPresent;
            this.extractorPresent = extractorPresent;
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            if ("org.lwjgl.sdl.SDLVideo".equals(name) && !sdlPresent) {
                throw new ClassNotFoundException(name);
            }
            if ("net.minecraft.client.gui.GuiGraphicsExtractor".equals(name) && !extractorPresent) {
                throw new ClassNotFoundException(name);
            }
            return super.loadClass(name, resolve);
        }
    }

    private static OverlayBootstrap bootstrap(ClassLoader loader) throws Exception {
        java.lang.reflect.Constructor<OverlayBootstrap> constructor =
                OverlayBootstrap.class.getDeclaredConstructor(
                        ClassLoader.class, Instrumentation.class, int.class, boolean.class);
        constructor.setAccessible(true);
        return constructor.newInstance(loader, null, 0, false);
    }

    @Test
    void missingGl11DoesNotClaimModernStack() throws Exception {
        java.lang.reflect.Method method = OverlayBootstrap.class.getDeclaredMethod(
                "isModernRenderStack", Class.class);
        method.setAccessible(true);
        assertFalse((Boolean) method.invoke(null, new Object[]{null}),
                "GL11 尚未就绪时不能按现代栈继续构造后端");
    }

    @Test
    void legacyStackMayStillForceGl() throws Exception {
        OverlayBootstrap bootstrap = bootstrap(new NamedLoader(false, false));
        assertTrue(bootstrap.forceGlAllowed(true));
        assertFalse(bootstrap.forceGlAllowed(false));
    }

    @Test
    void sdlAndExtractorStacksRejectForcedGl() throws Exception {
        assertFalse(bootstrap(new NamedLoader(true, false)).forceGlAllowed(true));
        assertFalse(bootstrap(new NamedLoader(false, true)).forceGlAllowed(true));
    }
}
