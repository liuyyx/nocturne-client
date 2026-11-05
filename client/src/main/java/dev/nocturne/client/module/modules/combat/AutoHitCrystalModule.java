package dev.nocturne.client.module.modules.combat;

import dev.nocturne.client.event.KeyEvent;
import dev.nocturne.client.event.UseItemEvent;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.module.Module;
import dev.nocturne.client.module.modules.EpsilonSupport;
import dev.nocturne.client.module.modules.render.WorldGeometry;
import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.client.render.WorldOverlay;
import dev.nocturne.client.render.WorldProjection;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.ColorValue;
import dev.nocturne.client.value.KeyValue;
import dev.nocturne.client.value.NumberValue;

/** AutoHitCrystal：独立按键触发的水晶攻击/补放节拍，并绘制淡出目标框。 */
public final class AutoHitCrystalModule extends GameModule implements WorldOverlay {
    private final KeyValue activate = add(new KeyValue("Activate Key", KeyValue.NONE));
    private final BooleanValue checkPlace = add(new BooleanValue("Check Place", false));
    private final NumberValue switchDelay = add(new NumberValue("Switch Delay", 0, 0, 20, 0.1));
    private final NumberValue switchChance = add(new NumberValue("Switch Chance", 100, 0, 100, 0.1));
    private final NumberValue placeDelay = add(new NumberValue("Place Delay", 0, 0, 20, 0.1));
    private final NumberValue placeChance = add(new NumberValue("Place Chance", 100, 0, 100, 0.1));
    private final BooleanValue workTotem = add(new BooleanValue("Work With Totem", false));
    private final BooleanValue workCrystal = add(new BooleanValue("Work With Crystal", false));
    private final BooleanValue workPickaxe = add(new BooleanValue("Work With Pickaxe", false));
    private final BooleanValue swordSwap = add(new BooleanValue("Sword Swap", true));
    private final BooleanValue swingHand = add(new BooleanValue("Swing Hand", true));
    private final BooleanValue render = add(new BooleanValue("Render", true));
    private final NumberValue fadeTime = add(new NumberValue("Fade Time", 500, 0, 3000, 50));
    private final ColorValue sideColor = add(new ColorValue("Side Color", 0x64FFB7C5));
    private final ColorValue lineColor = add(new ColorValue("Line Color", 0xFFFF69B4));
    private boolean held;
    private int cooldown;
    private int[] marked;
    private long markedAt;

    public AutoHitCrystalModule() {
        super("Auto Hit Crystal", Category.COMBAT);
    }

    @Override
    protected void onStart() {
        listen(KeyEvent.class, event -> {
            if (activate.bound() && event.keyCode == activate.get()) {
                held = event.pressed;
            }
        });
        listen(UseItemEvent.class, event -> {
            if (!event.post && held) {
                event.cancelled = true;
            }
        });
    }

    @Override
    public void onTick() {
        if (!held || cooldown-- > 0) {
            return;
        }
        Object hit = actions().pick(5.0, 1.0f);
        Object crystal = bridge().hitEntity(hit);
        if (crystal != null && bridge().isInstance(crystal, ClassType.END_CRYSTAL)) {
            if (swordSwap.get()) {
                int sword = EpsilonSupport.findHotbar(bridge(), "sword");
                if (sword >= 0 && chance(switchChance.get())) {
                    actions().selectSlot(sword, true);
                }
            }
            actions().attack(crystal);
            actions().swing(false, !swingHand.get());
            cooldown = switchDelay.asInt();
            return;
        }
        if (!checkPlace.get()) {
            return;
        }
        int[] pos = bridge().hitBlockPosition(hit);
        if (pos == null || !allowedHand()) {
            return;
        }
        String base = bridge().blockDescription(bridge().blockStateAt(pos[0], pos[1], pos[2]));
        if (!(base.contains("obsidian") || base.contains("bedrock"))) {
            return;
        }
        int slot = EpsilonSupport.findHotbar(bridge(), "end_crystal");
        if (slot >= 0 && chance(placeChance.get())) {
            actions().selectSlot(slot, true);
            actions().placeAt(pos[0], pos[1], pos[2], "up");
            actions().swing(false, !swingHand.get());
            marked = new int[]{pos[0], pos[1] + 1, pos[2]};
            markedAt = System.currentTimeMillis();
            cooldown = placeDelay.asInt();
        }
    }

    private boolean allowedHand() {
        String id = bridge().itemDescription(bridge().hotbarStack(state().selectedSlot()));
        return id.contains("end_crystal") || workTotem.get() && id.contains("totem")
                || workCrystal.get() && id.contains("crystal") || workPickaxe.get() && id.contains("pickaxe");
    }

    private static boolean chance(double value) {
        return EpsilonSupport.RANDOM.nextDouble() * 100.0 < value;
    }

    @Override
    public void drawWorldOverlay(OverlayDraw draw, WorldProjection projection) {
        if (!render.get() || marked == null) {
            return;
        }
        long age = System.currentTimeMillis() - markedAt;
        if (age > fadeTime.asInt()) {
            marked = null;
            return;
        }
        float scale = 1.0f - (float) age / Math.max(1, fadeTime.asInt());
        WorldGeometry.blockFilled(draw, projection, marked[0], marked[1], marked[2],
                1.0f, withAlpha(sideColor.argb(), scale), withAlpha(lineColor.argb(), scale));
    }

    private static int withAlpha(int color, float factor) {
        return (color & 0x00FFFFFF) | (int) ((color >>> 24) * factor) << 24;
    }

    @Override
    protected void onStop() {
        held = false;
        marked = null;
    }
}
