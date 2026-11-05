package dev.nocturne.client.module.modules.render.hud;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.HudModule;

import java.util.List;
import java.util.function.Supplier;

/** 显示当前侧边栏计分板标题与分数。 */
public final class ScoreboardModule extends HudModule {
    private final Supplier<String> line = new Supplier<String>() {
        @Override
        public String get() {
            NocturneClient client = NocturneClient.get();
            GameBridge bridge = client == null ? null : client.gameBridge();
            List<String> lines = bridge == null ? java.util.Collections.<String>emptyList()
                    : bridge.scoreboardLines();
            return lines.isEmpty() ? "" : String.join("\n", lines);
        }
    };

    @Override public String name() { return "Scoreboard"; }
    @Override public Category category() { return Category.RENDER; }
    @Override protected String hudId() { return "scoreboard"; }
    @Override protected Supplier<String> hudText() { return line; }
}
