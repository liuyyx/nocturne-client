package dev.nocturne.client.event;

import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.mapping.ClassType;

/**
 * 每 tick 已合成完成的移动输入意图。
 *
 * <p>模块只改本事件的字段，不写 {@code KeyMapping.setDown}。26.x 的 Input 是不可变 record，
 * 应用时整体替换 keyPresses 并同步 moveVector；旧版写 MovementInput 字段。sprint 在旧版
 * 通过实体状态应用，在现代记录里由 sprint 组件承载。
 */
public final class MovementInputEvent {

    /** 前进/后退强度，范围 [-1,1]。 */
    public float forward;
    /** 左右平移强度，范围 [-1,1]；正值向左，与原版 leftImpulse 一致。 */
    public float strafe;
    public boolean jump;
    public boolean sneak;
    public boolean sprint;

    private final GameBridge bridge;
    private final Object input;
    private final boolean modern;
    private final float originalForward;
    private final float originalStrafe;
    private final boolean originalJump;
    private final boolean originalSneak;
    private final boolean originalSprint;

    private MovementInputEvent(GameBridge bridge, Object input, boolean modern,
                               float forward, float strafe, boolean jump, boolean sneak, boolean sprint) {
        this.bridge = bridge;
        this.input = input;
        this.modern = modern;
        this.forward = forward;
        this.strafe = strafe;
        this.jump = jump;
        this.sneak = sneak;
        this.sprint = sprint;
        this.originalForward = forward;
        this.originalStrafe = strafe;
        this.originalJump = jump;
        this.originalSneak = sneak;
        this.originalSprint = sprint;
    }

    /** 读取当前输入；代际面或必需字段缺失时返回 null，不投递一份伪造的零输入。 */
    static MovementInputEvent read(GameBridge bridge, Object input) {
        if (bridge == null || input == null) {
            return null;
        }
        Object keys = bridge.readField(input, ClassType.CLIENT_INPUT, "keyPresses");
        if (keys != null) {
            Boolean forward = bool(bridge.callMapped(keys, ClassType.PLAYER_INPUT, "forward"));
            Boolean backward = bool(bridge.callMapped(keys, ClassType.PLAYER_INPUT, "backward"));
            Boolean left = bool(bridge.callMapped(keys, ClassType.PLAYER_INPUT, "left"));
            Boolean right = bool(bridge.callMapped(keys, ClassType.PLAYER_INPUT, "right"));
            Boolean jump = bool(bridge.callMapped(keys, ClassType.PLAYER_INPUT, "jump"));
            Boolean sneak = bool(bridge.callMapped(keys, ClassType.PLAYER_INPUT, "shift"));
            Boolean sprint = bool(bridge.callMapped(keys, ClassType.PLAYER_INPUT, "sprint"));
            Object move = bridge.callMapped(input, ClassType.CLIENT_INPUT, "getMoveVector");
            Number strafe = number(bridge.readField(move, ClassType.VEC2, "x"));
            Number forwardImpulse = number(bridge.readField(move, ClassType.VEC2, "y"));
            if (forward == null || backward == null || left == null || right == null
                    || jump == null || sneak == null || sprint == null) {
                return null;
            }
            float strafeValue = strafe != null ? strafe.floatValue() : (left ? 1f : 0f) - (right ? 1f : 0f);
            float forwardValue = forwardImpulse != null ? forwardImpulse.floatValue()
                    : (forward ? 1f : 0f) - (backward ? 1f : 0f);
            return new MovementInputEvent(bridge, input, true, forwardValue, strafeValue,
                    jump, sneak, sprint);
        }
        Number forward = number(bridge.readField(input, ClassType.INPUT, "forwardImpulse"));
        Number strafe = number(bridge.readField(input, ClassType.INPUT, "leftImpulse"));
        Boolean jump = bool(bridge.readField(input, ClassType.INPUT, "jumping"));
        Boolean sneak = bool(bridge.readField(input, ClassType.INPUT, "shiftKeyDown"));
        if (forward == null || strafe == null || jump == null || sneak == null) {
            return null;
        }
        Object player = bridge.player();
        Boolean sprint = bool(bridge.callMapped(player, ClassType.ENTITY, "isSprinting"));
        return new MovementInputEvent(bridge, input, false, forward.floatValue(), strafe.floatValue(),
                jump, sneak, sprint != null && sprint);
    }

    /** 将订阅者修改写回游戏；全部失败时返回 false，调用方只记录一次诊断。 */
    boolean apply() {
        forward = clamp(forward);
        strafe = clamp(strafe);
        if (!changed()) {
            return true;
        }
        if (modern) {
            float length = (float) Math.sqrt(forward * forward + strafe * strafe);
            if (length > 1.0f) {
                // tick 尾部已越过原版归一化；写回时保持同样的单位圆约束。
                forward /= length;
                strafe /= length;
            }
            boolean forwardKey = forward > 0.0f;
            boolean backwardKey = forward < 0.0f;
            boolean leftKey = strafe > 0.0f;
            boolean rightKey = strafe < 0.0f;
            Object keys = bridge.construct(ClassType.PLAYER_INPUT, forwardKey, backwardKey,
                    leftKey, rightKey, jump, sneak, sprint);
            Object move = bridge.construct(ClassType.VEC2, strafe, forward);
            return keys != null && move != null
                    && bridge.writeField(input, ClassType.CLIENT_INPUT, "keyPresses", keys)
                    && bridge.writeField(input, ClassType.CLIENT_INPUT, "moveVector", move);
        }
        boolean written = bridge.writeField(input, ClassType.INPUT, "forwardImpulse", forward)
                && bridge.writeField(input, ClassType.INPUT, "leftImpulse", strafe)
                && bridge.writeField(input, ClassType.INPUT, "jumping", jump)
                && bridge.writeField(input, ClassType.INPUT, "shiftKeyDown", sneak);
        if (written && sprint != originalSprint) {
            Object player = bridge.player();
            bridge.callMapped(player, ClassType.ENTITY, "setSprinting", sprint);
        }
        return written;
    }

    private boolean changed() {
        return forward != originalForward || strafe != originalStrafe || jump != originalJump
                || sneak != originalSneak || sprint != originalSprint;
    }

    private static float clamp(float value) {
        return !Float.isNaN(value) && !Float.isInfinite(value)
                ? Math.max(-1.0f, Math.min(1.0f, value)) : 0.0f;
    }

    private static Number number(Object value) {
        return value instanceof Number ? (Number) value : null;
    }

    private static Boolean bool(Object value) {
        return value instanceof Boolean ? (Boolean) value : null;
    }
}
