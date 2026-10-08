package dev.nocturne.client.module.modules;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.event.EventBus;
import dev.nocturne.client.event.RenderEvent;
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

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

/**
 * 方块搜索：在玩家周围逐格找名单方块，命中处画描边框（可选引线）。
 *
 * <p>行为对齐 OpenVape render.Search（只搬行为，不搬代码；注意它不是 world.XRay）：扫描半径、
 * 仅洞穴、引线开关。对方的方块名单走 SearchManager/SearchBlock 外部帧配置、不在 Value 体系，
 * 我方同样不设可见名单项——当前名单是内置的十种贵重方块（金/铁/钻石/绿宝石/青金石矿 +
 * 对应矿物块），与 Xray 默认白名单一致；待名单值类型落地后补可调名单。
 *
 * <p><b>怎么认方块</b>：用 {@code Block#getDescriptionId()}（1.8.9/1.12.2 是
 * {@code getUnlocalizedName()}）的字符串。这是跨代际唯一稳的方块身份面——按方块类认在 1.13+
 * 会失效（矿石都是同一个 {@code Block} 类，靠注册名区分），而注册名要读静态字段（桥接层不支持）。
 * 两代命名都收进名单：{@code tile.oreGold}（≤1.12.2）与 {@code block.minecraft.gold_ore}（≥1.13），
 * 这些字符串已对 66 个版本的 client jar 里 en_us 语言文件逐一核对。
 *
 * <p><b>怎么扫</b>：每 tick 扫固定预算的格子（反射读一格是百纳秒量级），网格以玩家所在方块为中心、
 * 纵向 ±{@code VERTICAL_RANGE} 格；扫完一遍自动回到起点再扫（世界在变）。命中记在缓存里，
 * 一轮扫完时清掉出范围的陈旧命中。坐标只能用 {@code BlockPos#offset(int,int,int)} 造——
 * 桥接层不支持构造器。
 *
 * <p>版本门走表驱动：方块/坐标/读取面缺失的版本一次性日志 + 跳过，不刷屏不抛异常。
 */
public final class SearchModule extends Module implements WorldOverlay {

    /** 每 tick 扫描的格数上限（4096 会让反射开销到毫秒级，1024 更保守）。 */
    private static final int BLOCKS_PER_TICK = 1024;
    /** 纵向扫描范围：以玩家所在层为中心各这么多格。 */
    private static final int VERTICAL_RANGE = 32;
    /** 命中框的半边长（像素）。 */
    private static final float BOX_HALF = 7f;
    /** 线宽（像素）。 */
    private static final float LINE_THICKNESS = 1.5f;

    /**
     * 名单方块：两代命名都收，值就是 {@code Block#getDescriptionId()} 的原样返回。
     *
     * <p>{@code tile.*} 来自 ≤1.12.2 的 {@code assets/minecraft/lang/en_US.lang}（去掉 {@code .name}），
     * {@code block.minecraft.*} 来自 ≥1.13 的 {@code en_us.json}，两套键在每个目标版本的 client jar
     * 里都存在（1.13 是切换点）。
     */
    private static final Set<String> TARGETS = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
            // ≤1.12.2
            "tile.oreGold", "tile.oreIron", "tile.oreDiamond", "tile.oreEmerald", "tile.oreLapis",
            "tile.blockGold", "tile.blockIron", "tile.blockDiamond", "tile.blockEmerald",
            "tile.blockLapis",
            // ≥1.13
            "block.minecraft.gold_ore", "block.minecraft.iron_ore", "block.minecraft.diamond_ore",
            "block.minecraft.emerald_ore", "block.minecraft.lapis_ore",
            "block.minecraft.gold_block", "block.minecraft.iron_block", "block.minecraft.diamond_block",
            "block.minecraft.emerald_block", "block.minecraft.lapis_block")));

    /** 空气的描述 id（“仅洞穴”判头顶是否露天）；两代命名都收。 */
    private static final Set<String> AIR = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList(
            "tile.air", "block.minecraft.air")));

    /** 扫描半径（对方默认 50，范围 1–100）。 */
    private final NumberValue range = add(new NumberValue("Range", 50.0, 1.0, 100.0, 1.0));
    /** 仅搜索暴露于空气的矿石。 */
    private final BooleanValue onlyCaves = add(new BooleanValue("Only caves", false));
    /** 给命中的方块加引线。 */
    private final BooleanValue useTracers = add(new BooleanValue("Use tracers", false));
    /** 命中框颜色。 */
    private final ColorValue color = add(new ColorValue("Color", 0xFFFFFFFF));

    /** 命中缓存：打包坐标（x/z 各 26 位、y 12 位，与游戏的方块坐标打包同构）→ 方块描述 id。 */
    private final Map<Long, String> hits = new HashMap<Long, String>();
    /** 方块实例 → 描述 id。方块是注册表单例，实例身份稳定，省掉每格的反射调用。 */
    private final Map<Object, String> descriptionIds = new IdentityHashMap<Object, String>();
    /** 扫描游标：当前网格里的下一个格子序号。 */
    private int cursor;
    /** 上一 tick 扫描所在的世界实例；换世界就清空缓存。 */
    private Object scannedLevel;

    /** RenderEvent 订阅句柄；禁用时退订。 */
    private EventBus.Subscription renderSubscription;
    /** 缺成员门日志是否已打过（每个版本只报一次）。 */
    private boolean gateLogged;
    /** 上一帧命中数（HUD/诊断用）。 */
    private int lastHitCount;

    /** 投影结果复用（命中可能上千个，避免逐个分配）。 */
    private final WorldProjection.ScreenPoint point = new WorldProjection.ScreenPoint();

    /** 模块名，注册表内唯一，也是 GUI 中的显示名。 */
    @Override
    public String name() {
        return "Search";
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
        clearScanState();
        subscribe();
    }

    /** 禁用时退订渲染事件并清空扫描缓存。 */
    @Override
    protected void onDisable() {
        unsubscribe();
        clearScanState();
    }

    /** 每 tick 确保订阅存在（总线晚于模块启用才就绪时补上），并推进一次扫描。 */
    @Override
    public void onTick() {
        if (renderSubscription == null) {
            subscribe();
        }
        scanOneBatch();
    }

    /** @return 上一帧命中数（实时诊断用）。 */
    public int lastHitCount() {
        return lastHitCount;
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

    /** 清空扫描状态（换世界或禁用时）。 */
    private void clearScanState() {
        hits.clear();
        descriptionIds.clear();
        cursor = 0;
        scannedLevel = null;
        lastHitCount = 0;
    }

    /** 每渲染帧只做门检查与计数（扫描在 tick 上推进）。 */
    private void onRender(RenderEvent event) {
        lastHitCount = hits.size();
    }

    /**
     * 推进一步扫描：读 {@link #BLOCKS_PER_TICK} 格，维护命中缓存。
     *
     * <p>不画任何东西——绘制在 {@link #drawWorldOverlay}（叠加层）里做，扫描不必与帧同步。
     */
    private void scanOneBatch() {
        GameBridge bridge = bridge();
        if (bridge == null) {
            return;
        }
        Object level = level(bridge);
        Object player = bridge.player();
        if (level == null || player == null) {
            if (scannedLevel != null) {
                clearScanState();
            }
            return;
        }
        if (level != scannedLevel) {
            hits.clear();
            descriptionIds.clear();
            cursor = 0;
            scannedLevel = level;
        }
        if (!hasScanFace(bridge)) {
            logGateOnce("方块/坐标面缺失（版本无该成员），Search 已禁用");
            return;
        }
        Object origin = bridge.callMapped(player, ClassType.ENTITY, "blockPosition");
        if (origin == null) {
            return;
        }
        int originX = coordinate(bridge, origin, "getX");
        int originY = coordinate(bridge, origin, "getY");
        int originZ = coordinate(bridge, origin, "getZ");
        if (originX == Integer.MIN_VALUE || originY == Integer.MIN_VALUE
                || originZ == Integer.MIN_VALUE) {
            return;
        }
        int radius = (int) Math.round(range.get());
        int spanXZ = radius * 2 + 1;
        int spanY = VERTICAL_RANGE * 2 + 1;
        long total = (long) spanXZ * spanY * spanXZ;
        boolean caved = onlyCaves.get();
        for (int step = 0; step < BLOCKS_PER_TICK; step++) {
            if (cursor >= total) {
                cursor = 0;
                prune(originX, originY, originZ, radius);
            }
            long index = cursor++;
            int offsetX = (int) (index % spanXZ) - radius;
            int offsetZ = (int) ((index / spanXZ) % spanXZ) - radius;
            int offsetY = (int) (index / ((long) spanXZ * spanXZ)) - VERTICAL_RANGE;
            long key = pack(originX + offsetX, originY + offsetY, originZ + offsetZ);
            String id = blockIdAt(bridge, level, origin, offsetX, offsetY, offsetZ);
            if (id == null) {
                continue;
            }
            boolean hit = TARGETS.contains(id);
            if (hit && caved) {
                hit = AIR.contains(blockIdAt(bridge, level, origin, offsetX, offsetY + 1, offsetZ));
            }
            if (hit) {
                hits.put(key, id);
            } else {
                hits.remove(key);
            }
        }
    }

    /**
     * 世界覆盖层：给每个命中方块画描边框（可选从屏幕底部中心引线）。
     */
    @Override
    public void drawWorldOverlay(OverlayDraw draw, WorldProjection projection) {
        if (hits.isEmpty()) {
            return;
        }
        int argb = color.argb();
        boolean tracers = useTracers.get();
        float bottomX = draw.width() / 2f;
        float bottomY = draw.height();
        float size = BOX_HALF * 2f;
        Iterator<Map.Entry<Long, String>> iterator = hits.entrySet().iterator();
        while (iterator.hasNext()) {
            long key = iterator.next().getKey();
            double x = unpackX(key);
            double y = unpackY(key);
            double z = unpackZ(key);
            projection.project(x + 0.5d, y + 0.5d, z + 0.5d, point);
            if (!point.visible) {
                continue;
            }
            if (tracers) {
                draw.line(bottomX, bottomY, point.x, point.y, LINE_THICKNESS, argb);
            }
            draw.rect(point.x - BOX_HALF, point.y - BOX_HALF, size, size, argb);
            draw.outline(point.x - BOX_HALF, point.y - BOX_HALF, size, size, LINE_THICKNESS, argb);
        }
    }

    /**
     * 读某格方块的描述 id（相对 {@code origin} 偏移）；读不到返回 {@code null}。
     */
    private String blockIdAt(GameBridge bridge, Object level, Object origin,
                             int offsetX, int offsetY, int offsetZ) {
        Object pos = offsetPos(bridge, origin, offsetX, offsetY, offsetZ);
        if (pos == null) {
            return null;
        }
        Object state = bridge.callMapped(level, ClassType.LEVEL_READER, "getBlockState", pos);
        if (state == null) {
            return null;
        }
        Object block = bridge.callMapped(state, ClassType.BLOCK_STATE, "getBlock");
        if (block == null) {
            return null;
        }
        String cached = descriptionIds.get(block);
        if (cached != null) {
            return cached;
        }
        Object id = bridge.callMapped(block, ClassType.BLOCK, "getDescriptionId");
        if (!(id instanceof String)) {
            return null;
        }
        descriptionIds.put(block, (String) id);
        return (String) id;
    }

    /**
     * 造一个相对 {@code origin} 偏移的方块坐标。
     *
     * <p>桥接层不支持构造器，只能用 {@code BlockPos} 的方向面拼：{@code above(dy)} +
     * {@code east(dx)} + {@code south(dz)}（负参数即反方向）。刻意不用
     * {@code offset(int,int,int)}：它有 DDD/III/Vec3i 三个重载，映射表每种成员只记一条，
     * 挑错重载就会在运行期静默找不到方法。偏移为 0 的分量直接跳过，省调用。
     *
     * @return 新坐标；任一环读不到时返回 {@code null}
     */
    private static Object offsetPos(GameBridge bridge, Object origin, int dx, int dy, int dz) {
        Object pos = origin;
        if (dy != 0) {
            pos = bridge.callMapped(pos, ClassType.BLOCK_POS, "above", dy);
        }
        if (pos != null && dx != 0) {
            pos = bridge.callMapped(pos, ClassType.BLOCK_POS, "east", dx);
        }
        if (pos != null && dz != 0) {
            pos = bridge.callMapped(pos, ClassType.BLOCK_POS, "south", dz);
        }
        return pos;
    }

    /** 丢掉离当前原点超过半径（+1 容差）的陈旧命中。 */
    private void prune(int originX, int originY, int originZ, int radius) {
        int limit = radius + 1;
        Iterator<Map.Entry<Long, String>> iterator = hits.entrySet().iterator();
        while (iterator.hasNext()) {
            long key = iterator.next().getKey();
            if (Math.abs(unpackX(key) - originX) > limit
                    || Math.abs(unpackZ(key) - originZ) > limit
                    || Math.abs(unpackY(key) - originY) > VERTICAL_RANGE + 1) {
                iterator.remove();
            }
        }
    }

    /** 取客户端世界；未进世界时返回 {@code null}。 */
    private static Object level(GameBridge bridge) {
        Object minecraft = bridge.minecraft();
        return minecraft == null ? null : bridge.readField(minecraft, ClassType.MINECRAFT, "level");
    }

    /** 扫描面是否存在：方块类与区块读取类都能解析出来即可。 */
    private static boolean hasScanFace(GameBridge bridge) {
        return resolveMapped(bridge, ClassType.BLOCK, null) != null
                && resolveMapped(bridge, ClassType.LEVEL_READER, null) != null;
    }

    /** 读方块坐标的一个分量；缺成员时返回 {@link Integer#MIN_VALUE}（真实坐标不可能是它）。 */
    private static int coordinate(GameBridge bridge, Object pos, String member) {
        Object value = bridge.callMapped(pos, ClassType.BLOCK_POS, member);
        return value instanceof Number ? ((Number) value).intValue() : Integer.MIN_VALUE;
    }

    /** 打包方块坐标（x/z 各 26 位、y 12 位），与游戏的方块坐标打包同构。 */
    private static long pack(int x, int y, int z) {
        return ((long) (x & 0x3FFFFFF) << 38) | ((long) (z & 0x3FFFFFF) << 12) | (y & 0xFFF);
    }

    /** 解出打包坐标的 x（符号位补齐）。 */
    private static int unpackX(long key) {
        return (int) (key << 0 >> 38);
    }

    /** 解出打包坐标的 y（12 位有符号）。 */
    private static int unpackY(long key) {
        return (int) (key << 52 >> 52);
    }

    /** 解出打包坐标的 z。 */
    private static int unpackZ(long key) {
        return (int) (key << 26 >> 38);
    }

    /**
     * 按映射候选名解析一个类；{@code loader} 为 {@code null} 时用本类的加载器。
     */
    private static Class<?> resolveMapped(GameBridge bridge, ClassType type, ClassLoader loader) {
        ClassLoader effective = loader != null ? loader : SearchModule.class.getClassLoader();
        for (String mapped : bridge.mapping().classNameCandidates(type)) {
            try {
                return Class.forName(mapped, false, effective);
            } catch (Throwable t) {
                // 该候选名在当前加载器里不可用：继续尝试下一个。
            }
        }
        return null;
    }

    /** 表驱动门日志：缺成员版本只报一次。 */
    private void logGateOnce(String reason) {
        if (gateLogged) {
            return;
        }
        gateLogged = true;
        System.out.println("[nocturne] Search: " + reason);
    }

    /** 取当前客户端的游戏桥接；客户端尚未构造时返回 {@code null}。 */
    private static GameBridge bridge() {
        NocturneClient client = NocturneClient.get();
        return client == null ? null : client.gameBridge();
    }
}
