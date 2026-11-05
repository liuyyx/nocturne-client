package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.control.Friends;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.client.render.WorldOverlay;
import dev.nocturne.client.render.WorldProjection;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.ColorValue;
import dev.nocturne.client.value.NumberValue;

/** Epsilon ESP 2D：八顶点实体框、分类颜色、外描边与左侧血条。 */
public final class ESP2DModule extends GameModule implements WorldOverlay {
    private final BooleanValue players = add(new BooleanValue("Players", true));
    private final BooleanValue friends = add(new BooleanValue("Friends", true));
    private final BooleanValue creatures = add(new BooleanValue("Creatures", false));
    private final BooleanValue monsters = add(new BooleanValue("Monsters", false));
    private final BooleanValue ambients = add(new BooleanValue("Ambients", false));
    private final BooleanValue others = add(new BooleanValue("Others", false));
    private final BooleanValue renderHealth = add(new BooleanValue("Render Health", true));
    private final NumberValue healthWidth = add(new NumberValue("Health Bar Width", 2, 0.5, 6, 0.5));
    private final BooleanValue healthOutline = add(new BooleanValue("Health Bar Outline", true));
    private final NumberValue healthOutlineWidth = add(new NumberValue("Health Bar Outline Width", 1, 0.5, 3, 0.5));
    private final BooleanValue renderBox = add(new BooleanValue("Render Box", true));
    private final BooleanValue boxOutline = add(new BooleanValue("Box Outline", true));
    private final ColorValue playersColor = add(new ColorValue("Players Color", 0xFFFF9200));
    private final ColorValue friendsColor = add(new ColorValue("Friends Color", 0xFF30FF00));
    private final ColorValue creaturesColor = add(new ColorValue("Creatures Color", 0xFFA0A4A6));
    private final ColorValue monstersColor = add(new ColorValue("Monsters Color", 0xFFFF0000));
    private final ColorValue ambientsColor = add(new ColorValue("Ambients Color", 0xFF7B00FF));
    private final ColorValue othersColor = add(new ColorValue("Others Color", 0xFFFF0062));
    private final ColorValue healthColor = add(new ColorValue("Health Color", 0xFF2FFF00));

    public ESP2DModule() {
        super("ESP 2D", Category.RENDER);
    }

    @Override
    public void drawWorldOverlay(OverlayDraw draw, WorldProjection projection) {
        RenderSupport.forEachEntity(bridge(), entity -> {
            if (entity == bridge().player() || !bridge().isInstance(entity, ClassType.LIVING_ENTITY)) {
                return;
            }
            int color = color(entity);
            if (color == 0) {
                return;
            }
            Bounds bounds = bounds(entity, projection);
            if (bounds == null) {
                return;
            }
            if (renderBox.get()) {
                draw.outline(bounds.minX, bounds.minY, bounds.width(), bounds.height(), 1.0f, color);
                if (boxOutline.get()) {
                    draw.outline(bounds.minX - 1.0f, bounds.minY - 1.0f, bounds.width() + 2.0f,
                            bounds.height() + 2.0f, 1.0f, 0xFF000000);
                }
            }
            if (renderHealth.get()) {
                float ratio = Math.max(0.0f, Math.min(1.0f, bridge().health(entity)
                        / Math.max(1.0f, bridge().maxHealth(entity))));
                float x = bounds.minX - healthWidth.asFloat() - 2.0f;
                float height = bounds.height() * ratio;
                if (healthOutline.get()) {
                    float outline = healthOutlineWidth.asFloat();
                    draw.rect(x - outline, bounds.minY - outline, healthWidth.asFloat() + outline * 2.0f,
                            bounds.height() + outline * 2.0f, 0xFF000000);
                }
                draw.rect(x, bounds.minY + bounds.height() - height, healthWidth.asFloat(), height,
                        healthColor.argb());
            }
        });
    }

    private int color(Object entity) {
        boolean friend = Friends.isFriend(bridge(), entity);
        if (friend) {
            return friends.get() ? friendsColor.argb() : 0;
        }
        if (bridge().isInstance(entity, ClassType.PLAYER)) {
            return players.get() ? playersColor.argb() : 0;
        }
        if (bridge().isInstance(entity, ClassType.AMBIENT)) {
            return ambients.get() ? ambientsColor.argb() : 0;
        }
        if (bridge().isInstance(entity, ClassType.ANIMAL)) {
            return creatures.get() ? creaturesColor.argb() : 0;
        }
        if (bridge().isInstance(entity, ClassType.MOB)) {
            return monsters.get() ? monstersColor.argb() : 0;
        }
        return others.get() ? othersColor.argb() : 0;
    }

    private Bounds bounds(Object entity, WorldProjection projection) {
        Object box = bridge().callMapped(entity, ClassType.ENTITY, "getBoundingBox");
        if (box == null) {
            return null;
        }
        double[] min = coordinates(box, "minX", "minY", "minZ");
        double[] max = coordinates(box, "maxX", "maxY", "maxZ");
        if (min == null || max == null) {
            return null;
        }
        float minX = Float.MAX_VALUE;
        float minY = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE;
        float maxY = -Float.MAX_VALUE;
        boolean visible = false;
        for (int i = 0; i < 8; i++) {
            WorldProjection.ScreenPoint point = projection.project((i & 1) == 0 ? min[0] : max[0],
                    (i & 2) == 0 ? min[1] : max[1], (i & 4) == 0 ? min[2] : max[2]);
            if (!point.visible) {
                continue;
            }
            visible = true;
            minX = Math.min(minX, point.x);
            minY = Math.min(minY, point.y);
            maxX = Math.max(maxX, point.x);
            maxY = Math.max(maxY, point.y);
        }
        return visible && maxX > minX && maxY > minY ? new Bounds(minX, minY, maxX, maxY) : null;
    }

    private double[] coordinates(Object box, String x, String y, String z) {
        Object xv = bridge().readField(box, ClassType.AABB, x);
        Object yv = bridge().readField(box, ClassType.AABB, y);
        Object zv = bridge().readField(box, ClassType.AABB, z);
        if (!(xv instanceof Number) || !(yv instanceof Number) || !(zv instanceof Number)) {
            return null;
        }
        return new double[]{((Number) xv).doubleValue(), ((Number) yv).doubleValue(), ((Number) zv).doubleValue()};
    }

    private static final class Bounds {
        private final float minX;
        private final float minY;
        private final float maxX;
        private final float maxY;

        private Bounds(float minX, float minY, float maxX, float maxY) {
            this.minX = minX;
            this.minY = minY;
            this.maxX = maxX;
            this.maxY = maxY;
        }

        private float width() {
            return maxX - minX;
        }

        private float height() {
            return maxY - minY;
        }
    }
}
