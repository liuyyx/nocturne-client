package dev.nocturne.client.module.modules.render.hud;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.hud.HudAnchor;
import dev.nocturne.client.hud.HudElement;
import dev.nocturne.client.module.HudElementModule;
import dev.nocturne.client.module.Module;
import dev.nocturne.client.module.ModuleRegistry;
import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.client.render.RenderSupport;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.ColorValue;
import dev.nocturne.client.value.ModeValue;
import dev.nocturne.client.value.NumberValue;
import java.util.List;
import java.util.Locale;

/** 启用顺序列表。上游降级：无文字辉光、阴影或模糊；入场时间固定，故不提供 Slider Speed。 */
public final class ModuleListElementModule extends HudElementModule {
    private final ModeValue mode = add(new ModeValue("Mode", "Normal", "Simple", "Normal"));
    private final NumberValue horizontalPadding = add(new NumberValue("Horizontal Padding", 3, 0, 20, 0.5));
    private final NumberValue verticalPadding = add(new NumberValue("Vertical Padding", 2, 0, 20, 0.5));
    private final NumberValue lineWidth = add(new NumberValue("Line Width", 2, 0, 5, 0.5));
    private final BooleanValue suffix = add(new BooleanValue("Show Suffix", true));
    private final NumberValue fontSize = add(new NumberValue("Font Size", 13, 8, 32, 1));
    private final BooleanValue background = add(new BooleanValue("Background", true));
    private final ColorValue backgroundColor = add(new ColorValue("Background Color", 0x32000000));
    private final ModeValue colorMode = add(new ModeValue("Color Mode", "Single", "Single", "Double", "Rainbow", "Fade"));
    private final NumberValue gradientLength = add(new NumberValue("Double Gradient Length", 100, 10, 1000, 10));
    private final NumberValue rainbowSpeed = add(new NumberValue("Rainbow Speed", 1, 0.1, 5, 0.1));
    private final NumberValue rainbowSpread = add(new NumberValue("Rainbow Spread", 8, 0, 60, 1));
    private final NumberValue rainbowOffset = add(new NumberValue("Rainbow Hue Offset", 0, 0, 360, 1));
    private final NumberValue rainbowSaturation = add(new NumberValue("Rainbow Saturation", 0.5, 0, 1, 0.05));
    private final NumberValue rainbowBrightness = add(new NumberValue("Rainbow Brightness", 1, 0.1, 1, 0.05));
    private final NumberValue rainbowAlpha = add(new NumberValue("Rainbow Alpha", 255, 0, 255, 1));
    private final ColorValue theme1 = add(new ColorValue("Theme Color 1", 0xFFFFFFFF));
    private final ColorValue theme2 = add(new ColorValue("Theme Color 2", 0xFFFF0000));
    private final ModeValue anchor = add(new ModeValue("Anchor", "TOP_LEFT", "TOP_LEFT", "TOP_CENTER", "TOP_RIGHT", "CENTER_LEFT", "CENTER", "CENTER_RIGHT", "BOTTOM_LEFT", "BOTTOM_CENTER", "BOTTOM_RIGHT"));
    private final Element element = new Element();

    public ModuleListElementModule() { super("ModuleList"); }
    @Override protected HudElement element() { return element; }

    private static final class Row {
        final Module module;
        final String name, suffixed;
        boolean enabled;
        long sequence;
        float progress, width;
        Row(Module module) {
            this.module = module;
            name = module.name();
            suffixed = name + " " + module.category().name().toLowerCase(Locale.ROOT);
        }
    }

    private final class Element implements HudElement {
        private Row[] rows = new Row[0], sorted = new Row[0];
        private ModuleRegistry registry;
        private long revision = -1, lastNanos, sequence;
        private int count;
        private float width, height;
        @Override public String id() { return name(); }
        @Override public HudAnchor anchor() { return HudAnchor.valueOf(anchor.get()); }
        @Override public float width(OverlayDraw draw) { update(draw); return width; }
        @Override public float height(OverlayDraw draw) { update(draw); return height; }

        private void update(OverlayDraw draw) {
            NocturneClient client = NocturneClient.get();
            ModuleRegistry current = client == null ? null : client.modules();
            if (current != registry || (current != null && current.revision() != revision)) {
                List<Module> modules = current == null ? null : current.all();
                int size = modules == null ? 0 : modules.size();
                Row[] next = new Row[size];
                for (int i = 0; i < size; i++) {
                    Module module = modules.get(i);
                    for (int j = 0; j < rows.length; j++) if (rows[j].module == module) { next[i] = rows[j]; break; }
                    if (next[i] == null) next[i] = new Row(module);
                }
                rows = next;
                sorted = new Row[size];
                registry = current;
                revision = current == null ? -1 : current.revision();
            }
            long now = System.nanoTime();
            float delta = lastNanos == 0 ? 0f : Math.min(1f, (now - lastNanos) / 200000000f);
            lastNanos = now;
            count = 0; width = 0; height = 0;
            float size = fontSize.asFloat();
            float padding = mode.is("Normal") ? horizontalPadding.asFloat() : 0f;
            float line = size + (mode.is("Normal") ? 2f * verticalPadding.asFloat() : 0f);
            for (int i = 0; i < rows.length; i++) {
                Row row = rows[i];
                boolean enabled = row.module.isEnabled();
                if (enabled && !row.enabled) { row.sequence = ++sequence; row.progress = 0f; }
                row.enabled = enabled;
                if (!enabled) { row.progress = 0f; continue; }
                row.progress = Math.min(1f, row.progress + delta);
                row.width = draw.textWidth(suffix.get() ? row.suffixed : row.name, size) + 2f * padding
                        + (mode.is("Normal") ? lineWidth.asFloat() : 0f);
                int pos = count;
                while (pos > 0 && sorted[pos - 1].sequence > row.sequence) { sorted[pos] = sorted[pos - 1]; pos--; }
                sorted[pos] = row; count++;
                width = Math.max(width, row.width);
                height += line * row.progress;
            }
        }

        @Override public void draw(OverlayDraw draw, float x, float y) {
            update(draw);
            boolean normal = mode.is("Normal");
            float size = fontSize.asFloat();
            float hp = normal ? horizontalPadding.asFloat() : 0f;
            float vp = normal ? verticalPadding.asFloat() : 0f;
            float line = size + 2f * vp;
            double seconds = System.nanoTime() / 1000000000.0;
            float offset = 0f;
            for (int i = 0; i < count; i++) {
                Row row = sorted[i];
                int tint = theme1.argb();
                if (colorMode.is("Double")) tint = RenderSupport.blend(tint, theme2.argb(), offset / gradientLength.asFloat());
                else if (colorMode.is("Fade")) tint = RenderSupport.blend(tint, theme2.argb(), (float) (0.5 + 0.5 * Math.sin(seconds)));
                else if (colorMode.is("Rainbow")) {
                    float hue = (float) ((seconds * rainbowSpeed.asFloat() + (rainbowOffset.asFloat() + i * rainbowSpread.asFloat()) / 360f) % 1.0);
                    tint = RenderSupport.hsv(rainbowAlpha.asInt(), hue, rainbowSaturation.asFloat(), rainbowBrightness.asFloat());
                }
                tint = RenderSupport.withAlpha(tint, row.progress);
                float rowHeight = line * row.progress;
                if (normal && background.get()) draw.roundedRect(x, y + offset, row.width, rowHeight, 1f, RenderSupport.withAlpha(backgroundColor.argb(), row.progress));
                if (normal) draw.rect(x, y + offset, lineWidth.asFloat(), rowHeight, tint);
                draw.text(suffix.get() ? row.suffixed : row.name, x + hp + (normal ? lineWidth.asFloat() : 0f), y + offset + vp, size, tint);
                offset += rowHeight;
            }
        }
    }
}
