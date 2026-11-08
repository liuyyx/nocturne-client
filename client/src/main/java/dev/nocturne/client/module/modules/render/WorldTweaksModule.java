package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.client.render.WorldOverlay;
import dev.nocturne.client.render.WorldProjection;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.ColorValue;
import dev.nocturne.client.value.NumberValue;

/** World Tweaks：雾范围/颜色与维度时钟修改。 */
public final class WorldTweaksModule extends GameModule implements WorldOverlay {
    private final BooleanValue fogModify = add(new BooleanValue("Fog Modify", true));
    private final NumberValue fogStart = add(new NumberValue("Fog Start", 0, 0, 256, 1));
    private final NumberValue fogEnd = add(new NumberValue("Fog End", 64, 10, 256, 1));
    private final ColorValue fogColor = add(new ColorValue("Fog Color", 0xFFA900FF));
    private final BooleanValue changeTime = add(new BooleanValue("Change Time", false));
    private final NumberValue time = add(new NumberValue("Time", 21, 0, 23, 1));

    public WorldTweaksModule() {
        super("World Tweaks", Category.RENDER);
    }

    @Override
    public void onTick() {
        if (changeTime.get()) {
            bridge().setDayTime(time.asInt() * 1000L);
        }
    }

    /** 「该后端不支持整帧遮罩」只提示一次，避免每帧刷屏。 */
    private boolean unsupportedLogged;

    /**
     * 雾色整帧遮罩。
     *
     * <p>本模块只做「屏幕空间雾色」这一种表现：一张覆盖整帧的半透明矩形。它必须满足两个硬条件，
     * 否则**不画**——宁可没有雾色，也绝不能把世界涂黑（真机：1.8.9 上雾色遮罩把整个世界变成
     * 黑幕，只剩 HUD 与准星）：
     * <ol>
     *   <li>后端 {@link OverlayDraw#reliableAlphaBlend()} 为 true（固定管线的 alpha 混合实测
     *       会被同帧重复应用、且可能退化成不透明）；</li>
     *   <li>算出来的 alpha 严格大于 0。</li>
     * </ol>
     */
    @Override
    public void drawWorldOverlay(OverlayDraw draw, WorldProjection projection) {
        if (!fogModify.get()) {
            return;
        }
        if (!draw.reliableAlphaBlend()) {
            if (!unsupportedLogged) {
                unsupportedLogged = true;
                System.out.println("[nocturne] World Tweaks: 当前后端（固定管线）不支持可靠的整帧"
                        + " alpha 混合，雾色遮罩已跳过（世界保持可见）");
            }
            return;
        }
        float alpha = Math.min(0.35f, (fogEnd.asFloat() - fogStart.asFloat()) / 256.0f);
        if (!(alpha > 0.0f)) {
            return;
        }
        int color = fogColor.argb() & 0xFFFFFF | (int) (255 * alpha) << 24;
        if ((color >>> 24) == 0) {
            return;
        }
        draw.rect(0, 0, draw.width(), draw.height(), color);
    }
}
