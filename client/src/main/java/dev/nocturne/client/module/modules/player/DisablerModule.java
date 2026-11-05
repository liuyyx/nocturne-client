package dev.nocturne.client.module.modules.player;

import dev.nocturne.client.event.PacketEvent;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.value.BooleanValue;

/** Disabler：ACA 去重槽位包，FastSwitch 时补发跨过的中间槽。 */
public final class DisablerModule extends GameModule {
    private final BooleanValue duplicate = add(new BooleanValue("ACA Duplicate Slot", true));
    private final BooleanValue fastSwitch = add(new BooleanValue("ACA Fast Switch", true));
    private int lastSlot = -1;
    private boolean sending;

    public DisablerModule() {
        super("Disabler", Category.PLAYER);
    }

    @Override
    protected void onStart() {
        listen(PacketEvent.class, event -> {
            if (sending || event.direction != PacketEvent.Direction.SEND
                    || !bridge().isInstance(event.packet, ClassType.SERVERBOUND_SET_CARRIED_ITEM)) {
                return;
            }
            int slot = bridge().carriedSlot(event.packet);
            if (duplicate.get() && (slot < 0 || slot == lastSlot)) {
                event.cancelled = true;
                return;
            }
            if (fastSwitch.get() && lastSlot >= 0 && slot >= 0 && Math.abs(slot - lastSlot) > 1) {
                sending = true;
                try {
                    int step = slot > lastSlot ? 1 : -1;
                    for (int current = lastSlot + step; current != slot; current += step) {
                        actions().selectSlot(current, true);
                    }
                } finally {
                    sending = false;
                }
            }
            lastSlot = slot;
        });
    }

    @Override
    protected void onStop() {
        lastSlot = -1;
        sending = false;
    }
}
