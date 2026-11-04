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
        return placeAt(x, y, z, face,
                0.5 + faceOffset(face, 'x'),
                0.5 + faceOffset(face, 'y'),
                0.5 + faceOffset(face, 'z'), false);
    }

    @Override
    public boolean placeAt(int x, int y, int z, String face,
                           double hitX, double hitY, double hitZ, boolean offHand) {
        if (!finite(hitX) || !finite(hitY) || !finite(hitZ)
                || hitX < 0.0 || hitX > 1.0 || hitY < 0.0 || hitY > 1.0
                || hitZ < 0.0 || hitZ > 1.0 || (offHand && binder.hand(true) == null)) {
            return false;
        }
        Object blockPos = binder.newBlockPos(x, y, z);
        Object direction = binder.direction(face);
        return performPlace(blockPos, direction,
                binder.newVec3(x + hitX, y + hitY, z + hitZ), offHand);
    }

    /** 点击支持方块的指定面；命中点由语义调用方给出，缺省取面心。 */
    private boolean performPlace(Object blockPos, Object direction) {
        return performPlace(blockPos, direction, null, false);
    }

    private boolean performPlace(Object blockPos, Object direction, Object hitVec, boolean offHand) {
        if (!binder.isAvailable("place") || blockPos == null || direction == null) {
            return false;
        }
        Object player = bridge.player();
        Object gameMode = gameMode();
        if (player == null || gameMode == null) {
            return false;
        }
        if (hitVec == null) {
            // 走 Vec3i 字段：BlockPos#getX() 在 1.16.5 有实例/静态两个重载，映射表只记一个。
            Number x = number(bridge.readField(blockPos, ClassType.VEC3_I, "x"));
            Number y = number(bridge.readField(blockPos, ClassType.VEC3_I, "y"));
            Number z = number(bridge.readField(blockPos, ClassType.VEC3_I, "z"));
            if (x == null || y == null || z == null) {
                return false;
            }
            String face = String.valueOf(direction);
            hitVec = binder.newVec3(
                    x.doubleValue() + 0.5 + faceOffset(face, 'x'),
                    y.doubleValue() + 0.5 + faceOffset(face, 'y'),
                    z.doubleValue() + 0.5 + faceOffset(face, 'z'));
        }
        Object hitResult = binder.newBlockHitResult(hitVec, direction, blockPos);
        if (hitResult == null) {
            return false;
        }
        Object[] arguments = interactionArguments("place", player, hitResult,
                blockPos, direction, hitVec, binder.hand(offHand), offHand);
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
        return useItem(false);
    }

    @Override
    public boolean useItem(boolean offHand) {
        Object player = bridge.player();
        Object gameMode = gameMode();
        Object hand = binder.hand(offHand);
        if (player == null || (offHand && hand == null)) {
            return false;
        }
        Object[] arguments = interactionArguments("useItem", player, null,
                null, null, null, hand, offHand);
        return arguments != null && binder.invoke("useItem", gameMode, arguments);
    }

    @Override
    public boolean swing(boolean offHand, boolean silent) {
        if (offHand && binder.hand(true) == null) {
            return false;
        }
        if (silent) {
            return binder.isAvailable("swingPacket") && sendPacket(binder.newSwing(offHand));
        }
        Object player = bridge.player();
        if (player == null) {
            return false;
        }
        java.util.List<String> declared = binder.arguments("swing");
        Object[] arguments = new Object[declared.size()];
        for (int i = 0; i < declared.size(); i++) {
            if (!"hand".equals(declared.get(i))) {
                return false;
            }
            arguments[i] = binder.hand(offHand);
        }
        return binder.invoke("swing", player, arguments);
    }

    @Override
    public boolean selectSlot(int slot, boolean silent) {
        if (slot < 0 || slot > 8) {
            return false;
        }
        if (silent) {
            return sendPacket(binder.newCarriedItem(slot));
        }
        Object player = bridge.player();
        Object inventory = player == null ? null
                : bridge.readField(player, ClassType.PLAYER, "inventory");
        if (inventory == null) {
            return false;
        }
        bridge.callMapped(inventory, ClassType.INVENTORY, "setSelectedSlot", slot);
        Number selected = number(bridge.callMapped(inventory, ClassType.INVENTORY, "getSelectedSlot"));
        if (selected != null) {
            return selected.intValue() == slot;
        }
        return bridge.writeField(inventory, ClassType.INVENTORY, "selected", slot);
    }

    @Override
    public boolean clickSlot(int slot, int button, String mode) {
        Object player = bridge.player();
        Object gameMode = gameMode();
        Object clickMode = binder.clickMode(mode);
        Object menu = player == null ? null
                : bridge.readField(player, ClassType.PLAYER, "containerMenu");
        Number containerId = number(menu == null ? null
                : bridge.readField(menu, ClassType.ABSTRACT_CONTAINER_MENU, "containerId"));
        if (player == null || gameMode == null || clickMode == null || containerId == null) {
            return false;
        }
        java.util.List<String> declared = binder.arguments("clickSlot");
        Object[] arguments = new Object[declared.size()];
        for (int i = 0; i < declared.size(); i++) {
            String semantic = declared.get(i);
            if ("containerId".equals(semantic)) {
                arguments[i] = containerId.intValue();
            } else if ("slot".equals(semantic)) {
                arguments[i] = slot;
            } else if ("button".equals(semantic)) {
                arguments[i] = button;
            } else if ("mode".equals(semantic)) {
                arguments[i] = clickMode;
            } else if ("player".equals(semantic)) {
                arguments[i] = player;
            } else {
                return false;
            }
        }
        return binder.invoke("clickSlot", gameMode, arguments);
    }

    @Override
    public boolean closeContainer() {
        Object player = bridge.player();
        return player != null && binder.invoke("closeContainer", player);
    }

    @Override
    public boolean sendChat(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        if (binder.isAvailable("sendChat")) {
            return binder.invoke("sendChat", connection(), text);
        }
        Object player = bridge.player();
        return player != null && binder.invoke("legacyChat", player, text);
    }

    @Override
    public boolean sendCommand(String command) {
        if (command == null || command.isEmpty()) {
            return false;
        }
        String normalized = command.startsWith("/") ? command.substring(1) : command;
        if (binder.isAvailable("sendCommand")) {
            return binder.invoke("sendCommand", connection(), normalized);
        }
        Object player = bridge.player();
        return player != null && binder.invoke("legacyChat", player, "/" + normalized);
    }

    @Override
    public boolean clickUse() {
        Object minecraft = bridge.minecraft();
        return minecraft != null && binder.invoke("clickUse", minecraft);
    }

    @Override
    public boolean setSprinting(boolean sprinting) {
        Object player = bridge.player();
        return player != null && binder.invoke("setSprinting", player, sprinting);
    }

    @Override
    public Object pick(double distance, float partialTicks) {
        Object player = bridge.player();
        if (player == null || !finite(distance) || distance <= 0.0 || !finite(partialTicks)) {
            return null;
        }
        java.util.List<String> declared = binder.arguments("pick");
        Object[] arguments = new Object[declared.size()];
        for (int i = 0; i < declared.size(); i++) {
            String semantic = declared.get(i);
            if ("distance".equals(semantic)) {
                arguments[i] = distance;
            } else if ("partialTicks".equals(semantic)) {
                arguments[i] = partialTicks;
            } else if ("fluid".equals(semantic)) {
                arguments[i] = false;
            } else if ("player".equals(semantic)) {
                arguments[i] = player;
            } else {
                return null;
            }
        }
        return binder.result("pick", player, arguments);
    }

    @Override
    public boolean setMotion(double x, double y, double z) {
        Object player = bridge.player();
        if (player == null || !finite(x) || !finite(y) || !finite(z)) {
            return false;
        }
        if (binder.isAvailable("setMotion")) {
            Object motion = binder.newVec3(x, y, z);
            return motion != null && binder.invoke("setMotion", player, motion);
        }
        return bridge.writeField(player, ClassType.ENTITY, "motionX", x)
                && bridge.writeField(player, ClassType.ENTITY, "motionY", y)
                && bridge.writeField(player, ClassType.ENTITY, "motionZ", z);
    }

    @Override
    public boolean setJumping(boolean jumping) {
        Object player = bridge.player();
        return player != null && bridge.writeField(player, ClassType.LIVING_ENTITY, "jumping", jumping);
    }

    @Override
    public boolean sendRotation(float yaw, float pitch, boolean onGround) {
        return finite(yaw) && finite(pitch) && sendPacket(binder.newMoveRot(yaw, pitch, onGround));
    }

    @Override
    public boolean sendPositionRotation(double x, double y, double z,
                                        float yaw, float pitch, boolean onGround) {
        return finite(x) && finite(y) && finite(z) && finite(yaw) && finite(pitch)
                && sendPacket(binder.newMovePosRot(x, y, z, yaw, pitch, onGround));
    }

    @Override
    public boolean sendGround(boolean onGround) {
        return sendPacket(binder.newMoveStatus(onGround));
    }

    @Override
    public boolean sendPlayerCommand(String action) {
        Object player = bridge.player();
        return player != null && binder.isAvailable("sendPlayerCommand")
                && sendPacket(binder.newPlayerCommand(player, action));
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
        return binder.invoke("sendPacket", connection(), packet);
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
        Object inventory = bridge.readField(player, ClassType.PLAYER, "inventory");
        Number selected = number(bridge.callMapped(inventory, ClassType.INVENTORY, "getSelectedSlot"));
        if (selected == null) {
            selected = number(bridge.readField(inventory, ClassType.INVENTORY, "selected"));
        }
        Object mainHand = bridge.heldItem(player, false);
        Object offHand = bridge.heldItem(player, true);
        Number health = number(bridge.callMapped(player, ClassType.LIVING_ENTITY, "getHealth"));
        Number absorption = number(bridge.callMapped(player, ClassType.LIVING_ENTITY,
                "getAbsorptionAmount"));
        Number attack = number(bridge.callMapped(player, ClassType.PLAYER,
                "getAttackStrengthScale", 0.5f));
        Object sprinting = bridge.callMapped(player, ClassType.ENTITY, "isSprinting");
        Object shifting = bridge.callMapped(player, ClassType.ENTITY, "isShiftKeyDown");
        Number strafe = number(bridge.readField(player, ClassType.LIVING_ENTITY, "xxa"));
        Number forward = number(bridge.readField(player, ClassType.LIVING_ENTITY, "zza"));
        boolean moving = Math.abs(strafe == null ? 0.0f : strafe.floatValue()) > 1.0e-3f
                || Math.abs(forward == null ? 0.0f : forward.floatValue()) > 1.0e-3f;
        return new PlayerState(player, x.doubleValue(), y.doubleValue(), z.doubleValue(),
                yaw.floatValue(), pitch.floatValue(), (Boolean) ground,
                selected == null ? -1 : selected.intValue(),
                bridge.itemCount(mainHand), bridge.itemMaxStackSize(mainHand),
                bridge.itemCount(offHand), health == null ? 0.0f : health.floatValue(),
                absorption == null ? 0.0f : absorption.floatValue(),
                attack == null ? 1.0f : attack.floatValue(),
                Boolean.TRUE.equals(sprinting), Boolean.TRUE.equals(shifting), moving);
    }

    @Override
    public boolean isAvailable(String actionId) {
        return binder.isAvailable(actionId);
    }

    /** 按候选的 arguments 列表装配参数；缺任何必需对象时不调用游戏方法。 */
    private Object[] interactionArguments(String actionId, Object player, Object hitResult,
                                           Object blockPos, Object direction, Object hitVec,
                                           Object hand, boolean offHand) {
        java.util.List<String> declared = binder.arguments(actionId);
        if (declared.isEmpty()) {
            return "place".equals(actionId)
                    ? new Object[]{player, hand, hitResult}
                    : new Object[]{player, hand};
        }
        Object[] arguments = new Object[declared.size()];
        for (int i = 0; i < declared.size(); i++) {
            String semantic = declared.get(i);
            if ("player".equals(semantic)) {
                arguments[i] = player;
            } else if ("level".equals(semantic)) {
                arguments[i] = level();
            } else if ("hand".equals(semantic)) {
                arguments[i] = hand;
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
                        offHand ? "getOffhandItem" : "getMainHandItem");
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

    private Object connection() {
        Object player = bridge.player();
        return player == null ? null
                : bridge.readField(player, ClassType.LOCAL_PLAYER, "connection");
    }

    private static Number number(Object value) {
        return value instanceof Number ? (Number) value : null;
    }

    private static boolean finite(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value);
    }

    private static boolean finite(double value) {
        return !Double.isNaN(value) && !Double.isInfinite(value);
    }
}
