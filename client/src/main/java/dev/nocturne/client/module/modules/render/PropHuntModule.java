package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.event.PacketEvent;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.client.render.WorldOverlay;
import dev.nocturne.client.render.WorldProjection;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

/** 记录最近十秒的方块更新包，并在世界中标出变化位置。 */
public final class PropHuntModule extends GameModule implements WorldOverlay {
    private final Map<Long, Integer> tracked = new LinkedHashMap<Long, Integer>();

    public PropHuntModule() {
        super("PropHunt", Category.RENDER);
    }

    @Override
    protected void onStart() {
        tracked.clear();
        listen(PacketEvent.class, this::onPacket);
    }

    private void onPacket(PacketEvent event) {
        GameBridge bridge = bridge();
        if (event.direction != PacketEvent.Direction.RECEIVE || bridge == null
                || !bridge.isInstance(event.packet, ClassType.CLIENTBOUND_BLOCK_UPDATE)) {
            return;
        }
        int[] pos = bridge.blockUpdatePosition(event.packet);
        if (pos == null || Math.abs(pos[0]) >= 30000000 || Math.abs(pos[2]) >= 30000000
                || pos[1] < -2048 || pos[1] > 2047) {
            return;
        }
        long key = ((long) pos[0] & 0x3FFFFFF) << 38
                | ((long) pos[2] & 0x3FFFFFF) << 12 | pos[1] & 0xFFF;
        if (tracked.size() >= 1024) {
            Iterator<Long> first = tracked.keySet().iterator();
            if (first.hasNext()) {
                first.next();
                first.remove();
            }
        }
        tracked.put(key, 0);
    }

    @Override
    public void onTick() {
        Iterator<Map.Entry<Long, Integer>> iterator = tracked.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<Long, Integer> entry = iterator.next();
            int age = entry.getValue() + 1;
            if (age >= 200) {
                iterator.remove();
            } else {
                entry.setValue(age);
            }
        }
    }

    @Override
    public void drawWorldOverlay(OverlayDraw draw, WorldProjection projection) {
        for (Long key : tracked.keySet()) {
            int x = (int) (key << 0 >> 38);
            int y = (int) (key << 52 >> 52);
            int z = (int) (key << 26 >> 38);
            RenderSupport.marker(draw, projection, x + 0.5, y + 0.5, z + 0.5, 0xFFFF2020);
        }
    }

    @Override
    protected void onStop() {
        tracked.clear();
    }
}
