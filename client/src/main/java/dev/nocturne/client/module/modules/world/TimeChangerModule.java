package dev.nocturne.client.module.modules.world;

import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.value.NumberValue;

/**
 * 将客户端世界时间写为配置值。
 *
 * <p>26.3 的 {@code ClientLevel#dayTime} 与 {@code LevelData#setDayTime} 均不存在，
 * {@link GameBridge#setDayTime(long)} 只会返回 {@code false}。这种“该版本没有时间入口”是
 * 能力事实而非临时失败：探测到一次就标记不可用、提示一次并停止每 tick 重试（旧实现在真机上
 * 每 tick 空转一次反射调用）。重新启用模块会重新探测。
 */
public final class TimeChangerModule extends GameModule {
    private final NumberValue time = add(new NumberValue("Time", 6000, 0, 24000, 1000));
    /** 本版本无可用时间入口：一次性提示后本启用周期内不再重试。 */
    private boolean unavailable;

    public TimeChangerModule() {
        super("Time", Category.WORLD);
    }

    @Override
    protected void onStart() {
        unavailable = false;
    }

    @Override
    public void onTick() {
        if (unavailable) {
            return;
        }
        GameBridge bridge = bridge();
        if (bridge == null || bridge.world() == null) {
            // 未进入世界时写不了时间，但这不代表该版本缺入口：等世界就绪再探测。
            return;
        }
        if (bridge.setDayTime(time.get().longValue())) {
            return;
        }
        unavailable = true;
        String message = "该版本无可用时间入口（dayTime/setDayTime 缺席），Time 模块已停用";
        System.out.println("[nocturne] Time: " + message);
        bridge.localMessage(message);
    }
}
