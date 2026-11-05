package dev.nocturne.client.module.modules.world;

import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.module.modules.EpsilonSupport;
import dev.nocturne.client.module.modules.render.WorldGeometry;
import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.client.render.WorldOverlay;
import dev.nocturne.client.render.WorldProjection;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.NumberValue;

/** Bed Nuker：范围床搜索、视线/旋转/挥手控制与目标 ESP。 */
public final class BedBreakerModule extends GameModule implements WorldOverlay {
    private final NumberValue range = add(new NumberValue("Range", 4.5, 1, 6, 0.1));
    private final BooleanValue throughWalls = add(new BooleanValue("Through Walls", false));
    private final BooleanValue rotate = add(new BooleanValue("Rotate", true));
    private final NumberValue rotationSpeed = add(new NumberValue("Rotation Speed", 180, 10, 180, 10));
    private final BooleanValue swingHand = add(new BooleanValue("Swing Hand", true));
    private final BooleanValue esp = add(new BooleanValue("ESP", true));
    private int[] target;

    public BedBreakerModule() {
        super("Bed Nuker", Category.PLAYER);
    }

    @Override
    public void onTick() {
        target = findBed();
        if (target == null || EpsilonSupport.enabled("Scaffold") || EpsilonSupport.enabled("Kill Aura")) {
            return;
        }
        if (!throughWalls.get()) {
            int[] hit = bridge().hitBlockPosition(actions().pick(range.get(), 1.0f));
            if (hit == null || !same(hit, target)) {
                return;
            }
        }
        if (rotate.get()) {
            EpsilonSupport.aim(bridge(), state(), target[0] + 0.5, target[1] + 0.5,
                    target[2] + 0.5, rotationSpeed.asFloat(), 5);
        }
        Object pos = bridge().blockPos(target[0], target[1], target[2]);
        Object direction = bridge().staticField(
                dev.nocturne.client.mapping.ClassType.DIRECTION, "UP");
        EpsilonSupport.startDestroy(bridge(), pos, direction);
        EpsilonSupport.continueDestroy(bridge(), pos, direction);
        if (swingHand.get()) {
            actions().swing(false, false);
        }
    }

    private int[] findBed() {
        int px = (int) Math.floor(state().x());
        int py = (int) Math.floor(state().y());
        int pz = (int) Math.floor(state().z());
        int radius = (int) Math.ceil(range.get());
        int[] best = null;
        double bestDistance = Double.MAX_VALUE;
        for (int x = px - radius; x <= px + radius; x++) {
            for (int y = py - radius; y <= py + radius; y++) {
                for (int z = pz - radius; z <= pz + radius; z++) {
                    String id = bridge().blockDescription(bridge().blockStateAt(x, y, z));
                    if (!(id.endsWith("_bed") || id.equals("tile.bed") || id.endsWith(".bed"))) {
                        continue;
                    }
                    double distance = Math.pow(x + 0.5 - state().x(), 2) + Math.pow(y + 0.5 - state().y(), 2)
                            + Math.pow(z + 0.5 - state().z(), 2);
                    if (distance < bestDistance) {
                        bestDistance = distance;
                        best = new int[]{x, y, z};
                    }
                }
            }
        }
        return best;
    }

    public boolean breakingTarget() {
        return target != null;
    }

    @Override
    public void drawWorldOverlay(OverlayDraw draw, WorldProjection projection) {
        if (esp.get() && target != null) {
            WorldGeometry.blockFilled(draw, projection, target[0], target[1], target[2],
                    1.0f, 0x40FF5B69, 0xFFFF5B69);
        }
    }

    private static boolean same(int[] a, int[] b) {
        return a[0] == b[0] && a[1] == b[1] && a[2] == b[2];
    }
}
