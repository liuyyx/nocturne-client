package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.client.render.WorldOverlay;
import dev.nocturne.client.render.WorldProjection;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.ColorValue;
import dev.nocturne.client.value.ModeValue;
import dev.nocturne.client.value.NumberValue;

/** Block Highlight：Both/BothSide/Fill/FilledSide/Outline/OutlinedSide 六模式。 */
public final class BlockHighlightModule extends GameModule implements WorldOverlay {
    private final ModeValue mode = add(new ModeValue("Mode", "Outline", "Both", "BothSide", "Fill", "FilledSide", "Outline", "OutlinedSide"));
    private final ColorValue color = add(new ColorValue("Color", 0x64FFFFFF));
    private final ColorValue lineColor = add(new ColorValue("Line Color", 0xFFFFFFFF));
    private final NumberValue lineWidth = add(new NumberValue("Line Width", 2, 0, 5, 0.5));
    private final BooleanValue blur = add(new BooleanValue("Blur", true));
    private final NumberValue blurStrength = add(new NumberValue("Blur Strength", 5, 0, 16, 0.5));

    public BlockHighlightModule() {
        super("Block Highlight", Category.RENDER);
    }

    @Override
    public void drawWorldOverlay(OverlayDraw draw, WorldProjection projection) {
        Object hit = actions().pick(6.0, 1.0f);
        int[] pos = bridge().hitBlockPosition(hit);
        if (pos == null) {
            return;
        }
        String face = String.valueOf(bridge().hitDirection(hit)).toLowerCase(java.util.Locale.ROOT);
        boolean fill = mode.is("Both") || mode.is("BothSide") || mode.is("Fill") || mode.is("FilledSide");
        boolean outline = mode.is("Both") || mode.is("BothSide") || mode.is("Outline") || mode.is("OutlinedSide");
        if (fill) {
            int fillColor = color.argb();
            if (blur.get()) {
                fillColor = fillColor & 0xFFFFFF | Math.min(fillColor >>> 24, blurStrength.asInt() * 6) << 24;
            }
            WorldGeometry.blockFilled(draw, projection, pos[0], pos[1], pos[2], 0.0f, fillColor, 0);
        }
        if (outline) {
            if (mode.is("BothSide") || mode.is("OutlinedSide")) {
                WorldGeometry.side(draw, projection, pos[0], pos[1], pos[2], face,
                        lineWidth.asFloat(), lineColor.argb());
            } else {
                WorldGeometry.block(draw, projection, pos[0], pos[1], pos[2],
                        lineWidth.asFloat(), lineColor.argb());
            }
        }
    }
}
