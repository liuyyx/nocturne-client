package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.event.RenderEvent;
import dev.nocturne.client.event.EventBus;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.Module;
import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.client.render.WorldOverlay;
import dev.nocturne.client.render.WorldProjection;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.ColorValue;
import dev.nocturne.client.value.NumberValue;

import java.util.List;

/**
 * 玩家透视：把其他玩家投影成 2D 框（外加可选的名字）。
 *
 * <p>行为对齐 OpenVape ESP（只搬行为，不搬代码）：玩家颜色、隐身显示、框 + 名字。
 * 版本门走表驱动——实体面缺失的版本一次性日志 + 跳过。
 *
 * <p>绘制：由 {@link WorldOverlay} 每帧在叠加层上画二维投影框，投影用 {@link WorldProjection}
 * 自算（四代通用）。对方的三维包围盒 / 骨骼连线需要世界渲染 pass 与实体渲染拦截，管线未接，
 * 因此不提供对应模式——宁可没有这个开关，也不放一个拨了没反应的档位（见 High-28）。
 */
public final class EspModule extends Module implements WorldOverlay {

    /** 玩家实体高度（米）：投影头顶与脚下两点来定框的高度，不需要读包围盒（表里也没有）。 */
    private static final double ENTITY_HEIGHT = 1.8d;
    /** 框宽与框高的比例：玩家 hitbox 宽 0.6、高 1.8，约 1/3。 */
    private static final float WIDTH_RATIO = 0.3f;
    /**
     * 框的最小边长（逻辑像素）：投影压扁时（低头/侧视/超远）保证仍然可见。
     *
     * <p>取 8 而不是更大：远处实体本来就会被压到几像素，强行放大到 12 会让整屏飘满小方块
     * （真机实测 40+ 个），可见性与噪声的折中点是 8。
     */
    private static final float MIN_BOX_PX = 8f;

    /** 投影结果复用（每帧几十个实体，避免逐点分配）。 */
    private final WorldProjection.ScreenPoint topPoint = new WorldProjection.ScreenPoint();
    private final WorldProjection.ScreenPoint bottomPoint = new WorldProjection.ScreenPoint();

    /**
     * 玩家颜色。
     *
     * <p>默认取高对比亮绿：原先的 `0xFF249824`（深绿）在草地、夜晚、洞穴里与背景亮度接近，
     * 再加上框只有 1px 描边，实战中就是"开了 ESP 但看不见框"。
     */
    private final ColorValue playerColor = add(new ColorValue("Player Color", 0xFF3CF03C));
    /** 显示隐身玩家。 */
    private final BooleanValue showInvisibles = add(new BooleanValue("Invisibles", false));
    /** 显示包围框。 */
    private final BooleanValue showBoundingBox = add(new BooleanValue("Bounding Box", true));
    /** 显示名字。 */
    private final BooleanValue showName = add(new BooleanValue("Name", false));
    /** 最大绘制距离（米）；0 = 不限。远处实体在最小框尺寸下会糊成一片，给玩家一个上限。 */
    private final NumberValue maxDistance = add(new NumberValue("Max Distance", 64.0, 0.0, 256.0, 8.0));

    /** 诊断日志限流：每秒最多一条。 */
    private long diagLoggedAt;

    /** RenderEvent 订阅句柄；禁用时退订。 */
    private volatile EventBus.Subscription renderSubscription;
    /** 缺成员门日志是否已打过（每个版本只报一次）。 */
    private boolean gateLogged;
    /** 上一帧收集到的可见玩家数（HUD/诊断用）。 */
    private int lastVisibleCount;

    /** 模块名，注册表内唯一，也是 GUI 中的显示名。 */
    @Override
    public String name() {
        return "ESP";
    }

    /** 归入“渲染”分组。 */
    @Override
    public Category category() {
        return Category.RENDER;
    }

    /** 启用时订阅渲染事件。 */
    @Override
    protected void onEnable() {
        gateLogged = false;
        lastVisibleCount = 0;
        subscribe();
    }

    /** 禁用时退订渲染事件。 */
    @Override
    protected void onDisable() {
        unsubscribe();
        lastVisibleCount = 0;
    }

    /** 每 tick 确保订阅存在（总线晚于模块启用才就绪时补上）。 */
    @Override
    public void onTick() {
        synchronized (this) {
            if (isEnabled() && renderSubscription == null) {
                // 只在启用状态下自愈重订阅：禁用后照旧重订阅会让监听器复活——GUI 关掉的模块
                // 会被游戏线程的 tick 又订回去，而且每次 subscribe 都新建 lambda，永远摘不干净。
                subscribe();
            }
        }
    }

    /** @return 上一帧收集到的可见玩家数（绘制挂钩落地前供验收用）。 */
    public int lastVisibleCount() {
        return lastVisibleCount;
    }

    /** 订阅渲染事件；总线未就绪时返回不抛，由 onTick 重试。 */
    private void subscribe() {
        NocturneClient client = NocturneClient.get();
        if (client == null || renderSubscription != null) {
            return;
        }
        try {
            renderSubscription = client.events().subscribe(RenderEvent.class, this::onRender);
        } catch (Throwable t) {
            renderSubscription = null;
        }
    }

    /** 退订渲染事件。 */
    private void unsubscribe() {
        if (renderSubscription == null) {
            return;
        }
        try {
            NocturneClient client = NocturneClient.get();
            if (client != null) {
                client.events().unsubscribe(renderSubscription);
            }
        } catch (Throwable ignored) {
            // 退订失败不影响禁用语义。
        } finally {
            renderSubscription = null;
        }
    }

    /**
     * 每渲染帧做门检查。
     *
     * <p>**不再在这里收集实体**：`RenderEvent` 在 {@code NocturneRuntime.drive()} 的绘制之后才
     * 广播，收藏结果给下一帧用会过期；而 {@link #drawWorldOverlay} 本身每帧就要遍历一次，
     * 两边各遍历一次等于把全量实体列表和每实体反射都做两遍——1.16.5 真机上这就是
     * 「开 ESP 之后帧率腰斩、随后游戏 tick 追赶」的主要开销之一。
     * {@code lastVisibleCount} 由绘制路径更新。
     *
     * <p>表驱动门：实体面（LivingEntity#getHealth 等）在某版本 absent 时，
     * 本帧跳过并打一次性日志——错版本上干净禁用，不刷屏不抛异常。
     */
    private void onRender(RenderEvent event) {
        GameBridge bridge = bridge();
        if (bridge == null) {
            return;
        }
        if (level(bridge) == null) {
            lastVisibleCount = 0;
            return;
        }
        if (!hasEntityFace(bridge)) {
            logGateOnce("实体面缺失（版本无该成员），ESP 已禁用");
            lastVisibleCount = 0;
        }
    }

    /** 取客户端世界；未进世界时返回 {@code null}。 */
    private static Object level(GameBridge bridge) {
        Object minecraft = bridge.minecraft();
        return minecraft == null ? null : bridge.readField(minecraft, ClassType.MINECRAFT, "level");
    }

    /** 实体面是否存在：LivingEntity 类可解析即认为可用（走桥上的类型缓存）。 */
    private static boolean hasEntityFace(GameBridge bridge) {
        return bridge.type(ClassType.LIVING_ENTITY) != null;
    }

    /**
     * 收集可见玩家实体。
     *
     * <p>走世界实体列表（{@code ClientLevel#entitiesForRendering}），只留 LivingEntity 实例、
     * 排除本地玩家。收集与绘制共用同一份列表，避免两处逻辑漂移。
     */
    @SuppressWarnings("unchecked")
    private List<Object> visibleEntities(GameBridge bridge, Object level) {
        Object raw;
        try {
            raw = bridge.callMapped(level, ClassType.CLIENT_LEVEL, "entitiesForRendering");
        } catch (Throwable t) {
            return java.util.Collections.emptyList();
        }
        // 1.15+ 这个方法返回 Iterable（1.8.9–1.12.2 才是 List）：只认 List 会让中间版本静默零输出
        // ——模块开着、计数器为零、屏幕上一个框都没有。
        if (!(raw instanceof Iterable)) {
            return java.util.Collections.emptyList();
        }
        Object self = bridge.player();
        // 距离上限（0 = 不限）：远处实体在最小框尺寸下会糊成一片，而且每帧仍要逐实体投影。
        double maxDistanceValue = maxDistance.get();
        double[] selfPos = maxDistanceValue > 0d && self != null ? position(bridge, self) : null;
        List<Object> out = new java.util.ArrayList<Object>();
        for (Object entity : (Iterable<Object>) raw) {
            if (entity == null || (self != null && self.equals(entity))) {
                continue;
            }
            if (!isLiving(bridge, entity)) {
                continue;
            }
            if (!showInvisibles.get() && isInvisible(bridge, entity)) {
                continue;
            }
            if (selfPos != null) {
                double[] pos = position(bridge, entity);
                if (pos != null && distanceSq(selfPos, pos) > maxDistanceValue * maxDistanceValue) {
                    continue;
                }
            }
            out.add(entity);
        }
        return out;
    }

    /**
     * 实体是否隐身。
     *
     * <p>{@code Entity#isInvisible} 目前不在映射表里：未混淆版本按规范名直接能调到，混淆版本读不到
     * 就一律当作"可见"——宁可在老版本上多画几个框，也不因为读不到而一个都不画。
     */
    private static boolean isInvisible(GameBridge bridge, Object entity) {
        Object value = bridge.callMapped(entity, ClassType.ENTITY, "isInvisible");
        return value instanceof Boolean && ((Boolean) value).booleanValue();
    }

    /**
     * 世界覆盖层：把可见玩家投影成 2D 框（外加可选的名字）。
     *
     * <p>框高由**头顶与脚下两点**的投影差决定，框宽按固定比例取——这样不需要读包围盒（映射表里也
     * 没有），远近自动缩放，而且与游戏的 FOV / 界面缩放天然一致（用的是同一套相机参数）。
     */
    @Override
    public void drawWorldOverlay(OverlayDraw draw, WorldProjection projection) {
        GameBridge bridge = bridge();
        if (bridge == null || !hasEntityFace(bridge)) {
            return;
        }
        Object level = level(bridge);
        if (level == null) {
            return;
        }
        int argb = playerColor.argb();
        boolean box = showBoundingBox.get();
        boolean name = showName.get();
        float lineHeight = draw.textHeight();
        List<Object> entities = visibleEntities(bridge, level);
        int drawn = 0;
        for (Object entity : entities) {
            float[] bounds = projectBox(bridge, entity, projection);
            if (bounds == null) {
                continue;
            }
            float left = bounds[0];
            float top = bounds[1];
            float width = bounds[2] - left;
            float height = bounds[3] - top;
            if (width < 1f || height < 1f) {
                continue;
            }
            float centerX = left + width / 2f;
            if (box) {
                if (System.getProperty("nocturne.ui.debug") != null && drawn < 3) {
                    System.out.println("[nocturne] ESP box: x=" + Math.round(left)
                            + " y=" + Math.round(top) + " w=" + Math.round(width)
                            + " h=" + Math.round(height)
                            + " screen=" + draw.width() + "x" + draw.height());
                }
                draw.rect(left, top, width, height, (argb & 0x00FFFFFF) | 0x40000000);
                draw.outline(left, top, width, height, 1f, argb);
                drawn++;
            }
            if (name) {
                String label = entityName(bridge, entity);
                if (label != null) {
                    draw.text(label, centerX - draw.textWidth(label) / 2f, top - lineHeight - 1f, argb);
                }
            }
        }
        lastVisibleCount = drawn;
        if (System.getProperty("nocturne.ui.debug") != null) {
            long now = System.currentTimeMillis();
            if (now - diagLoggedAt > 1000L) {
                diagLoggedAt = now;
                // entities 是"通过 LivingEntity + 隐身过滤"的数量；drawn 是真正投影成功并画了框的数量。
                // 两者差距大 = 投影/可见性判定把实体剔掉了（例如头顶/脚下点出屏）。
                System.out.println("[nocturne] ESP: entities=" + entities.size()
                        + " drawn=" + drawn + " box=" + box + " name=" + name);
            }
        }
    }

    /** 读实体的世界坐标（{@code position()} → {@code Vec3#x/y/z}）；缺成员时返回 {@code null}。 */
    private static double[] position(GameBridge bridge, Object entity) {
        Object vec = bridge.callMapped(entity, ClassType.ENTITY, "position");
        if (vec == null) {
            return null;
        }
        Object x = bridge.readField(vec, ClassType.VEC3, "x");
        Object y = bridge.readField(vec, ClassType.VEC3, "y");
        Object z = bridge.readField(vec, ClassType.VEC3, "z");
        if (!(x instanceof Number) || !(y instanceof Number) || !(z instanceof Number)) {
            return null;
        }
        return new double[]{((Number) x).doubleValue(), ((Number) y).doubleValue(),
                ((Number) z).doubleValue()};
    }

    /** 两点距离的平方（省掉开方）。 */
    private static double distanceSq(double[] a, double[] b) {
        double dx = a[0] - b[0];
        double dy = a[1] - b[1];
        double dz = a[2] - b[2];
        return dx * dx + dy * dy + dz * dz;
    }


    /** 包围盒 8 个角的临时投影点（复用，避免每实体分配）。 */
    private final WorldProjection.ScreenPoint cornerPoint = new WorldProjection.ScreenPoint();

    /**
     * 实体在屏幕上的包围矩形 {@code {left, top, right, bottom}}；不可见时返回 {@code null}。
     *
     * <p><b>投影 AABB 的 8 个角取包围盒</b>（对齐 eps-plus 的 {@code ESP2D.projectBox}）：
     * 只投影"头顶 + 脚下"两点时，低头/侧视/远距离会把竖直差压到 1–4px，框实际不可见；
     * 而且两点法不知道实体宽度，只能按固定比例猜。
     *
     * <p>角点用 {@code depth > 0} 判断在相机前方，不用 {@code visible}——后者还会按视口 2 倍
     * 范围裁剪，跨界的大框会被误判成"完全不可见"。
     *
     * <p>{@code getBoundingBox} 缺失的版本（1.8.9 的 {@code AxisAlignedBB} 不在表里）退回两点法，
     * 保证老版本仍然有框。
     */
    private float[] projectBox(GameBridge bridge, Object entity, WorldProjection projection) {
        Object box = bridge.callMapped(entity, ClassType.ENTITY, "getBoundingBox");
        if (box != null) {
            Double minX = number(bridge.readField(box, ClassType.AABB, "minX"));
            Double minY = number(bridge.readField(box, ClassType.AABB, "minY"));
            Double minZ = number(bridge.readField(box, ClassType.AABB, "minZ"));
            Double maxX = number(bridge.readField(box, ClassType.AABB, "maxX"));
            Double maxY = number(bridge.readField(box, ClassType.AABB, "maxY"));
            Double maxZ = number(bridge.readField(box, ClassType.AABB, "maxZ"));
            if (minX != null && minY != null && minZ != null
                    && maxX != null && maxY != null && maxZ != null) {
                float left = Float.POSITIVE_INFINITY;
                float top = Float.POSITIVE_INFINITY;
                float right = Float.NEGATIVE_INFINITY;
                float bottom = Float.NEGATIVE_INFINITY;
                for (int vertex = 0; vertex < 8; vertex++) {
                    double wx = (vertex & 1) == 0 ? minX.doubleValue() : maxX.doubleValue();
                    double wy = (vertex & 2) == 0 ? minY.doubleValue() : maxY.doubleValue();
                    double wz = (vertex & 4) == 0 ? minZ.doubleValue() : maxZ.doubleValue();
                    projection.project(wx, wy, wz, cornerPoint);
                    if (cornerPoint.depth <= 0d) {
                        continue;
                    }
                    if (cornerPoint.x < left) {
                        left = cornerPoint.x;
                    }
                    if (cornerPoint.y < top) {
                        top = cornerPoint.y;
                    }
                    if (cornerPoint.x > right) {
                        right = cornerPoint.x;
                    }
                    if (cornerPoint.y > bottom) {
                        bottom = cornerPoint.y;
                    }
                }
                if (left <= right && top <= bottom) {
                    return new float[]{left, top, right, bottom};
                }
                return null;
            }
        }
        // 回退：老版本没有 AABB 面，用头顶/脚下两点 + 固定宽度比例。
        double[] pos = position(bridge, entity);
        if (pos == null) {
            return null;
        }
        projection.project(pos[0], pos[1] + ENTITY_HEIGHT, pos[2], topPoint);
        projection.project(pos[0], pos[1], pos[2], bottomPoint);
        if (topPoint.depth <= 0d || bottomPoint.depth <= 0d) {
            return null;
        }
        float topY = Math.min(topPoint.y, bottomPoint.y);
        float height = Math.max(4f, Math.abs(bottomPoint.y - topPoint.y));
        float halfWidth = Math.max(2f, height * WIDTH_RATIO);
        return new float[]{topPoint.x - halfWidth, topY, topPoint.x + halfWidth, topY + height};
    }

    /** 把反射读到的值转成 {@code Double}；不是数字时返回 {@code null}。 */
    private static Double number(Object value) {
        return value instanceof Number ? Double.valueOf(((Number) value).doubleValue()) : null;
    }

    /** 实体显示名；读不到时返回 {@code null}。 */
    private static String entityName(GameBridge bridge, Object entity) {
        // getName 返回的是聊天组件而不是字符串（1.8.9 IChatComponent / 1.13+ Component），
        // 直接 instanceof String 会永远判为"没有名字"。文本必须经映射表读。
        return bridge.componentText(bridge.callMapped(entity, ClassType.ENTITY, "getName"));
    }

    /** 是否为生物实体（按 LivingEntity 映射类做 instanceof，避免写版本分支）。 */
    private static boolean isLiving(GameBridge bridge, Object entity) {
        // 必须走桥上的类型缓存：手写「候选名逐个 Class.forName」时，未命中的候选每个实体每帧都会
        // 抛一次异常，几百个实体就是每帧上千次类加载查询——实测表现为「一开透视框，游戏就开始卡、
        // 服务端 tick 掉队」。
        Class<?> living = bridge.type(ClassType.LIVING_ENTITY);
        return living != null && living.isInstance(entity);
    }

    /** 表驱动门日志：缺成员版本只报一次。 */
    private void logGateOnce(String reason) {
        if (gateLogged) {
            return;
        }
        gateLogged = true;
        System.out.println("[nocturne] ESP: " + reason);
    }

    /** 取当前客户端的游戏桥接；客户端尚未构造时返回 {@code null}。 */
    private static GameBridge bridge() {
        NocturneClient client = NocturneClient.get();
        return client == null ? null : client.gameBridge();
    }
}
