package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.control.Friends;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.client.render.RenderSupport;
import dev.nocturne.client.render.WorldOverlay;
import dev.nocturne.client.render.WorldProjection;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.ColorValue;
import dev.nocturne.client.value.NumberValue;

/** Epsilon 八角投影实体框与血条；不依赖实体绘制替换或 GL，逐实体复用投影点。 */
public final class Esp2DModule extends GameModule implements WorldOverlay, RenderSupport.EntityVisitor {
    private final BooleanValue players = add(new BooleanValue("Players", true));
    private final BooleanValue friends = add(new BooleanValue("Friends", true));
    private final BooleanValue creatures = add(new BooleanValue("Creatures", false));
    private final BooleanValue monsters = add(new BooleanValue("Monsters", false));
    private final BooleanValue ambients = add(new BooleanValue("Ambients", false));
    private final BooleanValue others = add(new BooleanValue("Others", false));
    private final BooleanValue renderHealth = add(new BooleanValue("Render Health", true));
    private final NumberValue healthWidth = add(new NumberValue("Health Bar Width", 2, .5, 6, .5));
    private final BooleanValue healthOutline = add(new BooleanValue("Health Bar Outline", true));
    private final NumberValue healthOutlineWidth = add(new NumberValue("Health Bar Outline Width", 1, .5, 3, .5));
    private final BooleanValue renderBox = add(new BooleanValue("Render Box", true));
    private final BooleanValue boxOutline = add(new BooleanValue("Box Outline", true));
    private final ColorValue playersColor = add(new ColorValue("Players Color", 0xFFFF9200));
    private final ColorValue friendsColor = add(new ColorValue("Friends Color", 0xFF30FF00));
    private final ColorValue creaturesColor = add(new ColorValue("Creatures Color", 0xFFA0A4A6));
    private final ColorValue monstersColor = add(new ColorValue("Monsters Color", 0xFFFF0000));
    private final ColorValue ambientsColor = add(new ColorValue("Ambients Color", 0xFF7B00FF));
    private final ColorValue othersColor = add(new ColorValue("Others Color", 0xFFFF0062));
    private final ColorValue healthColor = add(new ColorValue("Health Color", 0xFF2FFF00));
    private final double[] position = new double[3];
    private final WorldProjection.ScreenPoint[] points = new WorldProjection.ScreenPoint[8];
    private final java.util.Map<Object, Entry> cache = new java.util.IdentityHashMap<Object, Entry>();
    private final java.util.List<Entry> visible = new java.util.ArrayList<Entry>();
    private GameBridge tickBridge;
    private int epoch;

    public Esp2DModule() {
        super("ESP 2D", Category.RENDER);
        for (int i = 0; i < points.length; i++) points[i] = new WorldProjection.ScreenPoint();
    }

    @Override
    protected void onStart() {
        listen(dev.nocturne.client.event.WorldChangeEvent.class, event -> clear());
    }

    @Override
    protected void onStop() { clear(); }

    private void clear() { cache.clear(); visible.clear(); }

    @Override
    public void onTick() {
        visible.clear();
        tickBridge = bridge();
        if (tickBridge == null) return;
        epoch++;
        RenderSupport.forEachEntity(tickBridge, this);
        if (cache.size() != visible.size()) {
            java.util.Iterator<Entry> iterator = cache.values().iterator();
            while (iterator.hasNext()) if (iterator.next().seen != epoch) iterator.remove();
        }
    }

    @Override
    public void visit(Object entity) {
        GameBridge bridge = tickBridge;
        if (RenderSupport.isSelf(bridge, entity)) return;
        long selected = color(bridge, entity);
        if (selected < 0 || !bridge.entityPosition(entity, position)) return;
        Entry entry = cache.get(entity);
        if (entry == null) {
            entry = new Entry();
            cache.put(entity, entry);
        }
        entry.x = position[0]; entry.y = position[1]; entry.z = position[2];
        entry.radius = bridge.entityWidth(entity) * .5;
        entry.height = bridge.entityHeight(entity);
        entry.color = (int) selected;
        entry.healthRatio = -1f;
        if (renderHealth.get() && bridge.isInstance(entity, ClassType.LIVING_ENTITY)) {
            float maximum = bridge.maxHealth(entity);
            if (maximum > 0f) {
                entry.healthRatio = Math.max(0f, Math.min(1f, bridge.health(entity) / maximum));
            }
        }
        entry.seen = epoch;
        visible.add(entry);
    }

    @Override
    public void drawWorldOverlay(OverlayDraw draw, WorldProjection projection) {
        for (int index = 0; index < visible.size(); index++) {
            Entry entry = visible.get(index);
            float left = Float.POSITIVE_INFINITY, top = Float.POSITIVE_INFINITY;
            float right = Float.NEGATIVE_INFINITY, bottom = Float.NEGATIVE_INFINITY;
            for (int i = 0; i < 8; i++) {
                WorldProjection.ScreenPoint point = points[i];
                projection.project(entry.x + ((i & 1) == 0 ? -entry.radius : entry.radius),
                        entry.y + ((i & 2) == 0 ? 0 : entry.height),
                        entry.z + ((i & 4) == 0 ? -entry.radius : entry.radius), point);
                if (!point.visible) continue;
                left = Math.min(left, point.x); top = Math.min(top, point.y);
                right = Math.max(right, point.x); bottom = Math.max(bottom, point.y);
            }
            if (left == Float.POSITIVE_INFINITY || right <= left || bottom <= top) continue;
            float width = right - left, screenHeight = bottom - top;
            if (renderBox.get()) {
                if (boxOutline.get()) draw.outline(left, top, width, screenHeight, 3.2f, 0xFF000000);
                draw.outline(left, top, width, screenHeight, 1.2f, entry.color);
            }
            if (entry.healthRatio < 0f) continue;
            float barWidth = healthWidth.asFloat(), x = left - barWidth - 3f;
            draw.rect(x, top, barWidth, screenHeight, 0x80000000);
            if (healthOutline.get()) {
                float outline = healthOutlineWidth.asFloat();
                draw.outline(x - outline, top - outline, barWidth + outline * 2,
                        screenHeight + outline * 2, outline, 0xFF000000);
            }
            draw.rect(x, bottom - screenHeight * entry.healthRatio, barWidth,
                    screenHeight * entry.healthRatio, healthColor.argb());
        }
    }

    /** 负值表示该类别被关闭；非负值保留完整无符号 ARGB，包括全透明颜色。 */
    private long color(GameBridge bridge, Object entity) {
        RenderSupport.Kind kind = RenderSupport.kind(bridge, entity);
        if (kind == RenderSupport.Kind.PLAYER && Friends.isFriend(bridge, entity)) {
            return friends.get() ? friendsColor.argb() & 0xFFFFFFFFL : -1L;
        }
        switch (kind) {
            case PLAYER: return players.get() ? playersColor.argb() & 0xFFFFFFFFL : -1L;
            case MONSTER: return monsters.get() ? monstersColor.argb() & 0xFFFFFFFFL : -1L;
            case ANIMAL: return creatures.get() ? creaturesColor.argb() & 0xFFFFFFFFL : -1L;
            case AMBIENT: return ambients.get() ? ambientsColor.argb() & 0xFFFFFFFFL : -1L;
            default: return others.get() ? othersColor.argb() & 0xFFFFFFFFL : -1L;
        }
    }

    private static final class Entry {
        private double x, y, z, radius, height;
        private float healthRatio;
        private int color, seen;
    }
}
