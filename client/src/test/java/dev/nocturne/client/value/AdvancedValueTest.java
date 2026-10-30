package dev.nocturne.client.value;

import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 扩展设置类型的归一化与 GUI 可见性。 */
class AdvancedValueTest {

    @Test
    void textIsTruncatedAndNullBecomesEmpty() {
        TextValue value = new TextValue("Name", "abcdef", 4);
        assertEquals("abcd", value.get());
        value.set(null);
        assertEquals("", value.get());
        value.reset();
        assertEquals("abcd", value.get());
        assertTrue(value.isDefault());
    }

    @Test
    void boundsShareNumberRulesAndStayOrdered() {
        BoundsNumberValue value = new BoundsNumberValue("Range",
                new NumberBounds(8.0, 2.0), 0.0, 10.0, 0.5);
        assertEquals(new NumberBounds(2.0, 8.0), value.get());
        value.set(new NumberBounds(-2.0, 20.0));
        assertEquals(new NumberBounds(0.0, 10.0), value.get());
        value.set(new NumberBounds(7.3, 1.2));
        assertEquals(new NumberBounds(1.0, 7.5), value.get());
        value.reset();
        assertTrue(value.isDefault());
    }

    @Test
    void slotAndKeyValuesNormaliseSentinels() {
        SlotValue slot = new SlotValue("Slot", 12);
        assertEquals(8, slot.get());
        slot.set(-1);
        assertEquals(0, slot.get());
        assertEquals("1", slot.display());

        KeyValue key = new KeyValue("Key", -10);
        assertEquals(KeyValue.NONE, key.get());
        assertFalse(key.bound());
        key.set(java.awt.event.KeyEvent.VK_R);
        assertEquals("R", key.display());
    }

    @Test
    void groupAndMultiSelectionRemainWellFormed() {
        GroupValue group = new GroupValue("Group",
                Collections.singletonList(new BooleanValue("Child", true)), true);
        assertTrue(group.get());
        group.set(null);
        assertFalse(group.get());
        assertEquals(1, group.children().size());

        MultiBoolValue multi = new MultiBoolValue("Targets",
                Arrays.asList("Players", "Mobs", "Items"),
                Arrays.asList("Items", "Players", "Unknown"));
        assertEquals(Arrays.asList("Players", "Items"),
                Arrays.asList(multi.get().toArray(new String[0])));
        multi.toggle("Mobs");
        assertTrue(multi.selected("Mobs"));
        multi.toggle("Players");
        assertFalse(multi.selected("Players"));
        assertThrows(UnsupportedOperationException.class, () -> multi.get().add("Players"));
    }

    @Test
    void visibilityConditionIsLiveAndDoesNotChangeValue() {
        AtomicBoolean visible = new AtomicBoolean();
        BooleanValue value = new BooleanValue("Conditional", true);
        value.visibleWhen(visible::get);
        assertFalse(value.visible());
        visible.set(true);
        assertTrue(value.visible());
        value.visibleWhen(null);
        assertTrue(value.visible());
        assertTrue(value.get());
    }
}
