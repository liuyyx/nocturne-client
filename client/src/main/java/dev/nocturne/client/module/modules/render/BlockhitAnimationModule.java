package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.event.MouseButtonEvent;
import dev.nocturne.client.event.RenderEvent;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;

/** 持剑格挡时持续驱动第一人称挥舞动画。 */
public final class BlockhitAnimationModule extends GameModule {
    private boolean rightHeld;
    private long nextSwing;

    public BlockhitAnimationModule() {
        super("Blockhit Animation", Category.RENDER);
    }

    @Override
    protected void onStart() {
        listen(MouseButtonEvent.class, event -> {
            if (event.button == 1) rightHeld = event.pressed;
        });
        listen(RenderEvent.class, this::onRender);
    }

    private void onRender(RenderEvent event) {
        if (rightHeld && bridge().itemMatches(bridge().heldItem(bridge().player(), false), "swords")
                && event.frameNanos >= nextSwing && actions() != null) {
            actions().swing(false, false);
            nextSwing = event.frameNanos + 180_000_000L;
        }
    }

    @Override
    protected void onStop() {
        rightHeld = false;
    }
}
