package dev.noturne.ui.gl;

import dev.noturne.client.game.Reflect;
import dev.noturne.client.module.ModuleRegistry;
import dev.noturne.client.runtime.FrameListener;
import dev.noturne.ui.clickgui.ClickGui;

import java.lang.reflect.Method;

/**
 * Draws the click GUI over the game and toggles it from the keyboard.
 *
 * <p>Invoked once per frame from the patched swap point. Key state is polled rather than hooked,
 * because polling one key per frame is cheaper and far less invasive than rewriting callbacks.
 *
 * <p>Two input backends exist because the client spans two eras: LWJGL2 ({@code org.lwjgl.input.Keyboard},
 * Minecraft ≤ 1.12) and LWJGL3/GLFW ({@code org.lwjgl.glfw.GLFW}, 1.13+). Whichever is present is used.
 */
public final class GuiOverlay implements FrameListener {

    /** Key code for the right shift key; identical in LWJGL2 and GLFW. */
    public static final int KEY_RIGHT_SHIFT = 344;

    private static final int GLFW_PRESS = 1;

    private final ClickGui gui;
    private final UiBackend renderer;
    private final Method lwjgl2IsKeyDown;
    private final Method glfwGetKey;
    private final Method glfwGetCurrentContext;
    private boolean wasDown;
    private boolean loggedFirstDraw;

    public GuiOverlay(ModuleRegistry registry, UiBackend renderer) {
        this(registry, renderer, Thread.currentThread().getContextClassLoader());
    }

    public GuiOverlay(ModuleRegistry registry, UiBackend renderer, ClassLoader loader) {
        this.gui = new ClickGui(registry);
        this.renderer = renderer;

        Class<?> keyboard = Reflect.load("org.lwjgl.input.Keyboard", loader);
        Class<?> glfw = Reflect.load("org.lwjgl.glfw.GLFW", loader);

        this.lwjgl2IsKeyDown = keyboard == null ? null : Reflect.method(keyboard, "isKeyDown", int.class);
        this.glfwGetKey = glfw == null ? null : Reflect.method(glfw, "glfwGetKey", long.class, int.class);
        this.glfwGetCurrentContext =
                glfw == null ? null : Reflect.method(glfw, "glfwGetCurrentContext");
    }

    public ClickGui gui() {
        return gui;
    }

    /** Which input backend was found; useful in logs when a key "does nothing". */
    public String keyBackend() {
        if (lwjgl2IsKeyDown != null) {
            return "lwjgl2";
        }
        if (glfwGetKey != null && glfwGetCurrentContext != null) {
            return "glfw";
        }
        return "none";
    }

    @Override
    public void onFrame() {
        boolean down = pollKey();
        if (down && !wasDown) {
            gui.toggle();
        }
        wasDown = down;

        if (!gui.isOpen()) {
            return;
        }
        if (!loggedFirstDraw) {
            loggedFirstDraw = true;
            System.out.println("[noturne] click GUI opened");
        }
        gui.update(System.currentTimeMillis(), 0d, 0d);
        renderer.beginFrame();
        gui.render(renderer);
        renderer.endFrame();
    }

    private boolean pollKey() {
        if (lwjgl2IsKeyDown != null) {
            return Boolean.TRUE.equals(Reflect.call(lwjgl2IsKeyDown, null, KEY_RIGHT_SHIFT));
        }
        if (glfwGetKey != null && glfwGetCurrentContext != null) {
            Object context = Reflect.call(glfwGetCurrentContext, null);
            if (!(context instanceof Number)) {
                return false;
            }
            Object state = Reflect.call(glfwGetKey, null, ((Number) context).longValue(), KEY_RIGHT_SHIFT);
            return state instanceof Number && ((Number) state).intValue() == GLFW_PRESS;
        }
        return false;
    }
}
