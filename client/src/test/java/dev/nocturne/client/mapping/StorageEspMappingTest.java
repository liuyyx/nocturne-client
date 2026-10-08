package dev.nocturne.client.mapping;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * StorageEsp 的映射面契约：模块在运行期只"读表"，表里缺哪一项就在那一版上静默失效
 * （遍历不到容器 / 不画），所以把它的依赖钉成测试——换生成器或换数据源导致缺面时，这里先红。
 *
 * <p>版本选取：运行时真机验证过的几代（1.8.9 / 1.16.5 / 26.3）加各条映射路径的代表
 * （1.12.2 走 SRG 别名桥、1.20.1 / 1.21.4 走官方 ProGuard）。
 *
 * <p>不覆盖的已知缺口（另有文档记录，这里不断言）：`1.14–1.14.3` 的区块表字段面在表里缺失；
 * `1.9–1.14.3` 的 `ClientLevel#getBlockState` 面缺失（陷阱箱按普通箱画）。
 */
class StorageEspMappingTest {

    /** 每一代都必须解析出来的面：{类, 种类, 成员名}，种类为 {@code class}/{@code fields}/{@code methods}。 */
    private static final String[][] COMMON = {
        // 区块表：ClientLevel#getChunkSource → ClientChunkCache.storage（1.8.9/1.12.2 直接是 List/Map）
        {"net/minecraft/client/multiplayer/ClientLevel", "methods", "getChunkSource"},
        {"net/minecraft/client/multiplayer/ClientChunkCache", "fields", "storage"},
        // 区块的方块实体表：LevelChunk#getBlockEntities（1.8.9/1.12.2 是 Chunk#getTileEntityMap）
        {"net/minecraft/world/level/chunk/LevelChunk", "methods", "getBlockEntities"},
        // 方块实体坐标：BlockEntity#getBlockPos → BlockPos#getX/getY/getZ
        {"net/minecraft/world/level/block/entity/BlockEntity", "methods", "getBlockPos"},
        {"net/minecraft/core/BlockPos", "methods", "getX"},
        {"net/minecraft/core/BlockPos", "methods", "getY"},
        {"net/minecraft/core/BlockPos", "methods", "getZ"},
        // 容器类型判定：按方块实体类
        {"net/minecraft/world/level/block/entity/ChestBlockEntity", "class", null},
        {"net/minecraft/world/level/block/entity/EnderChestBlockEntity", "class", null},
        {"net/minecraft/world/level/block/entity/HopperBlockEntity", "class", null},
        {"net/minecraft/world/level/block/entity/AbstractFurnaceBlockEntity", "class", null},
        {"net/minecraft/world/level/block/entity/DispenserBlockEntity", "class", null},
        {"net/minecraft/world/level/block/entity/DropperBlockEntity", "class", null},
        // 陷阱箱判定：方块状态 → 方块 → 比类层次
        {"net/minecraft/world/level/block/state/BlockState", "methods", "getBlock"},
        {"net/minecraft/world/level/block/ChestBlock", "class", null},
        {"net/minecraft/client/multiplayer/ClientLevel", "methods", "getBlockState"},
    };

    /** 每一代都必须有的面（同上），外加 1.14.4 起才有的区块缓存内嵌类。 */
    private static final String[][] MODERN_ONLY = {
        {"net/minecraft/client/multiplayer/ClientChunkCache$Storage", "class", null},
        {"net/minecraft/client/multiplayer/ClientChunkCache$Storage", "fields", "chunks"},
    };

    /** 潜影盒是 1.11 加入的：1.8.9 表里本该没有它。 */
    private static final String[] SHULKER = {
        "net/minecraft/world/level/block/entity/ShulkerBoxBlockEntity", "class", null,
    };

    /** 走 SRG 别名桥、官方 ProGuard、恒等映射三条路径的代表版本都要覆盖 StorageEsp 的整条面。 */
    @Test
    void everySupportedGenerationCarriesTheStorageEspSurface() {
        String[] versions = {"1.8.9", "1.12.2", "1.16.5", "1.20.1", "1.21.4", "26.3"};
        for (String version : versions) {
            JsonObject table = load(version);
            for (String[] face : COMMON) {
                assertResolved(version, table, face[0], face[1], face[2]);
            }
            if (atLeast(version, 1, 11, 0)) {
                assertResolved(version, table, SHULKER[0], SHULKER[1], null);
            }
            if (atLeast(version, 1, 14, 4)) {
                for (String[] face : MODERN_ONLY) {
                    assertResolved(version, table, face[0], face[1], face[2]);
                }
            }
        }
    }

    /** 版本号大小比较（字符串比较会把 1.8.9 排到 1.11 之后，不能用）。 */
    private static boolean atLeast(String version, int major, int minor, int patch) {
        String[] parts = version.split("\\.");
        int a = Integer.parseInt(parts[0]);
        int b = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
        int c = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;
        if (a != major) {
            return a > major;
        }
        if (b != minor) {
            return b > minor;
        }
        return c >= patch;
    }

    /** 1.8.9 没有潜影盒方块实体：表里应当是显式 absent，而不是换了名字。 */
    @Test
    void legacyTableMarksTheShulkerBoxAsAbsent() {
        JsonObject entry = classEntry(load("1.8.9"),
                "net/minecraft/world/level/block/entity/ShulkerBoxBlockEntity");
        assertNotNull(entry, "1.8.9 表里应当有 ShulkerBoxBlockEntity 的 absent 记录");
        assertTrue(entry.has("absent") && entry.get("absent").getAsBoolean(),
                "1.8.9 没有潜影盒，应当是 absent");
    }

    /** 读一份映射表；资源缺失即失败（表是打进 jar 的产物，不允许悄悄消失）。 */
    private static JsonObject load(String version) {
        String resource = "/mappings-" + version + ".json";
        InputStream stream = StorageEspMappingTest.class.getResourceAsStream(resource);
        assertNotNull(stream, resource + " 不在 classpath 上");
        return JsonParser.parseReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    /** 断言某个面在表里存在且没有被标成 absent。 */
    private static void assertResolved(String version, JsonObject table, String owner, String kind,
                                       String member) {
        JsonObject classEntry = classEntry(table, owner);
        if (classEntry == null) {
            fail(version + ": 表里没有类 " + owner);
        }
        assertTrue(!classEntry.has("absent") || !classEntry.get("absent").getAsBoolean(),
                version + ": 类 " + owner + " 被标成 absent");
        if (member == null) {
            return;
        }
        JsonElement entry = classEntry.getAsJsonObject(kind).get(member);
        if (entry == null) {
            fail(version + ": 表里没有 " + owner + " 的 " + kind + " " + member);
        }
        assertTrue(!entry.getAsJsonObject().has("absent"),
                version + ": " + owner + "#" + member + " 被标成 absent");
    }

    /** 取类条目；不存在返回 {@code null}。 */
    private static JsonObject classEntry(JsonObject table, String owner) {
        JsonElement entry = table.getAsJsonObject("classes").get(owner);
        return entry == null || !entry.isJsonObject() ? null : entry.getAsJsonObject();
    }
}
