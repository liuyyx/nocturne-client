package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.Module;
import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.client.render.WorldOverlay;
import dev.nocturne.client.render.WorldProjection;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.NumberValue;

import java.util.List;

/**
 * 名称标签：给其他玩家/生物/动物头顶画穿墙血条 + 名字 + 距离 + 装备/药水图标。
 *
 * <p>行为对齐 OpenVape NameTags（只搬行为，不搬代码）：三组独立开关（玩家/动物/怪物，
 * 各带血量/距离/药水/最大距离）+ 全局隐身忽略/自动缩放/缩放/Bot 隐藏 + 玩家组专属
 * 装备显示/强度指示/药水折算。对方未进 addValue 的 Opacity（透明度参与计算但无设置项）
 * 在我方按隐藏行为处理，不设可见项。版本门走表驱动——实体面缺失的版本一次性日志 + 跳过。
 *
 * <p>绘制：由 {@link WorldOverlay} 每帧在叠加层上画——实体头顶上方一行标签（名字，可带距离）。
 * 图标（装备/药水）与血量条需要物品面与图标管线，尚未接；开关保留。
 */
public final class NameTagsModule extends Module implements WorldOverlay {

    /** 实体 → 显示名缓存：名字几乎不变，而 `getName()` + 组件转文本都是反射。 */
    private final java.util.WeakHashMap<Object, String> nameCache =
            new java.util.WeakHashMap<Object, String>();
    /** 标签文本 → 渲染宽度缓存：`textWidth` 每次都要反射调字体度量。 */
    private final java.util.HashMap<String, Float> widthCache = new java.util.HashMap<String, Float>();
    /** 宽度缓存上限；超出就整体清空（标签文本随距离/血量变化，长期累积没有意义）。 */
    private static final int WIDTH_CACHE_LIMIT = 512;

    /** 每帧最多画多少条标签：极端场景（大船/刷怪塔）下按距离截断，避免覆盖层拖垮渲染线程。 */
    private static final int MAX_LABELS = 256;

    /** 标签画在实体头顶上方多高处（米）；只在读不到实体真实高度时兜底。 */
    private static final double TAG_HEIGHT = 2.1d;

    /** 参考像素密度：depth 为 1 时的标签字号基准（对齐 eps-plus 的透视缩放）。 */
    private static final float PERSPECTIVE_PX = 64f;
    /** 标签基础字号（逻辑像素，缩放前）。 */
    private static final float BASE_FONT_PX = 8f;
    /** 标签相对实体头顶再抬升多少（米）。 */
    private static final double HEIGHT_OFFSET = 0.5d;
    /** 内边距、锚点间隙与圆角（逻辑像素，随透视缩放）。 */
    private static final float PADDING_X = 3f;
    private static final float PADDING_Y = 2f;
    private static final float ANCHOR_GAP = 2f;
    private static final float CORNER = 3f;
    /** 背景色：深色半透明，保证白字在任何地形上都清晰。 */
    private static final int BACKGROUND_COLOR = 0xA50D1014;
    /** 透视缩放夹取范围：太远太小看不清，太近太大挡视线。 */
    private static final float MIN_TAG_SCALE = 0.6f;
    private static final float MAX_TAG_SCALE = 2.0f;

    /** 投影结果复用（每帧几十个实体，避免逐点分配）。 */
    private final WorldProjection.ScreenPoint point = new WorldProjection.ScreenPoint();

    /** 隐身实体是否跳过（读不到隐身状态时按"可见"处理）。 */
    private final BooleanValue ignoreInvisibles = add(new BooleanValue("Ignore Invisibles", false));
    /** 玩家组总开关 + 血量/距离/最大距离。 */
    private final BooleanValue renderPlayers = add(new BooleanValue("Render Players", true));
    private final BooleanValue playersHealth = add(new BooleanValue("Health", false));
    private final BooleanValue playersDistance = add(new BooleanValue("Distance", false));
    private final NumberValue playersMaxDistance =
            add(new NumberValue("Player Max Distance", 0.0, 0.0, 250.0, 1.0));
    /** 动物组总开关 + 血量/距离/最大距离。 */
    private final BooleanValue renderAnimals = add(new BooleanValue("Render Animals", false));
    private final BooleanValue animalsHealth = add(new BooleanValue("Health", false));
    private final BooleanValue animalsDistance = add(new BooleanValue("Distance", false));
    private final NumberValue animalsMaxDistance =
            add(new NumberValue("Animal Max Distance", 0.0, 0.0, 250.0, 1.0));
    /** 怪物组总开关 + 血量/距离/最大距离。 */
    private final BooleanValue renderMobs = add(new BooleanValue("Render Mobs", false));
    private final BooleanValue mobsHealth = add(new BooleanValue("Health", false));
    private final BooleanValue mobsDistance = add(new BooleanValue("Distance", false));
    private final NumberValue mobsMaxDistance =
            add(new NumberValue("Mob Max Distance", 0.0, 0.0, 250.0, 1.0));

    /** 缺成员门日志是否已打过（每个版本只报一次）。 */
    private boolean gateLogged;
    /** 已解析的 LivingEntity 类；避免每个实体、每一帧重复 Class.forName。 */
    private ClassLoader livingLoader;
    private Class<?> livingClass;
    /** 上一帧收集到的可见实体数（HUD/诊断用）。 */
    private int lastVisibleCount;

    /** 模块名，注册表内唯一，也是 GUI 中的显示名。 */
    @Override
    public String name() {
        return "NameTags";
    }

    /** 归入“渲染”分组。 */
    @Override
    public Category category() {
        return Category.RENDER;
    }

    /** 启用时重置状态。 */
    @Override
    protected void onEnable() {
        gateLogged = false;
        lastVisibleCount = 0;
    }

    /** 禁用时重置状态。 */
    @Override
    protected void onDisable() {
        lastVisibleCount = 0;
    }

    /** @return 上一帧收集到的可见实体数（HUD/诊断用）。 */
    public int lastVisibleCount() {
        return lastVisibleCount;
    }

    /** 取客户端世界；未进世界时返回 {@code null}。 */
    private static Object level(GameBridge bridge) {
        Object minecraft = bridge.minecraft();
        return minecraft == null ? null : bridge.readField(minecraft, ClassType.MINECRAFT, "level");
    }

    /** 实体面是否存在：LivingEntity 类可解析即认为可用（缓存结果）。 */
    private boolean hasEntityFace(GameBridge bridge) {
        return livingClass(bridge) != null;
    }

    /** 解析并缓存当前类加载器下的 LivingEntity；类加载器变化时重新解析。 */
    private Class<?> livingClass(GameBridge bridge) {
        ClassLoader loader = bridge.gameLoader();
        if (livingClass != null && livingLoader == loader) {
            return livingClass;
        }
        livingLoader = loader;
        livingClass = null;
        for (String mapped : bridge.mapping().classNameCandidates(ClassType.LIVING_ENTITY)) {
            try {
                Class<?> found = Class.forName(mapped, false, loader);
                if (found != null) {
                    livingClass = found;
                    break;
                }
            } catch (Throwable t) {
                // 该候选名在当前加载器里不可用：继续尝试下一个。
            }
        }
        return livingClass;
    }

    /** 收集可见实体（三组开关，除自己）；收集与绘制共用同一份列表。 */
    @SuppressWarnings("unchecked")
    private List<Object> visibleEntities(GameBridge bridge, Object level) {
        List<Object> out = new java.util.ArrayList<Object>();
        if (!(renderPlayers.get() || renderAnimals.get() || renderMobs.get())) {
            return out;
        }
        Class<?> living = livingClass(bridge);
        if (living == null) {
            return out;
        }
        Object raw;
        try {
            raw = bridge.callMapped(level, ClassType.CLIENT_LEVEL, "entitiesForRendering");
        } catch (Throwable t) {
            return out;
        }
        // 1.15+ 返回 Iterable（1.8.9–1.12.2 才是 List）：只认 List 会让中间版本静默零输出。
        if (!(raw instanceof Iterable)) {
            return out;
        }
        Object self = bridge.player();
        for (Object entity : (Iterable<Object>) raw) {
            if (entity == null || (self != null && self.equals(entity))) {
                continue;
            }
            if (!living.isInstance(entity)) {
                continue;
            }
            if (ignoreInvisibles.get() && isInvisible(bridge, entity)) {
                continue;
            }
            out.add(entity);
        }
        return out;
    }

    /**
     * 世界覆盖层：在每个可见实体头顶画一行标签。
     *
     * <p>文本 = 名字（{@code Entity#getName}），"Distance" 打开时追加米数。血量条与装备/药水图标
     * 需要物品面与图标管线，尚未接——开关保留，不假装支持。
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
        boolean withDistance = playersDistance.get() || animalsDistance.get() || mobsDistance.get();
        boolean withHealth = playersHealth.get() || animalsHealth.get() || mobsHealth.get();
        float lineHeight = draw.textHeight();
        // 自己位置总是要读：距离过滤（Max Distance）即使不显示距离也需要它。
        double[] selfPos = position(bridge, bridge.player());
        // Max Distance 为 0 表示不限；三个分组取最大值作为整体上限——分组精确匹配需要 Animal 等
        // 映射面（现在只有 Player/Mob 两级），在拿到之前按最宽的那个值裁剪，至少不会把视距外的
        // 实体也全部投影一遍。之前这三个设置**从未被读过**，视距内所有生物每帧都要做投影与排版。
        double maxDistance = Math.max(playersMaxDistance.get(),
                Math.max(animalsMaxDistance.get(), mobsMaxDistance.get()));
        double maxDistanceSq = maxDistance * maxDistance;
        List<Object> entities = visibleEntities(bridge, level);
        int drawn = 0;
        for (Object entity : entities) {
            double[] pos = position(bridge, entity);
            if (pos == null) {
                continue;
            }
            if (maxDistance > 0d && selfPos != null && distanceSq(selfPos, pos) > maxDistanceSq) {
                continue;
            }
            projection.project(pos[0], pos[1] + bbHeightOf(bridge, entity) + HEIGHT_OFFSET,
                    pos[2], point);
            if (!point.visible || point.depth <= 0d) {
                continue;
            }
            String label = label(bridge, entity, withDistance, withHealth, selfPos, pos);
            if (label == null) {
                continue;
            }
            // 透视缩放（对齐 eps-plus 的 NameTags）：标签随距离变小，近处才读得清、远处不糊屏。
            float scale = clampScale((float) (PERSPECTIVE_PX / point.depth));
            float fontSize = BASE_FONT_PX * scale;
            float width = textWidthCached(label, fontSize, draw);
            float height = draw.textHeight(fontSize);
            float padX = PADDING_X * scale;
            float padY = PADDING_Y * scale;
            float left = point.x - width / 2f - padX;
            float top = point.y - height - padY - ANCHOR_GAP * scale;
            // 完全在屏幕外的标签直接跳过（不画也省一次排版）。
            if (left + width + padX * 2f < 0f || top + height + padY * 2f < 0f
                    || left > draw.width() || top > draw.height()) {
                continue;
            }
            draw.roundedRect(left, top, width + padX * 2f, height + padY * 2f,
                    CORNER * scale, BACKGROUND_COLOR);
            draw.text(label, point.x - width / 2f, top + padY, fontSize, 0xFFFFFFFF);
            drawn++;
            if (drawn >= MAX_LABELS) {
                break;
            }
        }
        lastVisibleCount = drawn;
    }

    /**
     * 标签文本：名字 [+ 血量] [+ 距离]；名字读不到时返回 {@code null}。
     *
     * <p>血量与距离的开关按"任一分组打开即生效"处理：按实体类型细分需要 Animal 等映射面
     * （现在只有 Player/Mob 两级），分组开关暂时合流——比"开关完全没人看"好得多，
     * 等 kind 面进表后再按组精确匹配。
     */
    private String label(GameBridge bridge, Object entity, boolean withDistance,
                         boolean withHealth, double[] selfPos, double[] pos) {
        // getName 返回的是聊天组件而不是字符串，必须经映射表读文本（见 GameBridge#componentText）。
        // 结果按实体身份缓存：名字几乎不变，而这条链每帧每实体要走两次反射。
        String name = nameCache.get(entity);
        if (name == null) {
            name = bridge.componentText(bridge.callMapped(entity, ClassType.ENTITY, "getName"));
            if (name != null) {
                nameCache.put(entity, name);
            }
        }
        if (name == null) {
            return null;
        }
        StringBuilder text = new StringBuilder(name);
        if (withHealth) {
            Object health = bridge.callMapped(entity, ClassType.LIVING_ENTITY, "getHealth");
            if (health instanceof Number) {
                text.append(' ').append(Math.round(((Number) health).doubleValue())).append("hp");
            }
        }
        if (withDistance) {
            // 复用调用方已经读到的坐标：每实体每帧再反射一次 position() 纯属浪费。
            double dist = distanceBetween(selfPos, pos);
            if (dist >= 0) {
                text.append(' ').append(Math.round(dist)).append('m');
            }
        }
        return text.toString();
    }

    /**
     * 实体是否隐身。
     *
     * <p>{@code Entity#isInvisible} 目前不在映射表里：未混淆版本（26.x）按规范名直接能调到，
     * 混淆版本读不到就一律当作"可见"——宁可在老版本上多显示几个标签，也不因为读不到而把实体全滤掉。
     */
    private static boolean isInvisible(GameBridge bridge, Object entity) {
        Object value = bridge.callMapped(entity, ClassType.ENTITY, "isInvisible");
        return value instanceof Boolean && ((Boolean) value).booleanValue();
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


    /** 实体视觉高度（米）：读不到时退回硬编码的玩家高度。 */
    private static double bbHeightOf(GameBridge bridge, Object entity) {
        Object value = bridge.callMapped(entity, ClassType.ENTITY, "getBbHeight");
        return value instanceof Number ? ((Number) value).doubleValue() : TAG_HEIGHT;
    }

    /** 透视缩放夹取。 */
    private static float clampScale(float value) {
        if (!Float.isFinite(value)) {
            return 1f;
        }
        return Math.max(MIN_TAG_SCALE, Math.min(MAX_TAG_SCALE, value));
    }

    /**
     * 标签渲染宽度（带缓存，按字号区分）。
     *
     * <p>`textWidth` 走 {@code MinecraftTextRenderer.width} → 反射调字体度量；标签文本每帧重拼，
     * 但「文本 + 字号」组合变化很少，缓存能把每条标签的反射次数从 2 次降到 1 次。
     *
     * @param label 标签文本
     * @param size  字号（透视缩放后）
     * @param draw  绘制接口
     * @return 渲染宽度
     */
    private float textWidthCached(String label, float size, OverlayDraw draw) {
        String key = size + "|" + label;
        Float cached = widthCache.get(key);
        if (cached != null) {
            return cached.floatValue();
        }
        float width = draw.textWidth(label, size);
        if (widthCache.size() >= WIDTH_CACHE_LIMIT) {
            widthCache.clear();
        }
        widthCache.put(key, Float.valueOf(width));
        return width;
    }

    /** 两点距离（米）；任一端缺失时返回负数。 */
    private static double distanceBetween(double[] a, double[] b) {
        if (a == null || b == null) {
            return -1.0;
        }
        return Math.sqrt(distanceSq(a, b));
    }

    /** 两点距离的平方：距离裁剪只需要比值，省掉每个实体一次开方。 */
    private static double distanceSq(double[] a, double[] b) {
        double dx = a[0] - b[0];
        double dy = a[1] - b[1];
        double dz = a[2] - b[2];
        return dx * dx + dy * dy + dz * dz;
    }

    /** 表驱动门日志：缺成员版本只报一次。 */
    private void logGateOnce(String reason) {
        if (gateLogged) {
            return;
        }
        gateLogged = true;
        System.out.println("[nocturne] NameTags: " + reason);
    }

    /** 取当前客户端的游戏桥接；客户端尚未构造时返回 {@code null}。 */
    private static GameBridge bridge() {
        NocturneClient client = NocturneClient.get();
        return client == null ? null : client.gameBridge();
    }
}
