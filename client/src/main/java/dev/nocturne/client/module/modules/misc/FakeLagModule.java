package dev.nocturne.client.module.modules.misc;

import dev.nocturne.client.control.PacketController;
import dev.nocturne.client.event.PacketEvent;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.value.NumberValue;

/** 以固定延迟排队发送全部出站包，禁用时强制 flush。 */
public final class FakeLagModule extends GameModule {
    private final NumberValue delay = add(new NumberValue("Delay", 100, 1, 1000, 10));
    private long firstQueuedAt;
    private boolean holding;

    public FakeLagModule() {
        super("FakeLag", Category.MISC);
    }

    @Override
    protected void onStart() {
        holding = client().packets().hold(PacketEvent.Direction.SEND);
        firstQueuedAt = 0L;
    }

    @Override
    public void onTick() {
        if (!holding) {
            return;
        }
        PacketController packets = client().packets();
        if (packets.queued(PacketEvent.Direction.SEND) == 0) {
            firstQueuedAt = 0L;
            return;
        }
        long now = System.currentTimeMillis();
        if (firstQueuedAt == 0L) {
            firstQueuedAt = now;
        }
        if (now - firstQueuedAt >= delay.get()) {
            packets.release(PacketEvent.Direction.SEND);
            holding = packets.hold(PacketEvent.Direction.SEND);
            firstQueuedAt = 0L;
        }
    }

    @Override
    protected void onStop() {
        if (holding) {
            client().packets().release(PacketEvent.Direction.SEND);
        }
        holding = false;
        firstQueuedAt = 0L;
    }
}
