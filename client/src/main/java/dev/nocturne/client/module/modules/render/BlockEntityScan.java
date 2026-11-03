package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.mapping.ClassType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReferenceArray;

/** 已加载区块中的方块实体扫描；集中处理五代区块表形态。 */
final class BlockEntityScan {

    private BlockEntityScan() {
    }

    /** @return 客户端世界；未进世界时为 {@code null} */
    static Object level(GameBridge bridge) {
        Object minecraft = bridge.minecraft();
        return minecraft == null ? null : bridge.readField(minecraft, ClassType.MINECRAFT, "level");
    }

    /** 扫描结果缓存的有效期：与模块 tick 同频（20Hz），方块实体不会在 50ms 内变几次。 */
    private static final long CACHE_NANOS = 50_000_000L;

    /** 上次扫描完成的时间戳（纳秒）；{@code 0} 表示尚无缓存。 */
    private static long cacheNanos;
    /** 上次扫描结果；调用方只读，不得修改。 */
    private static List<Object> cache;

    /**
     * @return 所有已加载区块里的方块实体；区块表面不可用时为空列表
     *
     * <p><b>按 20Hz 缓存</b>：这个扫描要遍历每个已加载区块、对每个区块反射调一次
     * {@code getBlockEntities}、再把所有方块实体塞进新列表。几百个区块时单次就是上千次分配 +
     * 反射，而 StorageESP 与 SpawnerFinder 每帧各要一次——1.16.5 真机上这就是「开容器透视后
     * 帧率腰斩、随后 tick 追赶」的主开销。方块实体在 50ms 内不会有几次变化，缓存无感知。
     */
    static List<Object> loadedBlockEntities(GameBridge bridge) {
        long now = System.nanoTime();
        List<Object> cached = cache;
        if (cached != null && now - cacheNanos < CACHE_NANOS) {
            return cached;
        }
        List<Object> out = new ArrayList<Object>();
        Object level = level(bridge);
        if (level == null) {
            return out;
        }
        List<Object> chunks = chunkList(loadedChunks(bridge, level));
        for (Object chunk : chunks) {
            Object table = bridge.callMapped(chunk, ClassType.LEVEL_CHUNK, "getBlockEntities");
            if (!(table instanceof Map)) {
                continue;
            }
            for (Object blockEntity : ((Map<?, ?>) table).values()) {
                if (blockEntity != null) {
                    out.add(blockEntity);
                }
            }
        }
        cache = out;
        cacheNanos = now;
        return out;
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
}
