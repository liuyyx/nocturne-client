package dev.nocturne.client.action;

import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.mapping.IdentityMapping;
import dev.nocturne.client.mapping.Mapping;
import org.junit.jupiter.api.Test;

import java.io.StringReader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** 候选绑定、真实调用与一次性降级。 */
class ActionBinderTest {

    @Test
    void firstMissFallsThroughToNextCandidateAndInvokesRealMethod() {
        List<String> messages = new ArrayList<String>();
        ActionBinder binder = new ActionBinder(contract(true), new FixtureMapping(),
                ActionBinderTest.class.getClassLoader(), messages::add);
        binder.bind();

        FakeGameMode receiver = new FakeGameMode(false);
        assertTrue(binder.isAvailable("attack"), messages::toString);
        assertTrue(binder.invoke("attack", receiver, new FakePlayer(), new FakeEntity()));
        assertEquals(1, receiver.calls);
        assertTrue(messages.isEmpty());
    }

    @Test
    void allCandidatesMissDisablesActionWithoutRepeatedLogging() {
        List<String> messages = new ArrayList<String>();
        ActionBinder binder = new ActionBinder(contract(false), new IdentityMapping(),
                ActionBinderTest.class.getClassLoader(), messages::add);
        binder.bind();

        assertFalse(binder.isAvailable("attack"));
        assertFalse(binder.invoke("attack", new Object()));
        assertFalse(binder.invoke("attack", new Object()));
        assertEquals(1, messages.size());
        assertTrue(messages.get(0).contains("no candidate matched"));
    }

    @Test
    void invocationFailureDisablesOnlyThatActionOnce() {
        List<String> messages = new ArrayList<String>();
        ActionBinder binder = new ActionBinder(contract(false), new FixtureMapping(),
                ActionBinderTest.class.getClassLoader(), messages::add);
        binder.bind();
        FakeGameMode receiver = new FakeGameMode(true);

        assertTrue(binder.isAvailable("attack"), messages::toString);
        assertFalse(binder.invoke("attack", receiver, new FakePlayer(), new FakeEntity()));
        assertFalse(binder.isAvailable("attack"));
        assertFalse(binder.invoke("attack", receiver, new FakePlayer(), new FakeEntity()));
        assertEquals(1, receiver.calls);
        assertEquals(1, messages.size());
        assertTrue(messages.get(0).contains("invocation failed"));
    }

    @Test
    void mainHandUsesMappedStaticFieldName() {
        String json = "{\"version\":1,\"actions\":[{"
                + "\"id\":\"useItem\",\"signature\":\"boolean useItem()\","
                + "\"receiver\":\"gameMode\",\"operation\":\"useItem\",\"degrade\":\"disable\","
                + "\"candidates\":[{"
                + "\"owner\":\"MULTI_PLAYER_GAME_MODE\",\"method\":\"useItem\","
                + "\"parameters\":[\"" + FakePlayer.class.getName() + "\","
                + "\"" + FakeHand.class.getName() + "\"]}]}]}";
        ActionBinder binder = new ActionBinder(ActionContract.parse(new StringReader(json)),
                new FixtureMapping(), ActionBinderTest.class.getClassLoader(),
                new java.util.ArrayList<String>()::add);

        binder.bind();

        assertTrue(binder.isAvailable("useItem"));
        assertEquals(FakeHand.PRIMARY, binder.mainHand());
    }

    @Test
    void signatureCandidateSkipsMissingArityAndKeepsArgumentOrder() {
        String first = "{\"owner\":\"MULTI_PLAYER_GAME_MODE\",\"method\":\"useItem\","
                + "\"parameters\":[\"" + FakePlayer.class.getName() + "\","
                + "\"" + FakeLevel.class.getName() + "\","
                + "\"" + FakeHand.class.getName() + "\"],"
                + "\"arguments\":[\"player\",\"level\",\"hand\"]},";
        String second = "{\"owner\":\"MULTI_PLAYER_GAME_MODE\",\"method\":\"useItem\","
                + "\"parameters\":[\"" + FakePlayer.class.getName() + "\","
                + "\"" + FakeHand.class.getName() + "\"],"
                + "\"arguments\":[\"player\",\"hand\"]}";
        String json = "{\"version\":1,\"actions\":[{"
                + "\"id\":\"useItem\",\"signature\":\"boolean useItem()\","
                + "\"receiver\":\"gameMode\",\"operation\":\"useItem\",\"degrade\":\"disable\","
                + "\"candidates\":[" + first + second + "]}]}";
        ActionBinder binder = new ActionBinder(ActionContract.parse(new StringReader(json)),
                new FixtureMapping(), ActionBinderTest.class.getClassLoader(),
                new java.util.ArrayList<String>()::add);

        binder.bind();

        assertTrue(binder.isAvailable("useItem"));
        assertEquals(Arrays.asList("player", "hand"), binder.arguments("useItem"));
    }

    @Test
    void movePacketAcceptsHorizontalCollisionShape() {
        String json = "{\"version\":1,\"actions\":[{"
                + "\"id\":\"sendRotation\",\"signature\":\"x\",\"receiver\":\"connection\","
                + "\"operation\":\"moveRotFactory\",\"degrade\":\"disable\",\"candidates\":[]}]}";
        ActionBinder binder = new ActionBinder(ActionContract.parse(new StringReader(json)),
                new FixtureMapping(), ActionBinderTest.class.getClassLoader(),
                new java.util.ArrayList<String>()::add);
        binder.bind();

        FakeMoveRot packet = (FakeMoveRot) binder.newMoveRot(10f, 20f, true);
        assertTrue(binder.isAvailable("sendRotation"));
        assertEquals(10f, packet.yaw);
        assertEquals(20f, packet.pitch);
        assertTrue(packet.onGround);
        assertFalse(packet.horizontalCollision);
    }

    @Test
    void mixedMovementPacketShapesUseTheirOwnArity() {
        String rot = "{\"id\":\"sendRotation\",\"signature\":\"x\",\"receiver\":\"connection\","
                + "\"operation\":\"moveRotFactory\",\"degrade\":\"disable\",\"candidates\":[]},";
        String posRot = "{\"id\":\"sendPositionRotation\",\"signature\":\"x\",\"receiver\":\"connection\","
                + "\"operation\":\"movePosRotFactory\",\"degrade\":\"disable\",\"candidates\":[]}";
        String json = "{\"version\":1,\"actions\":[" + rot + posRot + "]}";
        ActionBinder binder = new ActionBinder(ActionContract.parse(new StringReader(json)),
                new FixtureMapping(), ActionBinderTest.class.getClassLoader(),
                new java.util.ArrayList<String>()::add);
        binder.bind();

        FakeMovePosRot packet = (FakeMovePosRot) binder.newMovePosRot(1, 2, 3, 10f, 20f, true);
        assertNotNull(packet);
        assertTrue(packet.onGround);
    }

    @Test
    void noArgPunchPacketIsUsedWhenSwingClassIsAbsent() {
        String json = "{\"version\":1,\"actions\":[{"
                + "\"id\":\"swingPacket\",\"signature\":\"x\",\"receiver\":\"connection\","
                + "\"operation\":\"swingFactory\",\"degrade\":\"disable\",\"candidates\":[]}]}";
        ActionBinder binder = new ActionBinder(ActionContract.parse(new StringReader(json)),
                new FixtureMapping(), ActionBinderTest.class.getClassLoader(),
                new java.util.ArrayList<String>()::add);
        binder.bind();

        assertTrue(binder.isAvailable("swingPacket"));
        assertTrue(binder.newSwing(false) instanceof FakePunch);
    }

    @Test
    void clickModeUsesStableLegacyIntegerValues() {
        ActionBinder binder = new ActionBinder(contract(false), new FixtureMapping(),
                ActionBinderTest.class.getClassLoader(), new java.util.ArrayList<String>()::add);

        assertEquals(5, binder.clickMode("quick_craft"));
        assertEquals(6, binder.clickMode("pickup_all"));
        assertTrue(binder.clickMode("unknown") == null);
    }

    private static ActionContract contract(boolean includeMissingCandidate) {
        String missing = "{\"owner\":\"CONNECTION\",\"method\":\"send\","
                + "\"parameters\":[\"net.minecraft.network.protocol.Packet\"]},";
        String json = "{\"version\":1,\"actions\":[{"
                + "\"id\":\"attack\",\"signature\":\"boolean attack(Object target)\","
                + "\"receiver\":\"gameMode\",\"operation\":\"method\",\"degrade\":\"disable\","
                + "\"candidates\":[" + (includeMissingCandidate ? missing : "")
                + "{\"owner\":\"MULTI_PLAYER_GAME_MODE\",\"method\":\"attack\","
                + "\"parameters\":[\"" + FakePlayer.class.getName() + "\","
                + "\"" + FakeEntity.class.getName() + "\"]}]}]}";
        return ActionContract.parse(new StringReader(json));
    }

    public static final class FakeGameMode {
        private final boolean fail;
        private int calls;

        public FakeGameMode(boolean fail) {
            this.fail = fail;
        }

        public void attack(FakePlayer player, FakeEntity target) {
            calls++;
            if (fail) {
                throw new IllegalStateException("expected");
            }
        }

        public void useItem(FakePlayer player, FakeHand hand) {
            calls++;
            if (fail) {
                throw new IllegalStateException("expected");
            }
        }
    }

    public static final class FakeHand {
        public static final FakeHand PRIMARY = new FakeHand();
        public static final FakeHand SECONDARY = new FakeHand();
    }

    public static final class FakeMoveRot {
        private final float yaw;
        private final float pitch;
        private final boolean onGround;
        private final boolean horizontalCollision;

        public FakeMoveRot(float yaw, float pitch, boolean onGround, boolean horizontalCollision) {
            this.yaw = yaw;
            this.pitch = pitch;
            this.onGround = onGround;
            this.horizontalCollision = horizontalCollision;
        }
    }

    public static final class FakeMovePosRot {
        private final boolean onGround;

        public FakeMovePosRot(double x, double y, double z, float yaw, float pitch, boolean onGround) {
            this.onGround = onGround;
        }
    }

    public static final class FakePunch {
        public FakePunch() {
        }
    }

    public static final class FakeLevel {
    }

    public static final class FakePlayer {
    }

    public static final class FakeEntity {
    }

    private static final class FixtureMapping implements Mapping {
        private final IdentityMapping delegate = new IdentityMapping();

        @Override
        public String describe() {
            return "test fixtures";
        }

        @Override
        public String className(ClassType type) {
            if (type == ClassType.MULTI_PLAYER_GAME_MODE) {
                return FakeGameMode.class.getName();
            }
            if (type == ClassType.INTERACTION_HAND) {
                return FakeHand.class.getName();
            }
            if (type == ClassType.SERVERBOUND_MOVE_ROT) {
                return FakeMoveRot.class.getName();
            }
            if (type == ClassType.SERVERBOUND_MOVE_POS_ROT) {
                return FakeMovePosRot.class.getName();
            }
            if (type == ClassType.SERVERBOUND_PUNCH) {
                return FakePunch.class.getName();
            }
            return delegate.className(type);
        }

        @Override
        public String methodName(ClassType owner, String canonicalName, String descriptor) {
            return canonicalName;
        }

        @Override
        public String fieldName(ClassType owner, String canonicalName) {
            return owner == ClassType.INTERACTION_HAND && "MAIN_HAND".equals(canonicalName)
                    ? "PRIMARY" : canonicalName;
        }

        @Override
        public boolean isIdentity() {
            return true;
        }
    }
}
