package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;

/** Item Physics：掉落物落地后的水平阻尼与稳定静止。 */
public final class ItemPhysicsModule extends GameModule {
    public ItemPhysicsModule() {
        super("Item Physics", Category.RENDER);
    }

    @Override
    public void onTick() {
        RenderSupport.forEachEntity(bridge(), entity -> {
            if (!bridge().isInstance(entity, ClassType.ITEM_ENTITY)) {
                return;
            }
            Object ground = bridge().readField(entity, ClassType.ENTITY, "onGround");
            if (!Boolean.TRUE.equals(ground)) {
                return;
            }
            double[] velocity = bridge().entityVelocity(entity);
            double x = velocity[0] * 0.6;
            double z = velocity[2] * 0.6;
            if (Math.abs(x) < 0.005) x = 0.0;
            if (Math.abs(z) < 0.005) z = 0.0;
            // 先看映射表：旧版（1.8.9）没有 setDeltaMovement，直接写 motionX/motionZ 字段，
            // 不要每 tick 反射一次去换一条 "no overload"。可用时才构造 Vec3 调 setter。
            boolean hasSetter = !bridge().mapping()
                    .isMemberAbsent(ClassType.ENTITY, "setDeltaMovement");
            Object motion = hasSetter ? bridge().construct(ClassType.VEC3, x, 0.0, z) : null;
            if (motion == null
                    || !bridge().callMappedStatus(entity, ClassType.ENTITY, "setDeltaMovement", motion)) {
                bridge().writeField(entity, ClassType.ENTITY, "motionX", x);
                bridge().writeField(entity, ClassType.ENTITY, "motionZ", z);
            }
        });
    }
}
