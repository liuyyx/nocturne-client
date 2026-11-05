package dev.nocturne.client.module.modules.combat;

import dev.nocturne.client.event.BlockEvent;
import dev.nocturne.client.mapping.ClassType;
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

/** Packet Mine：双目标包级破坏、按真实工具速度推进、切换恢复与渐进渲染。 */
public final class PacketMineModule extends GameModule implements WorldOverlay {
    private final BooleanValue pauseOnUse = add(new BooleanValue("Pause On Use", true));
    private final BooleanValue onlyMain = add(new BooleanValue("Only Main", true));
    private final ModeValue switchMode = add(new ModeValue("Switch Mode", "Silent", "None", "Delay", "Silent"));
    private final NumberValue range = add(new NumberValue("Range", 6, 0, 12, 1));
    private final NumberValue maxBreaks = add(new NumberValue("Try Break Time", 6, 0, 10, 1));
    private final BooleanValue farCancel = add(new BooleanValue("Far Cancel", true));
    private final BooleanValue swingHand = add(new BooleanValue("Swing Hand", true));
    private final BooleanValue instantMine = add(new BooleanValue("Instant Mine", true));
    private final NumberValue instantDelay = add(new NumberValue("Instant Delay", 10, 0, 1000, 10));
    private final BooleanValue fastBypass = add(new BooleanValue("Fast Bypass", true));
    private final BooleanValue doubleBreak = add(new BooleanValue("Double Break", false));
    private final BooleanValue checkGround = add(new BooleanValue("Check Ground", true));
    private final BooleanValue bypassGround = add(new BooleanValue("Bypass Ground", false));
    private final BooleanValue clientRemove = add(new BooleanValue("Client Remove", true));
    private final NumberValue switchDamage = add(new NumberValue("Switch Damage", 95, 0, 100, 1));
    private final NumberValue switchTime = add(new NumberValue("Switch Time", 100, 0, 1000, 10));
    private final NumberValue mineDelay = add(new NumberValue("Mine Delay", 300, 0, 1000, 10));
    private final NumberValue packetDelay = add(new NumberValue("Packet Delay", 200, 0, 1000, 10));
    private final NumberValue damage = add(new NumberValue("Damage", 0.8, 0, 2, 0.05));
    private final ModeValue renderMode = add(new ModeValue("Render Mode", "Shrink", "Box", "Normal", "Shrink", "Grow"));
    private final BooleanValue fading = add(new BooleanValue("Fading", true));
    private final NumberValue renderTime = add(new NumberValue("Render Time", 0.1, 0, 5, 0.1));
    private final NumberValue fadeTime = add(new NumberValue("Fade Time", 0.2, 0, 5, 0.1));
    private final ColorValue fadeSide = add(new ColorValue("Fade Side Color", 0x1F46C89B));
    private final ColorValue fadeLine = add(new ColorValue("Fade Line Color", 0xE946C89B));
    private final ColorValue sideStart = add(new ColorValue("Side Start", 0x1FFF0000));
    private final ColorValue sideEnd = add(new ColorValue("Side End", 0x1F00960A));
    private final ColorValue lineStart = add(new ColorValue("Line Start", 0xE9FF0000));
    private final ColorValue lineEnd = add(new ColorValue("Line End", 0xE905A000));
    private final ColorValue secondSideStart = add(new ColorValue("Second Side Start", 0x1FFF0000));
    private final ColorValue secondSideEnd = add(new ColorValue("Second Side End", 0x1F00960A));
    private final ColorValue secondLineStart = add(new ColorValue("Second Line Start", 0xE9FF0000));
    private final ColorValue secondLineEnd = add(new ColorValue("Second Line End", 0xE905A000));
    private MineTarget primary;
    private MineTarget secondary;
    private long nextMine;
    private int original = -1;
    private long restoreAt;

    public PacketMineModule() {
        super("Packet Mine", Category.COMBAT);
    }

    @Override
    protected void onStart() {
        listen(BlockEvent.class, event -> {
            if (event.phase != BlockEvent.Phase.START || event.blockPos == null
                    || pauseOnUse.get() && bridge().usingItem(bridge().player())
                    && (!onlyMain.get() || !bridge().usingOffHand(bridge().player()))
                    || System.currentTimeMillis() < nextMine) {
                return;
            }
            int[] pos = bridge().blockCoordinates(event.blockPos);
            if (pos == null || farCancel.get() && Math.sqrt(distanceSquared(pos)) > range.get()) {
                return;
            }
            event.cancelled = true;
            mine(pos, event.direction);
            nextMine = System.currentTimeMillis() + mineDelay.asInt();
        });
    }

    private void mine(int[] pos, Object direction) {
        MineTarget target = new MineTarget(pos, direction);
        if (doubleBreak.get() && primary != null && !same(primary.pos, pos)) {
            secondary = primary;
        }
        primary = target;
    }

    @Override
    public void onTick() {
        if (original >= 0 && System.currentTimeMillis() >= restoreAt) {
            actions().selectSlot(original, switchMode.is("Silent"));
            original = -1;
        }
        primary = tickTarget(primary, false);
        if (doubleBreak.get()) {
            secondary = tickTarget(secondary, true);
        } else {
            secondary = null;
        }
    }

    private MineTarget tickTarget(MineTarget target, boolean second) {
        if (target == null) {
            return null;
        }
        if (farCancel.get() && Math.sqrt(distanceSquared(target.pos)) > range.get()) {
            return null;
        }
        long now = System.currentTimeMillis();
        if (!target.started) {
            Object pos = bridge().blockPos(target.pos[0], target.pos[1], target.pos[2]);
            EpsilonSupport.startDestroy(bridge(), pos, target.direction);
            if (fastBypass.get()) {
                Object bypass = bridge().blockPos((int) Math.floor(state().x()), 321, (int) Math.floor(state().z()));
                Object down = bridge().staticField(ClassType.DIRECTION, "DOWN");
                EpsilonSupport.startDestroy(bridge(), bypass, down);
            }
            if (swingHand.get()) {
                actions().swing(false, false);
            }
            target.started = true;
            target.last = now;
            return target;
        }
        double delta = (now - target.last) / 1000.0;
        target.last = now;
        double speed = bestSpeed(target.pos);
        double rate = checkGround.get() && !state().onGround() && !bypassGround.get() ? 4.0 : 20.0;
        target.progress += delta * rate;
        target.percent = Math.min(100, (int) (target.progress * speed * 100.0
                / Math.max(1.0, 20.0 * damage.get())));
        if (target.percent >= switchDamage.asInt() && original < 0 && !switchMode.is("None")) {
            int best = bestTool(target.pos);
            if (best >= 0) {
                original = state().selectedSlot();
                actions().selectSlot(best, switchMode.is("Silent"));
                restoreAt = now + switchTime.asInt();
            }
        }
        if (target.percent >= 100 && !target.completed) {
            Object pos = bridge().blockPos(target.pos[0], target.pos[1], target.pos[2]);
            EpsilonSupport.stopDestroy(bridge());
            EpsilonSupport.startDestroy(bridge(), pos, target.direction);
            target.completed = true;
            target.completedAt = now;
            target.attempts++;
            if (clientRemove.get()) {
                bridge().setAirBlock(target.pos[0], target.pos[1], target.pos[2]);
            }
        }
        if (target.completed && instantMine.get() && now - target.completedAt >= instantDelay.asInt()) {
            Object pos = bridge().blockPos(target.pos[0], target.pos[1], target.pos[2]);
            EpsilonSupport.startDestroy(bridge(), pos, target.direction);
            EpsilonSupport.stopDestroy(bridge());
            target.completedAt = now;
            target.attempts++;
        }
        if (target.attempts >= maxBreaks.asInt() * 10 || target.completed && !instantMine.get()
                && now - target.completedAt >= packetDelay.asInt()) {
            return null;
        }
        return target;
    }

    private double bestSpeed(int[] pos) {
        Object state = bridge().blockStateAt(pos[0], pos[1], pos[2]);
        double best = 1.0;
        for (int slot = 0; slot < 9; slot++) {
            Object value = bridge().callMapped(bridge().hotbarStack(slot), ClassType.ITEM_STACK,
                    "getDestroySpeed", state);
            if (value instanceof Number) {
                best = Math.max(best, ((Number) value).doubleValue());
            }
        }
        return best;
    }

    private int bestTool(int[] pos) {
        Object blockState = bridge().blockStateAt(pos[0], pos[1], pos[2]);
        int best = -1;
        double speed = 1.0;
        for (int slot = 0; slot < 9; slot++) {
            Object value = bridge().callMapped(bridge().hotbarStack(slot), ClassType.ITEM_STACK,
                    "getDestroySpeed", blockState);
            if (value instanceof Number && ((Number) value).doubleValue() > speed) {
                speed = ((Number) value).doubleValue();
                best = slot;
            }
        }
        return best;
    }

    private double distanceSquared(int[] pos) {
        return Math.pow(pos[0] + 0.5 - state().x(), 2) + Math.pow(pos[1] + 0.5 - state().y() - 1.62, 2)
                + Math.pow(pos[2] + 0.5 - state().z(), 2);
    }

    @Override
    public void drawWorldOverlay(OverlayDraw draw, WorldProjection projection) {
        renderTarget(draw, projection, primary, false);
        renderTarget(draw, projection, secondary, true);
    }

    private void renderTarget(OverlayDraw draw, WorldProjection projection, MineTarget target, boolean second) {
        if (target == null) {
            return;
        }
        float progress = target.percent / 100.0f;
        if (renderTime.asFloat() > 0.0f) {
            progress *= Math.min(1.0f, (System.currentTimeMillis() - target.createdAt)
                    / (renderTime.asFloat() * 1000.0f));
        }
        double scale = renderMode.is("Shrink") ? 1.0 - progress : renderMode.is("Grow") ? progress : 1.0;
        if (renderMode.is("Box") || scale <= 0.01) {
            scale = 1.0;
        }
        double inset = (1.0 - scale) * 0.5;
        int side = blend(second ? secondSideStart.argb() : sideStart.argb(),
                second ? secondSideEnd.argb() : sideEnd.argb(), progress);
        int line = blend(second ? secondLineStart.argb() : lineStart.argb(),
                second ? secondLineEnd.argb() : lineEnd.argb(), progress);
        if (fading.get() && target.completed) {
            float fade = Math.max(0.0f, 1.0f - (System.currentTimeMillis() - target.completedAt)
                    / Math.max(1.0f, fadeTime.asFloat() * 1000.0f));
            side = alpha(fadeSide.argb(), fade);
            line = alpha(fadeLine.argb(), fade);
        }
        WorldGeometry.box(draw, projection, target.pos[0] + inset, target.pos[1] + inset,
                target.pos[2] + inset, target.pos[0] + 1.0 - inset, target.pos[1] + 1.0 - inset,
                target.pos[2] + 1.0 - inset, 1.0f, line);
        WorldGeometry.label(draw, projection, target.percent + "%", target.pos[0] + 0.5,
                target.pos[1] + 1.2, target.pos[2] + 0.5, side);
    }

    private static int blend(int from, int to, float amount) {
        int a = (int) (((from >>> 24) + ((to >>> 24) - (from >>> 24)) * amount));
        int r = (int) (((from >> 16 & 255) + ((to >> 16 & 255) - (from >> 16 & 255)) * amount));
        int g = (int) (((from >> 8 & 255) + ((to >> 8 & 255) - (from >> 8 & 255)) * amount));
        int b = (int) (((from & 255) + ((to & 255) - (from & 255)) * amount));
        return a << 24 | r << 16 | g << 8 | b;
    }

    private static int alpha(int color, float factor) {
        return color & 0xFFFFFF | (int) ((color >>> 24) * factor) << 24;
    }

    private static boolean same(int[] a, int[] b) {
        return a[0] == b[0] && a[1] == b[1] && a[2] == b[2];
    }

    @Override
    protected void onStop() {
        if (original >= 0 && actions() != null) {
            actions().selectSlot(original, switchMode.is("Silent"));
        }
        original = -1;
        primary = null;
        secondary = null;
    }

    private static final class MineTarget {
        private final int[] pos;
        private final Object direction;
        private final long createdAt = System.currentTimeMillis();
        private double progress;
        private int percent;
        private boolean started;
        private boolean completed;
        private long last;
        private long completedAt;
        private int attempts;

        private MineTarget(int[] pos, Object direction) {
            this.pos = pos;
            this.direction = direction;
        }
    }
}
