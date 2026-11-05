package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.value.ColorValue;
import dev.nocturne.client.value.ModeValue;

/** Filter：Shader/LightMap 两种颜色滤镜模式。 */
public final class FilterModule extends GameModule {
    private final ModeValue mode = add(new ModeValue("Mode", "Shader", "Shader", "LightMap"));
    private final ColorValue baseColor = add(new ColorValue("Base Color", 0x32464696));

    public FilterModule() {
        super("Filter", Category.RENDER);
    }

    public String mode() {
        return mode.get();
    }

    public int color() {
        return baseColor.argb();
    }
}
