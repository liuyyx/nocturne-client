package dev.nocturne.client.module.modules.world;

import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.module.modules.render.RenderSupport;
import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.client.render.WorldOverlay;
import dev.nocturne.client.render.WorldProjection;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.NumberValue;
import dev.nocturne.client.value.TextValue;

import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;

/** 显示持可疑武器的玩家，并可按延迟自动播报。 */
public final class MurdererFinderModule extends GameModule implements WorldOverlay {
    private final BooleanValue callout = add(new BooleanValue("Callout", false));
    private final NumberValue delay = add(new NumberValue("Delay", 3100, 0, 5000, 100));
    private final TextValue messages = add(new TextValue("Messages", "%s is the murderer!", 512));
    private final TextValue murdererItems = add(new TextValue(
            "Murderer Items", "swords,shovels,axes,pickaxes", 512));
    private final Map<Integer, String> murderers = new LinkedHashMap<Integer, String>();
    private final ArrayDeque<String> pendingMessages = new ArrayDeque<String>();
    private long nextCallout;

    public MurdererFinderModule() {
        super("MurdererFinder", Category.WORLD);
    }

    @Override
    public void onTick() {
        GameBridge bridge = bridge();
        if (bridge == null) {
            return;
        }
        RenderSupport.forEachEntity(bridge, entity -> {
            if (entity == bridge.player() || !bridge.isInstance(entity, ClassType.PLAYER)) {
                return;
            }
            Object held = bridge.heldItem(entity, false);
            if (!bridge.isEmptyStack(held) && bridge.itemMatches(held, murdererItems.get())) {
                int id = bridge.entityId(entity);
                String name = bridge.entityName(entity);
                if (!murderers.containsKey(id) && callout.get()) {
                    pendingMessages.add(messages.get().replace("%s", name));
                }
                murderers.put(id, name);
            }
        });
        String message = pendingMessages.peek();
        if (message != null && System.currentTimeMillis() >= nextCallout) {
            if (actions().sendChat(message)) {
                pendingMessages.poll();
            }
            nextCallout = System.currentTimeMillis() + delay.asInt();
        }
    }

    @Override
    public void drawWorldOverlay(OverlayDraw draw, WorldProjection projection) {
        if (murderers.isEmpty()) {
            return;
        }
        float y = 15f;
        float x = draw.width() * 0.5f;
        draw.text("Murderer List", x - draw.textWidth("Murderer List") * 0.5f, y, 0xFFFF5050);
        for (String name : murderers.values()) {
            y += 12f;
            draw.text(name, x - draw.textWidth(name) * 0.5f, y, 0xFFFFFFFF);
        }
    }

    @Override
    protected void onStop() {
        murderers.clear();
        pendingMessages.clear();
    }
}
