package dev.noturne.ui.anim;

/**
 * A single scalar transition between two values over a fixed duration.
 *
 * <p>Time is supplied by the caller (milliseconds) so the UI has no hidden clock and is fully
 * deterministic under test.
 */
public final class Animation {

    public enum Easing {
        LINEAR,
        EASE_OUT_CUBIC,
        EASE_IN_OUT_CUBIC
    }

    private final long durationMs;
    private final Easing easing;
    private float from;
    private float to;
    private float value;
    private long startMs;
    private boolean running;

    public Animation(long durationMs, Easing easing, float initial) {
        this.durationMs = durationMs;
        this.easing = easing;
        this.from = initial;
        this.to = initial;
        this.value = initial;
        this.startMs = 0L;
    }

    public Animation(long durationMs, Easing easing) {
        this(durationMs, easing, 0f);
    }

    /** Jumps to {@code target} with no animation. */
    public void set(float target) {
        this.from = target;
        this.to = target;
        this.value = target;
        this.running = false;
    }

    /**
     * Starts (or restarts) a transition from the current value to {@code target}.
     *
     * <p>Declaratively re-asserting an unchanged target is a normal call pattern (a component
     * says "I want to go to 1" every frame).  Time-base continuity is this class's own
     * invariant, so the de-duplication lives here rather than in every caller: if the target
     * did not change, an in-flight transition keeps its original {@code startMs} and simply
     * keeps advancing.  Without that, a per-frame {@code animateTo} would reset the start on
     * every frame, pinning {@code elapsed} to 0 and freezing the value at {@code from}.
     */
    public void animateTo(float target, long nowMs) {
        if (target == this.to) {
            // 目标未变：进行中则保持既有时间基准（由 update 继续推进），已到达则无事可做。
            return;
        }
        this.from = this.value;
        this.to = target;
        this.startMs = nowMs;
        this.running = true;
        if (durationMs <= 0L) {
            this.value = target;
            this.running = false;
        }
    }

    /** Advances the transition; returns the current value. */
    public float update(long nowMs) {
        if (running) {
            long elapsed = nowMs - startMs;
            // 时钟回退（或混用不同时间基准）会让 elapsed 为负，缓动函数随即产生极端值
            // （例如 EASE_OUT_CUBIC 在 t 为负时给出 -8e29）。这里把下界夹到 0。
            if (elapsed < 0L) {
                elapsed = 0L;
            }
            if (elapsed >= durationMs) {
                value = to;
                running = false;
            } else {
                float t = durationMs <= 0L ? 1f : (float) elapsed / (float) durationMs;
                if (t < 0f) {
                    t = 0f;
                } else if (t > 1f) {
                    t = 1f;
                }
                value = from + (to - from) * apply(t);
            }
        }
        return value;
    }

    public float value() {
        return value;
    }

    public float target() {
        return to;
    }

    public boolean isRunning() {
        return running;
    }

    private float apply(float t) {
        switch (easing) {
            case EASE_OUT_CUBIC:
                return 1f - (1f - t) * (1f - t) * (1f - t);
            case EASE_IN_OUT_CUBIC:
                return t < 0.5f ? 4f * t * t * t : 1f - (float) Math.pow(-2f * t + 2f, 3f) / 2f;
            case LINEAR:
            default:
                return t;
        }
    }
}
