package dev.nocturne.client.module.modules.render.hud;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.HudModule;
import dev.nocturne.client.module.Module;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.ColorValue;
import dev.nocturne.client.value.ModeValue;
import dev.nocturne.client.value.NumberValue;
import dev.nocturne.client.value.TextValue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Supplier;

/** 真实的 ActiveModules 列表及其排序、过滤、后缀与显示样式。 */
public final class TextGuiSettingsModule extends HudModule {
    private final ModeValue sort = add(new ModeValue("Sort", "Length", "Length", "Alphabetical"));
    private final ModeValue suffix = add(new ModeValue("Suffix", "Basic", "None", "Basic", "Extended"));
    private final BooleanValue shadow = add(new BooleanValue("Shadow", true));
    private final BooleanValue watermark = add(new BooleanValue("Watermark", true));
    private final BooleanValue background = add(new BooleanValue("Render Background", true));
    private final NumberValue scale = add(new NumberValue("Scale", 1.0, 0.1, 2.0, 0.1));
    private final TextValue hidden = add(new TextValue("Hide Modules", "ESP,NameTags,StorageESP", 512));
    private final TextValue customText = add(new TextValue("Custom Text", "", 128));
    private final ColorValue color = add(new ColorValue("Custom Color", 0xFFFFFFFF));
    private final Supplier<String> line = new Supplier<String>() {
        @Override
        public String get() {
            NocturneClient client = NocturneClient.get();
            if (client == null) return "";
            List<String> lines = new ArrayList<String>();
            for (Module module : client.modules().all()) {
                if (!module.isEnabled() || hidden(module.name())) continue;
                String text = module.name();
                if (suffix.is("Extended")) text += " [" + module.category() + "]";
                lines.add(text);
            }
            if (sort.is("Length")) {
                lines.sort(Comparator.comparingInt(String::length).reversed());
            } else {
                lines.sort(String.CASE_INSENSITIVE_ORDER);
            }
            String prefix = customText.get().isEmpty() ? "" : customText.get() + " ";
            if (watermark.get()) prefix += "nocturne ";
            if (!prefix.isEmpty() && !lines.isEmpty()) lines.set(0, prefix + lines.get(0));
            return String.join("\n", lines);
        }
    };

    private boolean hidden(String name) {
        for (String token : hidden.get().split("[,;\\s]+")) {
            if (name.equalsIgnoreCase(token)) return true;
        }
        return false;
    }

    public static TextGuiSettingsModule current() {
        NocturneClient client = NocturneClient.get();
        return client == null ? null : (TextGuiSettingsModule) client.modules().get("Text GUI");
    }

    public float fontScale() { return scale.asFloat() * 10.0f; }
    /** 玩家设置的倍率（0.1–2.0）；HUD 行统一乘它。 */
    public float scaleFactor() { return scale.asFloat(); }
    public int color() { return color.argb(); }
    public boolean shadow() { return shadow.get(); }
    public boolean background() { return background.get(); }

    @Override public String name() { return "Text GUI"; }
    @Override public Category category() { return Category.RENDER; }
    @Override protected String hudId() { return "active-modules"; }
    @Override protected Supplier<String> hudText() { return line; }
}
