package dev.noturne.client.value;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ValueTest {

    @Test
    void booleanTogglesAndResets() {
        BooleanValue value = new BooleanValue("Enabled", false);
        assertFalse(value.get());
        assertTrue(value.isDefault());

        value.toggle();
        assertTrue(value.get());
        assertFalse(value.isDefault());

        value.reset();
        assertFalse(value.get());
        assertEquals("Off", value.display());
    }

    @Test
    void numberClampsToRange() {
        NumberValue value = new NumberValue("Reach", 3.0, 3.0, 6.0, 0.0);
        value.set(99.0);
        assertEquals(6.0, value.get(), 1e-9);
        value.set(-5.0);
        assertEquals(3.0, value.get(), 1e-9);
        assertEquals(3, value.asInt());
    }

    @Test
    void numberSnapsToStep() {
        NumberValue value = new NumberValue("Speed", 1.0, 0.0, 1.0, 0.25);
        value.set(0.62);
        assertEquals(0.5, value.get(), 1e-9);
        value.set(0.9);
        assertEquals(1.0, value.get(), 1e-9);
    }

    @Test
    void modeCyclesAndRejectsUnknownOptions() {
        ModeValue value = new ModeValue("Mode", "Toggle", "Toggle", "Hold", "Always");
        assertEquals("Toggle", value.get());
        assertEquals(0, value.index());

        value.next();
        assertEquals("Hold", value.get());
        value.next();
        value.next();
        assertEquals("Toggle", value.get(), "must wrap around");

        value.set("Nonsense");
        assertEquals("Toggle", value.get(), "unknown option falls back to the first");
        assertTrue(value.is("Toggle"));
    }

    @Test
    void changingValueMarksItNonDefault() {
        NumberValue value = new NumberValue("Delay", 1.0, 0.0, 5.0, 1.0);
        assertTrue(value.isDefault());
        value.set(3.0);
        assertFalse(value.isDefault());
        assertEquals("3", value.display());
    }
}
