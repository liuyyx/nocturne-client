package dev.nocturne.client.module.modules.misc;

import dev.nocturne.client.control.PacketController;
import dev.nocturne.client.control.TargetManager;
import dev.nocturne.client.event.AttackEvent;
import dev.nocturne.client.event.BlockEvent;
import dev.nocturne.client.event.PacketEvent;
import dev.nocturne.client.event.UseItemEvent;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.module.modules.EpsilonSupport;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.ModeValue;
import dev.nocturne.client.value.NumberValue;

/** Epsilon Fake Lag：固定/动态目标距离压包、反冲间隔与交互冲刷。 */
public final class FakeLagModule extends GameModule {
    private final NumberValue rangeMin = add(new NumberValue("Range Min", 3, 0, 10, 0.1));
    private final NumberValue rangeMax = add(new NumberValue("Range Max", 4.5, 0, 10, 0.1));
    private final NumberValue delayMin = add(new NumberValue("Delay Min", 50, 0, 1000, 5));
    private final NumberValue delayMax = add(new NumberValue("Delay Max", 155, 0, 1000, 5));
    private final NumberValue recoilTime = add(new NumberValue("Recoil Time", 39, 0, 500, 1));
    private final ModeValue mode = add(new ModeValue("Mode", "Dynamic", "Constant", "Dynamic"));
    private final BooleanValue flushEntity = add(new BooleanValue("Flush On Entity Interact", true));
    private final BooleanValue flushBlock = add(new BooleanValue("Flush On Block Interact", false));
    private final BooleanValue flushAction = add(new BooleanValue("Flush On Action", false));
    private boolean holding;
    private long firstQueued;
    private long releaseAfter;
    private long recoilUntil;
    private double lastDistance = Double.NaN;

    public FakeLagModule() {
        super("Fake Lag", Category.COMBAT);
    }

    @Override
    protected void onStart() {
        listen(AttackEvent.class, event -> {
            if (flushEntity.get()) flush();
        });
        listen(UseItemEvent.class, event -> {
            if (flushAction.get()) flush();
        });
        listen(BlockEvent.class, event -> {
            if (flushBlock.get()) flush();
        });
    }

    @Override
    public void onTick() {
        long now = System.currentTimeMillis();
        // 以控制器真实状态为准：换世界/panic/异线程释放后不要留下"幽灵开启"。
        if (holding && !client().packets().isHolding(PacketEvent.Direction.SEND, this)) {
            holding = false;
            firstQueued = 0L;
        }
        if (now < recoilUntil || bridge().currentScreen() != null || state().health() <= 0.0f
                || bridge().inWater(bridge().player())) {
            flush();
            return;
        }
        TargetManager.Target target = client().targets().find(client(), EpsilonSupport.targets(16.0,
                true, true, true, TargetManager.Sort.DISTANCE));
        boolean allowed = true;
        if (mode.is("Dynamic")) {
            if (target == null) {
                allowed = false;
            } else {
                double distance = bridge().distance(bridge().player(), target.entity());
                allowed = distance >= rangeMin.get() && distance <= rangeMax.get()
                        && (Double.isNaN(lastDistance) || distance >= lastDistance - 0.05);
                lastDistance = distance;
            }
        }
        if (!allowed) {
            flush();
            return;
        }
        if (!holding) {
            client().packets().hold(PacketEvent.Direction.SEND, this);
            holding = client().packets().isHolding(PacketEvent.Direction.SEND, this);
            firstQueued = 0L;
        }
        if (client().packets().queued(PacketEvent.Direction.SEND, this) == 0) {
            firstQueued = 0L;
            return;
        }
        if (firstQueued == 0L) {
            firstQueued = now;
            releaseAfter = EpsilonSupport.randomLong(delayMin.asInt(), delayMax.asInt());
        }
        if (now - firstQueued >= releaseAfter) {
            flush();
        }
    }

    private void flush() {
        PacketController packets = client() == null ? null : client().packets();
        if (holding && packets != null) {
            packets.release(PacketEvent.Direction.SEND, this);
            recoilUntil = System.currentTimeMillis() + recoilTime.asInt();
        }
        holding = false;
        firstQueued = 0L;
    }

    @Override
    protected void onStop() {
        flush();
        lastDistance = Double.NaN;
    }
}
