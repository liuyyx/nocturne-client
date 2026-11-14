package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.NumberValue;
import java.util.Map;
import java.util.Optional;

/**
 * 时间覆盖与禁雾。没有雾参数改写注入点，因此不提供 Fog Start、Fog End、Fog Color。
 * 只在能够保存原始时间和时钟状态时写入，停用或切换世界时恢复原值。
 */
public final class WorldTweaksModule extends GameModule {
    private final BooleanValue fog = add(new BooleanValue("No Fog", true));
    private final BooleanValue changeTime = add(new BooleanValue("Change Time", false));
    private final NumberValue time = add(new NumberValue("Time", 21, 0, 23, 1));
    private Object savedWorld;
    private Object savedData;
    private Long savedTime;
    private Map<Object, Object> clockMap;
    private Object clockKey;
    private Object clockInstance;
    private Object clockTicks;
    private Object clockPartial;
    private Object clockRate;

    public WorldTweaksModule() { super("World Tweaks", Category.RENDER); }

    /** 供雾渲染门调用。 */
    public boolean noFog() { return isEnabled() && fog.get(); }

    @Override public void onTick() {
        GameBridge bridge = bridge();
        if (bridge == null) return;
        Object world = bridge.world();
        if (savedWorld != null && (!changeTime.get() || world != savedWorld)) restore(bridge);
        if (!changeTime.get() || world == null) return;
        if (savedWorld == null && !capture(bridge, world)) return;
        bridge.setDayTime(time.get().longValue() * 1000L);
    }

    @SuppressWarnings("unchecked")
    private boolean capture(GameBridge bridge, Object world) {
        Object manager = bridge.callMapped(world, ClassType.CLIENT_LEVEL, "clockManager");
        Object clocks = bridge.readField(manager, ClassType.CLIENT_CLOCK_MANAGER, "clocks");
        if (clocks instanceof Map) {
            Object dimension = bridge.callMapped(world, ClassType.LEVEL, "dimensionType");
            Object defaultClock = bridge.callMapped(dimension, ClassType.DIMENSION_TYPE, "defaultClock");
            Object key = defaultClock instanceof Optional ? ((Optional<?>) defaultClock).orElse(null) : null;
            if (key == null) return false;
            Map<Object, Object> map = (Map<Object, Object>) clocks;
            Object instance = map.get(key);
            Object ticks = instance == null ? null : bridge.readField(instance, ClassType.CLIENT_CLOCK_INSTANCE, "totalTicks");
            Object partial = instance == null ? null : bridge.readField(instance, ClassType.CLIENT_CLOCK_INSTANCE, "partialTick");
            Object rate = instance == null ? null : bridge.readField(instance, ClassType.CLIENT_CLOCK_INSTANCE, "rate");
            if (instance != null && (!(ticks instanceof Number) || !(partial instanceof Number) || !(rate instanceof Number))) return false;
            clockMap = map;
            clockKey = key;
            clockInstance = instance;
            clockTicks = ticks;
            clockPartial = partial;
            clockRate = rate;
            savedWorld = world;
            return true;
        }
        Object data = bridge.readField(world, ClassType.CLIENT_LEVEL, "levelData");
        Object value = bridge.readField(world, ClassType.CLIENT_LEVEL, "dayTime");
        if (!(value instanceof Number)) value = bridge.callMapped(data, ClassType.LEVEL_DATA, "getDayTime");
        if (!(value instanceof Number)) value = bridge.callMapped(world, ClassType.LEVEL, "getDayTime");
        if (!(value instanceof Number)) return false;
        savedData = data;
        savedTime = ((Number) value).longValue();
        savedWorld = world;
        return true;
    }

    private void restore(GameBridge bridge) {
        Object world = savedWorld;
        if (world == null) return;
        if (clockMap != null) {
            if (clockInstance == null) {
                clockMap.remove(clockKey);
            } else {
                clockMap.put(clockKey, clockInstance);
                bridge.writeField(clockInstance, ClassType.CLIENT_CLOCK_INSTANCE, "totalTicks", clockTicks);
                bridge.writeField(clockInstance, ClassType.CLIENT_CLOCK_INSTANCE, "partialTick", clockPartial);
                bridge.writeField(clockInstance, ClassType.CLIENT_CLOCK_INSTANCE, "rate", clockRate);
            }
        } else if (savedTime != null && !bridge.writeField(world, ClassType.CLIENT_LEVEL, "dayTime", savedTime)) {
            bridge.callMappedStatus(savedData, ClassType.LEVEL_DATA, "setDayTime", savedTime);
        }
        savedWorld = null;
        savedData = null;
        savedTime = null;
        clockMap = null;
        clockKey = null;
        clockInstance = null;
        clockTicks = null;
        clockPartial = null;
        clockRate = null;
    }

    @Override protected void onStop() {
        GameBridge bridge = bridge();
        if (bridge != null) restore(bridge);
    }
}
