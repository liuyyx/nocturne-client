package dev.nocturne.client.module.modules.combat;

import dev.nocturne.client.event.KeyEvent;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.module.modules.EpsilonSupport;
import dev.nocturne.client.value.KeyValue;
import dev.nocturne.client.value.NumberValue;

/** AnchorBlast/DoubleAnchor 共用的真实准星交互状态机。 */
abstract class AnchorSequenceModule extends GameModule {
    private final KeyValue trigger = add(new KeyValue("Trigger Key", KeyValue.NONE));
    private final NumberValue detonateSlot = add(new NumberValue("Detonate Slot", 1, 1, 9, 1));
    private final NumberValue placeCps = add(new NumberValue("Place CPS", 10, 1, 30, 1));
    private final NumberValue chargeCps = add(new NumberValue("Charge CPS", 10, 1, 30, 1));
    private final boolean doubleSequence;
    private int phase;
    private int cooldown;
    private int original = -1;
    private boolean keyDown;
    private boolean wasKeyDown;

    AnchorSequenceModule(String name, boolean doubleSequence) {
        super(name, Category.COMBAT);
        this.doubleSequence = doubleSequence;
    }

    @Override
    protected void onStart() {
        listen(KeyEvent.class, event -> {
            if (trigger.bound() && event.keyCode == trigger.get()) {
                keyDown = event.pressed;
            }
        });
    }

    @Override
    public void onTick() {
        boolean newPress = keyDown && !wasKeyDown;
        wasKeyDown = keyDown;
        if (newPress) {
            if (conflict()) {
                return;
            }
            restore();
            original = state().selectedSlot();
            phase = 1;
            cooldown = 0;
        }
        if (phase == 0 || --cooldown > 0) {
            return;
        }
        Object hit = actions().pick(5.0, 1.0f);
        int[] pos = bridge().hitBlockPosition(hit);
        Object direction = bridge().hitDirection(hit);
        if (pos == null || direction == null) {
            return;
        }
        String face = String.valueOf(direction).toLowerCase(java.util.Locale.ROOT);
        String block = bridge().blockDescription(bridge().blockStateAt(pos[0], pos[1], pos[2]));
        if (phase == 1 && !block.contains("respawn_anchor")) {
            int slot = EpsilonSupport.findHotbar(bridge(), "respawn_anchor");
            if (slot < 0 || !select(slot) || !actions().placeAt(pos[0], pos[1], pos[2], face)) {
                phase = 4;
                return;
            }
            swing();
            cooldown = humanized(placeCps.asInt());
            phase = 2;
            return;
        }
        if (!block.contains("respawn_anchor")) {
            return;
        }
        if (phase == 2 || doubleSequence && phase == 6) {
            int slot = EpsilonSupport.findHotbar(bridge(), "glowstone");
            if (slot < 0 || !select(slot) || !actions().placeAt(pos[0], pos[1], pos[2], face)) {
                phase = 4;
                return;
            }
            swing();
            cooldown = humanized(chargeCps.asInt());
            phase = doubleSequence && phase == 2 ? 3 : 5;
            return;
        }
        if (doubleSequence && phase == 3) {
            int slot = EpsilonSupport.findHotbar(bridge(), "respawn_anchor");
            if (slot < 0 || !select(slot)) {
                phase = 4;
                return;
            }
            actions().placeAt(pos[0], pos[1], pos[2], face);
            swing();
            actions().placeAt(pos[0], pos[1], pos[2], face);
            swing();
            phase = 6;
            return;
        }
        if (phase == 5) {
            select(detonateSlot.asInt() - 1);
            actions().placeAt(pos[0], pos[1], pos[2], face);
            swing();
            phase = 4;
            return;
        }
        if (phase == 4) {
            restore();
            phase = 0;
        }
    }

    /** DoubleAnchor 的 AIRPLACE 在第一次充能后额外交互两次，再进入第二次充能。 */
    private boolean select(int slot) {
        actions().selectSlot(slot, false);
        return true;
    }

    private void swing() {
        actions().swing(false, false);
    }

    private boolean conflict() {
        return !"AnchorBlast".equals(name()) && EpsilonSupport.enabled("AnchorBlast")
                || !"DoubleAnchor".equals(name()) && EpsilonSupport.enabled("DoubleAnchor")
                || EpsilonSupport.enabled("SafeAnchor");
    }

    private int humanized(int cps) {
        int base = Math.max(1, 20 / Math.max(1, cps));
        double adjusted = Math.max(50.0, base * 50.0 * (1.0 + EpsilonSupport.RANDOM.nextGaussian() * 0.25));
        return Math.max(1, (int) Math.round(adjusted / 50.0));
    }

    private void restore() {
        if (original >= 0 && actions() != null) {
            actions().selectSlot(original, false);
        }
        original = -1;
    }

    public boolean active() {
        return phase != 0;
    }

    @Override
    protected void onStop() {
        restore();
        phase = 0;
        cooldown = 0;
        keyDown = false;
        wasKeyDown = false;
    }
}
