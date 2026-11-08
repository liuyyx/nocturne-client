package dev.nocturne.client.game;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.TestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** 人称极性与降级：两个字段面都缺失时返回 -1（未知），写回失败必须报 false。 */
class PerspectiveFallbackTest {

    @BeforeEach
    void setUp() {
        TestSupport.resetClient();
        FakeMinecraft.reset();
        NocturneClient.boot(null).setGameBridge(
                new GameBridge(null, new TestMapping(FakeMinecraft.class)));
    }

    @AfterEach
    void tearDown() {
        TestSupport.resetClient();
    }

    @Test
    void unknownPerspectiveIsMinusOne() {
        FakeMinecraft.instance.options = new Object();
        GameBridge bridge = NocturneClient.get().gameBridge();
        assertEquals(-1, bridge.perspective());
        assertFalse(bridge.setPerspective(1), "nothing was written, so this must report failure");
    }

    @Test
    void modernCameraTypeIsReadThroughOptionInstance() {
        FakeMinecraft.instance.options = new FakeCameraOptions("THIRD_PERSON_BACK");
        GameBridge bridge = NocturneClient.get().gameBridge();
        assertEquals(1, bridge.perspective());
    }

    @Test
    void legacyThirdPersonViewStillWins() {
        FakeMinecraft.instance.options = new FakeThirdPersonOptions(0);
        GameBridge bridge = NocturneClient.get().gameBridge();
        assertEquals(0, bridge.perspective());
    }

    public static final class FakeCameraOptions {
        public final FakeCameraOption cameraType;

        FakeCameraOptions(String value) {
            this.cameraType = new FakeCameraOption(value);
        }
    }

    public static final class FakeCameraOption {
        private final String value;

        FakeCameraOption(String value) {
            this.value = value;
        }

        public Object get() {
            return value;
        }

        public boolean set(Object replacement) {
            return replacement != null;
        }
    }

    public static final class FakeThirdPersonOptions {
        public int thirdPersonView;

        FakeThirdPersonOptions(int value) {
            this.thirdPersonView = value;
        }
    }
}
