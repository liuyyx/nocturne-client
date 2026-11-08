package dev.nocturne.client.module.modules.combat;

import dev.nocturne.client.control.TargetManager;
import dev.nocturne.client.event.AttackEvent;
import dev.nocturne.client.event.MovementInputEvent;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.module.modules.EpsilonSupport;

/** Criticals：维护合法下落暴击窗口，并在前两 tick 暂停疾跑。 */
public final class CriticalsModule extends GameModule {
    private int fallTicks;
    private boolean stopSprinting;

    public CriticalsModule() {
        super("Criticals", Category.COMBAT);
    }

    @Override
    protected void onStart() {
        // 攻击前置直接退疾跑，避免等下一次移动输入 tick 才生效（那一拍已经打出非暴击）。
        listen(AttackEvent.class, event -> {
            if (shouldSuppressSprint(stopSprinting, event.post) && actions() != null) {
                actions().setSprinting(false);
            }
        });
        listen(MovementInputEvent.class, event -> {
            if (stopSprinting && event.forward > 0.0f) {
                event.sprint = false;
                actions().setSprinting(false);
                stopSprinting = false;
            }
        });
    }

    /** 只在前置攻击、且本 tick 需要退疾跑时写状态；输入事件逻辑保留为兜底。 */
    static boolean shouldSuppressSprint(boolean stopSprinting, boolean post) {
        return stopSprinting && !post;
    }

    @Override
    public void onTick() {
        float distance = bridge().fallDistance(bridge().player());
        boolean valid = distance > 0.0f && !state().onGround() && !bridge().inWater(bridge().player())
                && !Boolean.TRUE.equals(bridge().callMapped(bridge().player(),
                dev.nocturne.client.mapping.ClassType.ENTITY, "isPassenger"));
        fallTicks = !valid || distance >= 1.0f ? 0 : fallTicks + 1;
        TargetManager.Target target = client().targets().find(client(), EpsilonSupport.targets(
                4.0, true, true, true, TargetManager.Sort.DISTANCE));
        stopSprinting = fallTicks > 0 && fallTicks < 3 && state().sprinting() && target != null;
    }

    @Override
    protected void onStop() {
        fallTicks = 0;
        stopSprinting = false;
    }
}
