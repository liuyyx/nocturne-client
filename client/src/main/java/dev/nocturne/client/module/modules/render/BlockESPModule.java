package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.client.render.WorldOverlay;
import dev.nocturne.client.render.WorldProjection;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.ColorValue;
import dev.nocturne.client.value.NumberValue;
import dev.nocturne.client.value.TextValue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Block ESP：后台扫描容器与非法方块，主线程绘制填充/描边框。 */
public final class BlockESPModule extends GameModule implements WorldOverlay {
    private final TextValue blockList = add(new TextValue("Block List", "chest,trapped_chest,copper_chest,ender_chest,barrel,shulker_box", 2048));
    private final BooleanValue illegals = add(new BooleanValue("Illegals", false));
    private final NumberValue range = add(new NumberValue("Range", 64, 1, 128, 1));
    private final ColorValue sideColor = add(new ColorValue("Side Color", 0x1EA0D2FF));
    private final ColorValue lineColor = add(new ColorValue("Line Color", 0xB4A0D2FF));
    private final BooleanValue blur = add(new BooleanValue("Blur", true));
    private final NumberValue blurStrength = add(new NumberValue("Blur Strength", 8, 0, 16, 0.5));
    private volatile List<int[]> boxes = new ArrayList<int[]>();
    private ExecutorService executor;
    private volatile long lastScan;

    public BlockESPModule() {
        super("Block ESP", Category.RENDER);
    }

    @Override
    protected void onStart() {
        executor = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "Nocturne-BlockESP");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override
    public void onTick() {
        long now = System.currentTimeMillis();
        if (executor == null || now - lastScan < 1000L) {
            return;
        }
        lastScan = now;
        final double[] origin = {state().x(), state().y(), state().z()};
        executor.execute(() -> {
            List<int[]> found = new ArrayList<int[]>();
            for (Object blockEntity : BlockEntityScan.loadedBlockEntities(bridge())) {
                int[] pos = bridge().blockCoordinates(bridge().callMapped(blockEntity,
                        ClassType.BLOCK_ENTITY, "getBlockPos"));
                if (pos != null && matches(pos)) {
                    found.add(pos);
                }
            }
            if (illegals.get()) {
                int radius = (int) Math.ceil(range.get());
                int px = (int) Math.floor(origin[0]);
                int py = (int) Math.floor(origin[1]);
                int pz = (int) Math.floor(origin[2]);
                for (int x = px - radius; x <= px + radius; x++) {
                    for (int y = Math.max(-64, py - 16); y <= Math.min(320, py + 16); y++) {
                        for (int z = pz - radius; z <= pz + radius; z++) {
                            String id = bridge().blockDescription(bridge().blockStateAt(x, y, z));
                            if (id.contains("command_block") || id.contains("barrier")) {
                                found.add(new int[]{x, y, z});
                            }
                        }
                    }
                }
            }
            boxes = found;
        });
    }

    private boolean matches(int[] pos) {
        String id = bridge().blockDescription(bridge().blockStateAt(pos[0], pos[1], pos[2]));
        for (String token : blockList.get().toLowerCase(java.util.Locale.ROOT).split("[,;\\s]+")) {
            if (!token.isEmpty() && id.toLowerCase(java.util.Locale.ROOT).contains(token)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void drawWorldOverlay(OverlayDraw draw, WorldProjection projection) {
        int side = sideColor.argb();
        if (blur.get()) {
            side = side & 0xFFFFFF | Math.min(side >>> 24, blurStrength.asInt() * 4) << 24;
        }
        for (int[] pos : boxes) {
            WorldGeometry.blockFilled(draw, projection, pos[0], pos[1], pos[2], 1.0f, side, lineColor.argb());
        }
    }

    @Override
    protected void onStop() {
        if (executor != null) {
            executor.shutdownNow();
        }
        executor = null;
        boxes = new ArrayList<int[]>();
    }
}
