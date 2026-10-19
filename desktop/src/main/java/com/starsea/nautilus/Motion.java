package com.starsea.nautilus;

import java.awt.Color;
import javax.swing.AbstractButton;

/** Shared frame cadence, easing and reversible transitions for desktop controls. */
final class Motion {
    static final int FRAME_MS = 16;
    static final long DURATION = 220_000_000L;
    static final long FEEDBACK_DURATION = 450_000_000L;
    private Motion() {}

    static double seconds(long now, long previous) { return Math.max(0, (now - previous) / 1e9); }
    static double follow(double value, double target, double seconds) {
        double next = value + (target - value) * (1 - Math.exp(-24 * seconds));
        return Math.abs(next - target) < .001 ? target : next;
    }
    static Color blend(Color from, Color to, double amount) {
        double t = Math.max(0, Math.min(1, amount));
        return new Color((int) Math.round(from.getRed() + (to.getRed() - from.getRed()) * t),
            (int) Math.round(from.getGreen() + (to.getGreen() - from.getGreen()) * t),
            (int) Math.round(from.getBlue() + (to.getBlue() - from.getBlue()) * t));
    }
    static void reset(AbstractButton button) {
        button.getModel().setArmed(false); button.getModel().setPressed(false); button.getModel().setRollover(false);
    }
    static final class Tween {
        private double from, target;
        private long started;
        Tween(double value) { reset(value); }
        void reset(double value) { from = target = value; started = 0; }
        void to(double value, long now) {
            if (target == value) return;
            from = value(now); target = value; started = now;
        }
        double value(long now) {
            double t = started == 0 ? 1 : Math.max(0, Math.min(1, (now - started) / (double) DURATION));
            return from + (target - from) * (1 - Math.pow(1 - t, 3));
        }
        boolean moving(long now) { return from != target && now - started < DURATION; }
    }
}
