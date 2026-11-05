package dev.nocturne.client.module.modules.combat;

import dev.nocturne.client.action.ActionApi;
import dev.nocturne.client.action.PlayerState;
import dev.nocturne.client.control.TargetManager;
import dev.nocturne.client.event.MouseButtonEvent;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.ModeValue;
import dev.nocturne.client.value.NumberValue;

/** 自动攻击已有晶体，或在目标脚下合法基块上放置晶体。 */
public final class CrystalAuraModule extends GameModule {
    private final ModeValue mode = add(new ModeValue("Mode", "Auto", "Auto", "Manual"));
    private final NumberValue range = add(new NumberValue("Range", 4.5, 0, 6, 0.1));
    private final NumberValue maxAngle = add(new NumberValue("Max angle", 120, 1, 360, 1));
    private final NumberValue delay = add(new NumberValue("Delay", 50, 0, 500, 10));
    private final BooleanValue antiSuicide = add(new BooleanValue("Anti suicide", true));
    private final NumberValue maxSelfDamage = add(new NumberValue("Max self damage", 19, 0, 20, 1));
    private final TargetManager.Options options = new TargetManager.Options();
    private boolean rightHeld;
    private long nextAction;

    public CrystalAuraModule() {
        super("CrystalAura", Category.COMBAT);
    }

    @Override
    protected void onStart() {
        listen(MouseButtonEvent.class, event -> {
            if (event.button == 1) {
                rightHeld = event.pressed;
            }
        });
    }

    @Override
    public void onTick() {
        GameBridge bridge = bridge();
        ActionApi actions = actions();
        PlayerState state = state();
        if (bridge == null || actions == null || !state.present()
                || mode.is("Manual") && !rightHeld || antiSuicide.get()
                && state.health() + state.absorption() <= Math.min(4.0, maxSelfDamage.get())) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now < nextAction) {
            return;
        }
        Object crystal = nearestCrystal(bridge, state);
        if (crystal != null) {
            actions.attack(crystal);
            nextAction = now + delay.asInt();
            return;
        }
        options.range = range.get();
        options.maxAngle = maxAngle.get();
        TargetManager.Target target = client().targets().find(client(), options);
        if (target == null || !equipCrystal(bridge, actions)) {
            return;
        }
        int x = (int) Math.floor(target.x());
        int y = (int) Math.floor(target.y()) - 1;
        int z = (int) Math.floor(target.z());
        String base = bridge.blockDescription(bridge.blockStateAt(x, y, z));
        if ((base.contains("obsidian") || base.contains("bedrock"))
                && actions.placeAt(x, y, z, "up")) {
            nextAction = now + delay.asInt();
        }
    }

    private Object nearestCrystal(GameBridge bridge, PlayerState state) {
        Object entities = bridge.entities();
        Object best = null;
        double bestDistance = range.get();
        if (entities instanceof Iterable<?>) {
            for (Object entity : (Iterable<?>) entities) {
                if (!bridge.isInstance(entity, ClassType.END_CRYSTAL)) {
                    continue;
                }
                double distance = bridge.distance(state.player(), entity);
                if (distance <= bestDistance) {
                    best = entity;
                    bestDistance = distance;
                }
            }
        }
        return best;
    }

    private boolean equipCrystal(GameBridge bridge, ActionApi actions) {
        if (bridge.itemDescription(bridge.heldItem(state().player(), false)).contains("end_crystal")) {
            return true;
        }
        for (int slot = 0; slot <= 8; slot++) {
            if (bridge.itemDescription(bridge.hotbarStack(slot)).contains("end_crystal")) {
                return actions.selectSlot(slot, false);
            }
        }
        return false;
    }
}
