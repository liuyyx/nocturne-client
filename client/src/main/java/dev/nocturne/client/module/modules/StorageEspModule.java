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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * 储物透视：对箱子/陷阱箱/末影箱/漏斗/熔炉/发射器/投掷器/潜影盒画穿墙方块框。
 *
 * <p>行为对齐 OpenVape StorageESP（只搬行为，不搬代码）：按类型开关 + 配色画框。
 *
 * <p><b>容器从哪来</b>：容器是方块实体而非实体，实体列表里没有它们，所以走
 * {@code ClientLevel#getChunkSource() → 已加载区块表 → LevelChunk#getBlockEntities()} 遍历。
 * 区块表两代形态不同：现代是 {@code ClientChunkCache.storage.chunks}（{@code AtomicReferenceArray}），
 * 1.8.9 是 {@code ChunkProviderClient.chunkListing}（{@code List}），1.12.2 是
 * {@code ChunkProviderClient.loadedChunks}（{@code Long2ObjectMap}）——三种都认。
 *
 * <p><b>陷阱箱判定</b>：陷阱箱与普通箱共用方块实体类，只能看方块——取
 * {@code ClientLevel#getBlockState(BlockEntity#getBlockPos())} 再 {@code BlockState#getBlock()}，
 * 「是箱子方块的实例但不是箱子方块类本身」即陷阱箱（1.8.9 的 BlockTrappedChest、现代的
 * TrappedChestBlock 都是唯一子类）。方块读不到的版本按普通箱处理。
 *
 * <p><b>不做“开盖反色描边”</b>：开盖数在 26.x 是 {@code ChestBlockEntity.getOpenCount(BlockGetter, BlockPos)}
 * 静态双参方法、在 1.8.9/1.12.2 是 {@code TileEntityChest.numPlayersUsing} 字段——三代没有同一个面，
 * 与其留一个在部分版本静默失效的开关，不如不提供。
 *
 * <p>版本门走表驱动：方块实体面/槽位表缺失的版本一次性日志 + 跳过，不刷屏不抛异常。
 */
public final class StorageEspModule extends Module implements WorldOverlay {

    /** 框的半边长（像素）。世界空间画成屏幕方块，尺寸固定，不随距离缩放。 */
    private static final float BOX_HALF = 6f;
    /** 框线宽（像素）。 */
    private static final float BOX_THICKNESS = 1.5f;

    /** 投影结果复用（每帧可能有上百个容器，避免逐个分配）。 */
    private final WorldProjection.ScreenPoint point = new WorldProjection.ScreenPoint();

    /** 各容器类型开关。 */
    private final BooleanValue renderChests = add(new BooleanValue("Render Chests", true));
    private final BooleanValue renderTrappedChests = add(new BooleanValue("Render Trapped Chests", true));
    private final BooleanValue renderEnderchests = add(new BooleanValue("Render Enderchests", false));
    private final BooleanValue renderHopper = add(new BooleanValue("Render Hopper", false));
    private final BooleanValue renderFurnace = add(new BooleanValue("Render Furnace", false));
    private final BooleanValue renderDispenser = add(new BooleanValue("Render Dispenser", false));
    private final BooleanValue renderDropper = add(new BooleanValue("Render Dropper", false));
    private final BooleanValue renderShulker = add(new BooleanValue("Render Shulker", false));
    /** 各容器配色（默认对齐 OpenVape 的 RGBA）。 */
    private final ColorValue chestColor = add(new ColorValue("Chest Color", 0x6401FF92));
    private final ColorValue trappedChestColor = add(new ColorValue("Trapped Chest Color", 0x64FF0000));
    private final ColorValue enderChestColor = add(new ColorValue("Ender Chest Color", 0x647E159C));
    private final ColorValue hopperColor = add(new ColorValue("Hopper Color", 0xFF8A8A8A));
    private final ColorValue furnaceColor = add(new ColorValue("Furnace Color", 0xFF5A5A5A));
    private final ColorValue dispenserColor = add(new ColorValue("Dispenser Color", 0x640114C8));
    private final ColorValue dropperColor = add(new ColorValue("Dropper Color", 0x6446C8C8));
    private final ColorValue shulkerColor = add(new ColorValue("Shulker Color", 0x64FFFFFF));

    /** RenderEvent 订阅句柄；禁用时退订。 */
    private EventBus.Subscription renderSubscription;
    /** 缺成员门日志是否已打过（每个版本只报一次）。 */
    private boolean gateLogged;
    /** 区块表读不到时的门日志（每个版本只报一次）。 */
    private boolean chunkGateLogged;
    /** 上一帧画出来的容器数（HUD/诊断用）。 */
    private int lastVisibleCount;

    /** 容器种类：决定用哪个开关与哪个配色。 */
    private enum Kind {
        /** 普通箱子。 */
        CHEST,
        /** 陷阱箱。 */
        TRAPPED_CHEST,
        /** 末影箱。 */
        ENDER_CHEST,
        /** 漏斗。 */
        HOPPER,
        /** 熔炉（含高炉/烟熏炉，它们共用抽象方块实体类）。 */
        FURNACE,
        /** 发射器。 */
        DISPENSER,
        /** 投掷器。 */
        DROPPER,
        /** 潜影盒。 */
        SHULKER
    }

    /** 模块名，注册表内唯一，也是 GUI 中的显示名。 */
    @Override
    public String name() {
        return "StorageESP";
    }

    /** 归入“渲染”分组。 */
    @Override
    public Category category() {
        return Category.RENDER;
    }

    /** 启用时订阅渲染事件；订阅失败（总线未就绪）时保持未订阅，下一 tick 重试。 */
    @Override
    protected void onEnable() {
        gateLogged = false;
        chunkGateLogged = false;
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
        if (renderSubscription == null) {
            subscribe();
        }
    }

    /** @return 上一帧画出来的容器数（实时诊断用）。 */
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
     * 每渲染帧做门检查（收集与绘制都在 {@link #drawWorldOverlay} 里，避免同一帧遍历两遍）。
     *
     * <p>表驱动门：方块实体面（如 {@code BlockEntity#getBlockPos}）在某版本 absent 时，
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
        if (!hasBlockEntityFace(bridge)) {
            logGateOnce("方块实体面缺失（版本无该成员），StorageESP 已禁用");
            lastVisibleCount = 0;
            return;
        }
        // 计数由 drawWorldOverlay 在遍历时累加（同帧、同一份列表）。
        lastVisibleCount = 0;
    }

    /**
     * 世界覆盖层：遍历已加载区块的方块实体，给每个启用的容器画一个穿墙框。
     *
     * <p>位置取 {@code BlockEntity#getBlockPos()}，投影到方块中心（+0.5）；投影不可见
     * （出屏或在相机背后）时跳过。
     */
    @Override
    public void drawWorldOverlay(OverlayDraw draw, WorldProjection projection) {
        GameBridge bridge = bridge();
        if (bridge == null || !hasBlockEntityFace(bridge)) {
            return;
        }
        Object level = level(bridge);
        if (level == null) {
            return;
        }
        List<Object> chunks = chunkList(loadedChunks(bridge, level));
        if (chunks.isEmpty()) {
            logChunkGateOnce("读不到已加载区块表（版本面缺失），StorageESP 本帧无容器");
            return;
        }
        int visible = 0;
        for (Object chunk : chunks) {
            Object table = bridge.callMapped(chunk, ClassType.LEVEL_CHUNK, "getBlockEntities");
            if (!(table instanceof Map)) {
                continue;
            }
            for (Object blockEntity : ((Map<?, ?>) table).values()) {
                if (blockEntity == null) {
                    continue;
                }
                Kind kind = kindOf(bridge, level, blockEntity);
                if (kind == null || !enabledFor(kind)) {
                    continue;
                }
                if (drawContainer(draw, projection, bridge, blockEntity, kind)) {
                    visible++;
                }
            }
        }
        lastVisibleCount = visible;
    }

    /** 取客户端世界；未进世界时返回 {@code null}。 */
    private static Object level(GameBridge bridge) {
        Object minecraft = bridge.minecraft();
        return minecraft == null ? null : bridge.readField(minecraft, ClassType.MINECRAFT, "level");
    }

    /**
     * 方块实体面是否存在：映射表给的候选类名里有任意一个能在当前加载器里解析出来即可。
     */
    private static boolean hasBlockEntityFace(GameBridge bridge) {
        return resolveMapped(bridge, ClassType.BLOCK_ENTITY, null) != null;
    }

    /**
     * 取已加载区块表。
     *
     * <p>现代：{@code ClientLevel#getChunkSource() → ClientChunkCache.storage → Storage.chunks}
     * （{@code AtomicReferenceArray}）。1.8.9：{@code storage} 本身就是 {@code List}（chunkListing）；
     * 1.12.2：{@code storage} 是 {@code Long2ObjectMap}（loadedChunks）。两者都没有内嵌的 Storage 类
     * （表里 absent），此时直接返回它。
     */
    private static Object loadedChunks(GameBridge bridge, Object level) {
        Object source = bridge.callMapped(level, ClassType.CLIENT_LEVEL, "getChunkSource");
        if (source == null) {
            return null;
        }
        Object storage = bridge.readField(source, ClassType.CLIENT_CHUNK_CACHE, "storage");
        if (storage == null || storage instanceof Iterable || storage instanceof Object[]
                || storage instanceof Map || storage instanceof AtomicReferenceArray) {
            return storage;
        }
        Object chunks = bridge.readField(storage, ClassType.CLIENT_CHUNK_STORAGE, "chunks");
        return chunks != null ? chunks : storage;
    }

    /** 把区块表摊平成列表（现代是 {@code AtomicReferenceArray}，1.8.9 是 {@code List}，1.12.2 是 {@code Map}）。 */
    private static List<Object> chunkList(Object chunks) {
        List<Object> out = new ArrayList<Object>();
        if (chunks instanceof AtomicReferenceArray) {
            AtomicReferenceArray<?> array = (AtomicReferenceArray<?>) chunks;
            for (int i = 0; i < array.length(); i++) {
                Object chunk = array.get(i);
                if (chunk != null) {
                    out.add(chunk);
                }
            }
        } else if (chunks instanceof Object[]) {
            for (Object chunk : (Object[]) chunks) {
                if (chunk != null) {
                    out.add(chunk);
                }
            }
        } else if (chunks instanceof Map) {
            for (Object chunk : ((Map<?, ?>) chunks).values()) {
                if (chunk != null) {
                    out.add(chunk);
                }
            }
        } else if (chunks instanceof Iterable) {
            for (Object chunk : (Iterable<?>) chunks) {
                if (chunk != null) {
                    out.add(chunk);
                }
            }
        }
        return out;
    }

    /** 画一个容器框；不可见或坐标读不到时返回 {@code false}。 */
    private boolean drawContainer(OverlayDraw draw, WorldProjection projection, GameBridge bridge,
                                  Object blockEntity, Kind kind) {
        Object pos = bridge.callMapped(blockEntity, ClassType.BLOCK_ENTITY, "getBlockPos");
        if (pos == null) {
            return false;
        }
        double x = coordinate(bridge, pos, "getX");
        double y = coordinate(bridge, pos, "getY");
        double z = coordinate(bridge, pos, "getZ");
        if (Double.isNaN(x) || Double.isNaN(y) || Double.isNaN(z)) {
            return false;
        }
        projection.project(x + 0.5d, y + 0.5d, z + 0.5d, point);
        if (!point.visible) {
            return false;
        }
        int argb = colorOf(kind);
        float size = BOX_HALF * 2f;
        draw.rect(point.x - BOX_HALF, point.y - BOX_HALF, size, size, argb);
        draw.outline(point.x - BOX_HALF, point.y - BOX_HALF, size, size, BOX_THICKNESS, argb);
        return true;
    }

    /** 读方块坐标的一个分量；缺成员时返回 {@code NaN}。 */
    private static double coordinate(GameBridge bridge, Object pos, String member) {
        Object value = bridge.callMapped(pos, ClassType.BLOCK_POS, member);
        return value instanceof Number ? ((Number) value).doubleValue() : Double.NaN;
    }

    /**
     * 判定方块实体的容器种类；不是容器时返回 {@code null}。
     *
     * <p>顺序要紧：投掷器继承发射器，必须先判投掷器，否则投掷器会被算成发射器。
     */
    private static Kind kindOf(GameBridge bridge, Object level, Object blockEntity) {
        if (isInstance(bridge, ClassType.ENDER_CHEST_BLOCK_ENTITY, blockEntity)) {
            return Kind.ENDER_CHEST;
        }
        if (isInstance(bridge, ClassType.SHULKER_BOX_BLOCK_ENTITY, blockEntity)) {
            return Kind.SHULKER;
        }
        if (isInstance(bridge, ClassType.CHEST_BLOCK_ENTITY, blockEntity)) {
            return isTrappedChest(bridge, level, blockEntity) ? Kind.TRAPPED_CHEST : Kind.CHEST;
        }
        if (isInstance(bridge, ClassType.DROPPER_BLOCK_ENTITY, blockEntity)) {
            return Kind.DROPPER;
        }
        if (isInstance(bridge, ClassType.DISPENSER_BLOCK_ENTITY, blockEntity)) {
            return Kind.DISPENSER;
        }
        if (isInstance(bridge, ClassType.HOPPER_BLOCK_ENTITY, blockEntity)) {
            return Kind.HOPPER;
        }
        if (isInstance(bridge, ClassType.FURNACE_BLOCK_ENTITY, blockEntity)) {
            return Kind.FURNACE;
        }
        return null;
    }

    /**
     * 陷阱箱判定：陷阱箱与普通箱共用方块实体类，只能看方块——取该位置的方块，
     * 若它是箱子方块的实例、但不是箱子方块类本身，那它就是陷阱箱。
     *
     * <p>用类层次而不是单独去映射 {@code TrappedChestBlock}：MCP 的 1.8.9/1.12.2
     * joined.srg 里根本没有陷阱箱方块类（未命名），只有 {@code BlockChest}；而"唯一的
     * 子类就是陷阱箱"在每一代都成立（1.8.9 的 BlockTrappedChest、现代的 TrappedChestBlock）。
     *
     * <p>方块读不到的版本（1.13.2/1.14.x 的映射面缺失，或未进世界）按普通箱处理——不猜。
     */
    private static boolean isTrappedChest(GameBridge bridge, Object level, Object blockEntity) {
        Class<?> chestBlock = resolveMapped(bridge, ClassType.CHEST_BLOCK, null);
        if (chestBlock == null) {
            return false;
        }
        Object pos = bridge.callMapped(blockEntity, ClassType.BLOCK_ENTITY, "getBlockPos");
        if (pos == null) {
            return false;
        }
        Object state = bridge.callMapped(level, ClassType.CLIENT_LEVEL, "getBlockState", pos);
        if (state == null) {
            return false;
        }
        Object block = bridge.callMapped(state, ClassType.BLOCK_STATE, "getBlock");
        return block != null && chestBlock.isInstance(block)
                && !chestBlock.equals(block.getClass());
    }

    /** 该种类的开关是否打开。 */
    private boolean enabledFor(Kind kind) {
        switch (kind) {
            case CHEST:
                return renderChests.get();
            case TRAPPED_CHEST:
                return renderTrappedChests.get();
            case ENDER_CHEST:
                return renderEnderchests.get();
            case HOPPER:
                return renderHopper.get();
            case FURNACE:
                return renderFurnace.get();
            case DISPENSER:
                return renderDispenser.get();
            case DROPPER:
                return renderDropper.get();
            case SHULKER:
                return renderShulker.get();
            default:
                return false;
        }
    }

    /** 该种类的配色。 */
    private int colorOf(Kind kind) {
        switch (kind) {
            case CHEST:
                return chestColor.argb();
            case TRAPPED_CHEST:
                return trappedChestColor.argb();
            case ENDER_CHEST:
                return enderChestColor.argb();
            case HOPPER:
                return hopperColor.argb();
            case FURNACE:
                return furnaceColor.argb();
            case DISPENSER:
                return dispenserColor.argb();
            case DROPPER:
                return dropperColor.argb();
            case SHULKER:
                return shulkerColor.argb();
            default:
                return 0;
        }
    }

    /** 是否为某映射类的实例（不写版本分支）。 */
    private static boolean isInstance(GameBridge bridge, ClassType type, Object target) {
        Class<?> resolved = resolveMapped(bridge, type, target.getClass().getClassLoader());
        return resolved != null && resolved.isInstance(target);
    }

    /**
     * 按映射候选名解析一个类；{@code loader} 为 {@code null} 时用本类的加载器。
     *
     * <p>候选顺序是「原版混淆名 → intermediary → SRG → NeoForge → 规范名」，第一个能加载的胜出——
     * 这样同一份代码在四种安装上都不需要版本分支；某版本没有该类（表里 absent）时全部落空，
     * 返回 {@code null}（调用方据此跳过）。
     */
    private static Class<?> resolveMapped(GameBridge bridge, ClassType type, ClassLoader loader) {
        ClassLoader effective = loader != null ? loader : StorageEspModule.class.getClassLoader();
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
        System.out.println("[nocturne] StorageESP: " + reason);
    }

    /** 区块表面缺失的门日志（每个版本只报一次，避免每帧刷屏）。 */
    private void logChunkGateOnce(String reason) {
        if (chunkGateLogged) {
            return;
        }
        chunkGateLogged = true;
        System.out.println("[nocturne] StorageESP: " + reason);
    }

    /** 取当前客户端的游戏桥接；客户端尚未构造时返回 {@code null}。 */
    private static GameBridge bridge() {
        NocturneClient client = NocturneClient.get();
        return client == null ? null : client.gameBridge();
    }
}
