package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.value.ModeValue;

/**
 * 提高 gamma 或添加夜视；已有夜视由游戏保管，不覆盖也不移除。
 * 效果实例无法辨认、构造或写入时改用 gamma，并提示一次。
 */
public final class FullbrightModule extends GameModule {
    private final ModeValue mode = add(new ModeValue("Mode", "Gamma", "Gamma", "Potion"));
    private Object originalGamma;
    private boolean gammaApplied;
    private Object effectPlayer;
    /** 是否由本模块加过夜视；玩家自带夜视不覆盖、也不移除。 */
    private boolean weApplied;
    private boolean potionFallback;
    private boolean warned;

    public FullbrightModule() { super("Fullbright", Category.RENDER); }

    @Override protected void onStart() {
        potionFallback = false;
        warned = false;
        weApplied = false;
        effectPlayer = null;
        update();
    }

    @Override public void onTick() { update(); }

    private void update() {
        GameBridge bridge = bridge();
        if (bridge == null) return;
        if (!"Potion".equals(mode.get())) {
            cleanup(bridge);
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
            // 换了玩家实体：旧实体已不可达，夜视随其消失；只重置标记，不对新玩家误移除。
            weApplied = false;
            effectPlayer = null;
        }
        // 本模块加过就每 tick 续期；玩家自带（weApplied=false）且有夜视时不碰。
        if (!weApplied && bridge.hasEffect("NIGHT_VISION")) return;
        Object nightVision = bridge.staticField(ClassType.MOB_EFFECTS, "NIGHT_VISION");
        Object instance = nightVision == null ? null
                : bridge.construct(ClassType.MOB_EFFECT_INSTANCE, nightVision, 100, 0, false, false);
        if (instance == null && nightVision != null) {
            Object id = bridge.readField(nightVision, ClassType.MOB_EFFECT, "id");
            if (id instanceof Number) instance = bridge.construct(ClassType.MOB_EFFECT_INSTANCE,
                    ((Number) id).intValue(), 100, 0, false, false);
        }
        if (instance == null || !bridge.callMappedStatus(player, ClassType.LIVING_ENTITY, "addEffect", instance)) {
            cleanup(bridge);
            fallback(bridge);
            return;
        }
        effectPlayer = player;
        weApplied = true;
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

    /** 停用 / 降级时移除本模块加过的夜视；玩家自带夜视（weApplied=false）不动。 */
    private void cleanup(GameBridge bridge) {
        boolean applied = weApplied;
        weApplied = false;
        effectPlayer = null;
        Object player = applied ? bridge.player() : null;
        if (player == null) return;
        Object effect = bridge.staticField(ClassType.MOB_EFFECTS, "NIGHT_VISION");
        if (effect == null) return;
        // 走映射表描述符精确调用，不用反射遍历 getMethods() 的宽匹配。
        if (!bridge.callMappedStatus(player, ClassType.LIVING_ENTITY, "removeEffect", effect)) {
            Object id = bridge.readField(effect, ClassType.MOB_EFFECT, "id");
            if (id instanceof Number) bridge.callMappedStatus(player, ClassType.LIVING_ENTITY,
                    "removeEffect", ((Number) id).intValue());
        }
    }

    @Override protected void onStop() {
        GameBridge bridge = bridge();
        if (bridge == null) return;
        cleanup(bridge);
        restoreGamma(bridge);
        potionFallback = false;
    }
}
