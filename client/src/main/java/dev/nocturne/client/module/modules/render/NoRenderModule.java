package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.event.PacketEvent;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.module.Module;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.TextValue;
import dev.nocturne.client.value.Value;

/** NoRender：实体/生成包/天气/效果过滤，并向其它渲染面暴露统一 hide 查询。 */
public final class NoRenderModule extends GameModule {
    private final BooleanValue portalOverlay = add(new BooleanValue("Portal Overlay", false));
    private final BooleanValue spyglassOverlay = add(new BooleanValue("Spyglass Overlay", false));
    private final BooleanValue nausea = add(new BooleanValue("Nausea", false));
    private final BooleanValue fireOverlay = add(new BooleanValue("Fire Overlay", false));
    private final BooleanValue liquidOverlay = add(new BooleanValue("Liquid Overlay", false));
    private final BooleanValue blockOverlay = add(new BooleanValue("Block Overlay", true));
    private final BooleanValue vignette = add(new BooleanValue("Vignette", false));
    private final BooleanValue totemAnimation = add(new BooleanValue("Totem Animation", false));
    private final BooleanValue eatParticles = add(new BooleanValue("Eat Particles", false));
    private final BooleanValue enchantGlint = add(new BooleanValue("Enchant Glint", false));
    private final BooleanValue bossBar = add(new BooleanValue("Boss Bar", false));
    private final BooleanValue scoreboard = add(new BooleanValue("Scoreboard", false));
    private final BooleanValue chat = add(new BooleanValue("Chat", false));
    private final BooleanValue crosshair = add(new BooleanValue("Crosshair", false));
    private final BooleanValue title = add(new BooleanValue("Title", false));
    private final BooleanValue heldItemName = add(new BooleanValue("Held Item Name", false));
    private final BooleanValue obfuscation = add(new BooleanValue("Obfuscation", false));
    private final BooleanValue potionIcons = add(new BooleanValue("Potion Icons", false));
    private final BooleanValue weather = add(new BooleanValue("Weather", false));
    private final BooleanValue worldBorder = add(new BooleanValue("World Border", false));
    private final BooleanValue blindness = add(new BooleanValue("Blindness", false));
    private final BooleanValue darkness = add(new BooleanValue("Darkness", false));
    private final BooleanValue fog = add(new BooleanValue("Fog", false));
    private final BooleanValue enchTableBook = add(new BooleanValue("Ench Table Book", false));
    private final BooleanValue signText = add(new BooleanValue("Sign Text", false));
    private final BooleanValue blockBreakOverlay = add(new BooleanValue("Block Break Overlay", false));
    private final BooleanValue beaconBeams = add(new BooleanValue("Beacon Beams", false));
    private final BooleanValue fallingBlocks = add(new BooleanValue("Falling Blocks", false));
    private final BooleanValue caveCulling = add(new BooleanValue("Cave Culling", false));
    private final BooleanValue mapMarkers = add(new BooleanValue("Map Markers", false));
    private final BooleanValue mapContents = add(new BooleanValue("Map Contents", false));
    private final BooleanValue banners = add(new BooleanValue("Banners", false));
    private final BooleanValue fireworkExplosions = add(new BooleanValue("Firework Explosions", false));
    private final BooleanValue barrierInvisibility = add(new BooleanValue("Barrier Invisibility", false));
    private final BooleanValue textureRotations = add(new BooleanValue("Texture Rotations", false));
    private final TextValue entities = add(new TextValue("Entities", "", 1024));
    private final BooleanValue dropSpawnPacket = add(new BooleanValue("Drop Spawn Packet", false));
    private final BooleanValue noArmor = add(new BooleanValue("No Armor", false));
    private final BooleanValue noMobInSpawner = add(new BooleanValue("No Mob In Spawner", false));
    private final BooleanValue noDeadEntities = add(new BooleanValue("No Dead Entities", false));
    private final BooleanValue noNametags = add(new BooleanValue("No Nametags", false));

    public NoRenderModule() {
        super("No Render", Category.RENDER);
    }

    @Override
    protected void onStart() {
        listen(PacketEvent.class, event -> {
            if (dropSpawnPacket.get() && event.direction == PacketEvent.Direction.RECEIVE
                    && bridge().isInstance(event.packet, ClassType.CLIENTBOUND_ADD_ENTITY)) {
                event.cancelled = true;
            }
        });
        reloadIfNeeded();
    }

    @Override
    public void onTick() {
        if (weather.get()) {
            bridge().setWeather(0.0f, 0.0f);
        }
        if (blindness.get()) {
            bridge().removeEffect("BLINDNESS");
        }
        if (darkness.get()) {
            bridge().removeEffect("DARKNESS");
        }
    }

    public static boolean shouldRender(Object entity) {
        NoRenderModule module = current();
        if (module == null || entity == null) {
            return true;
        }
        NocturneClient client = NocturneClient.get();
        // 死亡判定只在**能确认**血量时成立：非 LivingEntity 不参与（1.21.5+ 的实体渲染首参是
        // EntityRenderState，不是实体）；血量读不到时保持可见（未知 ≠ 已死）。
        // 旧实现把"读不到"当 0，于是 1.21.5–1.21.8 上所有实体（含玩家）都被删掉。
        if (module.noDeadEntities.get() && client.gameBridge() != null
                && client.gameBridge().isDeadForRendering(entity)) {
            return false;
        }
        String type = entity.getClass().getSimpleName().toLowerCase(java.util.Locale.ROOT);
        if (module.fallingBlocks.get() && type.contains("fallingblock")) {
            return false;
        }
        for (String token : module.entities.get().toLowerCase(java.util.Locale.ROOT).split("[,;\\s]+")) {
            if (!token.isEmpty() && type.contains(token)) {
                return false;
            }
        }
        return true;
    }

    /** 其它渲染 Hook 按 Epsilon 设置名查询是否隐藏。 */
    public static boolean hide(String name) {
        NoRenderModule module = current();
        if (module == null) {
            return false;
        }
        for (Value<?> value : module.values()) {
            if (value.name().equals(name) && value.get() instanceof Boolean) {
                return (Boolean) value.get();
            }
        }
        return false;
    }

    public static boolean noNametags() {
        return hide("No Nametags");
    }

    public static boolean fog() {
        return hide("Fog");
    }

    private static NoRenderModule current() {
        NocturneClient client = NocturneClient.get();
        Module module = client == null ? null : client.modules().get("No Render");
        return module instanceof NoRenderModule && module.isEnabled() ? (NoRenderModule) module : null;
    }

    private void reloadIfNeeded() {
        if (caveCulling.get() || textureRotations.get()) {
            bridge().reloadChunks();
        }
    }

    @Override
    protected void onStop() {
        reloadIfNeeded();
    }
}
