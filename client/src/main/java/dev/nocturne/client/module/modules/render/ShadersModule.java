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
import dev.nocturne.client.value.ColorValue;
import dev.nocturne.client.value.ModeValue;
import dev.nocturne.client.value.NumberValue;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 上游降级：实体与已加载箱子使用投影盒、剪影和稀疏点近似，不替换模型或执行 shader。
 * 手部没有可用轮廓目标，删除 Hands 开关及 Hands Shader 组；箱子复用实体设置，删除 Chest Shader 组。
 * 删除 Quality、Smoke Octaves、Glow Radius、Glow Exposure、Glow Gradient Speed、Gradient Factor，
 * 以及无法复现的 Glow Color Mode、渐变和额外烟雾颜色等着色器专属参数。
 */
public final class ShadersModule extends GameModule implements WorldOverlay, RenderSupport.EntityVisitor {
    private final BooleanValue players = add(new BooleanValue("Players", true));
    private final BooleanValue self = add(new BooleanValue("Self", true));
    private final BooleanValue friends = add(new BooleanValue("Friends", true));
    private final BooleanValue crystals = add(new BooleanValue("Crystals", true));
    private final BooleanValue chests = add(new BooleanValue("Chests", true));
    private final BooleanValue creatures = add(new BooleanValue("Creatures", false));
    private final BooleanValue monsters = add(new BooleanValue("Monsters", false));
    private final BooleanValue ambients = add(new BooleanValue("Ambients", false));
    private final BooleanValue others = add(new BooleanValue("Others", false));
    private final NumberValue range = add(new NumberValue("Max Range", 64, 16, 256, 1));
    private final ModeValue mode = add(new ModeValue("Mode", "Default", "Default", "Smoke", "Gradient", "Snow", "Fade"));
    private final ModeValue outlineMode = add(new ModeValue("Outline Mode", "Outline", "Outline", "Glow"));
    private final ColorValue outline = add(new ColorValue("Outline", 0x88FFFFFF));
    private final ColorValue fill = add(new ColorValue("Fill", 0x88FFFFFF));
    private final NumberValue lineWidth = add(new NumberValue("Line Width", 2, 0, 500, 1));
    private final NumberValue fillAlpha = add(new NumberValue("Fill Alpha", 170, 0, 255, 1));
    private final ColorValue glow = add(new ColorValue("Glow Color", 0xFFFFB7C5));
    private final ColorValue friendsColor = add(new ColorValue("Friends Color", 0xFF30FF00));
    private final List<Box> boxes = new ArrayList<Box>();
    private final double[] position = new double[3];
    private final double[] origin = new double[3];
    private final AtomicInteger generation = new AtomicInteger();
    private volatile int publishedGeneration = -1;
    private GameBridge tickBridge;
    private int count;
    private long ticks;

    public ShadersModule() { super("Shaders", Category.RENDER); }
    @Override protected void onStart() { listen(WorldChangeEvent.class, event -> clear()); }
    @Override protected void onStop() { clear(); }
    private void clear() { generation.incrementAndGet(); }

    @Override public void onTick() {
        int current = generation.get();
        count = 0;
        ticks++;
        tickBridge = bridge();
        try {
            if (tickBridge == null || !tickBridge.entityPosition(tickBridge.player(), origin)) {
                publishedGeneration = current;
                return;
            }
            RenderSupport.forEachEntity(tickBridge, this);
            if (chests.get()) {
                List<Object> blocks = BlockEntityScan.loadedBlockEntities(tickBridge);
                for (int i = 0; i < blocks.size(); i++) {
                    Object block = blocks.get(i);
                    if (!tickBridge.isInstance(block, ClassType.CHEST_BLOCK_ENTITY)
                            && !tickBridge.isInstance(block, ClassType.ENDER_CHEST_BLOCK_ENTITY)) continue;
                    Object pos = tickBridge.callMapped(block, ClassType.BLOCK_ENTITY, "getBlockPos");
                    int[] xyz = tickBridge.blockCoordinates(pos);
                    if (xyz != null && inRange(xyz[0] + .5, xyz[1], xyz[2] + .5))
                        addBox(xyz[0] + .5, xyz[1], xyz[2] + .5, 1, 1, false);
                }
            }
            publishedGeneration = current;
        } finally { tickBridge = null; }
    }

    @Override public void visit(Object entity) {
        GameBridge bridge = tickBridge;
        if (bridge == null || !bridge.entityPosition(entity, position)
                || !inRange(position[0], position[1], position[2])) return;
        boolean friend = false;
        if (bridge.isInstance(entity, ClassType.PLAYER)) {
            if (entity == bridge.player()) {
                if (!players.get() || !self.get()) return;
            } else {
                friend = Friends.isFriend(bridge, entity) || bridge.isAllied(bridge.player(), entity);
                if (!(friend ? friends.get() : players.get())) return;
            }
        } else if (bridge.isInstance(entity, ClassType.END_CRYSTAL)) {
            if (!crystals.get()) return;
        } else {
            switch (RenderSupport.kind(bridge, entity)) {
                case ANIMAL: if (!creatures.get()) return; break;
                case MONSTER: if (!monsters.get()) return; break;
                case AMBIENT: if (!ambients.get()) return; break;
                default: if (!others.get()) return; break;
            }
        }
        addBox(position[0], position[1], position[2], bridge.entityWidth(entity), bridge.entityHeight(entity), friend);
    }

    private boolean inRange(double x, double y, double z) {
        double dx = x - origin[0], dy = y - origin[1], dz = z - origin[2], r = range.get();
        return dx * dx + dy * dy + dz * dz <= r * r;
    }
    private void addBox(double x, double y, double z, double width, double height, boolean friend) {
        if (count == boxes.size()) boxes.add(new Box());
        Box box = boxes.get(count++);
        box.x = x; box.y = y; box.z = z; box.radius = width * .5; box.height = height; box.friend = friend;
    }

    @Override public void drawWorldOverlay(OverlayDraw draw, WorldProjection projection) {
        if (publishedGeneration != generation.get()) return;
        float width = Math.max(.5f, Math.min(4f, lineWidth.asFloat() / 2f));
        int fillColor = (fill.argb() & 0xFFFFFF) | (fillAlpha.asInt() << 24);
        for (int i = 0; i < count; i++) {
            Box b = boxes.get(i);
            double x0 = b.x - b.radius, x1 = b.x + b.radius, z0 = b.z - b.radius, z1 = b.z + b.radius, y1 = b.y + b.height;
            int line = b.friend ? friendsColor.argb() : outlineMode.is("Glow") ? glow.argb() : outline.argb();
            if (mode.is("Smoke")) {
                float t = (float) (.5 + .5 * Math.sin(ticks * .12));
                line = RenderSupport.blend(line, fillColor, t * .5f);
                WorldGeometry.silhouette(draw, projection, x0, b.y, z0, x1, y1, z1, RenderSupport.withAlpha(fillColor, .5f + t * .5f));
            } else if (mode.is("Gradient") || mode.is("Fade")) {
                int top = mode.is("Gradient") ? (fillAlpha.asInt() << 24) | (glow.argb() & 0xFFFFFF) : RenderSupport.withAlpha(fillColor, 0f);
                WorldGeometry.silhouetteFade(draw, projection, x0, b.y, z0, x1, y1, z1, top, fillColor);
            } else {
                WorldGeometry.silhouette(draw, projection, x0, b.y, z0, x1, y1, z1, fillColor);
            }
            if (mode.is("Snow")) {
                for (int j = 0; j < 12; j++) {
                    double y = b.y + b.height * ((j * 7 + ticks) % 13) / 13.0;
                    double x = (j & 1) == 0 ? x0 : x1, z = (j & 2) == 0 ? z0 : z1;
                    WorldGeometry.line(draw, projection, x, y, z, x, y + Math.min(.05, b.height / 20), z, width, line);
                }
            } else {
                if (outlineMode.is("Glow")) WorldGeometry.box(draw, projection, x0, b.y, z0, x1, y1, z1, width + 4f, RenderSupport.withAlpha(line, .25f));
                WorldGeometry.box(draw, projection, x0, b.y, z0, x1, y1, z1, width, line);
            }
        }
    }
    private static final class Box { double x, y, z, radius, height; boolean friend; }
}
