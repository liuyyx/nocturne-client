package dev.nocturne.client.action;

import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.mapping.ClassType;

/** 把 {@link ActionApi} 的语义参数装配成已绑定方法的实参。 */
final class BoundActionApi implements ActionApi {

    private final ActionBinder binder;
    private final GameBridge bridge;

    BoundActionApi(ActionBinder binder, GameBridge bridge) {
        this.binder = binder;
        this.bridge = bridge;
    }

    @Override
    public boolean attack(Object target) {
        Object player = bridge.player();
        Object gameMode = gameMode();
        return target != null && player != null
                && binder.invoke("attack", gameMode, player, target);
    }

    @Override
    public boolean place(Object blockPos, Object direction) {
        if (!binder.isAvailable("place") || blockPos == null || direction == null) {
            return false;
        }
        Object player = bridge.player();
        Object gameMode = gameMode();
        if (player == null || gameMode == null) {
            return false;
        }
        Number x = number(bridge.callMapped(blockPos, ClassType.BLOCK_POS, "getX"));
        Number y = number(bridge.callMapped(blockPos, ClassType.BLOCK_POS, "getY"));
        Number z = number(bridge.callMapped(blockPos, ClassType.BLOCK_POS, "getZ"));
        if (x == null || y == null || z == null) {
            return false;
        }
        try {
            Object hitVec = binder.vec3Constructor().newInstance(
                    x.doubleValue() + 0.5, y.doubleValue() + 0.5, z.doubleValue() + 0.5);
            Object hitResult = binder.blockHitResultConstructor().newInstance(
                    hitVec, direction, blockPos, false);
            return binder.invoke("place", gameMode, player, binder.mainHand(), hitResult);
        } catch (Throwable t) {
            return false;
        }
    }

    @Override
    public boolean useItem() {
        Object player = bridge.player();
        Object gameMode = gameMode();
        return player != null && binder.invoke("useItem", gameMode, player, binder.mainHand());
    }

    @Override
    public boolean setRotation(float yaw, float pitch) {
        if (!binder.isAvailable("setRotation") || !finite(yaw) || !finite(pitch)) {
            return false;
        }
        Object player = bridge.player();
        if (player == null) {
            return false;
        }
        boolean yawWritten = bridge.writeField(player, ClassType.ENTITY, "yRot", yaw);
        boolean pitchWritten = bridge.writeField(player, ClassType.ENTITY, "xRot", pitch);
        return yawWritten && pitchWritten;
    }

    @Override
    public boolean sendPacket(Object packet) {
        if (packet == null) {
            return false;
        }
        Object player = bridge.player();
        Object connection = player == null ? null
                : bridge.readField(player, ClassType.LOCAL_PLAYER, "connection");
        return binder.invoke("sendPacket", connection, packet);
    }

    @Override
    public PlayerState readState() {
        if (!binder.isAvailable("readState")) {
            return PlayerState.empty();
        }
        Object player = bridge.player();
        if (player == null) {
            return PlayerState.empty();
        }
        Object position = bridge.callMapped(player, ClassType.ENTITY, "position");
        Number x = number(bridge.readField(position, ClassType.VEC3, "x"));
        Number y = number(bridge.readField(position, ClassType.VEC3, "y"));
        Number z = number(bridge.readField(position, ClassType.VEC3, "z"));
        Number yaw = number(bridge.readField(player, ClassType.ENTITY, "yRot"));
        Number pitch = number(bridge.readField(player, ClassType.ENTITY, "xRot"));
        Object ground = bridge.readField(player, ClassType.ENTITY, "onGround");
        if (x == null || y == null || z == null || yaw == null || pitch == null
                || !(ground instanceof Boolean)) {
            return PlayerState.empty();
        }
        return new PlayerState(player, x.doubleValue(), y.doubleValue(), z.doubleValue(),
                yaw.floatValue(), pitch.floatValue(), (Boolean) ground);
    }

    @Override
    public boolean isAvailable(String actionId) {
        return binder.isAvailable(actionId);
    }

    private Object gameMode() {
        Object minecraft = bridge.minecraft();
        return minecraft == null ? null
                : bridge.readField(minecraft, ClassType.MINECRAFT, "gameMode");
    }

    private static Number number(Object value) {
        return value instanceof Number ? (Number) value : null;
    }

    private static boolean finite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }
}
