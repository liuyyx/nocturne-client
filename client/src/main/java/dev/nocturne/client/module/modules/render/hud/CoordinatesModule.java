package dev.nocturne.client.module.modules.render.hud;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.action.ActionApi;
import dev.nocturne.client.action.PlayerState;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.HudModule;
import dev.nocturne.client.value.ModeValue;

import java.util.Locale;
import java.util.function.Supplier;

/** 本地玩家坐标 HUD，支持 OpenVape 的水平/垂直文本布局。 */
public final class CoordinatesModule extends HudModule {
    private final ModeValue displayType = add(new ModeValue("Display Type", "Horizontal", "Horizontal", "Vertical"));
    private final Supplier<String> line = new Supplier<String>() {
        @Override
        public String get() {
            NocturneClient client = NocturneClient.get();
            ActionApi actions = client == null ? null : client.actions();
            PlayerState state = actions == null ? PlayerState.empty() : actions.readState();
            if (!state.present()) {
                return "";
            }
            return format(displayType.is("Vertical"), state.x(), state.y(), state.z());
        }
    };

    /**
     * 坐标文本：Horizontal 一行，Vertical 真正三行（HUD 渲染按 {@code \n} 分行）。
     *
     * @param vertical 是否垂直布局
     * @param x        X
     * @param y        Y
     * @param z        Z
     * @return 待显示文本
     */
    static String format(boolean vertical, double x, double y, double z) {
        if (vertical) {
            return String.format(Locale.ROOT, "X %.1f\nY %.1f\nZ %.1f", x, y, z);
        }
        return String.format(Locale.ROOT, "XYZ %.1f / %.1f / %.1f", x, y, z);
    }

    @Override
    public String name() {
        return "Coords";
    }

    @Override
    public Category category() {
        return Category.RENDER;
    }

    @Override
    protected String hudId() {
        return "coords";
    }

    @Override
    protected Supplier<String> hudText() {
        return line;
    }
}
