package dev.nocturne.client.module.modules.misc;

import dev.nocturne.client.control.PacketController;
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
    /** 本模块自己的闸门 tick 计数：Tick 设置项是 20Hz tick 而不是积压包数。 */
    private int heldTicks;
    private Object fake;

    public BlinkModule() {
        super("Blink", Category.MOVEMENT);
    }

    @Override
    protected void onStart() {
        // 换世界时 PacketController 先 reset 再投递事件；本监听器在事件里重新 hold。
        listen(WorldChangeEvent.class, event -> releaseAfterWorldChange());
        heldTicks = 0;
        acquire();
        if (fakePlayer.get()) {
            fake = bridge().spawnFakePlayer("Blink", true);
        }
    }

    @Override
    public void onTick() {
        // 自愈：panic/换世界/异线程释放后控制器不再持有本 owner，本 tick 重新装闸。
        if (!acquire()) {
            return;
        }
        heldTicks++;
        if (slowMove.get() && state().moving()) {
            client().packets().releaseSome(PacketEvent.Direction.SEND, this, slowMoveTick.asInt());
        }
        if (slowRelease.get()) {
            client().packets().releaseSome(PacketEvent.Direction.SEND, this, 1);
        }
        if (heldTicks >= tick.asInt()) {
            client().packets().release(PacketEvent.Direction.SEND, this);
            heldTicks = 0;
            acquire();
        }
    }

    /** 以本模块的 owner token 装闸；返回控制器是否真的持有。 */
    private boolean acquire() {
        PacketController packets = client() == null ? null : client().packets();
        if (packets == null) {
            return false;
        }
        if (!packets.isHolding(PacketEvent.Direction.SEND, this)) {
            packets.hold(PacketEvent.Direction.SEND, this);
        }
        return packets.isHolding(PacketEvent.Direction.SEND, this);
    }

    /** 换世界：控制器已 reset，本地计数与假人一起清掉，并在事件内立即重新装闸。 */
    private void releaseAfterWorldChange() {
        PacketController packets = client() == null ? null : client().packets();
        heldTicks = 0;
        if (packets == null) {
            fake = null;
            return;
        }
        // reset 已清空 owner，这里 release 是幂等收尾；随后重新 hold，避免换维度后 Blink 静默失效。
        packets.release(PacketEvent.Direction.SEND, this);
        acquire();
        if (bridge() != null) {
            bridge().removeFakePlayer(fake);
            fake = fakePlayer.get() ? bridge().spawnFakePlayer("Blink", true) : null;
        } else {
            fake = null;
        }
    }

    @Override
    protected void onStop() {
        if (client() != null && client().packets() != null) {
            client().packets().release(PacketEvent.Direction.SEND, this);
        }
        heldTicks = 0;
        if (bridge() != null) {
            bridge().removeFakePlayer(fake);
        }
        fake = null;
    }
}
