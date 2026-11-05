package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.event.AttackEvent;
import dev.nocturne.client.event.PacketEvent;
import dev.nocturne.client.event.UseItemEvent;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.module.modules.combat.AntiBotModule;
import dev.nocturne.client.module.modules.render.RenderSupport;
import dev.nocturne.client.render.OverlayDraw;
import dev.nocturne.client.render.WorldOverlay;
import dev.nocturne.client.render.WorldProjection;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.ColorValue;
import dev.nocturne.client.value.NumberValue;

/** ServerGuard：吞方块/吞攻击/回弹/假人警告与屏幕边缘闪烁。 */
public final class ServerGuardModule extends GameModule implements WorldOverlay {
    private final BooleanValue swallowBlock = add(new BooleanValue("Swallow Block", true));
    private final BooleanValue swallowAttack = add(new BooleanValue("Swallow Attack", true));
    private final BooleanValue rubberbandWarning = add(new BooleanValue("Rubberband Warning", true));
    private final NumberValue rubberbandRange = add(new NumberValue("Rubberband Range", 8, 1, 32, 0.5));
    private final BooleanValue botWarning = add(new BooleanValue("Bot Warning", true));
    private final BooleanValue tabListCheck = add(new BooleanValue("Tab List Check", false));
    private final NumberValue botRange = add(new NumberValue("Bot Range", 64, 8, 128, 4));
    private final NumberValue alertCooldown = add(new NumberValue("Alert Cooldown", 0, 0, 5000, 50));
    private final BooleanValue flash = add(new BooleanValue("Flash", true));
    private final NumberValue flashDuration = add(new NumberValue("Flash Duration", 1200, 200, 5000, 100));
    private final NumberValue flashThickness = add(new NumberValue("Flash Thickness", 0.30, 0.05, 1, 0.05));
    private final ColorValue flashColor = add(new ColorValue("Flash Color", 0xFFFF2A2A));
    private int[] placed;
    private long placedAt;
    private Object attacked;
    private long attackedAt;
    private long nextAlert;
    private long flashUntil;
    private int joinTicks;

    public ServerGuardModule() {
        super("ServerGuard", Category.RENDER);
    }

    @Override
    protected void onStart() {
        joinTicks = 20;
        listen(UseItemEvent.class, event -> {
            if (!event.post && swallowBlock.get()) {
                placed = bridge().hitBlockPosition(actions().pick(5.0, 1.0f));
                placedAt = System.currentTimeMillis();
            }
        });
        listen(AttackEvent.class, event -> {
            if (!event.post && swallowAttack.get()) {
                attacked = event.target;
                attackedAt = System.currentTimeMillis();
            }
        });
        listen(PacketEvent.class, event -> {
            if (event.direction != PacketEvent.Direction.RECEIVE) {
                return;
            }
            int[] pos = bridge().blockUpdatePosition(event.packet);
            if (placed != null && pos != null && same(placed, pos)
                    && System.currentTimeMillis() - placedAt < 3000L) {
                warn("Server swallowed a block at " + pos[0] + ',' + pos[1] + ',' + pos[2]);
                placed = null;
            }
            if (rubberbandWarning.get() && joinTicks == 0
                    && bridge().isInstance(event.packet, ClassType.CLIENTBOUND_PLAYER_POSITION)) {
                warn("Server rubberband detected");
            }
        });
    }

    @Override
    public void onTick() {
        if (joinTicks > 0) {
            joinTicks--;
        }
        if (attacked != null && System.currentTimeMillis() - attackedAt >= 1000L) {
            if (bridge().hurtTime(attacked) <= 0) {
                warn("Server swallowed an attack on " + bridge().entityName(attacked));
            }
            attacked = null;
        }
        if (botWarning.get()) {
            RenderSupport.forEachEntity(bridge(), entity -> {
                if (entity != bridge().player() && bridge().distance(bridge().player(), entity) <= botRange.get()
                        && AntiBotModule.isBot(bridge(), entity)
                        && (!tabListCheck.get() || bridge().entityUuid(entity) == null
                        || !bridge().onlinePlayerUuids().contains(bridge().entityUuid(entity)))) {
                    warn("Possible bot: " + bridge().entityName(entity));
                }
            });
        }
    }

    private void warn(String message) {
        long now = System.currentTimeMillis();
        if (now < nextAlert) {
            return;
        }
        nextAlert = now + alertCooldown.asInt();
        bridge().localMessage("[ServerGuard] " + message);
        if (flash.get()) {
            flashUntil = now + flashDuration.asInt();
        }
    }

    @Override
    public void drawWorldOverlay(OverlayDraw draw, WorldProjection projection) {
        if (System.currentTimeMillis() >= flashUntil) {
            return;
        }
        float thickness = Math.min(draw.width(), draw.height()) * flashThickness.asFloat();
        draw.rect(0, 0, draw.width(), thickness, flashColor.argb());
        draw.rect(0, draw.height() - thickness, draw.width(), thickness, flashColor.argb());
        draw.rect(0, 0, thickness, draw.height(), flashColor.argb());
        draw.rect(draw.width() - thickness, 0, thickness, draw.height(), flashColor.argb());
    }

    private static boolean same(int[] a, int[] b) {
        return a[0] == b[0] && a[1] == b[1] && a[2] == b[2];
    }

    @Override
    protected void onStop() {
        placed = null;
        attacked = null;
        flashUntil = 0L;
    }
}
