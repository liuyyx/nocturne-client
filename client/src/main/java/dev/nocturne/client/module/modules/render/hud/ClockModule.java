package dev.nocturne.client.module.modules.render.hud;

import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.HudModule;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.ModeValue;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.function.Supplier;

/** 实时时钟 HUD；文本后端用数字时钟表达 Analog 模式的同一实时语义。 */
public final class ClockModule extends HudModule {
    private final ModeValue clockType = add(new ModeValue("Clock Type", "Analog", "Analog", "Digital"));
    private final BooleanValue showDate = add(new BooleanValue("Show date", true));
    private final BooleanValue use24Hour = add(new BooleanValue("24 Hour Time", false));
    private final Date now = new Date();
    private final SimpleDateFormat formatter = new SimpleDateFormat("hh:mm:ss a", Locale.ROOT);
    private String pattern = "hh:mm:ss a";
    private final Supplier<String> line = new Supplier<String>() {
        @Override
        public String get() {
            String wanted = (use24Hour.get() ? "HH:mm:ss" : "hh:mm:ss a");
            if (clockType.is("Digital") && showDate.get()) {
                wanted = "yyyy-MM-dd " + wanted;
            }
            if (!wanted.equals(pattern)) {
                formatter.applyPattern(wanted);
                pattern = wanted;
            }
            now.setTime(System.currentTimeMillis());
            return formatter.format(now);
        }
    };

    @Override
    public String name() {
        return "Clock";
    }

    @Override
    public Category category() {
        return Category.RENDER;
    }

    @Override
    protected String hudId() {
        return "clock";
    }

    @Override
    protected Supplier<String> hudText() {
        return line;
    }
}
