package dev.nocturne.client.mapping;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 屏幕切换钩子的映射契约。
 *
 * <p>26.x 的界面切换走 {@code Gui.setScreen(Screen)}，Minecraft 侧已经没有 {@code setScreen}
 * （映射 absent）。如果这张表没解析出 Gui 的入口，{@code ScreenEvent} 在 26.x 就永远不投递；
 * 反过来，老版本若也解析出一个同名方法，则会和 {@code Minecraft.setScreen} 重复投递。
 * 这里把“只有 26.x 有”钉住。
 */
class ScreenHookMappingTest {

    /** 从资源表里读某个类的 methods 节点。 */
    private static JsonObject methods(String version, String owner) throws Exception {
        String path = "/mappings-" + version + ".json";
        try (InputStream in = ScreenHookMappingTest.class.getResourceAsStream(path)) {
            assertNotNull(in, "mapping resource missing: " + path);
            JsonObject root = new JsonParser().parse(new InputStreamReader(in,
                    StandardCharsets.UTF_8)).getAsJsonObject();
            return root.getAsJsonObject("classes").getAsJsonObject(owner)
                    .getAsJsonObject("methods");
        }
    }

    @Test
    void modernTableExposesGuiSetScreen() throws Exception {
        JsonObject setScreen = methods("26.3", "net/minecraft/client/gui/Gui")
                .getAsJsonObject("setScreen");
        assertNotNull(setScreen, "26.3 must map Gui#setScreen");
        assertTrue(setScreen.has("names"), "Gui#setScreen must resolve to a runtime name");
        assertEquals("setScreen", setScreen.getAsJsonObject("names").get("vanilla").getAsString());
    }

    @Test
    void legacyAndMiddleVersionsMarkGuiSetScreenAbsent() throws Exception {
        for (String version : new String[]{"1.8.9", "1.12.2", "1.20.1"}) {
            JsonObject setScreen = methods(version, "net/minecraft/client/gui/Gui")
                    .getAsJsonObject("setScreen");
            assertNotNull(setScreen, version + " must still carry an explicit entry");
            assertTrue(setScreen.has("absent") && setScreen.get("absent").getAsBoolean(),
                    version + " must mark Gui#setScreen absent to avoid a duplicate screen hook");
        }
    }
}
