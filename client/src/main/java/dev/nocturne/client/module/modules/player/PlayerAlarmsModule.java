package dev.nocturne.client.module.modules.player;

import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.GameModule;
import dev.nocturne.client.module.modules.render.RenderSupport;
import dev.nocturne.client.value.BooleanValue;
import dev.nocturne.client.value.ModeValue;
import dev.nocturne.client.value.NumberValue;
import dev.nocturne.client.value.TextValue;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** PlayerAlarms：加入、离开、渲染距离变化与游戏模式变化的分组声音/文本报警。 */
public final class PlayerAlarmsModule extends GameModule {
    private final ModeValue alertMode = add(new ModeValue("Alert Mode", "Chat", "Chat", "Notification", "Both"));
    private final BooleanValue showGameMode = add(new BooleanValue("Show Gamemode In Chat", false));
    private final TextValue names = add(new TextValue("Names", "ssy_,e_2,山水圆", 512));
    private final NumberValue joinRings = add(new NumberValue("Join Rings", 5, 1, 10, 1));
    private final NumberValue joinDelay = add(new NumberValue("Join Ring Delay", 20, 1, 100, 1));
    private final NumberValue joinVolume = add(new NumberValue("Join Volume", 1, 0, 1, 0.05));
    private final NumberValue joinPitch = add(new NumberValue("Join Pitch", 1, 0.5, 2, 0.05));
    private final TextValue joinSound = add(new TextValue("Join Sound", "BELL_BLOCK", 128));
    private final BooleanValue joinChat = add(new BooleanValue("Join Chat Message", true));
    private final NumberValue leaveRings = add(new NumberValue("Leave Rings", 3, 1, 10, 1));
    private final NumberValue leaveDelay = add(new NumberValue("Leave Ring Delay", 20, 1, 100, 1));
    private final NumberValue leaveVolume = add(new NumberValue("Leave Volume", 1, 0, 1, 0.05));
    private final NumberValue leavePitch = add(new NumberValue("Leave Pitch", 1, 0.5, 2, 0.05));
    private final TextValue leaveSound = add(new TextValue("Leave Sound", "ANVIL_LAND", 128));
    private final BooleanValue leaveChat = add(new BooleanValue("Leave Chat Message", true));
    private final NumberValue enterRings = add(new NumberValue("Enter Render Distance Rings", 2, 1, 10, 1));
    private final NumberValue enterDelay = add(new NumberValue("Enter Render Distance Ring Delay", 20, 1, 100, 1));
    private final NumberValue enterVolume = add(new NumberValue("Enter Render Distance Volume", 1, 0, 1, 0.05));
    private final NumberValue enterPitch = add(new NumberValue("Enter Render Distance Pitch", 1, 0.5, 2, 0.05));
    private final TextValue enterSound = add(new TextValue("Enter Render Distance Sound", "ANVIL_DESTROY", 128));
    private final BooleanValue enterChat = add(new BooleanValue("Enter Render Distance Chat Message", true));
    private final NumberValue exitRings = add(new NumberValue("Leave Render Distance Rings", 2, 1, 10, 1));
    private final NumberValue exitDelay = add(new NumberValue("Leave Render Distance Ring Delay", 20, 1, 100, 1));
    private final NumberValue exitVolume = add(new NumberValue("Leave Render Distance Volume", 1, 0, 1, 0.05));
    private final NumberValue exitPitch = add(new NumberValue("Leave Render Distance Pitch", 1, 0.5, 2, 0.05));
    private final TextValue exitSound = add(new TextValue("Leave Render Distance Sound", "BELL_BLOCK", 128));
    private final BooleanValue exitChat = add(new BooleanValue("Leave Render Distance Chat Message", true));
    private final NumberValue gameRings = add(new NumberValue("Gamemode Change Rings", 3, 1, 10, 1));
    private final NumberValue gameDelay = add(new NumberValue("Gamemode Change Ring Delay", 20, 1, 100, 1));
    private final NumberValue gameVolume = add(new NumberValue("Gamemode Change Volume", 1, 0, 1, 0.05));
    private final NumberValue gamePitch = add(new NumberValue("Gamemode Change Pitch", 1, 0.5, 2, 0.05));
    private final TextValue gameSound = add(new TextValue("Gamemode Change Sound", "ARROW_HIT_PLAYER", 128));
    private final BooleanValue gameChat = add(new BooleanValue("Gamemode Change Chat Message", true));
    private final Map<UUID, String> online = new HashMap<UUID, String>();
    private final Map<UUID, String> modes = new HashMap<UUID, String>();
    private final Set<UUID> visible = new HashSet<UUID>();
    private final RingState join = new RingState();
    private final RingState leave = new RingState();
    private final RingState enter = new RingState();
    private final RingState exit = new RingState();
    private final RingState game = new RingState();

    public PlayerAlarmsModule() {
        super("PlayerAlarms", Category.PLAYER);
    }

    @Override
    public void onTick() {
        GameBridge bridge = bridge();
        if (bridge == null || bridge.player() == null) {
            return;
        }
        Set<UUID> currentVisible = new HashSet<UUID>();
        RenderSupport.forEachEntity(bridge, entity -> {
            if (!bridge.isInstance(entity, ClassType.PLAYER) || entity == bridge.player()) {
                return;
            }
            UUID uuid = bridge.entityUuid(entity);
            if (uuid == null || !watched(bridge.entityName(entity))) {
                return;
            }
            currentVisible.add(uuid);
            online.put(uuid, bridge.entityName(entity));
            String currentMode = bridge.playerGameMode(uuid);
            String previous = modes.put(uuid, currentMode);
            if (previous != null && !previous.equals(currentMode)) {
                alert(game, gameRings, gameDelay, gameVolume, gamePitch, gameSound, gameChat,
                        online.get(uuid) + " changed gamemode"
                                + (showGameMode.get() ? " to " + currentMode : ""));
            }
        });
        for (UUID uuid : currentVisible) {
            if (visible.add(uuid)) {
                alert(enter, enterRings, enterDelay, enterVolume, enterPitch, enterSound, enterChat,
                        online.get(uuid) + " entered render distance");
            }
        }
        for (UUID uuid : visible) {
            if (!currentVisible.contains(uuid)) {
                alert(exit, exitRings, exitDelay, exitVolume, exitPitch, exitSound, exitChat,
                        online.get(uuid) + " left render distance");
            }
        }
        visible.retainAll(currentVisible);
        Map<UUID, String> currentPlayers = bridge.onlinePlayerNames();
        for (Map.Entry<UUID, String> entry : currentPlayers.entrySet()) {
            if (!watched(entry.getValue())) {
                continue;
            }
            if (!online.containsKey(entry.getKey())) {
                alert(join, joinRings, joinDelay, joinVolume, joinPitch, joinSound, joinChat,
                        entry.getValue() + " joined");
            }
            online.put(entry.getKey(), entry.getValue());
        }
        for (Map.Entry<UUID, String> entry : online.entrySet()) {
            if (!currentPlayers.containsKey(entry.getKey())) {
                alert(leave, leaveRings, leaveDelay, leaveVolume, leavePitch, leaveSound, leaveChat,
                        entry.getValue() + " left");
            }
        }
        online.keySet().retainAll(currentPlayers.keySet());
        tickRing(join, joinDelay, joinVolume, joinPitch, joinSound);
        tickRing(leave, leaveDelay, leaveVolume, leavePitch, leaveSound);
        tickRing(enter, enterDelay, enterVolume, enterPitch, enterSound);
        tickRing(exit, exitDelay, exitVolume, exitPitch, exitSound);
        tickRing(game, gameDelay, gameVolume, gamePitch, gameSound);
    }

    private boolean watched(String name) {
        String lower = name.toLowerCase(java.util.Locale.ROOT);
        for (String wanted : names.get().split("[,;\\s]+")) {
            if (!wanted.isEmpty() && lower.contains(wanted.toLowerCase(java.util.Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private void alert(RingState state, NumberValue rings, NumberValue delay, NumberValue volume,
                       NumberValue pitch, TextValue sound, BooleanValue chat, String message) {
        if (chat.get() && !alertMode.is("Notification")) {
            bridge().localMessage(message);
        } else if (!alertMode.is("Chat")) {
            bridge().localMessage("[Nocturne] " + message);
        }
        state.remaining = rings.asInt();
        state.delay = delay.asInt();
    }

    private void tickRing(RingState state, NumberValue delay, NumberValue volume,
                          NumberValue pitch, TextValue sound) {
        if (state.remaining <= 0 || --state.delay > 0) {
            return;
        }
        bridge().playSound(sound.get(), volume.asFloat(), pitch.asFloat());
        state.remaining--;
        state.delay = delay.asInt();
    }

    @Override
    protected void onStop() {
        online.clear();
        modes.clear();
        visible.clear();
        join.remaining = leave.remaining = enter.remaining = exit.remaining = game.remaining = 0;
    }

    private static final class RingState {
        private int remaining;
        private int delay;
    }
}
