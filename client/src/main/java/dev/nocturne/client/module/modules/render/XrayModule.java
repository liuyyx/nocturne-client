package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.action.PlayerState;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.client.render.WorldOverlay;
import dev.nocturne.client.render.WorldProjection;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.ModeValue;
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
    private final ModeValue plugin = add(new ModeValue("Plugin", "New", "Old", "New"));
    private final BooleanValue wallHack = add(new BooleanValue("WallHack", false));
    private final BooleanValue oreDeobf = add(new BooleanValue("Ore Deobf", false));
    private final BooleanValue fast = add(new BooleanValue("Fast", false));
    private final NumberValue delay = add(new NumberValue("Delay", 25, 1, 100, 1));
    private final NumberValue radius = add(new NumberValue("Radius", 5, 1, 64, 1));
    private final NumberValue up = add(new NumberValue("Up", 5, 1, 32, 1));
    private final NumberValue down = add(new NumberValue("Down", 5, 1, 32, 1));
    private final BooleanValue netherite = add(new BooleanValue("Netherite", false));
    private final BooleanValue diamond = add(new BooleanValue("Diamond ", false));
    private final BooleanValue gold = add(new BooleanValue("Gold", false));
    private final BooleanValue iron = add(new BooleanValue("Iron", false));
    private final BooleanValue emerald = add(new BooleanValue("Emerald", false));
    private final BooleanValue redstone = add(new BooleanValue("Redstone", false));
    private final BooleanValue lapis = add(new BooleanValue("Lapis", false));
    private final BooleanValue coal = add(new BooleanValue("Coal", false));
    private final BooleanValue quartz = add(new BooleanValue("Quartz", false));
    private final BooleanValue water = add(new BooleanValue("Water", false));
    private final BooleanValue lava = add(new BooleanValue("Lava", false));
    private long nextScan;
    private long nextDeobf;

    public XrayModule() {
        super("Xray", Category.RENDER);
    }

    @Override
    protected void onStart() {
        // 能力检查：26.1+ 的映射表把 BlockRenderDispatcher 整个标记为 absent，方块剔除钩子没有入口。
        // 此时模块只提供矿石框（overlay），并在日志里明确说明"剔除不可用"——绝不假装剔除生效。
        if (!dev.nocturne.client.bootstrap.GameplayHook.isWired("xrayBlock")) {
            String reason = dev.nocturne.client.bootstrap.GameplayHook.unavailableReason("xrayBlock");
            System.out.println("[nocturne] Xray: block culling unavailable on this version"
                    + (reason == null ? "" : " (" + reason + ")")
                    + "; ore boxes only");
        }
        listen(dev.nocturne.client.event.MoveEvent.class, event -> {
            if (oreDeobf.get()) {
                event.x = 0.0;
                event.z = 0.0;
            }
        });
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
        if (oreDeobf.get() && !enabledOre(id)) {
            return false;
        }
        if (wallHack.get()) {
            return true;
        }
        return !caveMode.get() || exposed(blockPos);
    }

    @Override
    public void onTick() {
        long now = System.currentTimeMillis();
        if (now < nextScan) {
            return;
        }
        nextScan = now + (oreDeobf.get() ? delay.asInt() : 500L);
        targets.clear();
        GameBridge bridge = bridge();
        PlayerState state = state();
        if (bridge == null || !state.present()) {
            return;
        }
        int px = (int) Math.floor(state.x());
        int py = (int) Math.floor(state.y());
        int pz = (int) Math.floor(state.z());
        int horizontal = oreDeobf.get() ? radius.asInt() : 12;
        int upward = oreDeobf.get() ? up.asInt() : 6;
        int downward = oreDeobf.get() ? down.asInt() : 6;
        for (int x = px - horizontal; x <= px + horizontal; x++) {
            for (int y = Math.max(-64, py - downward); y <= py + upward; y++) {
                for (int z = pz - horizontal; z <= pz + horizontal; z++) {
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
        for (int[] target : targets) {
            int color = oreColor(bridge().blockDescription(bridge().blockStateAt(target[0], target[1], target[2])));
            WorldGeometry.blockFilled(draw, projection, target[0], target[1], target[2], 1.0f,
                    color & 0xFFFFFF | alpha << 24, color);
            if (oreDeobf.get()) {
                probe(target);
            }
        }
    }

    private void probe(int[] target) {
        long now = System.currentTimeMillis();
        if (now < nextDeobf) {
            return;
        }
        nextDeobf = now + delay.asInt();
        Object pos = bridge().blockPos(target[0], target[1], target[2]);
        Object direction = bridge().staticField(ClassType.DIRECTION, "UP");
        dev.nocturne.client.module.modules.EpsilonSupport.startDestroy(bridge(), pos, direction);
        dev.nocturne.client.module.modules.EpsilonSupport.stopDestroy(bridge());
        if (fast.get()) {
            dev.nocturne.client.module.modules.EpsilonSupport.startDestroy(bridge(), pos, direction);
        }
    }

    private boolean enabledOre(String id) {
        String lower = id.toLowerCase(java.util.Locale.ROOT);
        return lower.contains("netherite") && netherite.get() || lower.contains("diamond") && diamond.get()
                || lower.contains("gold") && gold.get() || lower.contains("iron") && iron.get()
                || lower.contains("emerald") && emerald.get() || lower.contains("redstone") && redstone.get()
                || lower.contains("lapis") && lapis.get() || lower.contains("coal") && coal.get()
                || lower.contains("quartz") && quartz.get() || lower.contains("water") && water.get()
                || lower.contains("lava") && lava.get();
    }

    private static int oreColor(String id) {
        String lower = id.toLowerCase(java.util.Locale.ROOT);
        if (lower.contains("diamond")) return 0xFF00FFFF;
        if (lower.contains("gold")) return 0xFFFFFF00;
        if (lower.contains("iron")) return 0xFFB0B0B0;
        if (lower.contains("emerald")) return 0xFF00FF00;
        if (lower.contains("redstone")) return 0xFFFF0000;
        if (lower.contains("coal")) return 0xFF202020;
        if (lower.contains("lapis")) return 0xFF0000FF;
        if (lower.contains("quartz")) return 0xFFFFFFFF;
        if (lower.contains("netherite")) return 0xFF4A342E;
        return 0xFFAAAAAA;
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
