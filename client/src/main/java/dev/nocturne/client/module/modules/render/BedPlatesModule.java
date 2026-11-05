package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.action.PlayerState;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.client.render.WorldOverlay;
import dev.nocturne.client.render.WorldProjection;
import dev.nocturne.client.value.BooleanValue;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;

/** 扫描床并显示其周围防护方块数量和距离。 */
public final class BedPlatesModule extends GameModule implements WorldOverlay {
    private final BooleanValue showDistance = add(new BooleanValue("Show distance", true));
    private final List<int[]> beds = new ArrayList<int[]>();
    private long nextScan;

    public BedPlatesModule() {
        super("BedPlates", Category.RENDER);
    }

    @Override
    public void onTick() {
        long now = System.currentTimeMillis();
        if (now < nextScan) {
            return;
        }
        nextScan = now + 500L;
        beds.clear();
        GameBridge bridge = bridge();
        PlayerState state = state();
        if (bridge == null || !state.present()) {
            return;
        }
        int px = (int) Math.floor(state.x());
        int py = (int) Math.floor(state.y());
        int pz = (int) Math.floor(state.z());
        for (int x = px - 16; x <= px + 16; x++) {
            for (int y = Math.max(0, py - 8); y <= py + 8; y++) {
                for (int z = pz - 16; z <= pz + 16; z++) {
                    String id = bridge.blockDescription(bridge.blockStateAt(x, y, z));
                    if (id.endsWith("_bed") || id.equals("tile.bed") || id.endsWith(".bed")) {
                        beds.add(new int[]{x, y, z});
                    }
                }
            }
        }
    }

    @Override
    public void drawWorldOverlay(OverlayDraw draw, WorldProjection projection) {
        GameBridge bridge = bridge();
        PlayerState state = state();
        if (bridge == null || !state.present()) {
            return;
        }
        for (int[] bed : beds) {
            WorldProjection.ScreenPoint point = projection.project(bed[0] + 0.5, bed[1] + 1.2, bed[2] + 0.5);
            if (!point.visible) {
                continue;
            }
            int protection = 0;
            for (int x = bed[0] - 1; x <= bed[0] + 1; x++) {
                for (int y = bed[1]; y <= bed[1] + 2; y++) {
                    for (int z = bed[2] - 1; z <= bed[2] + 1; z++) {
                        if (!bridge.isAir(bridge.blockStateAt(x, y, z))) {
                            protection++;
                        }
                    }
                }
            }
            String text = "Bed  " + protection + " blocks";
            if (showDistance.get()) {
                text += String.format(Locale.ROOT, "  %.0fm", Math.sqrt(
                        Math.pow(bed[0] - state.x(), 2) + Math.pow(bed[1] - state.y(), 2)
                                + Math.pow(bed[2] - state.z(), 2)));
            }
            float width = draw.textWidth(text) + 8f;
            draw.roundedRect(point.x - width * 0.5f, point.y - 6f, width, 16f, 3f, 0x96000000);
            draw.text(text, point.x - width * 0.5f + 4f, point.y - 3f, 0xFFFFFFFF);
        }
    }

    @Override
    protected void onStop() {
        beds.clear();
    }
}
