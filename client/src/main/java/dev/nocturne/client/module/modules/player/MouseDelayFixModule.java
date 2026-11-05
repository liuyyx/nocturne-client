package dev.nocturne.client.module.modules.player;

import dev.nocturne.client.event.RenderEvent;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;

/** 每渲染帧更新准星，减少 1.8 命中使用旧鼠标位置的问题。 */
public final class MouseDelayFixModule extends GameModule {
    public MouseDelayFixModule() {
        super("MouseDelayFix", Category.PLAYER);
    }

    @Override
    protected void onStart() {
        listen(RenderEvent.class, event -> {
            if (bridge() != null && actions() != null && bridge().player() != null) {
                bridge().writeField(bridge().minecraft(), ClassType.MINECRAFT,
                        "hitResult", actions().pick(3.0, 1.0f));
            }
        });
    }
}
