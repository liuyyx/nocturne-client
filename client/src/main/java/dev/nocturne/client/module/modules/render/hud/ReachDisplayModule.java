package dev.nocturne.client.module.modules.render.hud;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.event.AttackEvent;
import dev.nocturne.client.event.EventBus;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.HudModule;

import java.util.Locale;
import java.util.function.Supplier;

/** 显示最近一次真实攻击目标与玩家脚部中心的距离。 */
public final class ReachDisplayModule extends HudModule {
    private EventBus.Subscription subscription;
    private double distance;
    private final Supplier<String> line = new Supplier<String>() {
        @Override
        public String get() {
            return String.format(Locale.ROOT, "Reach %.2fm", distance);
        }
    };

    @Override
    protected void onHudStart() {
        NocturneClient client = NocturneClient.get();
        if (client == null) {
            return;
        }
        subscription = client.events().subscribe(AttackEvent.class, event -> {
            if (!event.post && client.gameBridge() != null) {
                distance = client.gameBridge().distance(client.gameBridge().player(), event.target);
            }
        });
    }

    @Override
    protected void onHudStop() {
        NocturneClient client = NocturneClient.get();
        if (client != null) client.events().unsubscribe(subscription);
        subscription = null;
    }

    @Override public String name() { return "Reach Display"; }
    @Override public Category category() { return Category.RENDER; }
    @Override protected String hudId() { return "reach-display"; }
    @Override protected Supplier<String> hudText() { return line; }
}
