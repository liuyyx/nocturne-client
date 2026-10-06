package dev.nocturne.ui.skija;

import dev.nocturne.client.hud.HudSink;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * {@link HudSink} 的 Skija 侧实现：把模块发布的文本行按注册顺序保存，每帧拉取一次。
 *
 * <p>为什么需要它：客户端模块不得依赖 UI 模块，因此模块只面向 {@link HudSink} 发布文本；
 * 而「谁来接住这些行」必须由 UI 侧提供。此前没有任何实现，模块的发布全部落到空处
 * （优雅降级，但 HUD 上永远看不到模块文本）。
 *
 * <p>线程契约沿用 {@link HudSink}：所有方法由游戏侧单一线程调用。这里仍加同步，因为它同时被
 * 模块（启停时注册/注销）与渲染（每帧拉取）触碰，而两者的调用时序不由本类决定。
 */
public final class SkijaHudSink implements HudSink {

    /** id → 文本供给器；用 LinkedHashMap 保留注册顺序，HUD 上的行序才稳定。 */
    private final Map<String, Supplier<String>> rows = new LinkedHashMap<String, Supplier<String>>();

    @Override
    public synchronized void add(String id, Supplier<String> text) {
        if (id == null || text == null) {
            return;
        }
        rows.put(id, text);
    }

    @Override
    public synchronized void remove(String id) {
        rows.remove(id);
    }

    @Override
    public synchronized boolean has(String id) {
        return rows.containsKey(id);
    }

    /** @return 当前已注册的行数（诊断用） */
    public synchronized int size() {
        return rows.size();
    }

    /**
     * 拉取本帧的全部行文本。
     *
     * <p>单个供给器抛异常时**跳过该行**而不是中断整帧：一个模块的取值出错不该让 HUD 整体消失。
     * 返回空串的行也跳过——HUD 上留一个空卡片只是噪声。
     *
     * @return 本帧要显示的行文本，顺序为注册顺序
     */
    public synchronized List<String> values() {
        List<String> out = new ArrayList<String>(rows.size());
        for (Map.Entry<String, Supplier<String>> entry : rows.entrySet()) {
            String value;
            try {
                value = entry.getValue().get();
            } catch (Throwable failure) {
                System.out.println("[nocturne] hud row '" + entry.getKey() + "' failed: " + failure);
                continue;
            }
            if (value != null && !value.isEmpty()) {
                out.add(value);
            }
        }
        return out;
    }
}
