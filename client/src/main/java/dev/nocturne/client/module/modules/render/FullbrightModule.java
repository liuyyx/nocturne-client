package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.value.ModeValue;
import java.util.Map;

/**
 * 提高 gamma 或添加夜视；已有夜视由游戏保管，不覆盖也不移除。
 * 效果实例无法辨认、构造或写入时改用 gamma，并提示一次。
 */
public final class FullbrightModule extends GameModule {
    private final ModeValue mode = add(new ModeValue("Mode", "Gamma", "Gamma", "Potion"));
    private Object originalGamma;
    private boolean gammaApplied;
    private Object effectPlayer;
    private Object appliedEffect;
    private boolean potionFallback;
    private boolean warned;

    public FullbrightModule() { super("Fullbright", Category.RENDER); }

    @Override protected void onStart() {
        potionFallback = false;
        warned = false;
        update();
    }

    @Override public void onTick() { update(); }

    private void update() {
        GameBridge bridge = bridge();
        if (bridge == null) return;
        if (!"Potion".equals(mode.get())) {
            removeOwnEffect(bridge);
            potionFallback = false;
            applyGamma(bridge);
            return;
        }
        if (potionFallback) {
            applyGamma(bridge);
            return;
        }
        restoreGamma(bridge);
        Object player = bridge.player();
        if (player == null) return;
        if (effectPlayer != null && effectPlayer != player) {
            removeOwnEffect(bridge);
        }
        Object effects = bridge.activeEffects();
        if (!(effects instanceof Iterable) && !(effects instanceof Map)) {
            fallback(bridge);
            return;
        }
        if (appliedEffect != null && !containsIdentity(effects, appliedEffect)) {
            appliedEffect = null;
            effectPlayer = null;
        }
        if (appliedEffect == null && bridge.hasEffect("NIGHT_VISION")) return;
        Object nightVision = bridge.staticField(ClassType.MOB_EFFECTS, "NIGHT_VISION");
        Object instance = nightVision == null ? null
                : bridge.construct(ClassType.MOB_EFFECT_INSTANCE, nightVision, 100, 0, false, false);
        if (instance == null && nightVision != null) {
            Object id = bridge.readField(nightVision, ClassType.MOB_EFFECT, "id");
            if (id instanceof Number) instance = bridge.construct(ClassType.MOB_EFFECT_INSTANCE,
                    ((Number) id).intValue(), 100, 0, false, false);
        }
        if (instance == null || !bridge.callMappedStatus(player, ClassType.LIVING_ENTITY, "addEffect", instance)) {
            removeOwnEffect(bridge);
            fallback(bridge);
            return;
        }
        if (appliedEffect == null) {
            effectPlayer = player;
            appliedEffect = instance;
        }
    }

    private static boolean containsIdentity(Object effects, Object expected) {
        Iterable<?> values = effects instanceof Map ? ((Map<?, ?>) effects).values() : (Iterable<?>) effects;
        for (Object effect : values) if (effect == expected) return true;
        return false;
    }

    private void fallback(GameBridge bridge) {
        potionFallback = true;
        if (!warned) {
            warned = true;
            bridge.localMessage("Fullbright：夜视不可用，已切换为 Gamma。");
        }
        applyGamma(bridge);
    }

    private void applyGamma(GameBridge bridge) {
        if (gammaApplied) return;
        Object options = bridge.readField(bridge.minecraft(), ClassType.MINECRAFT, "options");
        Object field = bridge.readField(options, ClassType.OPTIONS, "gamma");
        Object value = field instanceof Number ? field : bridge.callMapped(field, ClassType.OPTION_INSTANCE, "get");
        if (!(value instanceof Number)) return;
        Object bright;
        if (value instanceof Float) bright = Float.valueOf(10f);
        else bright = Double.valueOf(10d);
        if (bridge.writeOption("gamma", bright)) {
            originalGamma = value;
            gammaApplied = true;
        }
    }

    private void restoreGamma(GameBridge bridge) {
        if (gammaApplied && bridge.writeOption("gamma", originalGamma)) {
            gammaApplied = false;
            originalGamma = null;
        }
    }

    private void removeOwnEffect(GameBridge bridge) {
        Object player = effectPlayer;
        Object instance = appliedEffect;
        effectPlayer = null;
        appliedEffect = null;
        if (player == null || instance == null || player != bridge.player()) return;
        Object effects = bridge.activeEffects();
        if (!(effects instanceof Iterable) && !(effects instanceof Map)) return;
        if (!containsIdentity(effects, instance)) return;
        Object effect = bridge.staticField(ClassType.MOB_EFFECTS, "NIGHT_VISION");
        if (effect == null) return;
        if (!bridge.callMappedStatus(player, ClassType.LIVING_ENTITY, "removeEffect", effect)) {
            Object id = bridge.readField(effect, ClassType.MOB_EFFECT, "id");
            if (id instanceof Number) bridge.callMappedStatus(player, ClassType.LIVING_ENTITY,
                    "removeEffect", ((Number) id).intValue());
        }
    }

    @Override protected void onStop() {
        GameBridge bridge = bridge();
        if (bridge == null) return;
        removeOwnEffect(bridge);
        restoreGamma(bridge);
        potionFallback = false;
    }
}
