package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.action.PlayerState;
import dev.nocturne.client.bootstrap.GameplayHook;
import dev.nocturne.client.event.WorldChangeEvent;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.client.render.WorldGeometry;
import dev.nocturne.client.render.WorldOverlay;
import dev.nocturne.client.render.WorldProjection;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.ModeValue;
import dev.nocturne.client.value.NumberValue;

import java.util.ArrayList;
import java.util.List;

/**
 * 按矿物名单剔除方块，并用自绘覆盖层标记扫描到的目标。
 *
 * <p>上游降级：Ore Deobf 删除，避免主动发包探测副作用。上游 Plugin 只服务于该探测的
 * 扫描范围与顺序；探测删除后，这里把 Plugin 落实为可见的绘制差异：Old 仅描边，
 * New 描边并叠加低透明度剪影。原版的填充盒统一降级为屏幕剪影。
 *
 * <p>扫描只在 tick 中进行并一次性发布坐标/颜色快照；绘制阶段不访问世界或映射层。
 * WallHack 的剔除在描述不可读时保守放行，且名单为空时始终放行，避免把世界剔黑。
 */
public final class XrayModule extends GameModule implements WorldOverlay {
    private static final int NETHERITE = 1;
    private static final int DIAMOND = 1 << 1;
    private static final int GOLD = 1 << 2;
    private static final int IRON = 1 << 3;
    private static final int EMERALD = 1 << 4;
    private static final int REDSTONE = 1 << 5;
    private static final int LAPIS = 1 << 6;
    private static final int COAL = 1 << 7;
    private static final int QUARTZ = 1 << 8;
    private static final int WATER = 1 << 9;
    private static final int LAVA = 1 << 10;

    private static final int[][] NO_TARGETS = new int[0][];

    private final ModeValue plugin = add(new ModeValue("Plugin", "New", "Old", "New"));
    private final BooleanValue wallHack = add(new BooleanValue("WallHack", false));
    private final BooleanValue fast = add(new BooleanValue("Fast", false));
    private final NumberValue delay = add(new NumberValue("Delay", 25, 1, 100, 1));
    private final NumberValue radius = add(new NumberValue("Radius", 5, 1, 64, 1));
    private final NumberValue up = add(new NumberValue("Up", 5, 1, 32, 1));
    private final NumberValue down = add(new NumberValue("Down", 5, 1, 32, 1));
    private final BooleanValue netherite = add(new BooleanValue("Netherite", false));
    private final BooleanValue diamond = add(new BooleanValue("Diamond", false));
    private final BooleanValue gold = add(new BooleanValue("Gold", false));
    private final BooleanValue iron = add(new BooleanValue("Iron", false));
    private final BooleanValue emerald = add(new BooleanValue("Emerald", false));
    private final BooleanValue redstone = add(new BooleanValue("Redstone", false));
    private final BooleanValue lapis = add(new BooleanValue("Lapis", false));
    private final BooleanValue coal = add(new BooleanValue("Coal", false));
    private final BooleanValue quartz = add(new BooleanValue("Quartz", false));
    private final BooleanValue water = add(new BooleanValue("Water", false));
    private final BooleanValue lava = add(new BooleanValue("Lava", false));

    /** 每个元素为 {x, y, z, 0xAARRGGBB}；发布后不再修改，供渲染线程读取。 */
    private volatile int[][] targets = NO_TARGETS;
    private long nextScan;
    private long settingsStamp;
    /** attach 晚于模块配置启用，故只能在 tick 中持续确认，不能在 onStart 固化结论。 */
    private volatile boolean cullingWired;
    private boolean unavailableLogged;

    public XrayModule() {
        super("Xray", Category.RENDER);
    }

    @Override
    protected void onStart() {
        targets = NO_TARGETS;
        nextScan = 0L;
        settingsStamp = currentSettingsStamp();
        cullingWired = false;
        unavailableLogged = false;
        listen(WorldChangeEvent.class, event -> {
            targets = NO_TARGETS;
            nextScan = 0L;
        });
        reloadChunks();
    }

    @Override
    protected void onStop() {
        targets = NO_TARGETS;
        nextScan = 0L;
        reloadChunks();
    }

    private void reloadChunks() {
        GameBridge bridge = bridge();
        if (bridge != null) {
            bridge.reloadChunks();
        }
    }

    /** 配置回放可能早于 agent 织入；每 tick 重新确认，后续 attach 成功后自动启用剔除。 */
    private void updateCullingAvailability() {
        cullingWired = GameplayHook.isWired("xrayBlock");
        if (cullingWired || unavailableLogged) {
            return;
        }
        unavailableLogged = true;
        String reason = GameplayHook.unavailableReason("xrayBlock");
        System.out.println("[nocturne] Xray: block culling unavailable on this version"
                + (reason == null || reason.isEmpty() ? "" : " (" + reason + ")")
                + "; ore boxes only");
    }

    /** 由真实方块渲染钩子调用；异常隔离与最终放行由 GameplayHook 统一负责。 */
    public boolean shouldRender(Object blockState, Object blockPos) {
        int mask = targetMask();
        if (!cullingWired || !wallHack.get() || mask == 0) {
            return true;
        }
        GameBridge bridge = bridge();
        if (bridge == null || blockState == null) {
            return true;
        }
        String description = bridge.blockDescription(blockState);
        return description.isEmpty() || selectedColor(description, mask) != 0;
    }

    @Override
    public void onTick() {
        updateCullingAvailability();
        long now = System.currentTimeMillis();
        long stamp = currentSettingsStamp();
        if (stamp != settingsStamp) {
            settingsStamp = stamp;
            nextScan = 0L;
            reloadChunks();
        }
        if (now < nextScan) {
            return;
        }
        nextScan = now + delay.asInt();

        int mask = targetMask();
        if (mask == 0) {
            targets = NO_TARGETS;
            return;
        }
        GameBridge bridge = bridge();
        PlayerState player = state();
        if (bridge == null || !player.present()) {
            targets = NO_TARGETS;
            return;
        }

        int centerX = (int) Math.floor(player.x());
        int centerY = (int) Math.floor(player.y());
        int centerZ = (int) Math.floor(player.z());
        int horizontal = radius.asInt();
        int upward = up.asInt();
        int downward = down.asInt();
        int step = fast.get() ? 2 : 1;
        Object world = bridge.world();
        if (world == null) {
            targets = NO_TARGETS;
            return;
        }
        List<int[]> found = new ArrayList<int[]>();
        for (int x = centerX - horizontal; x <= centerX + horizontal; x += step) {
            for (int y = centerY - downward; y <= centerY + upward; y += step) {
                for (int z = centerZ - horizontal; z <= centerZ + horizontal; z += step) {
                    Object blockState = bridge.blockStateAt(world, x, y, z);
                    if (blockState == null) {
                        continue;
                    }
                    String description = bridge.blockDescription(blockState);
                    int color = description.isEmpty() ? 0 : selectedColor(description, mask);
                    if (color != 0) {
                        found.add(new int[]{x, y, z, color});
                    }
                }
            }
        }
        targets = found.isEmpty() ? NO_TARGETS : found.toArray(new int[found.size()][]);
    }

    @Override
    public void drawWorldOverlay(OverlayDraw draw, WorldProjection projection) {
        int[][] snapshot = targets;
        boolean filled = plugin.is("New");
        for (int i = 0; i < snapshot.length; i++) {
            int[] target = snapshot[i];
            double x = target[0];
            double y = target[1];
            double z = target[2];
            int color = target[3];
            WorldGeometry.box(draw, projection, x, y, z, x + 1.0, y + 1.0, z + 1.0,
                    1.0f, color & 0x00FFFFFF | 0xC8000000);
            if (filled) {
                WorldGeometry.silhouette(draw, projection, x, y, z, x + 1.0, y + 1.0, z + 1.0,
                        color & 0x00FFFFFF | 0x28000000);
            }
        }
    }

    private int targetMask() {
        return (netherite.get() ? NETHERITE : 0)
                | (diamond.get() ? DIAMOND : 0)
                | (gold.get() ? GOLD : 0)
                | (iron.get() ? IRON : 0)
                | (emerald.get() ? EMERALD : 0)
                | (redstone.get() ? REDSTONE : 0)
                | (lapis.get() ? LAPIS : 0)
                | (coal.get() ? COAL : 0)
                | (quartz.get() ? QUARTZ : 0)
                | (water.get() ? WATER : 0)
                | (lava.get() ? LAVA : 0);
    }

    /** 用位段记录全部设置，GUI 修改后在下一 tick 触发扫描与区块重建。 */
    private long currentSettingsStamp() {
        return targetMask()
                | (plugin.is("Old") ? 1L << 12 : 0L)
                | (wallHack.get() ? 1L << 13 : 0L)
                | (fast.get() ? 1L << 14 : 0L)
                | (long) delay.asInt() << 15
                | (long) radius.asInt() << 22
                | (long) up.asInt() << 29
                | (long) down.asInt() << 35;
    }

    /** 返回固定不透明基色；0 表示当前描述不属于已启用的目标。 */
    private static int selectedColor(String description, int mask) {
        if ((mask & NETHERITE) != 0 && (contains(description, "ancient_debris")
                || contains(description, "ancientdebris")
                || contains(description, "netherite_scrap")
                || contains(description, "netheritescrap"))) {
            return 0xFFFFFFFF;
        }
        if ((mask & DIAMOND) != 0 && (contains(description, "diamond_ore")
                || contains(description, "orediamond"))) {
            return 0xFF00FFFF;
        }
        if ((mask & GOLD) != 0 && (contains(description, "gold_ore")
                || contains(description, "oregold"))) {
            return 0xFFFFD700;
        }
        if ((mask & IRON) != 0 && (contains(description, "iron_ore")
                || contains(description, "oreiron"))) {
            return 0xFFD5D5D5;
        }
        if ((mask & EMERALD) != 0 && (contains(description, "emerald_ore")
                || contains(description, "oreemerald"))) {
            return 0xFF00FF4D;
        }
        if ((mask & REDSTONE) != 0 && (contains(description, "redstone_ore")
                || contains(description, "oreredstone"))) {
            return 0xFFFF0000;
        }
        if ((mask & LAPIS) != 0 && (contains(description, "lapis_ore")
                || contains(description, "orelapis"))) {
            return 0xFF26619C;
        }
        if ((mask & COAL) != 0 && (contains(description, "coal_ore")
                || contains(description, "orecoal"))) {
            return 0xFF202020;
        }
        if ((mask & QUARTZ) != 0 && (contains(description, "quartz_ore")
                || contains(description, "orequartz"))) {
            return 0xFFAAAAAA;
        }
        if ((mask & WATER) != 0 && (description.equalsIgnoreCase("water")
                || endsWith(description, ".water"))) {
            return 0xFF388CD6;
        }
        if ((mask & LAVA) != 0 && (description.equalsIgnoreCase("lava")
                || endsWith(description, ".lava"))) {
            return 0xFFFF5A00;
        }
        return 0;
    }

    /** 渲染钩子也使用此匹配器，避免 toLowerCase 在每次方块判定时分配字符串。 */
    private static boolean contains(String value, String needle) {
        int last = value.length() - needle.length();
        for (int i = 0; i <= last; i++) {
            if (value.regionMatches(true, i, needle, 0, needle.length())) {
                return true;
            }
        }
        return false;
    }

    private static boolean endsWith(String value, String suffix) {
        int offset = value.length() - suffix.length();
        return offset >= 0 && value.regionMatches(true, offset, suffix, 0, suffix.length());
    }
}
