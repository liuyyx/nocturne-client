package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.event.BlockEvent;
import dev.nocturne.client.event.PacketEvent;
import dev.nocturne.client.event.UseItemEvent;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.value.BooleanValue;

import java.util.HashMap;
import java.util.Map;

/** NoGhostBlocks：本地预测破坏/放置，并过滤三秒内的服务端幽灵回滚包。 */
public final class NoGhostBlocksModule extends GameModule {
    private final BooleanValue breaking = add(new BooleanValue("Breaking", true));
    private final BooleanValue placing = add(new BooleanValue("Placing", true));
    private final Map<String, Long> predicted = new HashMap<String, Long>();

    public NoGhostBlocksModule() {
        super("No Ghost Blocks", Category.RENDER);
    }

    @Override
    protected void onStart() {
        listen(BlockEvent.class, event -> {
            if (!breaking.get() || event.phase != BlockEvent.Phase.START) {
                return;
            }
            int[] pos = bridge().blockCoordinates(event.blockPos);
            if (pos != null) {
                mark(pos);
                bridge().callMappedStatus(bridge().world(), ClassType.LEVEL, "playerWillDestroy",
                        bridge().player(), event.blockPos, bridge().blockStateAt(pos[0], pos[1], pos[2]));
            }
        });
        listen(UseItemEvent.class, event -> {
            if (placing.get() && !event.post) {
                int[] pos = bridge().hitBlockPosition(actions().pick(5.0, 1.0f));
                if (pos != null) {
                    mark(pos);
                }
            }
        });
        listen(PacketEvent.class, event -> {
            if (event.direction != PacketEvent.Direction.RECEIVE) {
                return;
            }
            int[] pos = bridge().blockUpdatePosition(event.packet);
            if (pos != null && predicted.containsKey(key(pos))) {
                event.cancelled = true;
                predicted.remove(key(pos));
            }
        });
    }

    private void mark(int[] pos) {
        predicted.put(key(pos), System.currentTimeMillis() + 3000L);
        predicted.entrySet().removeIf(entry -> entry.getValue() < System.currentTimeMillis());
    }

    private static String key(int[] pos) {
        return pos[0] + ":" + pos[1] + ":" + pos[2];
    }

    @Override
    protected void onStop() {
        predicted.clear();
    }
}
