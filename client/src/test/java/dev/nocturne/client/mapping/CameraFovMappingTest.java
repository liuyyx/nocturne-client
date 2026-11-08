package dev.nocturne.client.mapping;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 相机位姿 / 有效 FOV / 渲染插值这三条映射面：ESP 只"读表"，缺一项就在那一版静默退回
 * 玩家眼位或静态 FOV，所以把生成结果钉死——类别名或成员别名回归时先在这里红。
 *
 * <p>覆盖四条解析路径：1.8.9 走 MCP 别名桥（Timer#renderPartialTicks、getFOVModifier）、
 * 1.12.2 同路径、1.20.1 走官方 ProGuard（Timer 仍叫 Timer）、26.3 走恒等映射
 * （Camera 去掉 get 前缀）。1.21.1 验证 DeltaTracker 的现代入口。
 */
class CameraFovMappingTest {

    private static final String CAMERA = "net/minecraft/client/Camera";
    private static final String GAME_RENDERER = "net/minecraft/client/renderer/GameRenderer";
    private static final String ENTITY_RENDERER = "net/minecraft/client/renderer/EntityRenderer";
    private static final String TIMER = "net/minecraft/client/Timer";
    private static final String DELTA_TRACKER = "net/minecraft/client/DeltaTracker";

    /** 1.8.9–1.12.2：相机状态不在 Camera 上；插值与 FOV 走 Timer / EntityRenderer 的旧名。 */
    @Test
    void legacyGenerationBridgesTimerAndFovToTheirMcpNames() {
        for (String version : new String[]{"1.8.9", "1.12.2"}) {
            JsonObject table = load(version);
            assertResolved(version, table, TIMER, "fields", "renderPartialTicks");
            assertResolved(version, table, DELTA_TRACKER, "fields", "partialTick");
            assertResolved(version, table, GAME_RENDERER, "methods", "getFov");
            assertResolved(version, table, ENTITY_RENDERER, "methods", "getFOVModifier");
            assertAbsent(version, table, CAMERA, "class", null, "verified");
        }
    }

    /** 1.20.1：Camera + GameRenderer 全面，Timer#partialTick 经类别名照样可读。 */
    @Test
    void modernGenerationResolvesTheCameraAndFovSurface() {
        JsonObject table = load("1.20.1");
        for (String method : new String[]{"getPosition", "getYRot", "getXRot", "getEntity"}) {
            assertResolved("1.20.1", table, CAMERA, "methods", method);
        }
        assertResolved("1.20.1", table, GAME_RENDERER, "methods", "getMainCamera");
        assertResolved("1.20.1", table, GAME_RENDERER, "methods", "getFov");
        assertResolved("1.20.1", table, DELTA_TRACKER, "fields", "partialTick");
    }

    /** 1.21.1：插值入口换成 DeltaTracker（字段 retired，方法在）。 */
    @Test
    void deltaTrackerCarriesThePartialTickMethod() {
        JsonObject table = load("1.21.1");
        assertResolved("1.21.1", table, DELTA_TRACKER, "methods", "getGameTimeDeltaPartialTick");
        assertResolved("1.21.1", table, GAME_RENDERER, "methods", "getFov");
    }

    /**
     * 26.x 恒等映射：Camera 的访问器去掉 get 前缀，GameRenderer 的相机参数改名 mainCamera；
     * getFov 已移到 Camera#getFov()，GameRenderer 上应是 verified absent（覆盖层退回 Options.fov）。
     */
    @Test
    void identityGenerationFollowsTheRenamedAccessors() {
        JsonObject table = load("26.3");
        for (String method : new String[]{"getPosition", "getYRot", "getXRot", "getEntity"}) {
            assertResolved("26.3", table, CAMERA, "methods", method);
        }
        assertResolved("26.3", table, GAME_RENDERER, "methods", "getMainCamera");
        assertResolved("26.3", table, DELTA_TRACKER, "methods", "getGameTimeDeltaPartialTick");

        JsonObject delta = member(table, DELTA_TRACKER, "methods", "getGameTimeDeltaPartialTick");
        assertEquals("(Z)F", delta.getAsJsonObject("signatures").get("vanilla").getAsString(),
                "26.3 DeltaTracker#getGameTimeDeltaPartialTick 应当是 (Z)F");

        assertAbsent("26.3", table, GAME_RENDERER, "methods", "getFov", "verified");
        assertAbsent("26.3", table, TIMER, "class", null, "verified");
    }

    private static JsonObject load(String version) {
        String resource = "/mappings-" + version + ".json";
        InputStream stream = CameraFovMappingTest.class.getResourceAsStream(resource);
        assertNotNull(stream, resource + " 不在 classpath 上");
        return JsonParser.parseReader(
                new InputStreamReader(stream, StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static JsonObject classEntry(JsonObject table, String owner) {
        JsonElement entry = table.getAsJsonObject("classes").get(owner);
        return entry == null || !entry.isJsonObject() ? null : entry.getAsJsonObject();
    }

    private static JsonObject member(JsonObject table, String owner, String kind, String name) {
        JsonObject entry = classEntry(table, owner);
        if (entry == null) {
            fail("表里没有类 " + owner);
        }
        JsonElement found = entry.getAsJsonObject(kind).get(name);
        if (found == null) {
            fail("表里没有 " + owner + " 的 " + kind + " " + name);
        }
        return found.getAsJsonObject();
    }

    private static void assertResolved(String version, JsonObject table, String owner, String kind,
                                       String member) {
        JsonObject entry = member(table, owner, kind, member);
        assertTrue(!entry.has("absent"),
                version + ": " + owner + "#" + member + " 被标成 absent");
    }

    private static void assertAbsent(String version, JsonObject table, String owner, String kind,
                                     String name, String reason) {
        JsonObject entry = name == null ? classEntry(table, owner) : member(table, owner, kind, name);
        assertNotNull(entry, version + ": 表里没有 " + owner + (name == null ? "" : "#" + name));
        assertTrue(entry.has("absent") && entry.get("absent").getAsBoolean(),
                version + ": " + owner + (name == null ? "" : "#" + name) + " 应当是 absent");
        assertEquals(reason, entry.get("absentReason").getAsString(),
                version + ": absent 的理由必须是映射源/jar 实测，而不是猜测");
    }
}
