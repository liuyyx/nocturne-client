package dev.nocturne.client.game;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.TestSupport;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

/** Keystrokes 的键位来源：读游戏 KeyMapping 而不是写死键码。 */
class GameBridgeMovementKeysTest {

    private GameBridge bridge;

    @BeforeEach
    void setUp() {
        TestSupport.resetClient();
        FakeMinecraft.reset();
        NocturneClient client = NocturneClient.boot(null);
        bridge = new GameBridge(null, new TestMapping(FakeMinecraft.class));
        client.setGameBridge(bridge);
    }

    @AfterEach
    void tearDown() {
        TestSupport.resetClient();
        FakeMinecraft.reset();
    }

    @Test
    void readsModernKeyMappingFields() {
        Options options = new Options();
        options.keyUp.down = true;
        options.keyRight.down = true;
        options.keyJump.down = true;
        FakeMinecraft.instance.options = options;

        assertArrayEquals(new boolean[]{true, false, false, true, true}, bridge.movementKeys());
    }

    @Test
    void fallsBackToLegacyKeyBindFields() {
        LegacyOptions options = new LegacyOptions();
        options.keyBindLeft.down = true;
        options.keyBindBack.down = true;
        FakeMinecraft.instance.options = options;

        assertArrayEquals(new boolean[]{false, true, true, false, false}, bridge.movementKeys());
    }

    /** 替身 KeyMapping。 */
    public static final class Key {
        boolean down;

        public boolean isDown() {
            return down;
        }
    }

    /** 1.13+ 的 Options 字段名。 */
    public static final class Options {
        public Key keyUp = new Key();
        public Key keyDown = new Key();
        public Key keyLeft = new Key();
        public Key keyRight = new Key();
        public Key keyJump = new Key();
    }

    /** 1.8.9–1.12.2 的 Options 字段名。 */
    public static final class LegacyOptions {
        public Key keyBindForward = new Key();
        public Key keyBindBack = new Key();
        public Key keyBindLeft = new Key();
        public Key keyBindRight = new Key();
        public Key keyBindJump = new Key();
    }
}
