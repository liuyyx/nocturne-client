package dev.nocturne.client.module.modules.combat;

import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.module.modules.render.RenderSupport;
import dev.nocturne.client.value.NumberValue;

import java.util.IdentityHashMap;
import java.util.Map;

/** 保存并扩张实体真实 AABB，实体离场或模块停用时恢复。 */
public final class HitBoxesModule extends GameModule {
    private final NumberValue expansion = add(new NumberValue("Expansion", 0.2, 0.0, 2.0, 0.05));
    private final IdentityHashMap<Object, Object> original = new IdentityHashMap<Object, Object>();
    private final IdentityHashMap<Object, Boolean> seen = new IdentityHashMap<Object, Boolean>();

    public HitBoxesModule() {
        super("HitBoxes", Category.COMBAT);
    }

    @Override
    public void onTick() {
        GameBridge bridge = bridge();
        if (bridge == null) {
            return;
        }
        seen.clear();
        RenderSupport.forEachEntity(bridge, entity -> {
            if (entity == bridge.player() || !bridge.isInstance(entity, ClassType.LIVING_ENTITY)) {
                return;
            }
            Object box = bridge.callMapped(entity, ClassType.ENTITY, "getBoundingBox");
            if (box == null) {
                return;
            }
            original.put(entity, box);
            Object expanded = bridge.inflateBox(original.get(entity),
                    expansion.get(), expansion.get(), expansion.get());
            bridge.setEntityBox(entity, expanded);
            seen.put(entity, Boolean.TRUE);
        });
        for (Map.Entry<Object, Object> entry : original.entrySet()) {
            if (!seen.containsKey(entry.getKey())) {
                bridge.setEntityBox(entry.getKey(), entry.getValue());
            }
        }
        original.keySet().retainAll(seen.keySet());
    }

    @Override
    protected void onStop() {
        GameBridge bridge = bridge();
        if (bridge != null) {
            for (Map.Entry<Object, Object> entry : original.entrySet()) {
                bridge.setEntityBox(entry.getKey(), entry.getValue());
            }
        }
        original.clear();
    }
}
