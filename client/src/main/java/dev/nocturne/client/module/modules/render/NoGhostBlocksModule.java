package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.event.BlockEvent;
import dev.nocturne.client.event.UseItemEvent;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.value.BooleanValue;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** NoGhostBlocks：本地预测破坏/放置，并过滤三秒内的服务端幽灵回滚包。 */
public final class NoGhostBlocksModule extends GameModule {
    private final BooleanValue breaking = add(new BooleanValue("Breaking", true));
    private final BooleanValue placing = add(new BooleanValue("Placing", true));
    /** Netty 线程上的收包过滤器会读它，故必须并发安全（主线程在这里写入预测位置）。 */
    private final Map<String, Long> predicted = new ConcurrentHashMap<String, Long>();

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
        // 收包过滤必须同步（赶在原版处理前），因此在 Netty 线程求值；实现只读并发映射，
        // 不touch模块事件状态——RECEIVE 事件本身已改为主线程投递。
        client().packets().addReceiveFilter(this, this::dropPredictedUpdate);
    }

    private boolean dropPredictedUpdate(Object packet) {
        if (predicted.isEmpty() || bridge() == null) {
            return false;
        }
        int[] pos = bridge().blockUpdatePosition(packet);
        if (pos == null) {
            return false;
        }
        String key = key(pos);
        Long expires = predicted.remove(key);
        return expires != null && expires.longValue() >= System.currentTimeMillis();
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
        if (client() != null && client().packets() != null) {
            client().packets().removeReceiveFilter(this);
        }
        predicted.clear();
    }
}
