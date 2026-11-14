package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.mapping.ClassType;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReferenceArray;

/** 已加载区块中的方块实体扫描，统一处理各版本区块表与方块实体表的容器形态。 */
final class BlockEntityScan {
    private static final long CACHE_NANOS = 50_000_000L;

    /** 调用方只读；{@code null} 表示缓存已失效。 */
    private static List<Object> cache;
    private static long cacheNanos;

    private BlockEntityScan() {
    }

    /** 世界切换或模块状态清空时使共享缓存失效。 */
    static void clear() {
        cache = null;
        cacheNanos = 0L;
    }

    /**
     * 返回所有已加载区块里的方块实体；结果不可修改，区块表面不可用时为空列表。
     * 扫描结果缓存 50ms，避免同帧多个视觉模块重复遍历区块与反射读取。
     */
    static List<Object> loadedBlockEntities(GameBridge bridge) {
        long now = System.nanoTime();
        long elapsed = now - cacheNanos;
        List<Object> cached = cache;
        if (cached != null && elapsed >= 0L && elapsed < CACHE_NANOS) {
            return cached;
        }
        if (bridge == null) {
            return remember(Collections.<Object>emptyList(), now);
        }
        Object level = level(bridge);
        if (level == null) {
            return remember(Collections.<Object>emptyList(), now);
        }

        List<Object> found = new ArrayList<Object>();
        List<Object> chunks = values(loadedChunks(bridge, level));
        for (int i = 0; i < chunks.size(); i++) {
            Object chunk = chunks.get(i);
            Object table = bridge.callMapped(chunk, ClassType.LEVEL_CHUNK, "getBlockEntities");
            List<Object> blockEntities = values(table);
            for (int j = 0; j < blockEntities.size(); j++) {
                Object blockEntity = blockEntities.get(j);
                if (blockEntity != null) {
                    found.add(blockEntity);
                }
            }
        }
        return remember(Collections.unmodifiableList(found), now);
    }

    private static List<Object> remember(List<Object> result, long now) {
        cache = result;
        cacheNanos = now;
        return result;
    }

    /** 当前客户端世界；未进世界时为 {@code null}。 */
    private static Object level(GameBridge bridge) {
        Object minecraft = bridge.minecraft();
        return minecraft == null ? null : bridge.readField(minecraft, ClassType.MINECRAFT, "level");
    }

    /**
     * 取已加载区块表。现代版本为 {@code ClientChunkCache.storage.chunks}；
     * 旧版本的 {@code storage} 本身可能就是列表或映射，此时直接使用它。
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

    /** 把四代容器表面展开为紧凑列表；映射只取值，空槽与 {@code null} 元素跳过。 */
    private static List<Object> values(Object container) {
        if (container == null) {
            return Collections.emptyList();
        }
        List<Object> out = new ArrayList<Object>();
        if (container instanceof AtomicReferenceArray) {
            AtomicReferenceArray<?> array = (AtomicReferenceArray<?>) container;
            for (int i = 0; i < array.length(); i++) {
                Object value = array.get(i);
                if (value != null) {
                    out.add(value);
                }
            }
        } else if (container instanceof Object[]) {
            Object[] array = (Object[]) container;
            for (int i = 0; i < array.length; i++) {
                if (array[i] != null) {
                    out.add(array[i]);
                }
            }
        } else if (container instanceof Map) {
            for (Object value : ((Map<?, ?>) container).values()) {
                if (value != null) {
                    out.add(value);
                }
            }
        } else if (container instanceof Iterable) {
            for (Object value : (Iterable<?>) container) {
                if (value != null) {
                    out.add(value);
                }
            }
        }
        return out;
    }
}
