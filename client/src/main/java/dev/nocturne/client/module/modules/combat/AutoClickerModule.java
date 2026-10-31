package dev.nocturne.client.module.modules.combat;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.action.ActionApi;
import dev.nocturne.client.event.EventBus;
import dev.nocturne.client.event.MouseButtonEvent;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.Module;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.ModeValue;
import dev.nocturne.client.value.NumberValue;

import java.util.Random;

/** 按 CPS 触发游戏左键入口的自动点击器。 */
public final class AutoClickerModule extends Module {

    private static final String NORMAL = "Normal";
    private static final String EXTRA = "Extra";
    private static final String EXTRA_PLUS = "Extra+";

    private final NumberValue cps = add(new NumberValue("CPS", 8.0, 1.0, 20.0, 0.1));
    private final ModeValue randomization = add(new ModeValue(
            "Randomization", NORMAL, NORMAL, EXTRA, EXTRA_PLUS));
    private final BooleanValue jitter = add(new BooleanValue("Jitter", false));
    private final BooleanValue holdToClick = add(new BooleanValue("Hold to click", true));
    private final BooleanValue triggerMode = add(new BooleanValue("Trigger mode", false));

    private final Random random = new Random();
    private volatile EventBus.Subscription mouseSubscription;
    private boolean leftHeld;
    private long nextClickNanos;
    private volatile int clickCount;

    @Override
    public String name() {
        return "AutoClicker";
    }

    @Override
    public Category category() {
        return Category.COMBAT;
    }

    @Override
    protected void onEnable() {
        leftHeld = false;
        nextClickNanos = 0L;
        clickCount = 0;
        subscribe();
    }

    @Override
    protected void onDisable() {
        unsubscribe();
        leftHeld = false;
        nextClickNanos = 0L;
    }

    @Override
    public void onTick() {
        subscribeIfNeeded();
        if (holdToClick.get() && !leftHeld) {
            nextClickNanos = 0L;
            return;
        }
        if (triggerMode.get() && !entityUnderCrosshair()) {
            return;
        }
        NocturneClient client = NocturneClient.get();
        ActionApi actions = client == null ? null : client.actions();
        if (actions == null || !actions.isAvailable("click")) {
            return;
        }
        long now = System.nanoTime();
        if (nextClickNanos != 0L && now < nextClickNanos) {
            return;
        }
        if (actions.click()) {
            clickCount++;
            nextClickNanos = now + delayNanos();
        }
    }

    /** @return 本次启用以来成功触发的左键次数（诊断/验收用） */
    public int clickCount() {
        return clickCount;
    }

    private long delayNanos() {
        double base = 1_000_000_000.0 / Math.max(1.0, cps.get());
        if (randomization.is(EXTRA)) {
            base *= 0.85 + random.nextDouble() * 0.3;
        } else if (randomization.is(EXTRA_PLUS)) {
            base *= 0.7 + random.nextDouble() * 0.6;
        }
        if (jitter.get()) {
            base += (random.nextDouble() - 0.5) * 10_000_000.0;
        }
        return Math.max(20_000_000L, (long) base);
    }

    private boolean entityUnderCrosshair() {
        GameBridge bridge = bridge();
        Object minecraft = bridge == null ? null : bridge.minecraft();
        Object hit = minecraft == null ? null
                : bridge.readField(minecraft, ClassType.MINECRAFT, "hitResult");
        return hit != null && bridge.isInstance(hit, ClassType.ENTITY_HIT_RESULT);
    }

    private void subscribeIfNeeded() {
        if (!isEnabled() || mouseSubscription != null) {
            return;
        }
        subscribe();
    }

    private void subscribe() {
        NocturneClient client = NocturneClient.get();
        if (client == null || mouseSubscription != null) {
            return;
        }
        try {
            mouseSubscription = client.events().subscribe(MouseButtonEvent.class, this::onMouse);
        } catch (Throwable t) {
            mouseSubscription = null;
        }
    }

    private void unsubscribe() {
        EventBus.Subscription subscription = mouseSubscription;
        mouseSubscription = null;
        if (subscription == null) {
            return;
        }
        NocturneClient client = NocturneClient.get();
        if (client != null) {
            client.events().unsubscribe(subscription);
        }
    }

    private void onMouse(MouseButtonEvent event) {
        if (event.button == 0) {
            leftHeld = event.pressed;
            if (!event.pressed) {
                nextClickNanos = 0L;
            }
        }
    }

    private static GameBridge bridge() {
        NocturneClient client = NocturneClient.get();
        return client == null ? null : client.gameBridge();
    }
}
