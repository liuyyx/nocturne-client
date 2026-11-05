package dev.nocturne.client.module.modules.misc;

import dev.nocturne.client.control.PacketController;
import dev.nocturne.client.event.PacketEvent;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.value.BoundsNumberValue;
import dev.nocturne.client.value.NumberBounds;

import java.util.Random;

/** 将服务器下行包延迟一个随机窗口，形成可配置的回溯接收语义。 */
public final class BacktrackModule extends GameModule {
    private final BoundsNumberValue latency = add(new BoundsNumberValue(
            "Latency", new NumberBounds(50, 100), 0, 1000, 1));
    private final Random random = new Random();
    private long releaseAt;
    private boolean holding;

    public BacktrackModule() {
        super("BackTrack", Category.MISC);
    }

    @Override
    protected void onStart() {
        holding = client().packets().hold(PacketEvent.Direction.RECEIVE);
        releaseAt = 0L;
    }

    @Override
    public void onTick() {
        if (!holding) {
            return;
        }
        PacketController packets = client().packets();
        if (packets.queued(PacketEvent.Direction.RECEIVE) == 0) {
            releaseAt = 0L;
            return;
        }
        long now = System.currentTimeMillis();
        if (releaseAt == 0L) {
            NumberBounds bounds = latency.get();
            releaseAt = now + (long) (bounds.min() + random.nextDouble() * (bounds.max() - bounds.min()));
        }
        if (now >= releaseAt) {
            packets.release(PacketEvent.Direction.RECEIVE);
            holding = packets.hold(PacketEvent.Direction.RECEIVE);
            releaseAt = 0L;
        }
    }

    @Override
    protected void onStop() {
        if (holding) {
            client().packets().release(PacketEvent.Direction.RECEIVE);
        }
        holding = false;
        releaseAt = 0L;
    }
}
