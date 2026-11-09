package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.control.Friends;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.client.render.WorldOverlay;
import dev.nocturne.client.render.WorldProjection;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.ColorValue;
import dev.nocturne.client.value.NumberValue;

/**
 * Epsilon ESP 2D：八顶点实体框、分类颜色、黑色外描边与左侧血条。
 *
 * <p>视觉语义直接对齐 Epsilon {@code ESP2D}：彩色框不是半透明填充 + 细描边，而是四条**实心**
 * 彩色边；{@code Box Outline} 打开时先在框外铺一圈黑色实心描边，再把彩色边压在它内侧。
 * 血条的宽度、间距、描边宽度都按框高相对 45 像素缩放，血量按最大值（含吸收）从下往上填充。
 *
 * <p>1.8.9 没有 AABB 面映射时仍走 {@link GameBridge#entityPosition} 的两点回退，投影统一走
 * {@link WorldProjection}，不写版本分支。
 */
public final class ESP2DModule extends GameModule implements WorldOverlay {
    /** Epsilon 的彩色边厚度（逻辑像素）。 */
    private static final float EDGE_THICKNESS = 0.5f;
    /** Epsilon 的黑色外描边厚度（逻辑像素）。 */
    private static final float OUTLINE_THICKNESS = 1.5f;
    /** 血条缩放基准：框高 45 像素时使用设置原值。 */
    private static final float HEALTH_SCALE_BASE = 45.0f;
    /** 血条与框之间的基础间距（逻辑像素），同样按框高缩放。 */
    private static final float HEALTH_GAP = 3.0f;

    /** 与 Epsilon 默认颜色逐项一致（ARGB，不透明）。 */
    static final int DEFAULT_PLAYERS_COLOR = 0xFFFF9200;
    static final int DEFAULT_FRIENDS_COLOR = 0xFF30FF00;
    static final int DEFAULT_CREATURES_COLOR = 0xFFA0A4A6;
    static final int DEFAULT_MONSTERS_COLOR = 0xFFFF0000;
    static final int DEFAULT_AMBIENTS_COLOR = 0xFF7B00FF;
    static final int DEFAULT_OTHERS_COLOR = 0xFFFF0062;
    static final int DEFAULT_HEALTH_COLOR = 0xFF2FFF00;

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
    private final ColorValue playersColor = add(new ColorValue("Players Color", DEFAULT_PLAYERS_COLOR));
    private final ColorValue friendsColor = add(new ColorValue("Friends Color", DEFAULT_FRIENDS_COLOR));
    private final ColorValue creaturesColor = add(new ColorValue("Creatures Color", DEFAULT_CREATURES_COLOR));
    private final ColorValue monstersColor = add(new ColorValue("Monsters Color", DEFAULT_MONSTERS_COLOR));
    private final ColorValue ambientsColor = add(new ColorValue("Ambients Color", DEFAULT_AMBIENTS_COLOR));
    private final ColorValue othersColor = add(new ColorValue("Others Color", DEFAULT_OTHERS_COLOR));
    private final ColorValue healthColor = add(new ColorValue("Health Color", DEFAULT_HEALTH_COLOR));

    /** 每帧复用，避免逐实体分配血条布局对象。 */
    private final HealthBar healthBar = new HealthBar();

    public ESP2DModule() {
        super("ESP 2D", Category.RENDER);
    }

    @Override
    public void drawWorldOverlay(OverlayDraw draw, WorldProjection projection) {
        GameBridge bridge = bridge();
        if (bridge == null || draw == null || projection == null) {
            return;
        }
        Object self = bridge.player();
        RenderSupport.forEachEntity(bridge, entity -> {
            if (entity == null || entity == self || !bridge.isInstance(entity, ClassType.LIVING_ENTITY)) {
                return;
            }
            int color = color(bridge, entity);
            if (color == 0) {
                return;
            }
            Bounds bounds = bounds(bridge, entity, projection);
            if (bounds == null) {
                return;
            }
            if (renderBox.get()) {
                if (boxOutline.get()) {
                    drawBoxOutline(draw, bounds.minX, bounds.minY, bounds.maxX, bounds.maxY);
                }
                drawSolidBox(draw, bounds.minX, bounds.minY, bounds.maxX, bounds.maxY, color);
            }
            if (renderHealth.get()) {
                layoutHealthBar(bounds.minX, bounds.minY, bounds.maxY, healthWidth.get(),
                        healthOutlineWidth.get(), healthOutline.get(), bridge.health(entity),
                        effectiveMaxHealth(bridge, entity), healthBar);
                drawHealthBar(draw, healthBar, healthColor.argb());
            }
        });
    }

    /**
     * 分类优先级与 Epsilon 一致：好友 → 玩家 → 环境生物 → 动物 → 怪物 → 其它。
     * 先判环境/动物再判 {@code Mob}，否则它们会被 {@code Mob} 的父类判定吞掉。
     */
    static Kind kindOf(boolean friend, boolean player, boolean ambient, boolean animal, boolean mob) {
        if (friend) {
            return Kind.FRIEND;
        }
        if (player) {
            return Kind.PLAYER;
        }
        if (ambient) {
            return Kind.AMBIENT;
        }
        if (animal) {
            return Kind.ANIMAL;
        }
        if (mob) {
            return Kind.MOB;
        }
        return Kind.OTHER;
    }

    private int color(GameBridge bridge, Object entity) {
        Kind kind = kindOf(Friends.isFriend(bridge, entity),
                bridge.isInstance(entity, ClassType.PLAYER),
                bridge.isInstance(entity, ClassType.AMBIENT),
                bridge.isInstance(entity, ClassType.ANIMAL),
                bridge.isInstance(entity, ClassType.MOB));
        switch (kind) {
            case FRIEND:
                return friends.get() ? friendsColor.argb() : 0;
            case PLAYER:
                return players.get() ? playersColor.argb() : 0;
            case AMBIENT:
                return ambients.get() ? ambientsColor.argb() : 0;
            case ANIMAL:
                return creatures.get() ? creaturesColor.argb() : 0;
            case MOB:
                return monsters.get() ? monstersColor.argb() : 0;
            default:
                return others.get() ? othersColor.argb() : 0;
        }
    }

    /**
     * 最大血量：基础最大血量 + 吸收量（Epsilon 的 {@code getMaxHealth() + absorption}）。
     * 吸收量读不到时按 0 处理；上限至少为 1，避免除零把血条画满。
     */
    private static float effectiveMaxHealth(GameBridge bridge, Object entity) {
        float max = bridge.maxHealth(entity);
        Number absorption = number(bridge.callMapped(entity, ClassType.LIVING_ENTITY, "getAbsorptionAmount"));
        return effectiveMaxHealth(max, absorption == null ? 0.0f : absorption.floatValue());
    }

    /** 纯函数版本，便于单测覆盖吸收量与除零回退。 */
    static float effectiveMaxHealth(float maxHealth, float absorption) {
        float max = maxHealth + Math.max(0.0f, absorption);
        return Math.max(1.0f, max);
    }

    private Bounds bounds(GameBridge bridge, Object entity, WorldProjection projection) {
        Object box = bridge.callMapped(entity, ClassType.ENTITY, "getBoundingBox");
        if (box != null) {
            double[] min = coordinates(bridge, box, "minX", "minY", "minZ");
            double[] max = coordinates(bridge, box, "maxX", "maxY", "maxZ");
            if (min != null && max != null) {
                return project(projection, min, max);
            }
        }
        // 1.8.9 的 AxisAlignedBB 没有映射面：退回实体位置 + 桥上的宽高。
        double[] position = bridge.entityPosition(entity);
        if (position == null) {
            return null;
        }
        float halfWidth = (float) (bridge.entityWidth(entity) * 0.5d);
        double[] min = {position[0] - halfWidth, position[1], position[2] - halfWidth};
        double[] max = {position[0] + halfWidth, position[1] + bridge.entityHeight(entity),
                position[2] + halfWidth};
        return project(projection, min, max);
    }

    private Bounds project(WorldProjection projection, double[] min, double[] max) {
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

    private static double[] coordinates(GameBridge bridge, Object box, String x, String y, String z) {
        Object xv = bridge.readField(box, ClassType.AABB, x);
        Object yv = bridge.readField(box, ClassType.AABB, y);
        Object zv = bridge.readField(box, ClassType.AABB, z);
        if (!(xv instanceof Number) || !(yv instanceof Number) || !(zv instanceof Number)) {
            return null;
        }
        return new double[]{((Number) xv).doubleValue(), ((Number) yv).doubleValue(),
                ((Number) zv).doubleValue()};
    }

    /**
     * 黑色外描边：与 Epsilon 完全相同的四条实心矩形，铺在彩色边外侧。
     * 左/右两边向下多出 0.5 像素，底边用 1.5 像素厚，接缝处不会漏出背景。
     */
    static void drawBoxOutline(OverlayDraw draw, float x, float y, float endX, float endY) {
        int black = 0xFF000000;
        draw.rect(x - 1.0f, y, OUTLINE_THICKNESS, endY - y + 0.5f, black);
        draw.rect(x - 1.0f, y - 0.5f, endX - x + 1.5f, 1.0f, black);
        draw.rect(endX - 1.0f, y, OUTLINE_THICKNESS, endY - y + 0.5f, black);
        draw.rect(x - 1.0f, endY - 1.0f, endX - x + 1.5f, OUTLINE_THICKNESS, black);
    }

    /**
     * 四条实心彩色边（Epsilon 的 {@code drawSolidBox}）：左右各 0.5 像素宽、上下各 0.5 像素高。
     * 不用 {@link OverlayDraw#outline}，因为那个 API 在固定管线上会叠半透明填充或只保证 1 像素。
     */
    static void drawSolidBox(OverlayDraw draw, float x, float y, float endX, float endY, int color) {
        draw.rect(x - EDGE_THICKNESS, y, EDGE_THICKNESS, endY - y, color);
        draw.rect(x, endY - EDGE_THICKNESS, endX - x, EDGE_THICKNESS, color);
        draw.rect(x - EDGE_THICKNESS, y, endX - x + EDGE_THICKNESS, EDGE_THICKNESS, color);
        draw.rect(endX - EDGE_THICKNESS, y, EDGE_THICKNESS, endY - y, color);
    }

    /**
     * 血条布局：宽度、间距、描边宽度都乘以 {@code 框高 / 45}，因此远处框的血条会同比变细。
     * 结果写进复用对象，避免每帧每实体分配。
     */
    static void layoutHealthBar(float x, float y, float endY, double widthSetting,
                                double outlineSetting, boolean outline,
                                float health, float maxHealth, HealthBar out) {
        float height = endY - y;
        out.valid = height > 0.0f;
        if (!out.valid) {
            return;
        }
        float ratio = Math.max(0.0f, Math.min(1.0f, health / Math.max(1.0f, maxHealth)));
        float scale = height / HEALTH_SCALE_BASE;
        float width = (float) widthSetting * scale;
        float gap = HEALTH_GAP * scale;
        float outlineWidth = outline ? (float) outlineSetting * scale : 0.0f;
        out.x = x - gap - outlineWidth - width;
        out.y = y;
        out.width = width;
        out.height = height;
        out.fillY = endY - height * ratio;
        out.outline = outline;
        out.outlineWidth = outlineWidth;
    }

    /** Epsilon：先铺黑色底/边框，再用健康色从下往上填；描边关闭时黑底仍然存在。 */
    static void drawHealthBar(OverlayDraw draw, HealthBar bar, int color) {
        if (!bar.valid) {
            return;
        }
        if (bar.outline) {
            draw.rect(bar.x - bar.outlineWidth, bar.y - bar.outlineWidth,
                    bar.width + bar.outlineWidth * 2.0f, bar.height + bar.outlineWidth * 2.0f,
                    0xFF000000);
        } else {
            draw.rect(bar.x, bar.y, bar.width, bar.height, 0xFF000000);
        }
        draw.rect(bar.x, bar.fillY, bar.width, bar.y + bar.height - bar.fillY, color);
    }

    private static Number number(Object value) {
        return value instanceof Number ? (Number) value : null;
    }

    /** 实体分类，顺序即颜色优先级。 */
    enum Kind { FRIEND, PLAYER, AMBIENT, ANIMAL, MOB, OTHER }

    /** 血条布局结果；字段包可见以便单测直接断言。 */
    static final class HealthBar {
        boolean valid;
        boolean outline;
        float x;
        float y;
        float width;
        float height;
        float fillY;
        float outlineWidth;
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
    }
}
