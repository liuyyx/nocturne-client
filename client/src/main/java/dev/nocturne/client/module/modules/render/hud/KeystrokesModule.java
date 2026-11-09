package dev.nocturne.client.module.modules.render.hud;

import dev.nocturne.client.NocturneClient;
import dev.nocturne.client.event.EventBus;
import dev.nocturne.client.event.KeyEvent;
import dev.nocturne.client.event.MouseButtonEvent;
import dev.nocturne.client.game.GameBridge;
import dev.nocturne.client.module.Category;
import dev.nocturne.client.module.HudModule;
import dev.nocturne.client.runtime.PhysicalKeys;
import dev.nocturne.client.value.BooleanValue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** 显示 WASD/空格与左右键状态，并记录真实输入 CPS。 */
public final class KeystrokesModule extends HudModule {
    /** 事件回退用的键码（AWT）：W/A/S/D/空格。 */
    private static final int KEY_W = 87;
    private static final int KEY_A = 65;
    private static final int KEY_S = 83;
    private static final int KEY_D = 68;
    private static final int KEY_SPACE = 32;

    private final BooleanValue showSpacebar = add(new BooleanValue("Show Spacebar", true));
    private final BooleanValue showCpsOnly = add(new BooleanValue("Show CPS Only", false));
    private final boolean[] keys = new boolean[512];
    private final long[] leftClicks = new long[64];
    private final long[] rightClicks = new long[64];
    private int leftAt;
    private int rightAt;
    private final List<EventBus.Subscription> subscriptions = new ArrayList<EventBus.Subscription>();
    private final Supplier<String> line = new Supplier<String>() {
        @Override
        public String get() {
            long now = System.currentTimeMillis();
            int left = 0;
            int right = 0;
            for (long time : leftClicks) if (now - time <= 1000L) left++;
            for (long time : rightClicks) if (now - time <= 1000L) right++;
            boolean[] movement = movementState();
            return formatLine(movement[0], movement[1], movement[2], movement[3], movement[4],
                    showSpacebar.get(), left, right, showCpsOnly.get());
        }
    };

    /**
     * 移动键状态：物理快照（UI 每帧轮询）优先，其次是游戏 {@code KeyMapping#isDown}，
     * 最后才是按键事件——事件在原生 Screen 打开时被抑制，单独依赖它会卡在旧状态。
     *
     * @return {@code [前进, 左, 后退, 右, 跳跃]}
     */
    private boolean[] movementState() {
        boolean[] state = PhysicalKeys.available()
                ? PhysicalKeys.movement()
                : new boolean[]{keys[KEY_W], keys[KEY_A], keys[KEY_S], keys[KEY_D], keys[KEY_SPACE]};
        NocturneClient client = NocturneClient.get();
        GameBridge bridge = client == null ? null : client.gameBridge();
        if (bridge != null) {
            try {
                boolean[] actual = bridge.movementKeys();
                for (int i = 0; i < state.length && i < actual.length; i++) {
                    state[i] |= actual[i];
                }
            } catch (Throwable ignored) {
                // KeyMapping 读不到时物理快照仍然是有效输入。
            }
        }
        return state;
    }

    /**
     * 拼一行按键显示（纯函数，便于单测）。
     *
     * @param forward     前进
     * @param left        左
     * @param back        后退
     * @param right       右
     * @param jump        跳跃
     * @param showSpacebar 是否显示空格段
     * @param leftCps     左键 CPS
     * @param rightCps    右键 CPS
     * @param cpsOnly     只显示 CPS
     * @return 显示文本
     */
    static String formatLine(boolean forward, boolean left, boolean back, boolean right,
                             boolean jump, boolean showSpacebar,
                             int leftCps, int rightCps, boolean cpsOnly) {
        if (cpsOnly) {
            return leftCps + " / " + rightCps + " CPS";
        }
        String text = (forward ? "W" : "w") + " " + (left ? "A" : "a") + " "
                + (back ? "S" : "s") + " " + (right ? "D" : "d")
                + "  " + leftCps + "/" + rightCps + " CPS";
        return showSpacebar ? text + (jump ? "  [SPACE]" : "  [space]") : text;
    }

    @Override
    protected void onHudStart() {
        NocturneClient client = NocturneClient.get();
        if (client == null) {
            return;
        }
        subscriptions.add(client.events().subscribe(KeyEvent.class, event -> {
            if (event.keyCode >= 0 && event.keyCode < keys.length) {
                keys[event.keyCode] = event.pressed;
            }
        }));
        subscriptions.add(client.events().subscribe(MouseButtonEvent.class, event -> {
            if (!event.pressed) return;
            long now = System.currentTimeMillis();
            if (event.button == 0) leftClicks[leftAt++ & 63] = now;
            if (event.button == 1) rightClicks[rightAt++ & 63] = now;
        }));
    }

    @Override
    protected void onHudStop() {
        NocturneClient client = NocturneClient.get();
        if (client != null) for (EventBus.Subscription subscription : subscriptions) {
            client.events().unsubscribe(subscription);
        }
        subscriptions.clear();
        // 停用后重新启用时不能沿用停用前的按下态（真机：W 一直高亮）。
        java.util.Arrays.fill(keys, false);
    }

    @Override public String name() { return "Keystrokes"; }
    @Override public Category category() { return Category.RENDER; }
    @Override protected String hudId() { return "keystrokes"; }
    @Override protected Supplier<String> hudText() { return line; }
}
