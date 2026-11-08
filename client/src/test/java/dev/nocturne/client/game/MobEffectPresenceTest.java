package dev.nocturne.client.game;

import org.junit.jupiter.api.Test;

import java.util.Collections;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Speed 的 Slowness 判定必须按效果对象/旧 id 识别，不能靠本地化字符串。 */
class MobEffectPresenceTest {

    /** 1.8.9 Potion / 现代 MobEffect 的最小替身。 */
    static final class Effect {
        final int id;

        Effect(int id) {
            this.id = id;
        }
    }

    /** 1.21+ Holder&lt;MobEffect&gt; 的最小替身。 */
    static final class Holder {
        private final Effect value;

        Holder(Effect value) {
            this.value = value;
        }

        public Effect value() {
            return value;
        }
    }

    /** PotionEffect/MobEffectInstance 的最小替身：getEffect() 返回效果或 Holder。 */
    static final class Instance {
        private final Object effect;

        Instance(Object effect) {
            this.effect = effect;
        }

        public Object getEffect() {
            return effect;
        }
    }

    private static boolean matches(java.util.List<?> effects, Effect expected) {
        return GameBridge.containsEffect(effects, expected, Effect.class,
                effect -> Integer.valueOf(((Effect) effect).id));
    }

    @Test
    void identityMatchIsDetectedWithoutReadingToString() {
        Effect slowness = new Effect(2);
        assertTrue(matches(Collections.singletonList(new Instance(slowness)), slowness));
    }

    /** 1.8.9 的旧 id 路径：不同实例但 id 相同也算命中。 */
    @Test
    void legacyIdMatchIsDetected() {
        assertTrue(matches(Collections.singletonList(new Instance(new Effect(2))), new Effect(2)));
    }

    @Test
    void differentEffectIsNotReportedAsSlowness() {
        assertFalse(matches(Collections.singletonList(new Instance(new Effect(1))), new Effect(2)));
    }

    /** 1.21+ MobEffectInstance#getEffect 返回 Holder，需要再解一层。 */
    @Test
    void modernHolderIsUnwrapped() {
        Effect slowness = new Effect(2);
        assertTrue(matches(Collections.singletonList(new Instance(new Holder(slowness))), slowness));
    }

    @Test
    void missingConstantOrEmptyEffectsIsFalse() {
        assertFalse(GameBridge.containsEffect(Collections.emptyList(), null, Effect.class,
                effect -> Integer.valueOf(((Effect) effect).id)));
        assertFalse(matches(Collections.emptyList(), new Effect(2)));
    }
}
