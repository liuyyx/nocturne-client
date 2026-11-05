package dev.nocturne.client.module.modules.render.hud;

import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.HudModule;

import java.util.function.Supplier;

/** 以 HUD 供应器的真实帧调用频率统计 FPS，每秒更新一次文本。 */
public final class FpsDisplayModule extends HudModule {
    private long windowStarted;
    private int frames;
    private int fps;
    private final Supplier<String> line = new Supplier<String>() {
        @Override
        public String get() {
            long now = System.currentTimeMillis();
            if (windowStarted == 0L) {
                windowStarted = now;
            }
            frames++;
            long elapsed = now - windowStarted;
            if (elapsed >= 1000L) {
                fps = (int) Math.round(frames * 1000.0 / elapsed);
                frames = 0;
                windowStarted = now;
            }
            return "FPS " + fps;
        }
    };

    @Override
    public String name() {
        return "FPS";
    }

    @Override
    public Category category() {
        return Category.RENDER;
    }

    @Override
    protected String hudId() {
        return "fps";
    }

    @Override
    protected Supplier<String> hudText() {
        return line;
    }
}
