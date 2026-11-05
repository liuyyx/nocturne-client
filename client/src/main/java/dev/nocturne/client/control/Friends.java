package dev.nocturne.client.control;

import dev.nocturne.client.game.GameBridge;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** Epsilon 兼容的全局好友表；名称按忽略大小写归一，线程安全。 */
public final class Friends {
    private static final Set<String> NAMES = ConcurrentHashMap.newKeySet();

    private Friends() {
    }

    public static boolean toggle(String name) {
        if (name == null || name.trim().isEmpty()) {
            return false;
        }
        String key = name.trim().toLowerCase(java.util.Locale.ROOT);
        if (NAMES.add(key)) {
            return true;
        }
        NAMES.remove(key);
        return false;
    }

    public static boolean isFriend(String name) {
        return name != null && NAMES.contains(name.trim().toLowerCase(java.util.Locale.ROOT));
    }

    public static boolean isFriend(GameBridge bridge, Object entity) {
        return bridge != null && isFriend(bridge.entityName(entity));
    }

    public static void clear() {
        NAMES.clear();
    }
}
