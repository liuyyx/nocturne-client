package dev.nocturne.client.module.modules.movement;

import dev.nocturne.client.event.MoveEvent;
import dev.nocturne.client.event.MovementInputEvent;
import dev.nocturne.client.event.PacketEvent;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.ModeValue;

/** Epsilon NoSlowdown：Vanilla/GrimBlink/Grim1_2/Grim1_3 的物品与蛛网减速处理。 */
public final class NoSlowdownModule extends GameModule {
    private final ModeValue mode = add(new ModeValue("Mode", "Vanilla", "Vanilla", "GrimBlink", "Grim1_2", "Grim1_3"));
    private final BooleanValue food = add(new BooleanValue("Food", true));
    private final BooleanValue bow = add(new BooleanValue("Bow", true));
    private final BooleanValue crossbow = add(new BooleanValue("Crossbow", true));
    private final BooleanValue cobweb = add(new BooleanValue("Cobweb", true));
    private boolean holding;

    public NoSlowdownModule() {
        super("No Slowdown", Category.MOVEMENT);
    }

    @Override
    protected void onStart() {
        listen(MovementInputEvent.class, event -> {
            if (!activeForHeld()) {
                return;
            }
            if (event.forward != 0.0f) {
                event.forward = Math.signum(event.forward);
            }
            if (event.strafe != 0.0f) {
                event.strafe = Math.signum(event.strafe);
            }
        });
        listen(MoveEvent.class, event -> {
            if (activeForHeld()) {
                double length = Math.hypot(event.x, event.z);
                if (length > 1.0e-5 && length <= 0.21) {
                    event.x *= 5.0;
                    event.z *= 5.0;
                }
            }
            if (cobweb.get() && mode.is("Vanilla") && inCobweb()) {
                event.x *= 5.0;
                event.z *= 5.0;
                event.y *= 5.0;
            }
        });
        listen(PacketEvent.class, event -> {
            if (holding && event.direction == PacketEvent.Direction.SEND) {
                event.cancelled = true;
            }
        });
    }

    @Override
    public void onTick() {
        boolean blink = mode.is("GrimBlink") && bridge().usingItem(bridge().player()) && activeForHeld();
        if (blink && !holding) {
            holding = client().packets().hold(PacketEvent.Direction.SEND);
        } else if (!blink && holding) {
            holding = false;
            client().packets().release(PacketEvent.Direction.SEND);
        }
    }

    private boolean activeForHeld() {
        if (!bridge().usingItem(bridge().player())) {
            return false;
        }
        String id = bridge().itemDescription(bridge().usingItemStack(bridge().player()));
        return food.get() && id.matches(".*(apple|bread|stew|soup|porkchop|beef|chicken|mutton|fish|cookie|melon|carrot|potato|berries|honey).*")
                || bow.get() && id.contains("bow") && !id.contains("crossbow")
                || crossbow.get() && id.contains("crossbow") || mode.is("GrimBlink") && food.get();
    }

    private boolean inCobweb() {
        return bridge().blockDescription(bridge().blockStateAt((int) Math.floor(state().x()),
                (int) Math.floor(state().y()), (int) Math.floor(state().z()))).contains("cobweb");
    }

    @Override
    protected void onStop() {
        if (holding) {
            client().packets().release(PacketEvent.Direction.SEND);
        }
        holding = false;
    }
}
