package dev.nocturne.client.module.modules.misc;

import dev.nocturne.client.control.TargetManager;
import dev.nocturne.client.event.AttackEvent;
import dev.nocturne.client.event.PacketEvent;
import dev.nocturne.client.event.WorldChangeEvent;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.module.modules.EpsilonSupport;
import dev.nocturne.client.module.modules.render.WorldGeometry;
import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.client.render.WorldOverlay;
import dev.nocturne.client.render.WorldProjection;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.ColorValue;
import dev.nocturne.client.value.ModeValue;
import dev.nocturne.client.value.NumberValue;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/** Epsilon Backtrack：目标触发下行包回溯、历史位置缓存与 ESP。 */
public final class BacktrackModule extends GameModule implements WorldOverlay {
    private final NumberValue minRange = add(new NumberValue("Min Range", 2.5, 0, 10, 0.1));
    private final NumberValue maxRange = add(new NumberValue("Max Range", 5, 0, 10, 0.1));
    private final NumberValue minDelay = add(new NumberValue("Min Delay", 100, 0, 1000, 5));
    private final NumberValue maxDelay = add(new NumberValue("Max Delay", 150, 0, 1000, 5));
    private final NumberValue nextMin = add(new NumberValue("Next Backtrack Min", 0, 0, 2000, 10));
    private final NumberValue nextMax = add(new NumberValue("Next Backtrack Max", 10, 0, 2000, 10));
    private final NumberValue trackingBuffer = add(new NumberValue("Tracking Buffer", 500, 50, 5000, 50));
    private final NumberValue chance = add(new NumberValue("Chance", 50, 0, 100, 1));
    private final BooleanValue pauseHurt = add(new BooleanValue("Pause On Hurt Time", false));
    private final NumberValue hurtTime = add(new NumberValue("Hurt Time", 3, 0, 10, 1));
    private final ModeValue targetMode = add(new ModeValue("Target Mode", "Attack", "Attack", "Range"));
    private final NumberValue lastAttackTime = add(new NumberValue("Last Attack Time", 1000, 0, 5000, 50));
    private final BooleanValue players = add(new BooleanValue("Players", true));
    private final BooleanValue mobs = add(new BooleanValue("Mobs", false));
    private final BooleanValue animals = add(new BooleanValue("Animals", false));
    private final BooleanValue villagers = add(new BooleanValue("Villagers", false));
    private final BooleanValue ambient = add(new BooleanValue("Ambient", false));
    private final BooleanValue water = add(new BooleanValue("Water", false));
    private final BooleanValue others = add(new BooleanValue("Others", false));
    private final BooleanValue invisible = add(new BooleanValue("Invisible", true));
    private final BooleanValue esp = add(new BooleanValue("ESP", true));
    private final ColorValue sideColor = add(new ColorValue("Side Color", 0x23FFFFFF));
    private final ColorValue outlineColor = add(new ColorValue("Outline Color", 0xBEFFFFFF));
    private final Map<Integer, Deque<History>> history = new HashMap<Integer, Deque<History>>();
    private Object lastTarget;
    private long lastAttack;
    private long releaseAt;
    private long nextCycle;
    private boolean holding;

    public BacktrackModule() {
        super("Backtrack", Category.COMBAT);
    }

    @Override
    protected void onStart() {
        listen(AttackEvent.class, event -> {
            if (!event.post) {
                lastTarget = event.target;
                lastAttack = System.currentTimeMillis();
            }
        });
        listen(WorldChangeEvent.class, event -> release());
    }

    @Override
    public void onTick() {
        TargetManager.Target nearest = client().targets().find(client(), EpsilonSupport.targets(maxRange.get(),
                players.get(), mobs.get() || animals.get() || villagers.get() || ambient.get() || water.get() || others.get(),
                invisible.get(), TargetManager.Sort.DISTANCE));
        Object target = targetMode.is("Attack") && System.currentTimeMillis() - lastAttack <= lastAttackTime.asInt()
                ? lastTarget : nearest == null ? null : nearest.entity();
        if (target != null) {
            double distance = bridge().distance(bridge().player(), target);
            record(target);
            boolean active = distance >= minRange.get() && distance <= maxRange.get()
                    && System.currentTimeMillis() >= nextCycle
                    && !(pauseHurt.get() && bridge().hurtTime(bridge().player()) >= hurtTime.get());
            if (holding && !client().packets().isHolding(PacketEvent.Direction.RECEIVE, this)) {
                // 换世界 reset 后本地 holding 失真：重新计时，避免一直以为在回溯。
                holding = false;
            }
            if (active && !holding && EpsilonSupport.RANDOM.nextDouble() * 100.0 < chance.get()) {
                client().packets().hold(PacketEvent.Direction.RECEIVE, this);
                holding = client().packets().isHolding(PacketEvent.Direction.RECEIVE, this);
                releaseAt = System.currentTimeMillis() + EpsilonSupport.randomLong(minDelay.asInt(), maxDelay.asInt());
            }
        }
        if (holding && System.currentTimeMillis() >= releaseAt) {
            release();
            nextCycle = System.currentTimeMillis() + EpsilonSupport.randomLong(nextMin.asInt(), nextMax.asInt());
        }
        if (target == null && holding) {
            release();
        }
    }

    private void record(Object target) {
        int id = bridge().entityId(target);
        Deque<History> entries = history.get(id);
        if (entries == null) {
            entries = new ArrayDeque<History>();
            history.put(id, entries);
        }
        double[] pos = bridge().entityPosition(target);
        if (pos != null) {
            entries.addLast(new History(pos, System.currentTimeMillis()));
        }
        while (!entries.isEmpty() && System.currentTimeMillis() - entries.peekFirst().time > trackingBuffer.asInt()) {
            entries.removeFirst();
        }
    }

    private void release() {
        if (holding && client() != null && client().packets() != null) {
            client().packets().release(PacketEvent.Direction.RECEIVE, this);
        }
        holding = false;
        releaseAt = 0L;
    }

    @Override
    public void drawWorldOverlay(OverlayDraw draw, WorldProjection projection) {
        if (!esp.get()) {
            return;
        }
        for (Deque<History> entries : history.values()) {
            for (History entry : entries) {
                WorldGeometry.box(draw, projection, entry.pos[0] - 0.3, entry.pos[1], entry.pos[2] - 0.3,
                        entry.pos[0] + 0.3, entry.pos[1] + 1.8, entry.pos[2] + 0.3,
                        1.0f, outlineColor.argb());
                WorldGeometry.label(draw, projection, "■", entry.pos[0], entry.pos[1] + 0.8,
                        entry.pos[2], sideColor.argb());
            }
        }
    }

    @Override
    protected void onStop() {
        release();
        history.clear();
        lastTarget = null;
    }

    private static final class History {
        private final double[] pos;
        private final long time;

        private History(double[] pos, long time) {
            this.pos = pos;
            this.time = time;
        }
    }
}
