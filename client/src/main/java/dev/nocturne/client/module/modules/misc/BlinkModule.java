package dev.nocturne.client.module.modules.misc;

import dev.nocturne.client.control.PacketController;
import dev.nocturne.client.event.PacketEvent;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.ModeValue;
import dev.nocturne.client.value.NumberValue;

/** 扣住发包并在禁用或达到阈值时原序释放。 */
public final class BlinkModule extends GameModule {
    private final ModeValue direction = add(new ModeValue(
            "Direction", "Outgoing only", "Outgoing only", "Bi-directional"));
    private final BooleanValue autoSend = add(new BooleanValue("Auto send", false));
    private final NumberValue sendThreshold = add(new NumberValue("Send threshold", 50, 0, 100, 1));
    private boolean outbound;
    private boolean inbound;

    public BlinkModule() {
        super("Blink", Category.MISC);
    }

    @Override
    protected void onStart() {
        PacketController packets = client().packets();
        outbound = packets.hold(PacketEvent.Direction.SEND);
        if (direction.is("Bi-directional")) {
            inbound = packets.hold(PacketEvent.Direction.RECEIVE);
        }
    }

    @Override
    public void onTick() {
        int threshold = sendThreshold.asInt();
        if (!autoSend.get() || threshold <= 0) {
            return;
        }
        PacketController packets = client().packets();
        if (packets.queued(PacketEvent.Direction.SEND) >= threshold) {
            release();
            onStart();
        }
    }

    @Override
    protected void onStop() {
        release();
    }

    private void release() {
        PacketController packets = client().packets();
        if (outbound) {
            packets.release(PacketEvent.Direction.SEND);
        }
        if (inbound) {
            packets.release(PacketEvent.Direction.RECEIVE);
        }
        outbound = false;
        inbound = false;
    }
}
