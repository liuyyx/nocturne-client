package dev.nocturne.client.module.modules.combat;

import dev.nocturne.client.event.MovementInputEvent;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.module.modules.EpsilonSupport;
import dev.nocturne.client.module.modules.inventory.InventorySupport;
import dev.nocturne.client.module.modules.world.PlacementSupport;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.NumberValue;

/** Feet Trap：围绕脚下两层的四个水平方向放置方块，必要时扩展斜角。 */
public final class FeetTrapModule extends GameModule {
    private final BooleanValue toggleOnMove = add(new BooleanValue("Toggle On Move", true));
    private final BooleanValue toggleOnJump = add(new BooleanValue("Toggle On Jump", true));
    private final BooleanValue inAir = add(new BooleanValue("In Air", true));
    private final BooleanValue pauseOnEat = add(new BooleanValue("Pause On Eat", true));
    private final NumberValue placeDelay = add(new NumberValue("Place Delay", 50, 0, 500, 50));
    private final BooleanValue extend = add(new BooleanValue("Extend", true));
    private final NumberValue blocksPerTick = add(new NumberValue("Blocks Per Tick", 1, 1, 8, 1));
    private final BooleanValue rotate = add(new BooleanValue("Rotate", true));
    private final NumberValue rotationSpeed = add(new NumberValue("Rotation Speed", 180, 18, 180, 18));
    private final BooleanValue enderChest = add(new BooleanValue("Ender Chest", true));
    private final BooleanValue inventorySwap = add(new BooleanValue("Inventory Swap", true));
    private static final int[][] OFFSETS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
    private double startX;
    private double startY;
    private double startZ;
    private long nextPlace;
    private boolean jumping;

    public FeetTrapModule() {
        super("Feet Trap", Category.COMBAT);
    }

    @Override
    protected void onStart() {
        startX = state().x();
        startY = state().y();
        startZ = state().z();
        listen(MovementInputEvent.class, event -> jumping |= event.jump);
    }

    @Override
    public void onTick() {
        long now = System.currentTimeMillis();
        if (now < nextPlace || pauseOnEat.get() && bridge().usingItem(bridge().player())
                || !inAir.get() && !state().onGround()) {
            jumping = false;
            return;
        }
        double moved = Math.sqrt(Math.pow(state().x() - startX, 2) + Math.pow(state().y() - startY, 2)
                + Math.pow(state().z() - startZ, 2));
        if (toggleOnMove.get() && moved > 1.0 || toggleOnJump.get() && jumping) {
            jumping = false;
            setEnabled(false);
            return;
        }
        jumping = false;
        int slot = EpsilonSupport.findHotbar(bridge(), enderChest.get() ? "ender_chest" : "obsidian", "obsidian");
        int selected = slot;
        if (slot < 0 && inventorySwap.get()) {
            slot = EpsilonSupport.findInventory(bridge(), enderChest.get() ? "ender_chest" : "obsidian", "obsidian");
            if (slot >= 9) {
                selected = 8;
                actions().clickSlot(InventorySupport.menuSlot(slot), selected, "swap");
            }
        }
        if (selected < 0) {
            setEnabled(false);
            return;
        }
        int original = state().selectedSlot();
        actions().selectSlot(selected, false);
        int placed = 0;
        int px = (int) Math.floor(state().x());
        int py = (int) Math.floor(state().y());
        int pz = (int) Math.floor(state().z());
        for (int level = 0; level < 2 && placed < blocksPerTick.asInt(); level++) {
            for (int[] offset : OFFSETS) {
                if (!extend.get() && Math.abs(offset[0]) + Math.abs(offset[1]) > 1) {
                    continue;
                }
                int x = px + offset[0];
                int y = py + level;
                int z = pz + offset[1];
                if (!bridge().canBeReplaced(bridge().blockStateAt(x, y, z))) {
                    continue;
                }
                if (rotate.get()) {
                    EpsilonSupport.aim(bridge(), state(), x + 0.5, y + 0.5, z + 0.5,
                            rotationSpeed.asFloat(), 5);
                }
                if (PlacementSupport.placeNear(bridge(), actions(), x, y, z)) {
                    placed++;
                    if (placed >= blocksPerTick.asInt()) {
                        break;
                    }
                }
            }
        }
        actions().selectSlot(original, false);
        nextPlace = now + placeDelay.asInt();
    }
}
