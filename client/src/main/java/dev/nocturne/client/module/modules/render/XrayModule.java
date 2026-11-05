package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.action.PlayerState;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.client.render.WorldOverlay;
import dev.nocturne.client.render.WorldProjection;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.NumberValue;
import dev.nocturne.client.value.TextValue;

import java.util.ArrayList;
import java.util.List;

/** 在真实方块渲染入口剔除非名单方块，并标记目标矿位置。 */
public final class XrayModule extends GameModule implements WorldOverlay {
    private static final int[][] NEIGHBORS = {
            {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };
    private final NumberValue opacity = add(new NumberValue("Opacity", 60, 0, 255, 1));
    private final BooleanValue caveMode = add(new BooleanValue("Cave Mode", false));
    private final TextValue blocks = add(new TextValue("Xray Blocks",
            "gold_ore,iron_ore,diamond_ore,emerald_ore,lapis_ore,gold_block,iron_block,diamond_block,emerald_block,oreGold,oreIron,oreDiamond,oreEmerald,oreLapis", 512));
    private final List<int[]> targets = new ArrayList<int[]>();
    private String rulesSnapshot = "";
    private String[] tokens = new String[0];
    private long nextScan;

    public XrayModule() {
        super("Xray", Category.RENDER);
    }

    @Override
    protected void onStart() {
        if (bridge() != null) {
            bridge().reloadChunks();
        }
    }

    @Override
    protected void onStop() {
        targets.clear();
        if (bridge() != null) {
            bridge().reloadChunks();
        }
    }

    /** 由真实 BlockRenderDispatcher 钩子调用。 */
    public boolean shouldRender(Object blockState, Object blockPos) {
        String id = bridge().blockDescription(blockState);
        if (id.isEmpty()) {
            return true;
        }
        if (!matches(id)) {
            return false;
        }
        return !caveMode.get() || exposed(blockPos);
    }

    @Override
    public void onTick() {
        long now = System.currentTimeMillis();
        if (now < nextScan) {
            return;
        }
        nextScan = now + 500L;
        targets.clear();
        GameBridge bridge = bridge();
        PlayerState state = state();
        if (bridge == null || !state.present()) {
            return;
        }
        int px = (int) Math.floor(state.x());
        int py = (int) Math.floor(state.y());
        int pz = (int) Math.floor(state.z());
        for (int x = px - 12; x <= px + 12; x++) {
            for (int y = Math.max(0, py - 6); y <= py + 6; y++) {
                for (int z = pz - 12; z <= pz + 12; z++) {
                    Object block = bridge.blockStateAt(x, y, z);
                    if (shouldRender(block, bridge.blockPos(x, y, z))) {
                        targets.add(new int[]{x, y, z});
                    }
                }
            }
        }
    }

    @Override
    public void drawWorldOverlay(OverlayDraw draw, WorldProjection projection) {
        int alpha = Math.max(0, Math.min(255, opacity.asInt()));
        int color = (alpha << 24) | 0x00FFFFFF;
        for (int[] target : targets) {
            RenderSupport.marker(draw, projection, target[0] + 0.5, target[1] + 0.5, target[2] + 0.5, color);
        }
    }

    private boolean matches(String id) {
        String rules = blocks.get();
        if (!rules.equals(rulesSnapshot)) {
            rulesSnapshot = rules;
            tokens = rules.toLowerCase(java.util.Locale.ROOT).split("[,;\\s]+");
        }
        String lower = id.toLowerCase(java.util.Locale.ROOT);
        for (String token : tokens) {
            if (!token.isEmpty() && lower.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private boolean exposed(Object blockPos) {
        int[] pos = bridge().blockCoordinates(blockPos);
        if (pos == null) {
            return true;
        }
        for (int[] offset : NEIGHBORS) {
            if (bridge().isAir(bridge().blockStateAt(pos[0] + offset[0],
                    pos[1] + offset[1], pos[2] + offset[2]))) {
                return true;
            }
        }
        return false;
    }
}
