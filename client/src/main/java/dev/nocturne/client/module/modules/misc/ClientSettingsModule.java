package dev.nocturne.client.module.modules.misc;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.NumberValue;

/** 全局 GUI 设置：实际界面缩放与多键绑定行为。 */
public final class ClientSettingsModule extends GameModule {
    private final NumberValue guiScale = add(new NumberValue("GUI scale", 1, 1, 4, 1));
    private final BooleanValue multiKeybinding = add(new BooleanValue("Multi Keybinding", true));
    private int appliedScale = -1;

    public ClientSettingsModule() {
        super("Client Setting", Category.MISC);
    }

    @Override
    public void onTick() {
        int scale = guiScale.asInt();
        if (scale != appliedScale) {
            System.setProperty("nocturne.ui.scale", Integer.toString(scale));
            appliedScale = scale;
        }
    }

    public static boolean multiKeybinding() {
        NocturneClient client = NocturneClient.get();
        ClientSettingsModule module = client == null ? null
                : (ClientSettingsModule) client.modules().get("GUI");
        return module == null || module.multiKeybinding.get();
    }
}
