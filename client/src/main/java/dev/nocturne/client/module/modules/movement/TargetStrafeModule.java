package dev.nocturne.client.module.modules.movement;

import dev.nocturne.client.control.TargetManager;
import dev.nocturne.client.event.MoveEvent;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.module.modules.EpsilonSupport;
import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.client.render.WorldOverlay;
import dev.nocturne.client.render.WorldProjection;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.NumberValue;

/** TargetStrafe：围绕当前最近目标做切向移动，并可显示环绕半径。 */
public final class TargetStrafeModule extends GameModule implements WorldOverlay {
    private final NumberValue radius = add(new NumberValue("Radius", 1.0, 0.5, 6.0, 0.1));
    private final NumberValue points = add(new NumberValue("Points", 6.0, 3.0, 32.0, 1.0));
    private final BooleanValue requireJump = add(new BooleanValue("Require Jump", true));
    private final BooleanValue speedOnly = add(new BooleanValue("Speed Only", true));
    private final BooleanValue drawRadius = add(new BooleanValue("Draw Radius", true));
    private TargetManager.Target target;

    public TargetStrafeModule() {
        super("TargetStrafe", Category.MOVEMENT);
    }

    @Override
    public void onTick() {
        target = client().targets().find(client(), EpsilonSupport.targets(8.0, true, true, true,
                TargetManager.Sort.DISTANCE));
        if (requireJump.get() && actions() != null && state().moving()) {
            actions().setJumping(true);
        }
    }

    @Override
    protected void onStart() {
        listen(MoveEvent.class, event -> {
            if (target == null || speedOnly.get() && !EpsilonSupport.enabled("Speed")) {
                return;
            }
            double dx = state().x() - target.x();
            double dz = state().z() - target.z();
            double length = Math.hypot(dx, dz);
            if (length < 0.001) {
                return;
            }
            double speed = Math.hypot(event.x, event.z);
            event.x = -dz / length * speed + dx / length * (radius.get() - length) * 0.1;
            event.z = dx / length * speed + dz / length * (radius.get() - length) * 0.1;
        });
    }

    @Override
    public void drawWorldOverlay(OverlayDraw draw, WorldProjection projection) {
        if (!drawRadius.get() || target == null) {
            return;
        }
        int count = points.asInt();
        WorldProjection.ScreenPoint previous = null;
        for (int i = 0; i <= count; i++) {
            double angle = Math.PI * 2.0 * i / count;
            WorldProjection.ScreenPoint point = projection.project(target.x() + Math.cos(angle) * radius.get(),
                    target.y(), target.z() + Math.sin(angle) * radius.get());
            if (previous != null && previous.visible && point.visible) {
                draw.line(previous.x, previous.y, point.x, point.y, 1.0f, 0xFF85D2FF);
            }
            previous = point;
        }
    }
}
