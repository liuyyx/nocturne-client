package dev.nocturne.client.module.modules.combat;

import dev.nocturne.client.event.MouseButtonEvent;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.module.modules.EpsilonSupport;
import dev.nocturne.client.module.modules.world.PlacementSupport;
import dev.nocturne.client.value.BooleanValue;

/** AutoDtap：右键触发黑曜石→水晶的双击放置序列并恢复原槽。 */
public final class AutoDtapModule extends GameModule {
    private final BooleanValue swapBack = add(new BooleanValue("SwapBack", true));
    private int stage;
    private int delay;
    private int original = -1;

    public AutoDtapModule() {
        super("AutoDtap", Category.COMBAT);
    }

    @Override
    protected void onStart() {
        listen(MouseButtonEvent.class, event -> {
            if (event.pressed && event.button == 1 && stage == 0) {
                original = state().selectedSlot();
                stage = 1;
                delay = 0;
            }
        });
    }

    @Override
    public void onTick() {
        if (stage == 0 || bridge().player() == null) {
            return;
        }
        if (delay-- > 0) {
            return;
        }
        int slot = EpsilonSupport.findHotbar(bridge(), stage == 1 ? "obsidian" : "end_crystal");
        if (slot < 0) {
            stopSequence();
            return;
        }
        actions().selectSlot(slot, false);
        int x = (int) Math.floor(state().x() - Math.sin(Math.toRadians(state().yaw())));
        int y = (int) Math.floor(state().y());
        int z = (int) Math.floor(state().z() + Math.cos(Math.toRadians(state().yaw())));
        if (stage == 1) {
            PlacementSupport.placeNear(bridge(), actions(), x, y, z);
            stage = 2;
            delay = EpsilonSupport.randomInt(1, 2);
        } else {
            actions().placeAt(x, y, z, "up");
            stopSequence();
        }
    }

    private void stopSequence() {
        if (swapBack.get() && original >= 0) {
            actions().selectSlot(original, false);
        }
        original = -1;
        stage = 0;
    }

    @Override
    protected void onStop() {
        stopSequence();
    }
}
