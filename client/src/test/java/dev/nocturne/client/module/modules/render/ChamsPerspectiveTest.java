package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.game.FakeMinecraft;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.game.TestMapping;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.client.render.WorldProjection;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** 第一人称不能把本地实体包围盒投影成贯穿屏幕的线条。 */
class ChamsPerspectiveTest {
    static final class Options { int thirdPersonView; }
    static final class Vec { double x, y, z = 4; }
    static final class Player { Vec position() { return new Vec(); } }

    @Test void selfIsOnlyDrawnInThirdPerson() throws Exception {
        FakeMinecraft.reset();
        try {
            Player player = new Player();
            Options options = new Options();
            FakeMinecraft.instance.player = player;
            FakeMinecraft.instance.options = options;
            GameBridge bridge = new GameBridge(null, new TestMapping(FakeMinecraft.class)
                    .classOverride(ClassType.PLAYER, Player.class));
            ChamsModule module = new ChamsModule();
            Field tickBridge = ChamsModule.class.getDeclaredField("tickBridge");
            tickBridge.setAccessible(true);
            tickBridge.set(module, bridge);
            Field published = ChamsModule.class.getDeclaredField("publishedGeneration");
            published.setAccessible(true);
            published.setInt(module, 0);
            final int[] lines = {0};
            OverlayDraw draw = (OverlayDraw) Proxy.newProxyInstance(OverlayDraw.class.getClassLoader(),
                    new Class<?>[]{OverlayDraw.class}, (proxy, method, args) -> {
                        if (method.getName().equals("line")) lines[0]++;
                        if (method.getReturnType() == int.class) return 0;
                        if (method.getReturnType() == float.class) return 0f;
                        if (method.getReturnType() == boolean.class) return false;
                        return null;
                    });
            WorldProjection projection = new WorldProjection();
            projection.update(0, 1, 0, 0, 0, 70, 800, 600);
            module.visit(player);
            module.drawWorldOverlay(draw, projection);
            assertEquals(0, lines[0], "第一人称不能绘制自身包围盒");
            options.thirdPersonView = 1;
            module.visit(player);
            module.drawWorldOverlay(draw, projection);
            assertTrue(lines[0] > 0, "第三人称仍应显示自身包围盒");
        } finally {
            FakeMinecraft.reset();
        }
    }
}
