package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.render.RenderSupport;

/**
 * 掉落物落地时施加水平运动阻尼，低速轴归零。
 * 上游降级：这是客户端实体运动变化，不是上游的物品渲染姿态；服务端同步可能覆盖结果。
 */
public final class ItemPhysicsModule extends GameModule implements RenderSupport.EntityVisitor {
    private GameBridge tickBridge;
    private boolean warned;

    public ItemPhysicsModule() {
        super("Item Physics", Category.RENDER);
    }

    @Override
    protected void onStop() {
        tickBridge = null;
        warned = false;
    }

    @Override
    public void onTick() {
        tickBridge = bridge();
        if (tickBridge != null) RenderSupport.forEachEntity(tickBridge, this);
    }

    @Override
    public void visit(Object entity) {
        GameBridge bridge = tickBridge;
        if (bridge == null || !bridge.isInstance(entity, ClassType.ITEM_ENTITY)) return;
        Object ground = bridge.readField(entity, ClassType.ENTITY, "onGround");
        if (!(ground instanceof Boolean)) { unavailable(); return; }
        if (!((Boolean) ground)) return;

        Object delta = bridge.callMapped(entity, ClassType.ENTITY, "getDeltaMovement");
        Object x = bridge.readField(delta, ClassType.VEC3, "x");
        Object y = bridge.readField(delta, ClassType.VEC3, "y");
        Object z = bridge.readField(delta, ClassType.VEC3, "z");
        if (x instanceof Number && y instanceof Number && z instanceof Number) {
            double dx = damp(((Number) x).doubleValue());
            double dz = damp(((Number) z).doubleValue());
            Object next = bridge.construct(ClassType.VEC3, dx, ((Number) y).doubleValue(), dz);
            if (next != null && bridge.callMappedStatus(entity, ClassType.ENTITY,
                    "setDeltaMovement", next)) return;
        }

        Object oldX = bridge.readField(entity, ClassType.ENTITY, "motionX");
        Object oldZ = bridge.readField(entity, ClassType.ENTITY, "motionZ");
        if (!(oldX instanceof Number) || !(oldZ instanceof Number)) { unavailable(); return; }
        boolean wroteX = bridge.writeField(entity, ClassType.ENTITY, "motionX", damp(((Number) oldX).doubleValue()));
        boolean wroteZ = bridge.writeField(entity, ClassType.ENTITY, "motionZ", damp(((Number) oldZ).doubleValue()));
        if (!wroteX || !wroteZ) unavailable();
    }

    private static double damp(double velocity) {
        double result = velocity * .6;
        return Math.abs(result) < .005 ? 0.0 : result;
    }

    private void unavailable() {
        if (warned) return;
        warned = true;
        System.out.println("[nocturne] Item Physics: item movement unavailable on this version (ground or velocity mapping missing)");
    }
}
