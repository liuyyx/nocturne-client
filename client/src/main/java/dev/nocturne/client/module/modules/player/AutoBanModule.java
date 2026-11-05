package dev.nocturne.client.module.modules.player;

import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.module.modules.EpsilonSupport;
import dev.nocturne.client.util.LineStore;
import dev.nocturne.client.value.ModeValue;
import dev.nocturne.client.value.NumberValue;

import java.util.ArrayList;
import java.util.List;

/** AutoBan：倒计时后按顺序或随机发送 autoban 列表条目。 */
public final class AutoBanModule extends GameModule {
    private final ModeValue order = add(new ModeValue("Order", "SEQUENTIAL", "SEQUENTIAL", "RANDOM"));
    private final NumberValue delay = add(new NumberValue("Delay", 1000, 100, 10000, 100));
    private List<String> lines = new ArrayList<String>();
    private int index;
    private int countdown;
    private long next;

    public AutoBanModule() {
        super("Auto Ban", Category.PLAYER);
    }

    @Override
    protected void onStart() {
        lines = LineStore.load("epsilon/autoban.txt", "");
        index = 0;
        countdown = 60;
        next = 0L;
    }

    @Override
    public void onTick() {
        if (countdown > 0) {
            countdown--;
            if (countdown % 20 == 0) {
                System.out.println("[nocturne] AutoBan starts in " + countdown / 20);
            }
            return;
        }
        long now = System.currentTimeMillis();
        if (now < next || lines.isEmpty()) {
            return;
        }
        String text = order.is("RANDOM") ? lines.get(EpsilonSupport.RANDOM.nextInt(lines.size()))
                : lines.get(index++ % lines.size());
        actions().sendChat(text);
        next = now + delay.asInt();
    }
}
