package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.value.ColorValue;
import dev.nocturne.client.value.ModeValue;
import dev.nocturne.client.value.NumberValue;

/** CustomSky：Cine/Euabe/Local 三种天空色调与滚动速度。 */
public final class CustomSkyModule extends GameModule {
    private final ModeValue shader = add(new ModeValue("Shader", "Cine", "Cine", "Euabe", "Local"));
    private final NumberValue speed = add(new NumberValue("Speed", 4, 0, 20, 0.1));
    private final ColorValue color = add(new ColorValue("Color", 0xC8FFFFFF));
    private final ColorValue background = add(new ColorValue("Background Color (Local)", 0xFF000000));

    public CustomSkyModule() {
        super("CustomSky", Category.RENDER);
    }

    public String shader() {
        return shader.get();
    }

    public float speed() {
        return speed.asFloat();
    }

    public int color() {
        return color.argb();
    }

    public int background() {
        return background.argb();
    }
}
