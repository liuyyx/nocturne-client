package dev.nocturne.client.module.modules.render;

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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 玩家/生物 2D 包围框。
 *
 * <p>只做一件事：把每个存活实体的包围盒投影成屏幕矩形并描边。默认**不填充**——1.8.9 真机上
 * 贴脸实体的投影会 clamp 到视口边缘，半透明填充会把半个屏幕染成绿色；填充改成显式开关，且只对
 * 屏幕占比正常的框生效。
 *
 * <p>绘制由 {@link WorldOverlay} 每帧在叠加层绘制通道内直接回调，不订阅 {@code RenderEvent}：
 * 事件在帧尾广播，拿到的实体位置已经过期，而且每帧多遍历一次实体列表。
 */
public final class EspModule extends GameModule implements WorldOverlay {

    /** 框颜色；默认亮绿，保证在草地/夜晚/洞穴里都能看清。 */
    private final ColorValue playerColor = add(new ColorValue("Player Color", 0xFF3CF03C));
    /** 是否显示隐身实体。 */
    private final BooleanValue showInvisibles = add(new BooleanValue("Invisibles", false));
    /** 是否显示名字。 */
    private final BooleanValue showName = add(new BooleanValue("Name", false));
    /** 是否描边；保留旧配置键名。 */
    private final BooleanValue outline = add(new BooleanValue("Bounding Box", true));
    /** 是否填充；默认关闭，避免近处实体把屏幕染色。 */
    private final BooleanValue fill = add(new BooleanValue("Fill", false));
    /** 最大绘制距离（米）；0 = 不限。 */
    private final NumberValue maxDistance = add(new NumberValue("Max Distance", 64.0, 0.0, 256.0, 8.0));

    /** 候选实体列表（每帧 clear 复用，避免逐个分配）。 */
    private final List<Object> candidates = new ArrayList<Object>();
    /** 包围盒投影结果：left/top/right/bottom。 */
    private final float[] box = new float[4];
    /** 复用的投影点。 */
    private final WorldProjection.ScreenPoint point = new WorldProjection.ScreenPoint();
    /** 自身位置与候选位置（复用的 scratch，避免每实体分配 double[]）。 */
    private final double[] selfPosition = new double[3];
    private final double[] entityPosition = new double[3];

    public EspModule() {
        super("ESP", Category.RENDER);
    }

    @Override
    public void drawWorldOverlay(OverlayDraw draw, WorldProjection projection) {
        GameBridge bridge = bridge();
        if (bridge == null || draw == null || projection == null || !projection.isReady()) {
            return;
        }
        Object level = level(bridge);
        if (level == null) {
            return;
        }
        Object self = bridge.player();
        boolean selfKnown = self != null && readPosition(bridge, self, selfPosition);
        double max = maxDistance.get();
        double maxSquared = max > 0d ? max * max : 0d;

        collectCandidates(bridge, level, self, selfKnown, maxSquared);
        int color = playerColor.argb();
        boolean drawOutline = outline.get();
        boolean drawFill = fill.get();
        boolean drawName = showName.get();
        int width = draw.width();
        int height = draw.height();
        if (width <= 0 || height <= 0) {
            return;
        }
        // 超过视口 60% 的框多半是贴脸实体：描边可以，填充会把屏幕染成一片。
        float fillLimitX = width * 0.6f;
        float fillLimitY = height * 0.6f;
        // 贴脸实体的投影会被 clamp 到视口边缘，形成覆盖整屏的框；这种框没有可读信息，
        // 默认直接跳过（1.8.9 真机：僵尸贴脸时半个屏幕被绿框圈住）。
        float skipLimitX = width * 0.9f;
        float skipLimitY = height * 0.9f;

        for (int i = 0; i < candidates.size(); i++) {
            Object entity = candidates.get(i);
            if (!projectBox(bridge, entity, projection, box)) {
                continue;
            }
            float left = box[0];
            float top = box[1];
            float boxWidth = box[2] - left;
            float boxHeight = box[3] - top;
            if (boxWidth < 1f || boxHeight < 1f) {
                continue;
            }
            if (boxWidth > skipLimitX || boxHeight > skipLimitY) {
                continue;
            }
            if (drawFill && boxWidth <= fillLimitX && boxHeight <= fillLimitY) {
                draw.rect(left, top, boxWidth, boxHeight, (color & 0x00FFFFFF) | 0x35000000);
            }
            if (drawOutline) {
                draw.outline(left, top, boxWidth, boxHeight, 1f, color);
            }
            if (drawName) {
                String label = entityName(bridge, entity);
                if (label != null && !label.isEmpty()) {
                    float textWidth = draw.textWidth(label);
                    draw.text(label, left + (boxWidth - textWidth) / 2f,
                            top - draw.textHeight() - 1f, color);
                }
            }
        }
    }

    /** 收集可见实体：存活、非自己、按选项过滤隐身与距离。 */
    private void collectCandidates(GameBridge bridge, Object level, Object self,
                                   boolean selfKnown, double maxSquared) {
        candidates.clear();
        Object raw = bridge.callMapped(level, ClassType.CLIENT_LEVEL, "entitiesForRendering");
        if (!(raw instanceof Iterable)) {
            return;
        }
        Class<?> living = bridge.type(ClassType.LIVING_ENTITY);
        if (living == null) {
            return;
        }
        for (Object entity : (Iterable<?>) raw) {
            if (entity == null || (self != null && self.equals(entity))) {
                continue;
            }
            if (!living.isInstance(entity)) {
                continue;
            }
            if (!showInvisibles.get() && isInvisible(bridge, entity)) {
                continue;
            }
            if (maxSquared > 0d && selfKnown && readPosition(bridge, entity, entityPosition)) {
                double dx = entityPosition[0] - selfPosition[0];
                double dy = entityPosition[1] - selfPosition[1];
                double dz = entityPosition[2] - selfPosition[2];
                if (dx * dx + dy * dy + dz * dz > maxSquared) {
                    continue;
                }
            }
            candidates.add(entity);
        }
    }

    /**
     * 把实体的包围盒投影进 {@link #box}。
     *
     * <p>优先读 {@code getBoundingBox} 的 6 个字段（现代版本都有）；读不到时退回
     * “头顶 + 脚下两点 + 固定宽度”的老版本路径。
     *
     * @return 是否有至少一个角在相机前方且矩形有效
     */
    private boolean projectBox(GameBridge bridge, Object entity, WorldProjection projection,
                               float[] out) {
        Object aabb = bridge.callMapped(entity, ClassType.ENTITY, "getBoundingBox");
        if (aabb != null) {
            Double minX = number(bridge.readField(aabb, ClassType.AABB, "minX"));
            Double minY = number(bridge.readField(aabb, ClassType.AABB, "minY"));
            Double minZ = number(bridge.readField(aabb, ClassType.AABB, "minZ"));
            Double maxX = number(bridge.readField(aabb, ClassType.AABB, "maxX"));
            Double maxY = number(bridge.readField(aabb, ClassType.AABB, "maxY"));
            Double maxZ = number(bridge.readField(aabb, ClassType.AABB, "maxZ"));
            if (minX != null && minY != null && minZ != null
                    && maxX != null && maxY != null && maxZ != null) {
                float left = Float.POSITIVE_INFINITY;
                float top = Float.POSITIVE_INFINITY;
                float right = Float.NEGATIVE_INFINITY;
                float bottom = Float.NEGATIVE_INFINITY;
                for (int vertex = 0; vertex < 8; vertex++) {
                    double wx = (vertex & 1) == 0 ? minX : maxX;
                    double wy = (vertex & 2) == 0 ? minY : maxY;
                    double wz = (vertex & 4) == 0 ? minZ : maxZ;
                    projection.project(wx, wy, wz, point);
                    if (point.depth <= 0d) {
                        continue;
                    }
                    if (point.x < left) {
                        left = point.x;
                    }
                    if (point.y < top) {
                        top = point.y;
                    }
                    if (point.x > right) {
                        right = point.x;
                    }
                    if (point.y > bottom) {
                        bottom = point.y;
                    }
                }
                if (left <= right && top <= bottom) {
                    out[0] = left;
                    out[1] = top;
                    out[2] = right;
                    out[3] = bottom;
                    return true;
                }
                return false;
            }
        }
        // 1.8.9 的 AABB 面不在映射表里：用头顶/脚下两点 + 固定宽度比例。
        if (!readPosition(bridge, entity, entityPosition)) {
            return false;
        }
        projection.project(entityPosition[0], entityPosition[1] + 1.8d, entityPosition[2], point);
        if (point.depth <= 0d) {
            return false;
        }
        float topY = point.y;
        float centerX = point.x;
        projection.project(entityPosition[0], entityPosition[1], entityPosition[2], point);
        if (point.depth <= 0d) {
            return false;
        }
        float bottomY = point.y;
        float boxHeight = Math.abs(bottomY - topY);
        if (boxHeight < 4f) {
            boxHeight = 4f;
        }
        float halfWidth = Math.max(2f, boxHeight * 0.3f);
        out[0] = centerX - halfWidth;
        out[1] = Math.min(topY, bottomY);
        out[2] = centerX + halfWidth;
        out[3] = out[1] + boxHeight;
        return true;
    }

    /** 读实体位置到 {@code out}；读不到时返回 false。 */
    private static boolean readPosition(GameBridge bridge, Object entity, double[] out) {
        Object vec = bridge.callMapped(entity, ClassType.ENTITY, "position");
        if (vec == null) {
            return false;
        }
        Object x = bridge.readField(vec, ClassType.VEC3, "x");
        Object y = bridge.readField(vec, ClassType.VEC3, "y");
        Object z = bridge.readField(vec, ClassType.VEC3, "z");
        if (!(x instanceof Number) || !(y instanceof Number) || !(z instanceof Number)) {
            return false;
        }
        out[0] = ((Number) x).doubleValue();
        out[1] = ((Number) y).doubleValue();
        out[2] = ((Number) z).doubleValue();
        return true;
    }

    /** 取客户端世界；未进世界时返回 {@code null}。 */
    private static Object level(GameBridge bridge) {
        Object minecraft = bridge.minecraft();
        return minecraft == null ? null : bridge.readField(minecraft, ClassType.MINECRAFT, "level");
    }

    /** 实体是否隐身；成员缺失时按“可见”处理，避免老版本一个框都不画。 */
    private static boolean isInvisible(GameBridge bridge, Object entity) {
        Object value = bridge.callMapped(entity, ClassType.ENTITY, "isInvisible");
        return value instanceof Boolean && ((Boolean) value).booleanValue();
    }

    /** 实体显示名；读不到时返回 {@code null}。 */
    private static String entityName(GameBridge bridge, Object entity) {
        return bridge.componentText(bridge.callMapped(entity, ClassType.ENTITY, "getName"));
    }

    /** 反射值转 {@code Double}。 */
    private static Double number(Object value) {
        return value instanceof Number ? Double.valueOf(((Number) value).doubleValue()) : null;
    }
}
