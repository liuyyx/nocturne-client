package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.control.Friends;
import dev.nocturne.client.event.WorldChangeEvent;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.client.render.RenderSupport;
import dev.nocturne.client.render.WorldGeometry;
import dev.nocturne.client.render.WorldOverlay;
import dev.nocturne.client.render.WorldProjection;
import dev.nocturne.client.value.BooleanValue;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 实体与箱子的透视覆盖层。缺少实体材质替换通道，使用包围盒剪影与描边近似，
 * 不会改变原版模型；箱子只覆盖已加载区块中可扫描的箱子方块实体。
 */
public final class ChamsModule extends GameModule implements WorldOverlay, RenderSupport.EntityVisitor {
    private final BooleanValue players = add(new BooleanValue("Players", true));
    private final BooleanValue self = add(new BooleanValue("Self", true));
    private final BooleanValue friends = add(new BooleanValue("Friends", true));
    private final BooleanValue crystals = add(new BooleanValue("Crystals", true));
    private final BooleanValue chests = add(new BooleanValue("Chests", true));
    private final BooleanValue creatures = add(new BooleanValue("Creatures", false));
    private final BooleanValue monsters = add(new BooleanValue("Monsters", false));
    private final BooleanValue ambients = add(new BooleanValue("Ambients", false));
    private final BooleanValue others = add(new BooleanValue("Others", false));
    private final List<Box> boxes = new ArrayList<Box>();
    private final double[] position = new double[3];
    private GameBridge tickBridge;
    private int count;
    private final AtomicInteger generation = new AtomicInteger();
    private volatile int publishedGeneration = -1;

    public ChamsModule() { super("Chams", Category.RENDER); }

    @Override protected void onStart() { listen(WorldChangeEvent.class, event -> clear()); }
    @Override protected void onStop() { clear(); }
    private void clear() { generation.incrementAndGet(); BlockEntityScan.clear(); }

    /** 供其他模块复用实体选择规则；箱子由方块实体扫描单独处理。 */
    public boolean accept(GameBridge bridge, Object entity) {
        if (bridge == null || entity == null) return false;
        if (RenderSupport.isSelf(bridge, entity)) return self.get();
        if (bridge.isInstance(entity, ClassType.END_CRYSTAL)) return crystals.get();
        switch (RenderSupport.kind(bridge, entity)) {
            case PLAYER:
                return Friends.isFriend(bridge, entity) || bridge.isAllied(bridge.player(), entity)
                        ? friends.get() : players.get();
            case ANIMAL: return creatures.get();
            case MONSTER: return monsters.get();
            case AMBIENT: return ambients.get();
            default: return others.get();
        }
    }

    @Override public void onTick() {
        int currentGeneration = generation.get();
        try {
            count = 0;
            tickBridge = bridge();
            if (tickBridge == null) return;
            RenderSupport.forEachEntity(tickBridge, this);
            if (chests.get()) {
                List<Object> entities = BlockEntityScan.loadedBlockEntities(tickBridge);
                for (int i = 0; i < entities.size(); i++) {
                    Object entity = entities.get(i);
                    if (!tickBridge.isInstance(entity, ClassType.CHEST_BLOCK_ENTITY)
                            && !tickBridge.isInstance(entity, ClassType.ENDER_CHEST_BLOCK_ENTITY)) continue;
                    Object pos = tickBridge.callMapped(entity, ClassType.BLOCK_ENTITY, "getBlockPos");
                    int[] xyz = tickBridge.blockCoordinates(pos);
                    if (xyz != null) addBox(xyz[0], xyz[1], xyz[2], 1, 1, 0xFF55FFFF, true);
                }
            }
            publishedGeneration = currentGeneration;
        } finally { tickBridge = null; }
    }

    @Override public void visit(Object entity) {
        GameBridge bridge = tickBridge;
        // 第一人称相机位于本地实体内部；投影自身包围盒会把边线拉满屏幕。
        if (RenderSupport.isSelf(bridge, entity) && bridge.perspective() == 0) return;
        if (!accept(bridge, entity) || !bridge.entityPosition(entity, position)) return;
        int color = bridge.isInstance(entity, ClassType.END_CRYSTAL) ? 0xFFA078FF
                : RenderSupport.kind(bridge, entity) == RenderSupport.Kind.PLAYER ? 0xFFFFFFFF : 0xFFAAAAAA;
        addBox(position[0], position[1], position[2], bridge.entityWidth(entity),
                bridge.entityHeight(entity), color, false);
    }

    private void addBox(double x, double y, double z, double width, double height, int color, boolean corner) {
        if (count == boxes.size()) boxes.add(new Box());
        Box box = boxes.get(count++);
        box.x = corner ? x + width * .5 : x; box.y = y; box.z = corner ? z + width * .5 : z;
        box.radius = width * .5; box.height = height; box.color = color;
    }

    @Override public void drawWorldOverlay(OverlayDraw draw, WorldProjection projection) {
        if (publishedGeneration != generation.get()) return;
            for (int i = 0; i < count; i++) {
                Box b = boxes.get(i);
                WorldGeometry.silhouette(draw, projection, b.x-b.radius, b.y, b.z-b.radius,
                        b.x+b.radius, b.y+b.height, b.z+b.radius, 0x33FFFFFF);
                WorldGeometry.box(draw, projection, b.x-b.radius, b.y, b.z-b.radius,
                        b.x+b.radius, b.y+b.height, b.z+b.radius, 1f, b.color);
            }

    }
    private static final class Box { double x, y, z, radius, height; int color; }
}
