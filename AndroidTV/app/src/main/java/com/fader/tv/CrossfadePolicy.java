package com.fader.tv;

/** Supported crossfade lengths in seconds. 0 means no crossfade (hard hand-off). */
public final class CrossfadePolicy {
    private CrossfadePolicy() { }
    private static final int[] STEPS = {0, 3, 5, 8, 10};

    /** Advance to the next supported length, wrapping 10 back to 0. */
    public static int next(int current) {
        for (int i = 0; i < STEPS.length; i++) {
            if (STEPS[i] == current) return STEPS[(i + 1) % STEPS.length];
        }
        return clamp(current);
    }

    /** Nearest supported length, so an old persisted value never produces an invalid step. */
    public static int clamp(int seconds) {
        int best = STEPS[0];
        for (int s : STEPS) if (Math.abs(s - seconds) <= Math.abs(best - seconds)) best = s;
        return best;
    }

    public static boolean valid(int seconds) {
        for (int s : STEPS) if (s == seconds) return true;
        return false;
    }
}
