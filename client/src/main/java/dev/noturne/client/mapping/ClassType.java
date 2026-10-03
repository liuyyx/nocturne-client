package dev.noturne.client.mapping;

/**
 * The game classes the client talks about, named canonically (Mojang mappings).
 *
 * <p>On unobfuscated builds (Minecraft 26.1+) these are the real runtime names. On obfuscated
 * builds a {@link Mapping} translates them to whatever the running jar actually exposes.
 */
public enum ClassType {

    MINECRAFT("net.minecraft.client.Minecraft"),
    OPTIONS("net.minecraft.client.Options"),
    LOCAL_PLAYER("net.minecraft.client.player.LocalPlayer"),
    REMOTE_PLAYER("net.minecraft.client.player.RemotePlayer"),
    CLIENT_LEVEL("net.minecraft.client.multiplayer.ClientLevel"),
    PLAYER("net.minecraft.world.entity.player.Player"),
    LIVING_ENTITY("net.minecraft.world.entity.LivingEntity"),
    ENTITY("net.minecraft.world.entity.Entity"),
    MULTI_PLAYER_GAME_MODE("net.minecraft.client.multiplayer.MultiPlayerGameMode"),
    SCREEN("net.minecraft.client.gui.screens.Screen"),
    FONT_RENDERER("net.minecraft.client.gui.FontRenderer"),
    ITEM_STACK("net.minecraft.world.item.ItemStack"),
    WINDOW("com.mojang.blaze3d.platform.Window");

    private final String canonicalName;

    ClassType(String canonicalName) {
        this.canonicalName = canonicalName;
    }

    /** Fully-qualified name under Mojang mappings. */
    public String canonicalName() {
        return canonicalName;
    }
}
