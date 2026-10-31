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
    public boolean click() {
        Object minecraft = bridge.minecraft();
        return minecraft != null && binder.invoke("click", minecraft);
    }

    @Override
    public boolean place(Object blockPos, Object direction) {
        return performPlace(blockPos, direction);
    }

    @Override
    public boolean placeAt(int x, int y, int z, String face) {
        Object blockPos = binder.newBlockPos(x, y, z);
        Object direction = binder.direction(face);
        return performPlace(blockPos, direction);
    }

    /** 点击支持方块的指定面；命中点取该面的面心，而不是方块中心。 */
    private boolean performPlace(Object blockPos, Object direction) {
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
        String face = String.valueOf(direction);
        Object hitVec = binder.newVec3(
                x.doubleValue() + 0.5 + faceOffset(face, 'x'),
                y.doubleValue() + 0.5 + faceOffset(face, 'y'),
                z.doubleValue() + 0.5 + faceOffset(face, 'z'));
        Object hitResult = binder.newBlockHitResult(hitVec, direction, blockPos);
        if (hitResult == null) {
            return false;
        }
        Object[] arguments = interactionArguments("place", player, hitResult,
                blockPos, direction, hitVec);
        return arguments != null && binder.invoke("place", gameMode, arguments);
    }

    /** 面心相对方块中心的偏移：只在一个轴上取 ±0.5。 */
    private static double faceOffset(String face, char axis) {
        if (face == null) {
            return 0.0;
        }
        String normalized = face.toLowerCase(java.util.Locale.ROOT);
        if (axis == 'x') {
            return "east".equals(normalized) ? 0.5 : "west".equals(normalized) ? -0.5 : 0.0;
        }
        if (axis == 'y') {
            return "up".equals(normalized) ? 0.5 : "down".equals(normalized) ? -0.5 : 0.0;
        }
        return "south".equals(normalized) ? 0.5 : "north".equals(normalized) ? -0.5 : 0.0;
    }

    @Override
    public boolean useItem() {
        Object player = bridge.player();
        Object gameMode = gameMode();
        if (player == null) {
            return false;
        }
        Object[] arguments = interactionArguments("useItem", player, null,
                null, null, null);
        return arguments != null && binder.invoke("useItem", gameMode, arguments);
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

    /** 按候选的 arguments 列表装配参数；缺任何必需对象时不调用游戏方法。 */
    private Object[] interactionArguments(String actionId, Object player, Object hitResult,
                                           Object blockPos, Object direction, Object hitVec) {
        java.util.List<String> declared = binder.arguments(actionId);
        if (declared.isEmpty()) {
            return "place".equals(actionId)
                    ? new Object[]{player, binder.mainHand(), hitResult}
                    : new Object[]{player, binder.mainHand()};
        }
        Object[] arguments = new Object[declared.size()];
        for (int i = 0; i < declared.size(); i++) {
            String semantic = declared.get(i);
            if ("player".equals(semantic)) {
                arguments[i] = player;
            } else if ("level".equals(semantic)) {
                arguments[i] = level();
            } else if ("hand".equals(semantic)) {
                arguments[i] = binder.mainHand();
            } else if ("hitResult".equals(semantic)) {
                arguments[i] = hitResult;
            } else if ("blockPos".equals(semantic)) {
                arguments[i] = blockPos;
            } else if ("direction".equals(semantic)) {
                arguments[i] = direction;
            } else if ("hitVec".equals(semantic)) {
                arguments[i] = hitVec;
            } else if ("heldItem".equals(semantic)) {
                arguments[i] = bridge.callMapped(player, ClassType.LIVING_ENTITY,
                        "getMainHandItem");
            } else {
                return null;
            }
            if (arguments[i] == null) {
                return null;
            }
        }
        return arguments;
    }

    private Object level() {
        Object minecraft = bridge.minecraft();
        return minecraft == null ? null : bridge.readField(minecraft, ClassType.MINECRAFT, "level");
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
