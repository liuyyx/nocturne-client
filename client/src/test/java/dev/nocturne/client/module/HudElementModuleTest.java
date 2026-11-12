package dev.nocturne.client.module;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.TestSupport;
import dev.nocturne.client.hud.HudAnchor;
import dev.nocturne.client.hud.HudElement;
import dev.nocturne.client.hud.HudElementSink;
import dev.nocturne.client.render.OverlayDraw;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 与文本 HUD 相同的汇迁移契约；每个用例隔离客户端单例与持久化配置。 */
class HudElementModuleTest {
    @BeforeEach
    void isolate() {
        TestSupport.resetClient();
    }

    @AfterEach
    void cleanUp() {
        TestSupport.resetClient();
    }

    @Test
    void enablingPublishesTheStableElementAndDisablingRemovesIt() {
        NocturneClient client = NocturneClient.boot(null);
        RecordingSink sink = new RecordingSink();
        client.setHudElementSink(sink);
        FakeElementModule module = register(client);

        assertEquals(Category.RENDER, module.category());
        assertEquals(module.name(), module.element().id());
        assertFalse(sink.hasElement(module.name()));
        module.setEnabled(true);
        assertTrue(sink.hasElement(module.name()));
        assertSame(module.element(), sink.elements.get(module.name()));
        module.setEnabled(false);
        assertFalse(sink.hasElement(module.name()));
        assertEquals(1, sink.addCalls);
        assertEquals(1, sink.removeCalls);
    }

    @Test
    void lateSinkPicksUpAnAlreadyEnabledModule() {
        NocturneClient client = NocturneClient.boot(null);
        FakeElementModule module = register(client);
        module.setEnabled(true);
        RecordingSink sink = new RecordingSink();

        client.setHudElementSink(sink);

        assertTrue(module.isEnabled());
        assertSame(module.element(), sink.elements.get(module.name()));
        assertEquals(1, sink.addCalls);
    }

    @Test
    void replacementMigratesFromOldSinkAndDisableRemovesFromNewSink() {
        NocturneClient client = NocturneClient.boot(null);
        FakeElementModule module = register(client);
        RecordingSink oldSink = new RecordingSink();
        RecordingSink newSink = new RecordingSink();
        client.setHudElementSink(oldSink);
        module.setEnabled(true);

        client.setHudElementSink(newSink);

        assertFalse(oldSink.hasElement(module.name()));
        assertSame(module.element(), newSink.elements.get(module.name()));
        assertEquals(1, oldSink.removeCalls);
        assertEquals(1, newSink.addCalls);
        module.setEnabled(false);
        assertFalse(newSink.hasElement(module.name()));
        assertEquals(1, newSink.removeCalls);
    }

    @Test
    void removingSinkRetractsElementAndLaterInstallationRestoresIt() {
        NocturneClient client = NocturneClient.boot(null);
        FakeElementModule module = register(client);
        RecordingSink oldSink = new RecordingSink();
        client.setHudElementSink(oldSink);
        module.setEnabled(true);

        client.setHudElementSink(null);

        assertTrue(module.isEnabled());
        assertTrue(oldSink.elements.isEmpty());
        RecordingSink replacement = new RecordingSink();
        client.setHudElementSink(replacement);
        assertSame(module.element(), replacement.elements.get(module.name()));
    }

    @Test
    void repeatedEnableAndSameSinkDoNotDuplicateTheId() {
        NocturneClient client = NocturneClient.boot(null);
        FakeElementModule module = register(client);
        RecordingSink sink = new RecordingSink();
        client.setHudElementSink(sink);
        module.setEnabled(true);
        module.setEnabled(true);
        client.setHudElementSink(sink);
        client.setHudElementSink(sink);
        assertEquals(1, sink.elements.size());
        assertEquals(1, sink.addCalls);
        assertEquals(0, sink.removeCalls);

        module.setEnabled(false);
        module.setEnabled(false);
        module.setEnabled(true);
        assertEquals(1, sink.elements.size());
        assertSame(module.element(), sink.elements.get(module.name()));
        assertEquals(2, sink.addCalls);
        assertEquals(1, sink.removeCalls);
    }

    @Test
    void disabledModuleIsNotRegisteredWhenSinkArrivesOrChanges() {
        NocturneClient client = NocturneClient.boot(null);
        FakeElementModule module = register(client);
        module.setEnabled(true);
        module.setEnabled(false);
        RecordingSink first = new RecordingSink();
        RecordingSink second = new RecordingSink();
        client.setHudElementSink(first);
        client.setHudElementSink(second);
        assertTrue(first.elements.isEmpty());
        assertTrue(second.elements.isEmpty());
        assertEquals(0, first.addCalls);
        assertEquals(0, second.addCalls);
    }

    private static FakeElementModule register(NocturneClient client) {
        FakeElementModule module = new FakeElementModule();
        client.modules().register(module);
        return module;
    }

    private static final class RecordingSink implements HudElementSink {
        final Map<String, HudElement> elements = new LinkedHashMap<String, HudElement>();
        int addCalls;
        int removeCalls;

        @Override
        public void addElement(HudElement element) {
            addCalls++;
            elements.put(element.id(), element);
        }

        @Override
        public void removeElement(String id) {
            removeCalls++;
            elements.remove(id);
        }

        @Override
        public boolean hasElement(String id) {
            return elements.containsKey(id);
        }
    }

    private static final class FakeElementModule extends HudElementModule {
        private final HudElement element = new HudElement() {
            @Override
            public String id() {
                return name();
            }

            @Override
            public HudAnchor anchor() {
                return HudAnchor.TOP_LEFT;
            }

            @Override
            public float width(OverlayDraw draw) {
                return 40f;
            }

            @Override
            public float height(OverlayDraw draw) {
                return 12f;
            }

            @Override
            public void draw(OverlayDraw draw, float x, float y) {
                draw.text("test", x, y, 0xFFFFFFFF);
            }
        };

        FakeElementModule() {
            super("Fake HUD Element");
        }

        @Override
        protected HudElement element() {
            return element;
        }
    }
}
