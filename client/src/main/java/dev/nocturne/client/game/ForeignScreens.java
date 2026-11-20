package dev.nocturne.client.game;

import java.util.Locale;

/** 已知外部客户端的原生 Screen 判定，供叠加层与压制守卫共用。 */
public final class ForeignScreens {
    private static final String[] FOREIGN_PACKAGES = {
            "top.fpsmaster.",
    };

    private ForeignScreens() {
    }

    /** @return 类名是否属于已知外部客户端界面 */
    public static boolean isForeign(String className) {
        if (className == null) {
            return false;
        }
        String name = className.toLowerCase(Locale.ROOT);
        for (String prefix : FOREIGN_PACKAGES) {
            if (name.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }
}
