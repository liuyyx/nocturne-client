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

    /** @return 所有已加载区块里的方块实体；区块表面不可用时为空列表 */
    static List<Object> loadedBlockEntities(GameBridge bridge) {
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
