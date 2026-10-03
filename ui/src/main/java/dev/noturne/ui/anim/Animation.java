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

    /** Starts (restarts) a transition from the current value to {@code target}. */
    public void animateTo(float target, long nowMs) {
        if (target == this.to && !running) {
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
            if (elapsed >= durationMs) {
                value = to;
                running = false;
            } else {
                float t = durationMs <= 0L ? 1f : (float) elapsed / (float) durationMs;
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
