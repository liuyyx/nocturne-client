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
import java.util.List;
import java.util.Locale;

/** 启用模块列表。上游降级：覆盖层不支持阴影，故不提供 Shadow Color。 */
public final class HotkeyElementModule extends HudElementModule {
    private final ModeValue colorMode = add(new ModeValue("Color Mode", "Single", "Single", "Double"));
    private final ColorValue color = add(new ColorValue("Color", 0xFFFFFFFF));
    private final ColorValue color1 = add(new ColorValue("Color 1", 0xFFFFFFFF));
    private final ColorValue color2 = add(new ColorValue("Color 2", 0xFFFF0000));
    private final BooleanValue suffix = add(new BooleanValue("Show Suffix", true));
    private final ModeValue anchor = add(new ModeValue("Anchor", "TOP_RIGHT", "TOP_LEFT", "TOP_CENTER", "TOP_RIGHT", "CENTER_LEFT", "CENTER", "CENTER_RIGHT", "BOTTOM_LEFT", "BOTTOM_CENTER", "BOTTOM_RIGHT"));
    private final Element element = new Element();

    public HotkeyElementModule() { super("Hotkey"); }
    @Override protected HudElement element() { return element; }

    private static final class Row {
        final Module module;
        final String name, suffixed;
        boolean enabled;
        float progress, y, width;
        Row(Module module) {
            this.module = module;
            name = module.name();
            suffixed = name + " " + module.category().name().toLowerCase(Locale.ROOT);
        }
    }

    private final class Element implements HudElement {
        private Row[] rows = new Row[0], sorted = new Row[0];
        private long revision = -1, lastNanos;
        private int visibleCount;
        private ModuleRegistry registry;
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
                int count = modules == null ? 0 : modules.size();
                Row[] next = new Row[count];
                for (int i = 0; i < count; i++) {
                    Module module = modules.get(i);
                    for (int j = 0; j < rows.length; j++) if (rows[j].module == module) { next[i] = rows[j]; break; }
                    if (next[i] == null) next[i] = new Row(module);
                }
                rows = next;
                sorted = new Row[count];
                registry = current;
                revision = current == null ? -1 : current.revision();
            }
            long now = System.nanoTime();
            float delta = lastNanos == 0 ? 0f : Math.min(1f, (now - lastNanos) / 200000000f);
            lastNanos = now;
            int count = 0;
            width = 0;
            for (int i = 0; i < rows.length; i++) {
                Row row = rows[i];
                row.enabled = row.module.isEnabled();
                row.progress = Math.max(0f, Math.min(1f, row.progress + (row.enabled ? delta : -delta)));
                if (row.progress <= 0f) continue;
                row.width = draw.textWidth(suffix.get() ? row.suffixed : row.name);
                int pos = count;
                while (pos > 0 && sorted[pos - 1].width < row.width) { sorted[pos] = sorted[pos - 1]; pos--; }
                sorted[pos] = row;
                count++;
                width = Math.max(width, row.width + 5f);
            }
            float line = draw.textHeight() + 2f;
            float target = 0f;
            for (int i = 0; i < count; i++) {
                Row row = sorted[i];
                row.y += (target - row.y) * delta;
                target += line * row.progress;
            }
            visibleCount = count;
            height = target;
        }

        @Override public void draw(OverlayDraw draw, float x, float y) {
            update(draw);
            float line = draw.textHeight() + 2f;
            int index = 0;
            while (index < visibleCount) {
                Row row = sorted[index];
                if (row == null || row.progress <= 0f) break;
                int tint = colorMode.is("Double") ? RenderSupport.blend(color1.argb(), color2.argb(), Math.min(1f, index / 10f)) : color.argb();
                tint = RenderSupport.withAlpha(tint, row.progress);
                float left = x + (width - row.width - 5f) + (1f - row.progress) * row.width;
                draw.rect(left, y + row.y, 2f, line - 1f, tint);
                draw.text(suffix.get() ? row.suffixed : row.name, left + 5f, y + row.y + 1f, tint);
                index++;
            }
        }
    }
}
