package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.action.PlayerState;
import dev.nocturne.client.event.WorldChangeEvent;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.client.render.WorldGeometry;
import dev.nocturne.client.render.WorldOverlay;
import dev.nocturne.client.render.WorldProjection;
import dev.nocturne.client.value.ColorValue;
import dev.nocturne.client.value.MultiBoolValue;
import dev.nocturne.client.value.NumberValue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Epsilon Block ESP：主 tick 扫描已加载区块中的容器方块实体，绘制低透明填充与十二边描边。
 *
 * <p>上游降级：{@code Illegals} 删除，因为本模块只扫描方块实体，无法用该数据源发现命令方块、
 * 屏障等没有方块实体的目标；{@code Blur} 与 {@code Blur Strength} 删除，因为覆盖层没有模糊通道，
 * 保留它们只会形成无效设置。
 */
public final class BlockEspModule extends GameModule implements WorldOverlay {
    private static final String CHEST = "Chest";
    private static final String TRAPPED_CHEST = "Trapped Chest";
    private static final String COPPER_CHEST = "Copper Chest";
    private static final String ENDER_CHEST = "Ender Chest";
    private static final String BARREL = "Barrel";
    private static final String SHULKER_BOX = "Shulker Box";
    private static final String DYED_SHULKER_BOX = "Dyed Shulker Box";
    private static final long SCAN_INTERVAL_NANOS = 1_000_000_000L;

    private final MultiBoolValue blockList = add(new MultiBoolValue("Block List",
            Arrays.asList(CHEST, TRAPPED_CHEST, COPPER_CHEST, ENDER_CHEST, BARREL,
                    SHULKER_BOX, DYED_SHULKER_BOX),
            Arrays.asList(CHEST, TRAPPED_CHEST, ENDER_CHEST, BARREL,
                    SHULKER_BOX, DYED_SHULKER_BOX)));
    private final NumberValue range = add(new NumberValue("Range", 64, 1, 128, 1));
    private final ColorValue sideColor = add(new ColorValue("Side Color", 0x1EA0D2FF));
    private final ColorValue lineColor = add(new ColorValue("Line Color", 0xB4A0D2FF));

    /** 不可变坐标快照；绘制线程只读，不在绘制期访问游戏对象。 */
    private volatile List<int[]> boxes = Collections.emptyList();
    private long lastScanNanos;
    private volatile double playerX;
    private volatile double playerY;
    private volatile double playerZ;
    private volatile boolean playerPresent;

    public BlockEspModule() {
        super("Block ESP", Category.RENDER);
    }

    @Override
    protected void onStart() {
        clear();
        listen(WorldChangeEvent.class, event -> clear());
    }

    @Override
    protected void onStop() {
        clear();
    }

    private void clear() {
        boxes = Collections.emptyList();
        lastScanNanos = 0L;
        playerPresent = false;
        BlockEntityScan.clear();
    }

    @Override
    public void onTick() {
        PlayerState player = state();
        playerPresent = player.present();
        if (!player.present()) {
            boxes = Collections.emptyList();
            return;
        }
        playerX = player.x();
        playerY = player.y();
        playerZ = player.z();

        long now = System.nanoTime();
        long elapsed = now - lastScanNanos;
        if (lastScanNanos != 0L && elapsed >= 0L && elapsed < SCAN_INTERVAL_NANOS) {
            return;
        }
        lastScanNanos = now;

        GameBridge bridge = bridge();
        if (bridge == null) {
            boxes = Collections.emptyList();
            return;
        }
        List<Object> blockEntities = BlockEntityScan.loadedBlockEntities(bridge);
        List<int[]> found = new ArrayList<int[]>();
        double maximumDistance = range.get();
        double maximumDistanceSquared = maximumDistance * maximumDistance;
        for (int i = 0; i < blockEntities.size(); i++) {
            Object blockEntity = blockEntities.get(i);
            int family = family(bridge, blockEntity);
            if (family == 0) {
                continue;
            }
            Object blockPos = bridge.callMapped(blockEntity, ClassType.BLOCK_ENTITY, "getBlockPos");
            int[] coordinates = bridge.blockCoordinates(blockPos);
            if (coordinates == null) {
                continue;
            }
            double dx = coordinates[0] + 0.5 - player.x();
            double dy = coordinates[1] + 0.5 - player.y();
            double dz = coordinates[2] + 0.5 - player.z();
            if (dx * dx + dy * dy + dz * dz > maximumDistanceSquared) {
                continue;
            }
            String description = bridge.blockDescription(bridge.blockStateAt(
                    coordinates[0], coordinates[1], coordinates[2])).toLowerCase(Locale.ROOT);
            if (selected(family, description)) {
                found.add(coordinates);
            }
        }
        boxes = Collections.unmodifiableList(found);
    }

    /** 先用方块实体类型排除非容器；具体箱子与潜影盒变体再由描述 id 区分。 */
    private static int family(GameBridge bridge, Object blockEntity) {
        if (bridge.isInstance(blockEntity, ClassType.CHEST_BLOCK_ENTITY)) {
            return 1;
        }
        if (bridge.isInstance(blockEntity, ClassType.ENDER_CHEST_BLOCK_ENTITY)) {
            return 2;
        }
        if (bridge.isInstance(blockEntity, ClassType.BARREL_BLOCK_ENTITY)) {
            return 3;
        }
        if (bridge.isInstance(blockEntity, ClassType.SHULKER_BOX_BLOCK_ENTITY)) {
            return 4;
        }
        return 0;
    }

    private boolean selected(int family, String description) {
        switch (family) {
            case 1:
                if (description.contains("copper_chest")) {
                    return blockList.selected(COPPER_CHEST);
                }
                if (description.contains("trapped_chest")) {
                    return blockList.selected(TRAPPED_CHEST);
                }
                return blockList.selected(CHEST);
            case 2:
                return blockList.selected(ENDER_CHEST);
            case 3:
                return blockList.selected(BARREL);
            case 4:
                if (isDyedShulker(description)) {
                    return blockList.selected(DYED_SHULKER_BOX);
                }
                return blockList.selected(SHULKER_BOX);
            default:
                return false;
        }
    }

    /** 原版未染色盒名为 shulker_box，十六种染色变体均带颜色前缀。 */
    private static boolean isDyedShulker(String description) {
        if (description.isEmpty()) {
            return false;
        }
        int separator = description.lastIndexOf('.');
        String blockName = separator < 0 ? description : description.substring(separator + 1);
        return blockName.endsWith("shulker_box") && !"shulker_box".equals(blockName);
    }

    @Override
    public void drawWorldOverlay(OverlayDraw draw, WorldProjection projection) {
        if (!playerPresent) {
            return;
        }
        List<int[]> snapshot = boxes;
        int side = sideColor.argb();
        int line = lineColor.argb();
        double x = playerX;
        double y = playerY;
        double z = playerZ;
        double maximumDistance = range.get();
        double maximumDistanceSquared = maximumDistance * maximumDistance;
        for (int i = 0; i < snapshot.size(); i++) {
            int[] pos = snapshot.get(i);
            double dx = pos[0] + 0.5 - x;
            double dy = pos[1] + 0.5 - y;
            double dz = pos[2] + 0.5 - z;
            if (dx * dx + dy * dy + dz * dz > maximumDistanceSquared) {
                continue;
            }
            WorldGeometry.silhouette(draw, projection,
                    pos[0], pos[1], pos[2], pos[0] + 1.0, pos[1] + 1.0, pos[2] + 1.0, side);
            WorldGeometry.box(draw, projection,
                    pos[0], pos[1], pos[2], pos[0] + 1.0, pos[1] + 1.0, pos[2] + 1.0, 1.0f, line);
        }
    }
}
