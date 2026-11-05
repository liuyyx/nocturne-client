package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.event.EntityRenderEvent;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.render.RenderCapabilities;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.ColorValue;

/** 在真实实体渲染入口/出口应用并恢复 Chams 状态。 */
public final class ChamsModule extends GameModule {
    private final BooleanValue colored = add(new BooleanValue("Colored", false));
    private final ColorValue visibleColor = add(new ColorValue("Visible Color", 0xFFFF0000));
    private final BooleanValue colorBehindWalls = add(new BooleanValue("Color Behind Walls", true));
    private final ColorValue occludedColor = add(new ColorValue("Invisible Color", 0xFFFFFF00));
    private final BooleanValue hideBots = add(new BooleanValue("Hide Bots", false));

    public ChamsModule() {
        super("Chams", Category.RENDER);
    }

    @Override
    protected void onStart() {
        listen(EntityRenderEvent.class, this::onRender);
    }

    private void onRender(EntityRenderEvent event) {
        if (!bridge().isInstance(event.entity, ClassType.PLAYER)
                || event.entity == bridge().player()
                || hideBots.get() && bridge().entityName(event.entity).contains("[NPC]")) {
            return;
        }
        RenderCapabilities capabilities = client().renderCapabilities();
        if (capabilities == null) {
            return;
        }
        if (!event.post) {
            if (colored.get()) {
                capabilities.depthTest(false);
                capabilities.texture2d(false);
                capabilities.color(colorBehindWalls.get() ? occludedColor.argb() : visibleColor.argb());
            } else {
                capabilities.polygonOffset(true);
            }
            return;
        }
        restore(capabilities);
    }

    @Override
    protected void onStop() {
        RenderCapabilities capabilities = client().renderCapabilities();
        if (capabilities != null) {
            restore(capabilities);
        }
    }

    private void restore(RenderCapabilities capabilities) {
        capabilities.polygonOffset(false);
        capabilities.depthTest(true);
        capabilities.texture2d(true);
        capabilities.color(0xFFFFFFFF);
    }
}
