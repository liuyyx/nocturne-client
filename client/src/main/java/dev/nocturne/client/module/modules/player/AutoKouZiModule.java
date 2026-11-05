package dev.nocturne.client.module.modules.player;

import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.module.modules.EpsilonSupport;
import dev.nocturne.client.util.LineStore;
import dev.nocturne.client.value.ModeValue;
import dev.nocturne.client.value.NumberValue;
import dev.nocturne.client.value.TextValue;

import java.util.ArrayList;
import java.util.List;

/** AutoKouZi：按 TXT 不重复随机或 SENTENCE 模板发送配置文本。 */
public final class AutoKouZiModule extends GameModule {
    private final ModeValue mode = add(new ModeValue("Mode", "TXT", "TXT", "SENTENCE"));
    private final NumberValue delay = add(new NumberValue("Delay", 1000, 100, 10000, 100));
    private final TextValue customPath = add(new TextValue("Custom Path", "", 512));
    private List<String> lines = new ArrayList<String>();
    private final List<Integer> used = new ArrayList<Integer>();
    private long next;

    public AutoKouZiModule() {
        super("AutoKouZi", Category.PLAYER);
    }

    @Override
    protected void onStart() {
        lines = LineStore.load("epsilon/kouzi.txt", customPath.get());
        used.clear();
        next = 0L;
    }

    @Override
    public void onTick() {
        long now = System.currentTimeMillis();
        if (now < next || lines.isEmpty()) {
            return;
        }
        String text;
        if (mode.is("TXT")) {
            if (used.size() >= lines.size()) {
                used.clear();
            }
            int index;
            do {
                index = EpsilonSupport.RANDOM.nextInt(lines.size());
            } while (used.contains(index));
            used.add(index);
            text = lines.get(index);
        } else {
            text = lines.get(EpsilonSupport.RANDOM.nextInt(lines.size()))
                    + " " + lines.get(EpsilonSupport.RANDOM.nextInt(lines.size()));
        }
        actions().sendChat(text.length() > 256 ? text.substring(0, 256) : text);
        next = now + delay.asInt();
    }

    @Override
    protected void onStop() {
        used.clear();
    }
}
