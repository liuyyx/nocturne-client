package dev.nocturne.ui.skija;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * HUD 各元素的位置（左上角像素坐标）。
 *
 * <p>为什么单独抽一层：绘制与编辑器（拖动）必须读写**同一份**位置——若绘制自带一份默认位置、
 * 编辑器改的是另一份，拖完再打开就"弹回原位"。这里把位置变成可注入的状态，绘制只是读取方。
 *
 * <p>默认值是**懒初始化**的：像"右上角帧率卡"这种位置依赖元素自身宽度与屏幕宽度，只有绘制那一刻
 * 才知道，所以由绘制方在首帧用 {@link #ensureDefault(String, float, float)} 写入。一旦用户拖动过
 * （值已存在），默认值不再覆盖——这正是"拖动结果不会被下一帧冲掉"的保证。
 *
 * <p>线程契约：与 HUD 其它状态一致，只在游戏侧单一线程读写；仍加同步，因为编辑器与渲染可能落在
 * 同一帧的不同阶段。
 */
public final class HudLayout {

    /** id → {x, y}；LinkedHashMap 保留注册顺序，便于诊断与持久化时稳定输出。 */
    private final Map<String, float[]> positions = new LinkedHashMap<String, float[]>();

    /** @return 该元素左上角 x；尚未初始化时返回 {@code NaN}（调用方应先 {@link #ensureDefault}） */
    public synchronized float x(String id) {
        float[] position = positions.get(id);
        return position == null ? Float.NaN : position[0];
    }

    /** @return 该元素左上角 y；尚未初始化时返回 {@code NaN} */
    public synchronized float y(String id) {
        float[] position = positions.get(id);
        return position == null ? Float.NaN : position[1];
    }

    /** @return 该元素是否已有位置（即已被默认值或用户拖动写入过） */
    public synchronized boolean has(String id) {
        return positions.containsKey(id);
    }

    /**
     * 仅在尚无位置时写入默认值。
     *
     * <p>这就是"懒初始化默认值"的语义：用户拖动过的位置永远不会被默认值覆盖。
     */
    public synchronized void ensureDefault(String id, float defaultX, float defaultY) {
        if (id == null || positions.containsKey(id)) {
            return;
        }
        positions.put(id, new float[]{defaultX, defaultY});
    }

    /** 写入位置（编辑器拖动时调用）。 */
    public synchronized void set(String id, float x, float y) {
        if (id == null) {
            return;
        }
        positions.put(id, new float[]{x, y});
    }

    /** 恢复某元素的默认值（下次绘制会用当时的默认位置重新懒初始化）。 */
    public synchronized void reset(String id) {
        positions.remove(id);
    }

    /** 全部恢复默认。 */
    public synchronized void resetAll() {
        positions.clear();
    }

    /** @return 当前位置的快照（id → {x, y}），用于持久化或诊断 */
    public synchronized Map<String, float[]> snapshot() {
        Map<String, float[]> copy = new HashMap<String, float[]>();
        for (Map.Entry<String, float[]> entry : positions.entrySet()) {
            copy.put(entry.getKey(), new float[]{entry.getValue()[0], entry.getValue()[1]});
        }
        return copy;
    }
}
