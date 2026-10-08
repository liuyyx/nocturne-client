package dev.nocturne.client.mapping;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Search 的映射面契约：扫描要「读某一格是什么方块」，这依赖三样东西——逐格读方块的面、
 * 方块身份面、以及在没有构造器可用的前提下造方块坐标用的方向面。表里缺哪一项，
 * 模块就会在那一版上静默失效，所以把它钉成测试。
 *
 * <p>版本选取同 {@link StorageEspMappingTest}：真机验证过的几代 + 各条映射路径的代表。
 *
 * <p>不覆盖的已知缺口（另有文档记录，这里不断言）：
 * <ul>
 *   <li>{@code 1.13–1.14.3}：{@code LevelReader} 类与 {@code Block#getDescriptionId} 在表里缺失
 *       （1.9–1.14.3 走 intermediary 锚点，而这些成员的锚点名是合成 id，跨版本查不到）；</li>
 *   <li>{@code 1.14.4–1.16.4}：{@code Entity#blockPosition} 缺失（该区间用的是别的成员名）。</li>
 * </ul>
 */
class SearchMappingTest {

    /** 每一代都必须解析出来的面：{类, 种类, 成员名}，种类为 {@code class}/{@code fields}/{@code methods}。 */
    private static final String[][] FACES = {
        // 逐格读方块：LevelReader#getBlockState(BlockPos)（1.8.9/1.12.2 是 IBlockAccess）
        {"net/minecraft/world/level/LevelReader", "class", null},
        {"net/minecraft/world/level/LevelReader", "methods", "getBlockState"},
        // 方块身份：Block#getDescriptionId()（1.8.9 是 getUnlocalizedName，1.12.2 是 getTranslationKey）
        {"net/minecraft/world/level/block/Block", "class", null},
        {"net/minecraft/world/level/block/Block", "methods", "getDescriptionId"},
        {"net/minecraft/world/level/block/state/BlockState", "methods", "getBlock"},
        // 扫描原点：玩家所在方块
        {"net/minecraft/world/entity/Entity", "methods", "blockPosition"},
        // 坐标偏移：用 arity-1 的方向面（offset 有 DDD/III/Vec3i 三个重载，表里只能记一条）
        {"net/minecraft/core/BlockPos", "methods", "east"},
        {"net/minecraft/core/BlockPos", "methods", "south"},
        {"net/minecraft/core/BlockPos", "methods", "above"},
    };

    /** 走 SRG 别名桥、官方 ProGuard、恒等映射三条路径的代表版本都要覆盖 Search 的整条面。 */
    @Test
    void everySupportedGenerationCarriesTheSearchSurface() {
        String[] versions = {"1.8.9", "1.12.2", "1.16.5", "1.20.1", "1.21.4", "26.3"};
        for (String version : versions) {
            JsonObject table = load(version);
            for (String[] face : FACES) {
                assertResolved(version, table, face[0], face[1], face[2]);
            }
        }
    }

    /** 读一份映射表；资源缺失即失败（表是打进 jar 的产物，不允许悄悄消失）。 */
    private static JsonObject load(String version) {
        String resource = "/mappings-" + version + ".json";
        InputStream stream = SearchMappingTest.class.getResourceAsStream(resource);
        assertNotNull(stream, resource + " 不在 classpath 上");
        return JsonParser.parseReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    /** 断言某个面在表里存在且没有被标成 absent。 */
    private static void assertResolved(String version, JsonObject table, String owner, String kind,
                                       String member) {
        JsonElement element = table.getAsJsonObject("classes").get(owner);
        if (element == null || !element.isJsonObject()) {
            fail(version + ": 表里没有类 " + owner);
        }
        JsonObject classEntry = element.getAsJsonObject();
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
}
