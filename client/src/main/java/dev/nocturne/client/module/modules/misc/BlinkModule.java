package dev.nocturne.client.module.modules.misc;

import dev.nocturne.client.event.PacketEvent;
import dev.nocturne.client.event.WorldChangeEvent;
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
        // 换世界时 PacketController 会 reset（holding 被清、队列被放），本地标志必须跟着走，
        // 否则模块以为自己还在 hold，实际早已放行（过门后瞬间穿包）。
        listen(WorldChangeEvent.class, event -> releaseAfterWorldChange());
        holding = client().packets().hold(PacketEvent.Direction.SEND);
        if (fakePlayer.get()) {
            fake = bridge().spawnFakePlayer("Blink", true);
        }
    }

    @Override
    public void onTick() {
        // 以控制器真实状态为准：panic/换世界/他人释放后，本地 holding 不能变成"幽灵开启"。
        holding = client().packets().isHolding(PacketEvent.Direction.SEND);
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

    /** 换世界：控制器已 reset，本地状态与假人一起清掉，避免跨世界残留。 */
    private void releaseAfterWorldChange() {
        if (client() == null || client().packets() == null) {
            holding = false;
            fake = null;
            return;
        }
        client().packets().release(PacketEvent.Direction.SEND);
        holding = false;
        if (bridge() != null) {
            bridge().removeFakePlayer(fake);
            fake = fakePlayer.get() ? bridge().spawnFakePlayer("Blink", true) : null;
        } else {
            fake = null;
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
