package dev.nocturne.client.module.modules.movement;

import dev.nocturne.client.event.MoveEvent;
import dev.nocturne.client.event.PacketEvent;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.module.Module;
import dev.nocturne.client.module.modules.EpsilonSupport;
import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.client.render.WorldOverlay;
import dev.nocturne.client.render.WorldProjection;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.ColorValue;
import dev.nocturne.client.value.NumberValue;

/** HoleSnap：搜索单/双/四格洞并以限速移动吸附中心，含碰撞和回弹停用。 */
public final class HoleSnapModule extends GameModule implements WorldOverlay {
    private final BooleanValue useStep = add(new BooleanValue("Use Step", true));
    private final BooleanValue toggleStep = add(new BooleanValue("Toggle Step", true));
    private final BooleanValue jump = add(new BooleanValue("Jump", false));
    private final NumberValue jumpCooldown = add(new NumberValue("Jump Cooldown", 5, 0, 100, 1));
    private final NumberValue range = add(new NumberValue("Range", 3, 0, 10, 1));
    private final NumberValue downRange = add(new NumberValue("Down Range", 3, 0, 5, 1));
    private final NumberValue collisionLimit = add(new NumberValue("Collisions To Disable", 15, 0, 100, 1));
    private final NumberValue rubberbandLimit = add(new NumberValue("Rubberbands To Disable", 1, 0, 100, 1));
    private final NumberValue speed = add(new NumberValue("Speed", 0.2873, 0, 1, 0.0001));
    private final BooleanValue boost = add(new BooleanValue("Speed Boost", false));
    private final NumberValue boostedSpeed = add(new NumberValue("Boosted Speed", 0.5, 0, 1, 0.01));
    private final NumberValue boostTicks = add(new NumberValue("Boost Ticks", 3, 1, 10, 1));
    private final NumberValue timer = add(new NumberValue("Timer", 1.5, 0.1, 100, 0.1));
    private final BooleanValue singleTarget = add(new BooleanValue("Single Target", true));
    private final NumberValue depth = add(new NumberValue("Hole Depth", 3, 1, 5, 1));
    private final BooleanValue singleHoles = add(new BooleanValue("Single Holes", true));
    private final BooleanValue doubleHoles = add(new BooleanValue("Double Holes", true));
    private final BooleanValue quadHoles = add(new BooleanValue("Quad Holes", true));
    private final BooleanValue render = add(new BooleanValue("Render", true));
    private final NumberValue lineWidth = add(new NumberValue("Line Width", 2, 1, 4, 0.1));
    private final ColorValue color = add(new ColorValue("Color", 0xFFFFFFFF));
    private double[] target;
    private int collisions;
    private int rubberbands;
    private int jumpTicks;
    private int boostLeft;

    public HoleSnapModule() {
        super("Hole Snap", Category.MOVEMENT);
    }

    @Override
    protected void onStart() {
        target = findHole();
        boostLeft = boost.get() ? boostTicks.asInt() : 0;
        listen(PacketEvent.class, event -> {
            if (event.direction == PacketEvent.Direction.RECEIVE
                    && bridge().isInstance(event.packet, ClassType.CLIENTBOUND_PLAYER_POSITION)
                    && rubberbandLimit.asInt() > 0 && ++rubberbands >= rubberbandLimit.asInt()) {
                setEnabled(false);
            }
        });
        listen(MoveEvent.class, this::move);
    }

    private void move(MoveEvent event) {
        if (!singleTarget.get() || target == null) {
            target = findHole();
        }
        if (target == null) {
            return;
        }
        bridge().setTimerSpeed(timer.asFloat());
        double dx = target[0] - state().x();
        double dy = target[1] - state().y();
        double dz = target[2] - state().z();
        if (Math.abs(dx) < 0.01 && Math.abs(dz) < 0.01) {
            event.x = 0.0;
            event.z = 0.0;
            if (Math.abs(dy) < 0.1) {
                setEnabled(false);
            }
            return;
        }
        double length = Math.hypot(dx, dz);
        double currentSpeed = boostLeft-- > 0 ? boostedSpeed.get() : speed.get();
        event.x = Math.min(Math.abs(dx), currentSpeed) * Math.signum(dx);
        event.z = Math.min(Math.abs(dz), currentSpeed) * Math.signum(dz);
        Object box = bridge().callMapped(bridge().player(), ClassType.ENTITY, "getBoundingBox");
        if (!bridge().noCollision(bridge().player(), bridge().moveBox(box, event.x, 0, event.z))) {
            if (collisionLimit.asInt() > 0 && ++collisions >= collisionLimit.asInt()) {
                setEnabled(false);
                return;
            }
        } else {
            collisions = 0;
        }
        if (jumpTicks > 0) {
            jumpTicks--;
        } else if (jump.get() && state().onGround()) {
            event.y = 0.42;
            jumpTicks = jumpCooldown.asInt();
        }
        if (useStep.get()) {
            Module step = client().modules().get("Step");
            if (step != null) {
                step.setEnabled(true);
            }
        }
    }

    private double[] findHole() {
        int px = (int) Math.floor(state().x());
        int py = (int) Math.floor(state().y());
        int pz = (int) Math.floor(state().z());
        double[] best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int x = px - range.asInt(); x <= px + range.asInt(); x++) {
            for (int y = py; y >= py - downRange.asInt(); y--) {
                for (int z = pz - range.asInt(); z <= pz + range.asInt(); z++) {
                    int shape = holeShape(x, y, z);
                    if (shape == 0 || shape == 1 && !singleHoles.get() || shape == 2 && !doubleHoles.get()
                            || shape == 4 && !quadHoles.get()) {
                        continue;
                    }
                    double distance = Math.pow(x + 0.5 - state().x(), 2) + Math.pow(z + 0.5 - state().z(), 2);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = holeCenter(x, y, z, shape);
                    }
                }
            }
        }
        return best;
    }

    private int holeShape(int x, int y, int z) {
        for (int down = 1; down <= depth.asInt(); down++) {
            if (bridge().isAir(bridge().blockStateAt(x, y - down, z))) {
                return 0;
            }
        }
        if (!bridge().isAir(bridge().blockStateAt(x, y, z))
                || !bridge().isAir(bridge().blockStateAt(x, y + 1, z))) {
            return 0;
        }
        boolean east = holeCell(x + 1, y, z);
        boolean south = holeCell(x, y, z + 1);
        boolean diagonal = holeCell(x + 1, y, z + 1);
        return east && south && diagonal ? 4 : east || south ? 2 : 1;
    }

    private double[] holeCenter(int x, int y, int z, int shape) {
        boolean doubleX = shape >= 2 && holeCell(x + 1, y, z);
        boolean doubleZ = shape >= 2 && holeCell(x, y, z + 1);
        return new double[]{x + 0.5 + (doubleX ? 0.5 : 0.0), y,
                z + 0.5 + (doubleZ ? 0.5 : 0.0)};
    }

    private boolean holeCell(int x, int y, int z) {
        return !bridge().isAir(bridge().blockStateAt(x, y - 1, z))
                && bridge().isAir(bridge().blockStateAt(x, y, z))
                && bridge().isAir(bridge().blockStateAt(x, y + 1, z));
    }

    @Override
    public void drawWorldOverlay(OverlayDraw draw, WorldProjection projection) {
        if (!render.get() || target == null) {
            return;
        }
        WorldProjection.ScreenPoint from = projection.project(state().x(), state().y() + 1.62, state().z());
        WorldProjection.ScreenPoint to = projection.project(target[0], target[1], target[2]);
        if (from.visible && to.visible) {
            draw.line(from.x, from.y, to.x, to.y, lineWidth.asFloat(), color.argb());
        }
    }

    @Override
    protected void onStop() {
        bridge().setTimerSpeed(1.0f);
        if (toggleStep.get() && useStep.get()) {
            Module step = client().modules().get("Step");
            if (step != null) {
                step.setEnabled(false);
            }
        }
        target = null;
    }
}
