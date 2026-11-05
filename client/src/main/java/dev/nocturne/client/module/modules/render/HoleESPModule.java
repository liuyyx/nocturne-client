package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.client.render.WorldOverlay;
import dev.nocturne.client.render.WorldProjection;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.ColorValue;
import dev.nocturne.client.value.NumberValue;

import java.util.ArrayList;
import java.util.List;

/** Hole ESP：安全/非安全/墙洞/空气标记扫描，分组填充与描边渐隐。 */
public final class HoleESPModule extends GameModule implements WorldOverlay {
    private final NumberValue startFade = number("Start Fade", 5, 1, 20, 0.1);
    private final NumberValue distance = number("Distance", 6, 1, 20, 0.1);
    private final NumberValue height = number("Height", 1, -3, 3, 0.1);
    private final NumberValue updateDelay = number("Update Delay", 50, 0, 1000, 10);
    private final NumberValue airHeight = number("Air Height", 1, -3, 3, 0.01);
    private final BooleanValue airYCheck = bool("Air Y Check", true);
    private final BooleanValue airFill = bool("Air Fill", true);
    private final ColorValue airFillColor = color("Air Fill Color", 0x64940000);
    private final BooleanValue airFade = bool("Air Fade", true);
    private final ColorValue airFadeColor = color("Air Fade Color", 0x00940000);
    private final BooleanValue airBox = bool("Air Box", true);
    private final ColorValue airBoxColor = color("Air Box Color", 0x64940000);
    private final BooleanValue unsafeFill = bool("Unsafe Fill", true);
    private final ColorValue unsafeFillColor = color("Unsafe Fill Color", 0x32FF0000);
    private final BooleanValue unsafeFade = bool("Unsafe Fade", true);
    private final ColorValue unsafeFadeColor = color("Unsafe Fade Color", 0x00FF0000);
    private final BooleanValue unsafeBox = bool("Unsafe Box", true);
    private final ColorValue unsafeBoxColor = color("Unsafe Box Color", 0x64FF0000);
    private final BooleanValue safeFill = bool("Safe Fill", true);
    private final ColorValue safeFillColor = color("Safe Fill Color", 0x3208FF4F);
    private final BooleanValue safeFade = bool("Safe Fade", true);
    private final ColorValue safeFadeColor = color("Safe Fade Color", 0x6408FF4F);
    private final BooleanValue safeBox = bool("Safe Box", true);
    private final ColorValue safeBoxColor = color("Safe Box Color", 0x6408FF4F);
    private final NumberValue wallHeight = number("Wall Height", 3, -3, 3, 0.1);
    private final BooleanValue sideCheck = bool("Side Check", true);
    private final BooleanValue wallFill = bool("Wall Fill", true);
    private final ColorValue wallFillColor = color("Wall Fill Color", 0x8000FFFF);
    private final BooleanValue wallFade = bool("Wall Fade", true);
    private final ColorValue wallFadeColor = color("Wall Fade Color", 0x4000FFFF);
    private final BooleanValue wallBox = bool("Wall Box", true);
    private final ColorValue wallBoxColor = color("Wall Box Color", 0xFF00E1FF);
    private final BooleanValue wallSideFill = bool("Wall Side Fill", true);
    private final ColorValue wallSideFillColor = color("Wall Side Fill Color", 0x8000FFFF);
    private final BooleanValue wallSideFade = bool("Wall Side Fade", true);
    private final ColorValue wallSideFadeColor = color("Wall Side Fade Color", 0x4000FFFF);
    private final BooleanValue wallSideBox = bool("Wall Side Box", true);
    private final ColorValue wallSideBoxColor = color("Wall Side Box Color", 0xFF00E1FF);
    private final List<Hole> holes = new ArrayList<Hole>();
    private long nextScan;

    public HoleESPModule() {
        super("Hole ESP", Category.RENDER);
    }

    @Override
    public void onTick() {
        long now = System.currentTimeMillis();
        if (now < nextScan) {
            return;
        }
        nextScan = now + updateDelay.asInt();
        holes.clear();
        int radius = (int) Math.ceil(distance.get());
        int px = (int) Math.floor(state().x());
        int py = (int) Math.floor(state().y());
        int pz = (int) Math.floor(state().z());
        for (int x = px - radius; x <= px + radius; x++) {
            for (int y = py + 1; y >= py - 3; y--) {
                for (int z = pz - radius; z <= pz + radius; z++) {
                    Hole hole = classify(x, y, z);
                    if (hole != null) {
                        holes.add(hole);
                    }
                }
            }
        }
    }

    private Hole classify(int x, int y, int z) {
        if (!bridge().isAir(bridge().blockStateAt(x, y, z))
                || !bridge().isAir(bridge().blockStateAt(x, y + 1, z))) {
            return null;
        }
        String below = id(x, y - 1, z);
        if (below.isEmpty() || below.contains("air")) {
            return null;
        }
        int solidSides = 0;
        int bedrockSides = 0;
        int[][] sides = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] side : sides) {
            String id = id(x + side[0], y, z + side[1]);
            if (hard(id)) {
                solidSides++;
            }
            if (id.contains("bedrock")) {
                bedrockSides++;
            }
        }
        if (solidSides < 4) {
            return airYCheck.get() && hard(id(x, y + 1, z)) ? new Hole(x, y, z, Kind.AIR,
                    Math.sqrt(Math.pow(x + 0.5 - state().x(), 2) + Math.pow(z + 0.5 - state().z(), 2))) : null;
        }
        Kind kind = solidSides == 4 && sideCheck.get() && hard(id(x, y + 1, z)) ? Kind.WALL
                : below.contains("bedrock") && bedrockSides == 4 ? Kind.SAFE : Kind.UNSAFE;
        double dist = Math.sqrt(Math.pow(x + 0.5 - state().x(), 2) + Math.pow(y - state().y(), 2)
                + Math.pow(z + 0.5 - state().z(), 2));
        return dist <= distance.get() ? new Hole(x, y, z, kind, dist) : null;
    }

    private boolean hard(String id) {
        return id.matches(".*(bedrock|obsidian|ender_chest|netherite_block|crying_obsidian|respawn_anchor|ancient_debris|anvil).*");
    }

    private String id(int x, int y, int z) {
        return bridge().blockDescription(bridge().blockStateAt(x, y, z));
    }

    @Override
    public void drawWorldOverlay(OverlayDraw draw, WorldProjection projection) {
        for (Hole hole : holes) {
            float alpha = hole.distance <= startFade.get() ? 1.0f
                    : (float) Math.max(0.0, 1.0 - (hole.distance - startFade.get())
                    / Math.max(0.1, distance.get() - startFade.get()));
            if (hole.kind == Kind.AIR) {
                drawHole(draw, projection, hole, airHeight.get(), airFill.get(), airFillColor.argb(),
                        airFade.get(), airFadeColor.argb(), airBox.get(), airBoxColor.argb(), alpha);
            } else if (hole.kind == Kind.WALL) {
                drawHole(draw, projection, hole, wallHeight.get(), wallFill.get(), wallFillColor.argb(),
                        wallFade.get(), wallFadeColor.argb(), wallBox.get(), wallBoxColor.argb(), alpha);
                if (wallSideFill.get()) {
                    int side = withAlpha(wallSideFillColor.argb(), alpha);
                    if (wallSideFade.get()) {
                        side = blend(side, wallSideFadeColor.argb(), 1.0f - alpha);
                    }
                    WorldGeometry.blockFilled(draw, projection, hole.x, hole.y, hole.z,
                            1.0f, side, withAlpha(wallSideBoxColor.argb(), alpha));
                }
                if (wallSideBox.get()) {
                    WorldGeometry.side(draw, projection, hole.x, hole.y, hole.z, "up", 1.0f,
                            withAlpha(wallSideBoxColor.argb(), alpha));
                }
            } else {
                boolean safe = hole.kind == Kind.SAFE;
                drawHole(draw, projection, hole, height.get(), safe ? safeFill.get() : unsafeFill.get(),
                        safe ? safeFillColor.argb() : unsafeFillColor.argb(), safe ? safeFade.get() : unsafeFade.get(),
                        safe ? safeFadeColor.argb() : unsafeFadeColor.argb(), safe ? safeBox.get() : unsafeBox.get(),
                        safe ? safeBoxColor.argb() : unsafeBoxColor.argb(), alpha);
            }
        }
    }

    private void drawHole(OverlayDraw draw, WorldProjection projection, Hole hole, double height,
                          boolean fill, int fillColor, boolean fade, int fadeColor,
                          boolean outline, int outlineColor, float alpha) {
        int line = withAlpha(outlineColor, alpha);
        int side = withAlpha(fill ? fillColor : 0, alpha);
        if (fade) {
            side = blend(side, fadeColor, 1.0f - alpha);
        }
        WorldGeometry.box(draw, projection, hole.x, hole.y, hole.z, hole.x + 1.0,
                hole.y + height, hole.z + 1.0, 1.0f, line);
        if (fill) {
            WorldGeometry.blockFilled(draw, projection, hole.x, hole.y, hole.z, 1.0f, side, line);
        }
    }

    private static int withAlpha(int color, float factor) {
        return color & 0xFFFFFF | (int) ((color >>> 24) * factor) << 24;
    }

    private static int blend(int from, int to, float amount) {
        int a = (int) ((from >>> 24) + ((to >>> 24) - (from >>> 24)) * amount);
        int r = (int) ((from >> 16 & 255) + ((to >> 16 & 255) - (from >> 16 & 255)) * amount);
        int g = (int) ((from >> 8 & 255) + ((to >> 8 & 255) - (from >> 8 & 255)) * amount);
        int b = (int) ((from & 255) + ((to & 255) - (from & 255)) * amount);
        return a << 24 | r << 16 | g << 8 | b;
    }

    private NumberValue number(String name, double def, double min, double max, double step) {
        return add(new NumberValue(name, def, min, max, step));
    }

    private BooleanValue bool(String name, boolean def) {
        return add(new BooleanValue(name, def));
    }

    private ColorValue color(String name, int def) {
        return add(new ColorValue(name, def));
    }

    private enum Kind { SAFE, UNSAFE, WALL, AIR }

    private static final class Hole {
        private final int x;
        private final int y;
        private final int z;
        private final Kind kind;
        private final double distance;

        private Hole(int x, int y, int z, Kind kind, double distance) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.kind = kind;
            this.distance = distance;
        }
    }
}
