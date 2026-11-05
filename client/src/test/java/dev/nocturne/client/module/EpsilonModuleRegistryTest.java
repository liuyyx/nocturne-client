package dev.nocturne.client.module;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.TestSupport;
import dev.nocturne.client.value.ModeValue;
import dev.nocturne.client.value.Value;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Epsilon 128 项权威注册表与关键视觉模式契约。 */
class EpsilonModuleRegistryTest {
    private static final List<String> EXPECTED = Arrays.asList(
            "Client Setting",
            "Aim Bot", "Anchor Blast", "Anti Bot", "Backtrack", "Auto Clicker", "Auto Dtap",
            "Auto Hit Crystal", "Auto Mend", "Auto Throw", "Auto Totem", "Auto Weapon", "Criticals",
            "Crystal Aura", "Crystal Blocker", "Feet Trap", "Double Anchor", "Elytra Combat", "Fake Lag",
            "Hover Totem", "Kill Aura", "Key Pearl", "Mace Aura", "Multi Aura", "Packet Mine", "Safe Anchor",
            "Safe Crystal", "Silent Aim", "Spear Kill", "Trigger Bot", "Zealot Crystal Plus",
            "Auto Armor", "Auto Crossbow Release", "Auto Firework", "Auto KouZi", "Auto Ban", "Auto MLG",
            "Auto Tool", "Bed Nuker", "Break Cooldown", "Chest Aura", "Disabler", "Elytra Swap", "Fake Player",
            "Ghost Hand", "Input Disabler", "Inv Manager", "Jump Cooldown", "Key Friend", "Multi Task", "NoFall",
            "No Rotate", "Packet Eat", "Player Alarms", "Server Guard", "Sound FX", "Stealer", "Timer",
            "Use Cooldown", "Auto Queue",
            "Elytra Fly", "Elytra Grim Acc", "Dolphin", "Auto Sprint", "Auto Build", "Blink", "Eagle", "Auto Map",
            "Flight", "GUI Move", "Hole Snap", "Jump Reset", "Keep Sprint", "Movement Fix", "No Slowdown", "Phase",
            "Reverse Step", "Safe Walk", "Scaffold", "Great Wall", "Speed", "Step", "Strafe", "Stuck",
            "Target Strafe", "Telly", "Velocity",
            "Kill Effect", "Anti Alias", "Aspect Ratio", "Better Chat", "Better Scoreboard", "Block ESP",
            "Block Highlight", "Camera Clip", "Chams", "Crystal Chams", "CustomSky", "Derp", "ESP 2D", "Filter",
            "Free Camera", "Fullbright", "Game Animation", "Hand View", "Hat", "Health", "Hit Particles", "Hole ESP",
            "Item Physics", "Jump Circle", "Logout Spots", "Mas Effects", "Motion Blur", "NameTags", "No Ghost Blocks",
            "No Render", "Particles", "Pop Chams", "Shaders", "Sneak Tweak", "Title Replace", "Totem Animation",
            "TNT Timer", "Trajectories", "World Tweaks", "Xray", "Ysm Model"
    );

    @BeforeEach
    void reset() {
        TestSupport.resetClient();
    }

    @AfterEach
    void clean() {
        TestSupport.resetClient();
    }

    @Test
    void all128EpsilonModulesAreRegisteredUnderSourceNames() {
        NocturneClient client = NocturneClient.boot(null);
        for (String expected : EXPECTED) {
            assertNotNull(find(client, expected), expected);
        }
    }

    @Test
    void visualModeContractsMatchEpsilon() {
        NocturneClient client = NocturneClient.boot(null);
        assertOptions(client, "Scaffold", "Mode", "TellyBridge", "GodBridge", "Heypixel", "Hypixel", "Elowen", "RavenB4");
        assertOptions(client, "Kill Aura", "Aim Mode", "Normal", "Polar", "Ai");
        assertOptions(client, "Kill Aura", "ESP Mode", "CaptureMark", "Circle", "Firefly", "Deobf");
        assertOptions(client, "Fullbright", "Mode", "Gamma", "Potion");
        assertOptions(client, "Motion Blur", "Algorithm", "Backwards", "Centered");
        assertOptions(client, "Ysm Model", "Model", "Auto", "Alice", "Boqijiang", "ToyotaAe86", "Yatuoya", "Custom");
    }

    private static void assertOptions(NocturneClient client, String module, String setting, String... options) {
        Module found = find(client, module);
        assertNotNull(found, module);
        for (Value<?> value : found.values()) {
            if (value.name().equals(setting) && value instanceof ModeValue) {
                List<String> actual = ((ModeValue) value).options();
                assertTrue(actual.containsAll(Arrays.asList(options)), module + "/" + setting + "=" + actual);
                return;
            }
        }
        throw new AssertionError(module + " missing mode " + setting);
    }

    private static Module find(NocturneClient client, String name) {
        String wanted = normalize(name);
        for (Module module : client.modules().all()) {
            if (normalize(module.name()).equals(wanted)) {
                return module;
            }
        }
        return null;
    }

    private static String normalize(String name) {
        return name.replace(" ", "").toLowerCase(java.util.Locale.ROOT);
    }
}
