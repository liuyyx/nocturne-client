package dev.noturne.client.module;

import dev.noturne.client.NoturneClient;
import dev.noturne.client.hud.HudSink;
import dev.noturne.client.module.modules.WatermarkModule;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HudModuleTest {

    static final class RecordingSink implements HudSink {
        final Map<String, Supplier<String>> lines = new LinkedHashMap<String, Supplier<String>>();

        @Override
        public void add(String id, Supplier<String> text) {
            lines.put(id, text);
        }

        @Override
        public void remove(String id) {
            lines.remove(id);
        }

        @Override
        public boolean has(String id) {
            return lines.containsKey(id);
        }
    }

    @Test
    void armingPublishesTheLineAndDisarmingRetractsIt() {
        RecordingSink sink = new RecordingSink();
        NoturneClient.boot(null).setHudSink(sink);

        WatermarkModule module = new WatermarkModule("test-client");
        assertFalse(sink.has("watermark"));

        module.setEnabled(true);
        assertTrue(sink.has("watermark"));
        assertEquals("test-client", sink.lines.get("watermark").get());

        module.setEnabled(false);
        assertFalse(sink.has("watermark"), "disarming must remove the HUD line");
    }

    @Test
    void armingWithoutAUiInstalledIsHarmless() {
        NoturneClient.boot(null).setHudSink(null);
        WatermarkModule module = new WatermarkModule();
        module.setEnabled(true);
        assertTrue(module.isEnabled());
        module.setEnabled(false);
    }

    @Test
    void defaultClientRegistersTheWatermark() {
        NoturneClient client = NoturneClient.boot(null);
        assertTrue(client.modules().get("Watermark") != null, "Watermark must be registered on boot");
        assertEquals(Category.RENDER, client.modules().get("Watermark").category());
    }
}
