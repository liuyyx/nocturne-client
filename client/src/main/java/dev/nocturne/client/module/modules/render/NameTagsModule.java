package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.control.Friends;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.client.render.WorldOverlay;
import dev.nocturne.client.render.WorldProjection;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.ColorValue;
import dev.nocturne.client.value.NumberValue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Epsilon NameTags：距离/缩放/高度、好友/队伍/血量分段、背景描边和阴影。 */
public final class NameTagsModule extends GameModule implements WorldOverlay {
    private final NumberValue range = add(new NumberValue("Range", 128, 8, 256, 1));
    private final NumberValue scale = add(new NumberValue("Scale", 0.75, 0.4, 1.4, 0.05));
    private final NumberValue heightOffset = add(new NumberValue("Height Offset", 0.5, 0, 1.5, 0.1));
    private final BooleanValue showSelf = add(new BooleanValue("Show Self", false));
    private final BooleanValue friendStatus = add(new BooleanValue("Friend Status", true));
    private final BooleanValue teamStatus = add(new BooleanValue("Team Status", true));
    private final BooleanValue showHealth = add(new BooleanValue("Show Health", true));
    private final BooleanValue outline = add(new BooleanValue("Outline", true));
    private final ColorValue nameColor = add(new ColorValue("Name Color", 0xF5F7F9FC));
    private final ColorValue friendColor = add(new ColorValue("Friend Color", 0xF569E5A6));
    private final ColorValue teamColor = add(new ColorValue("Team Color", 0xF574D296));
    private final ColorValue healthColor = add(new ColorValue("Health Color", 0xF5D7DCE4));
    private final ColorValue heartColor = add(new ColorValue("Heart Color", 0xFAFF5B69));
    private final ColorValue backgroundColor = add(new ColorValue("Background Color", 0xA50D1014));
    private final ColorValue outlineColor = add(new ColorValue("Outline Color", 0x1CFFFFFF));
    private final BooleanValue backgroundBlur = add(new BooleanValue("Background Blur", true));
    private final NumberValue blurStrength = add(new NumberValue("Blur Strength", 6, 1, 16, 0.5));
    private final BooleanValue dropShadow = add(new BooleanValue("Drop Shadow", true));
    private final NumberValue shadowBlur = add(new NumberValue("Shadow Blur", 8, 2, 24, 1));
    private final ColorValue shadowColor = add(new ColorValue("Shadow Color", 0x87000000));

    public NameTagsModule() {
        super("NameTags", Category.RENDER);
    }

    @Override
    public void drawWorldOverlay(OverlayDraw draw, WorldProjection projection) {
        if (NoRenderModule.noNametags()) {
            return;
        }
        List<Tag> tags = new ArrayList<Tag>();
        RenderSupport.forEachEntity(bridge(), entity -> {
            if (!bridge().isInstance(entity, ClassType.PLAYER) || entity == bridge().player() && !showSelf.get()) {
                return;
            }
            double distance = bridge().distance(bridge().player(), entity);
            if (distance > range.get()) {
                return;
            }
            double[] pos = bridge().entityPosition(entity);
            if (pos != null) {
                tags.add(new Tag(entity, pos, distance));
            }
        });
        tags.sort(Comparator.comparingDouble(tag -> -tag.distance));
        float size = 12.0f * scale.asFloat();
        for (Tag tag : tags) {
            WorldProjection.ScreenPoint point = projection.project(tag.pos[0],
                    tag.pos[1] + bridge().entityHeight(tag.entity) + heightOffset.get(), tag.pos[2]);
            if (!point.visible) {
                continue;
            }
            boolean friend = Friends.isFriend(bridge(), tag.entity);
            boolean team = bridge().isAllied(bridge().player(), tag.entity);
            String prefix = friendStatus.get() && friend ? "★ " : teamStatus.get() && team ? "◆ " : "";
            String name = bridge().entityName(tag.entity);
            // 读不到血量（1.21.5+/26.x 映射缺失或实体状态异常）时不画 ♥ 段，
            // 不能把“未知”折叠成 0 显示成 ♥0.0。
            Float healthValue = showHealth.get() ? bridge().healthOrNull(tag.entity) : null;
            String health = healthValue == null ? "" : " ♥" + String.format(
                    java.util.Locale.ROOT, "%.1f", healthValue.floatValue());
            float width = draw.textWidth(prefix, size) + draw.textWidth(name, size)
                    + draw.textWidth(health, size);
            float x = point.x - width * 0.5f;
            float y = point.y - size;
            if (dropShadow.get()) {
                float offset = shadowBlur.asFloat() * 0.2f;
                draw.roundedRect(x - 4.0f + offset, y - 3.0f + offset, width + 8.0f,
                        size + 6.0f, 3.0f, shadowColor.argb());
            }
            int background = backgroundColor.argb();
            if (backgroundBlur.get()) {
                background = background & 0xFFFFFF | Math.min(background >>> 24, blurStrength.asInt() * 16) << 24;
            }
            draw.roundedRect(x - 4.0f, y - 3.0f, width + 8.0f, size + 6.0f, 3.0f, background);
            if (outline.get()) {
                draw.outline(x - 4.0f, y - 3.0f, width + 8.0f, size + 6.0f, 0.5f, outlineColor.argb());
            }
            draw.text(prefix, x, y, size, friend ? friendColor.argb() : team ? teamColor.argb() : nameColor.argb());
            x += draw.textWidth(prefix, size);
            draw.text(name, x, y, size, nameColor.argb());
            x += draw.textWidth(name, size);
            if (healthValue != null) {
                draw.text(" ♥", x, y, size, heartColor.argb());
                x += draw.textWidth(" ♥", size);
                draw.text(String.format(java.util.Locale.ROOT, "%.1f", healthValue.floatValue()),
                        x, y, size, healthColor.argb());
            }
        }
    }

    private static final class Tag {
        private final Object entity;
        private final double[] pos;
        private final double distance;

        private Tag(Object entity, double[] pos, double distance) {
            this.entity = entity;
            this.pos = pos;
            this.distance = distance;
        }
    }
}
