package net.fabricmc.api;

/**
 * Compile-time stub of the Fabric loader's entry point interface.
 *
 * <p>Not packaged: at runtime the real {@code net.fabricmc.api.ModInitializer} is provided by
 * Fabric's class loader and resolved by name. Keeping a stub here means the agent module needs
 * no Fabric dependency and stays independent of the loader version.
 */
public interface ModInitializer {
    void onInitialize();
}
