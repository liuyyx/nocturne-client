package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.render.RenderSupport;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.MultiBoolValue;
import java.util.Arrays;
import java.util.Collections;

/**
 * 实体、雾与天气过滤。
 *
 * <p>上游降级（阶段 6）：其余 30+ 开关因本仓库无对应渲染注入点，一律不提供（不注册死开关）：
 * Overlay：portal、spyglass、nausea、fire、water、vignette、totem、eat particles、enchant glint；
 * HUD：boss bar、scoreboard、chat、crosshair、title、held item name、obfuscation、potion icons；
 * World：world border、blindness、darkness、block break overlay、beacon beams、cave culling、
 * map markers、map contents、firework explosions、barrier invisibility、texture rotations、drop spawn packet；
 * Entity：ench table book、sign text、falling blocks、banners、no armor、no mob in spawner、no nametags。
 * 计分板由独立模块负责，不在此重复控制。
 */
public final class NoRenderModule extends GameModule {
    private final BooleanValue fog = add(new BooleanValue("Fog", false));
    private final BooleanValue weather = add(new BooleanValue("Weather", false));
    private final MultiBoolValue entities = add(new MultiBoolValue("Entities",
            Arrays.asList("Player", "Monster", "Animal", "Ambient", "Other"),
            Collections.<String>emptyList()));
    private final BooleanValue noDeadEntities = add(new BooleanValue("No Dead Entities", false));
    private Object weatherWorld;
    private float previousRain;
    private float previousThunder;

    public NoRenderModule() { super("No Render", Category.RENDER); }

    /** 返回 false 时调用方应取消该实体的原版渲染。 */
    public boolean entityRenderGate(Object entity) {
        GameBridge bridge = bridge();
        if (!isEnabled() || bridge == null || entity == null) return true;
        if (noDeadEntities.get() && bridge.isDeadForRendering(entity)) return false;
        switch (RenderSupport.kind(bridge, entity)) {
            case PLAYER: return !entities.selected("Player");
            case MONSTER: return !entities.selected("Monster");
            case ANIMAL: return !entities.selected("Animal");
            case AMBIENT: return !entities.selected("Ambient");
            default: return !entities.selected("Other");
        }
    }

    /** 供雾渲染门调用。 */
    public boolean noFog() { return isEnabled() && fog.get(); }

    @Override public void onTick() {
        GameBridge bridge = bridge();
        if (bridge == null) return;
        Object world = bridge.world();
        if (weatherWorld != null && (!weather.get() || world != weatherWorld)) restoreWeather(bridge);
        if (!weather.get() || world == null) return;
        if (weatherWorld == null) {
            Object rain = bridge.readField(world, ClassType.LEVEL, "rainLevel");
            Object thunder = bridge.readField(world, ClassType.LEVEL, "thunderLevel");
            if (!(rain instanceof Number) || !(thunder instanceof Number)) return;
            previousRain = ((Number) rain).floatValue();
            previousThunder = ((Number) thunder).floatValue();
            weatherWorld = world;
        }
        bridge.setWeather(0f, 0f);
    }

    @Override protected void onStop() {
        GameBridge bridge = bridge();
        if (bridge != null) restoreWeather(bridge);
    }

    private void restoreWeather(GameBridge bridge) {
        Object world = weatherWorld;
        weatherWorld = null;
        if (world == null) return;
        if (!bridge.writeField(world, ClassType.LEVEL, "rainLevel", previousRain))
            bridge.callMappedStatus(world, ClassType.LEVEL, "setRainLevel", previousRain);
        if (!bridge.writeField(world, ClassType.LEVEL, "thunderLevel", previousThunder))
            bridge.callMappedStatus(world, ClassType.LEVEL, "setThunderLevel", previousThunder);
    }
}
