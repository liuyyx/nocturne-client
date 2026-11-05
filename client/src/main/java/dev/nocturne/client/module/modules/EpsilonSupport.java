package dev.nocturne.client.module.modules;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.action.PlayerState;
import dev.nocturne.client.control.RotationController;
import dev.nocturne.client.control.TargetManager;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Module;

import java.util.Random;

/** Epsilon 移植模块共享的映射动作与目标工具，不包含任何运行版本判断。 */
public final class EpsilonSupport {
    public static final Random RANDOM = new Random();

    private EpsilonSupport() {
    }

    public static int findHotbar(GameBridge bridge, String... rules) {
        if (bridge == null) {
            return -1;
        }
        for (int slot = 0; slot < 9; slot++) {
            Object stack = bridge.hotbarStack(slot);
            for (String rule : rules) {
                if (bridge.itemMatches(stack, rule)) {
                    return slot;
                }
            }
        }
        return -1;
    }

    public static int findInventory(GameBridge bridge, String... rules) {
        if (bridge == null) {
            return -1;
        }
        for (int slot = 0; slot < 36; slot++) {
            Object stack = bridge.inventoryStack(slot);
            for (String rule : rules) {
                if (bridge.itemMatches(stack, rule)) {
                    return slot;
                }
            }
        }
        return -1;
    }

    public static void aim(GameBridge bridge, PlayerState state, double x, double y, double z,
                           float speed, int priority) {
        if (bridge == null || state == null || !state.present()) {
            return;
        }
        double dx = x - state.x();
        double dz = z - state.z();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = RotationController.approach(state.yaw(), RotationController.yawTo(dx, dz), speed);
        float pitch = RotationController.approach(state.pitch(),
                RotationController.pitchTo(horizontal, y - state.y() - 1.62), speed);
        NocturneClient client = NocturneClient.get();
        if (client != null) {
            client.rotations().request(yaw, pitch, priority, 150_000_000L);
        }
    }

    public static TargetManager.Options targets(double range, boolean players, boolean mobs,
                                                 boolean invisibles, TargetManager.Sort sort) {
        TargetManager.Options options = new TargetManager.Options();
        options.range = range;
        options.players = players;
        options.mobs = mobs;
        options.invisibles = invisibles;
        options.maxAngle = 360.0;
        options.sort = sort;
        return options;
    }

    public static Module module(String name) {
        NocturneClient client = NocturneClient.get();
        if (client == null) {
            return null;
        }
        Module exact = client.modules().get(name);
        if (exact != null) {
            return exact;
        }
        String wanted = normalize(name);
        for (Module candidate : client.modules().all()) {
            if (normalize(candidate.name()).equals(wanted)) {
                return candidate;
            }
        }
        return null;
    }

    public static boolean enabled(String name) {
        Module module = module(name);
        return module != null && module.isEnabled();
    }

    private static String normalize(String name) {
        return name == null ? "" : name.replace(" ", "").toLowerCase(java.util.Locale.ROOT);
    }

    public static Object gameMode(GameBridge bridge) {
        return bridge == null ? null : bridge.readField(bridge.minecraft(), ClassType.MINECRAFT, "gameMode");
    }

    public static boolean startDestroy(GameBridge bridge, Object pos, Object direction) {
        return bridge.callMappedStatus(gameMode(bridge), ClassType.MULTI_PLAYER_GAME_MODE,
                "startDestroyBlock", pos, direction);
    }

    public static boolean continueDestroy(GameBridge bridge, Object pos, Object direction) {
        return bridge.callMappedStatus(gameMode(bridge), ClassType.MULTI_PLAYER_GAME_MODE,
                "continueDestroyBlock", pos, direction);
    }

    public static boolean stopDestroy(GameBridge bridge) {
        return bridge.callMappedStatus(gameMode(bridge), ClassType.MULTI_PLAYER_GAME_MODE, "stopDestroyBlock");
    }

    public static int randomInt(int min, int max) {
        int low = Math.min(min, max);
        int high = Math.max(min, max);
        return low + RANDOM.nextInt(high - low + 1);
    }

    public static long randomLong(long min, long max) {
        long low = Math.min(min, max);
        long high = Math.max(min, max);
        return low + (long) (RANDOM.nextDouble() * (high - low + 1L));
    }

    public static int rgba(int red, int green, int blue, int alpha) {
        return (alpha & 255) << 24 | (red & 255) << 16 | (green & 255) << 8 | blue & 255;
    }
}
