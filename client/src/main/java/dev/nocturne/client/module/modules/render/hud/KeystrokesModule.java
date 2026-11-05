package dev.nocturne.client.module.modules.render.hud;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.event.EventBus;
import dev.nocturne.client.event.KeyEvent;
import dev.nocturne.client.event.MouseButtonEvent;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.HudModule;
import dev.nocturne.client.value.BooleanValue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** 显示 WASD/空格与左右键状态，并记录真实输入 CPS。 */
public final class KeystrokesModule extends HudModule {
    private final BooleanValue showSpacebar = add(new BooleanValue("Show Spacebar", true));
    private final BooleanValue showCpsOnly = add(new BooleanValue("Show CPS Only", false));
    private final boolean[] keys = new boolean[512];
    private final long[] leftClicks = new long[64];
    private final long[] rightClicks = new long[64];
    private int leftAt;
    private int rightAt;
    private final List<EventBus.Subscription> subscriptions = new ArrayList<EventBus.Subscription>();
    private final Supplier<String> line = new Supplier<String>() {
        @Override
        public String get() {
            long now = System.currentTimeMillis();
            int left = 0;
            int right = 0;
            for (long time : leftClicks) if (now - time <= 1000L) left++;
            for (long time : rightClicks) if (now - time <= 1000L) right++;
            if (showCpsOnly.get()) {
                return left + " / " + right + " CPS";
            }
            String text = (keys[87] ? "W" : "w") + " " + (keys[65] ? "A" : "a") + " "
                    + (keys[83] ? "S" : "s") + " " + (keys[68] ? "D" : "d")
                    + "  " + left + "/" + right + " CPS";
            return showSpacebar.get() ? text + (keys[32] ? "  [SPACE]" : "  [space]") : text;
        }
    };

    @Override
    protected void onHudStart() {
        NocturneClient client = NocturneClient.get();
        if (client == null) {
            return;
        }
        subscriptions.add(client.events().subscribe(KeyEvent.class, event -> {
            if (event.keyCode >= 0 && event.keyCode < keys.length) {
                keys[event.keyCode] = event.pressed;
            }
        }));
        subscriptions.add(client.events().subscribe(MouseButtonEvent.class, event -> {
            if (!event.pressed) return;
            long now = System.currentTimeMillis();
            if (event.button == 0) leftClicks[leftAt++ & 63] = now;
            if (event.button == 1) rightClicks[rightAt++ & 63] = now;
        }));
    }

    @Override
    protected void onHudStop() {
        NocturneClient client = NocturneClient.get();
        if (client != null) for (EventBus.Subscription subscription : subscriptions) {
            client.events().unsubscribe(subscription);
        }
        subscriptions.clear();
    }

    @Override public String name() { return "Keystrokes"; }
    @Override public Category category() { return Category.RENDER; }
    @Override protected String hudId() { return "keystrokes"; }
    @Override protected Supplier<String> hudText() { return line; }
}
