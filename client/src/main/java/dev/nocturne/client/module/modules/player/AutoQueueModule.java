package dev.nocturne.client.module.modules.player;

import dev.nocturne.client.event.PacketEvent;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.util.LineStore;
import dev.nocturne.client.value.ModeValue;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** AutoQueue：识别 XIN_2B2T 排队问题并回复聊天中捕获的玩家前缀。 */
public final class AutoQueueModule extends GameModule {
    private final ModeValue queueMode = add(new ModeValue("Queue Mode", "XIN_2B2T", "XIN_2B2T"));
    private final List<Rule> rules = new ArrayList<Rule>();

    public AutoQueueModule() {
        super("AutoQueue", Category.PLAYER);
    }

    @Override
    protected void onStart() {
        rules.clear();
        for (String line : LineStore.load("epsilon/xinqueue_questions.txt", "")) {
            int split = line.indexOf('|');
            if (split > 0) {
                rules.add(new Rule(line.substring(0, split), Pattern.compile(line.substring(split + 1))));
            }
        }
        listen(PacketEvent.class, event -> {
            if (event.direction != PacketEvent.Direction.RECEIVE
                    || !bridge().isInstance(event.packet, ClassType.CLIENTBOUND_SYSTEM_CHAT)) {
                return;
            }
            String text = bridge().systemChatText(event.packet);
            if (text == null) {
                return;
            }
            String clean = text.replaceAll("<[^>]+>", "");
            for (Rule rule : rules) {
                if (!clean.contains(rule.question)) {
                    continue;
                }
                Matcher matcher = rule.answer.matcher(clean);
                if (matcher.find() && matcher.groupCount() >= 1) {
                    actions().sendChat(matcher.group(1));
                }
                return;
            }
        });
    }

    @Override
    protected void onStop() {
        rules.clear();
    }

    private static final class Rule {
        private final String question;
        private final Pattern answer;

        private Rule(String question, Pattern answer) {
            this.question = question;
            this.answer = answer;
        }
    }
}
