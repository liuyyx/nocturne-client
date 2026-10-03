package dev.noturne.client.module.modules;

import dev.noturne.client.module.Category;
import dev.noturne.client.module.HudModule;

import java.util.function.Supplier;

/** Draws the client name in the corner of the HUD. */
public final class WatermarkModule extends HudModule {

    private final String text;

    public WatermarkModule() {
        this("noturne");
    }

    public WatermarkModule(String text) {
        this.text = text;
    }

    @Override
    public String name() {
        return "Watermark";
    }

    @Override
    public Category category() {
        return Category.RENDER;
    }

    @Override
    protected String hudId() {
        return "watermark";
    }

    @Override
    protected Supplier<String> hudText() {
        return new Supplier<String>() {
            @Override
            public String get() {
                return text;
            }
        };
    }
}
