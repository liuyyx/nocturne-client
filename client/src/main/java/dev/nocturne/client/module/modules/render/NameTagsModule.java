package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.control.Friends;
import dev.nocturne.client.event.WorldChangeEvent;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.client.render.RenderSupport;
import dev.nocturne.client.render.WorldOverlay;
import dev.nocturne.client.render.WorldProjection;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.ColorValue;
import dev.nocturne.client.value.NumberValue;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

/**
 * 玩家名牌覆盖层；名称、血量或状态变化时才更新文字，绘制期复用投影点。
 * 上游降级：删除图标字体、模糊与投影阴影；原版名牌不被隐藏，标签上移避免重叠。
 */
public final class NameTagsModule extends GameModule implements WorldOverlay, RenderSupport.EntityVisitor {
    private final NumberValue range = add(new NumberValue("Range", 128, 8, 256, 1));
    private final NumberValue scale = add(new NumberValue("Scale", .75, .4, 1.4, .05));
    private final NumberValue heightOffset = add(new NumberValue("Height Offset", .5, 0, 1.5, .05));
    private final BooleanValue showSelf = add(new BooleanValue("Show Self", false));
    private final BooleanValue friendStatus = add(new BooleanValue("Friend Status", true));
    private final BooleanValue teamStatus = add(new BooleanValue("Team Status", true));
    private final BooleanValue showHealth = add(new BooleanValue("Show Health", true));
    private final BooleanValue outline = add(new BooleanValue("Outline", true));
    private final ColorValue nameColor = add(new ColorValue("Name Color", 0xF5F7F9FC));
    private final ColorValue friendColor = add(new ColorValue("Friend Color", 0xF569E5A6));
    private final ColorValue teamColor = add(new ColorValue("Team Color", 0xF574D296));
    private final ColorValue healthColor = add(new ColorValue("Health Color", 0xF5D7DCE4));
    private final ColorValue backgroundColor = add(new ColorValue("Background Color", 0xA50D1014));
    private final ColorValue outlineColor = add(new ColorValue("Outline Color", 0x1CFFFFFF));
    private final Map<Object, Tag> cache = new IdentityHashMap<Object, Tag>();
    private final List<Tag> visible = new ArrayList<Tag>();
    private final double[] selfPosition = new double[3];
    private final double[] position = new double[3];
    private final WorldProjection.ScreenPoint point = new WorldProjection.ScreenPoint();
    private GameBridge tickBridge;
    private int epoch;

    public NameTagsModule() { super("Name Tags", Category.RENDER); }

    @Override
    protected void onStart() { listen(WorldChangeEvent.class, event -> clear()); }

    @Override
    protected void onStop() { clear(); }

    private void clear() { cache.clear(); visible.clear(); }

    @Override
    public void onTick() {
        visible.clear();
        tickBridge = bridge();
        if (tickBridge == null || !tickBridge.entityPosition(tickBridge.player(), selfPosition)) return;
        epoch++;
        RenderSupport.forEachEntity(tickBridge, this);
        if (cache.size() != visible.size()) {
            java.util.Iterator<Tag> iterator = cache.values().iterator();
            while (iterator.hasNext()) if (iterator.next().seen != epoch) iterator.remove();
        }
    }

    @Override
    public void visit(Object entity) {
        GameBridge bridge = tickBridge;
        if (!bridge.isInstance(entity, ClassType.PLAYER)
                || (!showSelf.get() && RenderSupport.isSelf(bridge, entity))
                || !bridge.entityPosition(entity, position)) return;
        double dx = position[0] - selfPosition[0], dy = position[1] - selfPosition[1];
        double dz = position[2] - selfPosition[2];
        if (dx * dx + dy * dy + dz * dz > range.get() * range.get()) return;
        Tag tag = cache.get(entity);
        if (tag == null) {
            tag = new Tag();
            cache.put(entity, tag);
        }
        String name = bridge.entityName(entity);
        boolean friend = friendStatus.get() && Friends.isFriend(bridge, entity);
        boolean team = teamStatus.get() && bridge.isAllied(bridge.player(), entity);
        int flags = (friend ? 1 : 0) | (team ? 2 : 0) | (showHealth.get() ? 4 : 0);
        int health = Math.round(bridge.health(entity) * 10f);
        if (!name.equals(tag.sourceName) || flags != tag.flags) {
            tag.sourceName = name;
            tag.flags = flags;
            tag.name = name + (friend ? " [F]" : "") + (team ? " [T]" : "");
            tag.health = Integer.MIN_VALUE;
        }
        if (tag.health != health) {
            tag.health = health;
            tag.healthText = showHealth.get() ? " ❤ " + (health / 10) + "." + Math.abs(health % 10) : "";
        }
        tag.x = position[0]; tag.y = position[1]; tag.z = position[2];
        tag.height = bridge.entityHeight(entity);
        tag.seen = epoch;
        visible.add(tag);
    }

    @Override
    public void drawWorldOverlay(OverlayDraw draw, WorldProjection projection) {
        float size = 8f * scale.asFloat();
        for (int i = 0; i < visible.size(); i++) {
            Tag tag = visible.get(i);
            projection.project(tag.x, tag.y + tag.height + heightOffset.get() + .35, tag.z, point);
            // project 会把近平面附近的点钳到最多两倍视口；单点名牌不能因此钉在屏幕边缘。
            if (!point.visible || point.x <= 0f || point.y <= 0f
                    || point.x >= draw.width() || point.y >= draw.height()) continue;
            float nameWidth = draw.textWidth(tag.name, size);
            float width = nameWidth + draw.textWidth(tag.healthText, size) + 6f;
            float height = draw.textHeight(size) + 4f;
            float x = point.x - width * .5f, y = point.y - height;
            draw.roundedRect(x, y, width, height, 2f, backgroundColor.argb());
            if (outline.get()) draw.outline(x, y, width, height, 1f, outlineColor.argb());
            int color = (tag.flags & 1) != 0 ? friendColor.argb()
                    : (tag.flags & 2) != 0 ? teamColor.argb() : nameColor.argb();
            draw.text(tag.name, x + 3f, y + 2f, size, color);
            draw.text(tag.healthText, x + 3f + nameWidth, y + 2f, size, healthColor.argb());
        }
    }

    private static final class Tag {
        private String sourceName = "";
        private String name = "";
        private String healthText = "";
        private double x, y, z, height;
        private int flags = -1;
        private int seen;
        private int health = Integer.MIN_VALUE;
    }
}
