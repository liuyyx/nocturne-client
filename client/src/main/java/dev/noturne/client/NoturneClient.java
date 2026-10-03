package dev.noturne.client;

import dev.noturne.client.event.EventBus;
import dev.noturne.client.hud.HudSink;
import dev.noturne.client.module.ModuleRegistry;

import java.lang.instrument.Instrumentation;

/**
 * Client singleton: owns the event bus and the module registry, and is the single place the
 * agent / mod-loader entry points hand control to.
 *
 * <p>{@link #boot} is idempotent — several entry points can fire (a mod loader initialiser and
 * an attach, for instance) and only the first one installs the client.
 */
public final class NoturneClient {

    private static volatile NoturneClient instance;

    private final EventBus eventBus = new EventBus();
    private final ModuleRegistry modules = new ModuleRegistry();
    private final Instrumentation instrumentation;
    private volatile HudSink hudSink;
    private volatile dev.noturne.client.game.GameBridge gameBridge;

    private NoturneClient(Instrumentation instrumentation) {
        this.instrumentation = instrumentation;
    }

    /** HUD line sink supplied by the UI module; {@code null} until one is installed. */
    public HudSink hudSink() {
        return hudSink;
    }

    public void setHudSink(HudSink sink) {
        this.hudSink = sink;
    }

    /** Reflection bridge to the running game; {@code null} outside a game JVM. */
    public dev.noturne.client.game.GameBridge gameBridge() {
        return gameBridge;
    }

    public void setGameBridge(dev.noturne.client.game.GameBridge bridge) {
        this.gameBridge = bridge;
    }

    public static NoturneClient boot(Instrumentation instrumentation) {
        NoturneClient local = instance;
        if (local == null) {
            synchronized (NoturneClient.class) {
                local = instance;
                if (local == null) {
                    local = new NoturneClient(instrumentation);
                    local.install();
                    instance = local;
                }
            }
        }
        return local;
    }

    /** True once the client has been installed in this JVM. */
    public static boolean isRunning() {
        return instance != null;
    }

    /** The installed client, or {@code null} before {@link #boot}. */
    public static NoturneClient get() {
        return instance;
    }

    private void install() {
        registerModules();
        log("client installed (modules=" + modules.size() + ")");
        // Later phases: mapping initialisation, render/input hooks, HUD.
    }

    /** Modules that are part of the client out of the box. */
    private void registerModules() {
        modules.register(new dev.noturne.client.module.modules.WatermarkModule());
        modules.register(new dev.noturne.client.module.modules.FullBrightModule());
        modules.register(new dev.noturne.client.module.modules.SprintModule());
        modules.register(new dev.noturne.client.module.modules.AutoRespawnModule());
    }

    public EventBus events() {
        return eventBus;
    }

    public ModuleRegistry modules() {
        return modules;
    }

    public Instrumentation instrumentation() {
        return instrumentation;
    }

    public void shutdown() {
        for (dev.noturne.client.module.Module module : modules.all()) {
            module.setEnabled(false);
        }
        log("client shut down");
    }

    private static void log(String message) {
        System.out.println("[noturne] " + message);
    }
}
