package dev.nocturne.client.module.modules.render;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.TestSupport;
import dev.nocturne.client.event.EntityRenderEvent;
import dev.nocturne.client.game.FakeMinecraft;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.game.TestMapping;
import dev.nocturne.client.mapping.ClassType;
import dev.nocturne.client.render.RenderCapabilities;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Chams 深度恢复：快照原值、按原值还原，嵌套时必须用栈。
 *
 * <p>旧实现 post/onStop 硬编码 {@code depthTest(true)}：进入时深度本来就是关的版本会被
 * 强行打开（HUD/世界遮挡错乱）；嵌套实体渲染时单槽 ThreadLocal 让内层覆盖外层，
 * 外层 post 会把内层快照当成自己的原值。
 */
class ChamsDepthRestoreTest {

    @AfterEach
    void tearDown() {
        TestSupport.resetClient();
    }

    @Test
    void nestedEntityRenderRestoresEachDepthSnapshot() {
        DepthState capabilities = new DepthState();
        enable(capabilities);

        post(false);
        post(false);

        assertFalse(capabilities.depth, "both pre events must disable depth");

        post(true);
        assertFalse(capabilities.depth, "inner post must restore the inner snapshot (false), not true");

        post(true);
        assertTrue(capabilities.depth, "outer post must restore the original true");
    }

    /** 进入时深度本来就是关闭的：post 之后必须仍是关闭。 */
    @Test
    void restoreUsesTheSnapshotInsteadOfHardcodedTrue() {
        DepthState capabilities = new DepthState();
        capabilities.depth = false;
        enable(capabilities);

        post(false);
        post(true);

        assertFalse(capabilities.depth, "原深度是 false 时不得硬编码还原为 true");
    }

    /** 无法查询原状态的后端：不修改深度，也不在 post 猜一个值写回去。 */
    @Test
    void unknownDepthIsNeverOverwritten() {
        DepthState capabilities = new DepthState();
        capabilities.queryable = false;
        enable(capabilities);

        post(false);
        post(true);

        assertTrue(capabilities.calls.isEmpty(), "无法快照时不得改深度或猜默认值");
        assertTrue(capabilities.depth, "unknown-depth backend must keep its original state");
    }

    /** pre 内 depthTest 抛异常：快照必须弹栈，后续 post 不得写坏状态。 */
    @Test
    void failingPreDoesNotLeakASnapshot() {
        DepthState capabilities = new DepthState();
        capabilities.failNextDisable = true;
        enable(capabilities);

        post(false);
        post(true);

        assertTrue(capabilities.depth, "failed pre must leave the original depth untouched");
        assertTrue(capabilities.calls.isEmpty(),
                "failed pre must pop its snapshot; the following post must not write anything");
    }

    /** 走真实事件总线：模块在 onStart 里订阅的是 EntityRenderEvent。 */
    private static void post(boolean post) {
        NocturneClient.get().events().post(new EntityRenderEvent(FakeMinecraft.instance.player, post));
    }

    private static void enable(RenderCapabilities capabilities) {
        NocturneClient client = NocturneClient.boot(null);
        FakeMinecraft.reset();
        Object player = new FakePlayer();
        FakeMinecraft.instance.player = player;
        client.setGameBridge(new GameBridge(null,
                new TestMapping(FakeMinecraft.class).classOverride(ClassType.PLAYER, FakePlayer.class)));
        client.setRenderCapabilities(capabilities);
        ChamsModule module = new ChamsModule();
        module.setEnabled(true);
    }

    /** 替身玩家：只用于类型判断与“本地玩家”比较。 */
    public static final class FakePlayer {
    }

    /** 深度状态替身：记录快照次数与 true/false 写入序列。 */
    private static final class DepthState implements RenderCapabilities {
        boolean depth = true;
        boolean queryable = true;
        boolean failNextDisable;
        final List<Boolean> calls = new ArrayList<Boolean>();

        @Override
        public void depthTest(boolean enabled) {
            if (!enabled && failNextDisable) {
                failNextDisable = false;
                throw new IllegalStateException("depth write rejected");
            }
            depth = enabled;
            calls.add(Boolean.valueOf(enabled));
        }

        @Override
        public Boolean depthTestEnabled() {
            if (!queryable) {
                return null;
            }
            return Boolean.valueOf(depth);
        }

        @Override
        public void texture2d(boolean enabled) {
        }

        @Override
        public void color(int argb) {
        }

        @Override
        public void polygonOffset(boolean enabled) {
        }

        @Override
        public int[] frameSize() {
            return null;
        }

        @Override
        public boolean readPixels(int width, int height, int[] out) {
            return false;
        }
    }
}
