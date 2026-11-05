package dev.nocturne.client.module.modules.misc;

import dev.nocturne.client.event.PacketEvent;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.NumberValue;

/** Epsilon Blink：Tick 阈值、慢速释放、移动释放与本地假人。 */
public final class BlinkModule extends GameModule {
    private final NumberValue tick = add(new NumberValue("Tick", 30, 5, 200, 1));
    private final BooleanValue slowRelease = add(new BooleanValue("Slow Release", true));
    private final BooleanValue slowMove = add(new BooleanValue("Slow Move", false));
    private final NumberValue slowMoveTick = add(new NumberValue("Slow Move Tick", 5, 1, 20, 1));
    private final BooleanValue fakePlayer = add(new BooleanValue("Fake Player", false));
    private boolean holding;
    private Object fake;

    public BlinkModule() {
        super("Blink", Category.MOVEMENT);
    }

    @Override
    protected void onStart() {
        holding = client().packets().hold(PacketEvent.Direction.SEND);
        if (fakePlayer.get()) {
            fake = bridge().spawnFakePlayer("Blink", true);
        }
    }

    @Override
    public void onTick() {
        if (!holding) {
            return;
        }
        if (slowMove.get() && state().moving()) {
            client().packets().releaseSome(PacketEvent.Direction.SEND, slowMoveTick.asInt());
        }
        if (slowRelease.get()) {
            client().packets().releaseSome(PacketEvent.Direction.SEND, 1);
        }
        if (client().packets().queued(PacketEvent.Direction.SEND) >= tick.asInt()) {
            client().packets().release(PacketEvent.Direction.SEND);
            holding = client().packets().hold(PacketEvent.Direction.SEND);
        }
    }

    @Override
    protected void onStop() {
        if (holding) {
            client().packets().release(PacketEvent.Direction.SEND);
        }
        holding = false;
        if (bridge() != null) {
            bridge().removeFakePlayer(fake);
        }
        fake = null;
    }
}
