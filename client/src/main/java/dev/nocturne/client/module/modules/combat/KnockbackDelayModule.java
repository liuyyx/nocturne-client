package dev.nocturne.client.module.modules.combat;

import dev.nocturne.client.control.PacketController;
import dev.nocturne.client.event.PacketEvent;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.value.BoundsNumberValue;
import dev.nocturne.client.value.NumberBounds;
import dev.nocturne.client.value.NumberValue;

import java.util.Random;

/** 把击退及其后续接收包延迟到选定窗口后原序释放。 */
public final class KnockbackDelayModule extends GameModule {
    private final NumberValue chance = add(new NumberValue("Chance", 40, 0, 100, 1));
    private final BoundsNumberValue airDelay = add(new BoundsNumberValue(
            "Air delay", new NumberBounds(50, 100), 0, 500, 1));
    private final BoundsNumberValue groundDelay = add(new BoundsNumberValue(
            "Ground delay", new NumberBounds(200, 250), 0, 500, 1));
    private final Random random = new Random();
    private long releaseAt;
    private boolean holding;

    public KnockbackDelayModule() {
        super("KnockbackDelay", Category.COMBAT);
    }

    @Override
    protected void onStart() {
        listen(PacketEvent.class, dev.nocturne.client.event.EventBus.Priority.LOWEST, this::onPacket);
    }

    @Override
    protected void onStop() {
        release();
    }

    private void onPacket(PacketEvent event) {
        if (event.direction != PacketEvent.Direction.RECEIVE || holding || bridge() == null
                || !bridge().isLocalVelocity(event.packet)
                || random.nextDouble() * 100.0 >= chance.get()) {
            return;
        }
        PacketController packets = client().packets();
        packets.hold(PacketEvent.Direction.RECEIVE, this);
        if (!packets.isHolding(PacketEvent.Direction.RECEIVE, this)
                || !packets.capture(PacketEvent.Direction.RECEIVE, event.packet)) {
            return;
        }
        event.cancelled = true;
        holding = true;
        NumberBounds bounds = state().onGround() ? groundDelay.get() : airDelay.get();
        releaseAt = System.currentTimeMillis()
                + (long) (bounds.min() + random.nextDouble() * (bounds.max() - bounds.min()));
    }

    @Override
    public void onTick() {
        if (holding && !client().packets().isHolding(PacketEvent.Direction.RECEIVE, this)) {
            // 换世界 reset 已清掉闸门：本地状态跟着走，避免永久不再 hold。
            holding = false;
        }
        if (holding && System.currentTimeMillis() >= releaseAt) {
            release();
        }
    }

    private void release() {
        if (holding) {
            client().packets().release(PacketEvent.Direction.RECEIVE, this);
        }
        holding = false;
        releaseAt = 0L;
    }
}
