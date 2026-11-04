package dev.nocturne.client.event;

import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.mapping.IdentityMapping;
import dev.nocturne.client.mapping.Mapping;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 移动输入的 record 与字段两种实际形状。 */
class MovementInputEventTest {

    @Test
    void modernInputReplacesRecordAndNormalizesOnlyWhenAboveUnitLength() {
        FakeClientInput input = new FakeClientInput();
        MovementInputEvent event = MovementInputEvent.read(new GameBridge(null, new FixtureMapping()), input);
        event.forward = 1.0f;
        event.strafe = 1.0f;
        event.jump = true;
        event.sprint = true;

        assertTrue(event.apply());

        assertNotSame(input.originalKeys, input.keyPresses);
        assertTrue(input.keyPresses.forward());
        assertTrue(input.keyPresses.jump());
        assertTrue(input.keyPresses.sprint());
        assertEquals(1.0, Math.sqrt(input.moveVector.x * input.moveVector.x
                + input.moveVector.y * input.moveVector.y), 1.0e-6);
    }

    @Test
    void unchangedModernInputDoesNotAllocateReplacement() {
        FakeClientInput input = new FakeClientInput();
        MovementInputEvent event = MovementInputEvent.read(new GameBridge(null, new FixtureMapping()), input);

        assertTrue(event.apply());

        assertSame(input.originalKeys, input.keyPresses);
        assertSame(input.originalMove, input.moveVector);
    }

    @Test
    void legacyInputWritesFieldsWithoutModernNormalization() {
        FakeLegacyInput input = new FakeLegacyInput();
        MovementInputEvent event = MovementInputEvent.read(new GameBridge(null, new FixtureMapping()), input);
        event.forward = 1.0f;
        event.strafe = 1.0f;
        event.jump = true;
        event.sneak = true;

        assertTrue(event.apply());

        assertEquals(1.0f, input.forwardImpulse);
        assertEquals(1.0f, input.leftImpulse);
        assertTrue(input.jumping);
        assertTrue(input.shiftKeyDown);
    }

    public static final class FakeInput {
        private final boolean forward;
        private final boolean backward;
        private final boolean left;
        private final boolean right;
        private final boolean jump;
        private final boolean shift;
        private final boolean sprint;

        public FakeInput(boolean forward, boolean backward, boolean left, boolean right,
                         boolean jump, boolean shift, boolean sprint) {
            this.forward = forward;
            this.backward = backward;
            this.left = left;
            this.right = right;
            this.jump = jump;
            this.shift = shift;
            this.sprint = sprint;
        }

        public boolean forward() { return forward; }
        public boolean backward() { return backward; }
        public boolean left() { return left; }
        public boolean right() { return right; }
        public boolean jump() { return jump; }
        public boolean shift() { return shift; }
        public boolean sprint() { return sprint; }
    }

    public static final class FakeVec2 {
        public final float x;
        public final float y;

        public FakeVec2(float x, float y) {
            this.x = x;
            this.y = y;
        }
    }

    public static final class FakeClientInput {
        private FakeInput originalKeys = new FakeInput(false, false, false, false, false, false, false);
        private FakeVec2 originalMove = new FakeVec2(0, 0);
        private FakeInput keyPresses = originalKeys;
        private FakeVec2 moveVector = originalMove;

        public FakeVec2 getMoveVector() {
            return moveVector;
        }
    }

    public static final class FakeLegacyInput {
        private float forwardImpulse;
        private float leftImpulse;
        private boolean jumping;
        private boolean shiftKeyDown;
    }

    private static final class FixtureMapping implements Mapping {
        private final IdentityMapping delegate = new IdentityMapping();

        @Override
        public String describe() {
            return "movement fixtures";
        }

        @Override
        public String className(ClassType type) {
            if (type == ClassType.PLAYER_INPUT) {
                return FakeInput.class.getName();
            }
            if (type == ClassType.VEC2) {
                return FakeVec2.class.getName();
            }
            return delegate.className(type);
        }

        @Override
        public String methodName(ClassType owner, String canonicalName, String descriptor) {
            return canonicalName;
        }

        @Override
        public String fieldName(ClassType owner, String canonicalName) {
            return canonicalName;
        }

        @Override
        public boolean isIdentity() {
            return true;
        }
    }
}
