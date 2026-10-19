package com.starsea.nautilus;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionListener;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Shared frame cadence, easing and reversible transitions for desktop controls. */
final class Motion {
    static final int FRAME_MS = 16;
    static final long DURATION = 220_000_000L;
    static final long FEEDBACK_DURATION = 450_000_000L;
    private static final Set<Loop> ACTIVE = new LinkedHashSet<>();
    private static final Timer CLOCK = new Timer(FRAME_MS,event -> {
        for(Loop loop:List.copyOf(ACTIVE))if(ACTIVE.contains(loop))loop.listener.actionPerformed(event);
    });
    private Motion() {}

    /** All active effects share one coalesced EDT clock; no clock runs while idle. */
    static final class Loop {
        private final ActionListener listener;
        Loop(ActionListener listener){this.listener=listener;}
        void start(){ACTIVE.add(this);if(!CLOCK.isRunning())CLOCK.start();}
        void stop(){ACTIVE.remove(this);if(ACTIVE.isEmpty())CLOCK.stop();}
        boolean isRunning(){return ACTIVE.contains(this);}
    }
    static int activeAnimations(){return ACTIVE.size();}
    static boolean clockRunning(){return CLOCK.isRunning();}

    @SuppressWarnings("serial")
    static final class RevealPanel extends JPanel {
        private final Tween opacity=new Tween(1);
        private final Loop animation=new Loop(e->{repaint();if(!opacity.moving(System.nanoTime()))animationStop();});
        RevealPanel(LayoutManager layout){super(layout);}
        void reveal(){if(!isShowing())return;opacity.reset(.25);opacity.to(1,System.nanoTime());animation.start();repaint();}
        private void animationStop(){animation.stop();}
        @Override protected void paintChildren(Graphics source){
            double value=opacity.value(System.nanoTime());Graphics2D g=(Graphics2D)source.create();
            g.setComposite(Theme.alpha(g,value));g.translate(0,Math.round(5*(1-value)));super.paintChildren(g);g.dispose();
        }
        @Override public void removeNotify(){animation.stop();opacity.reset(1);super.removeNotify();}
    }

    static double seconds(long now, long previous) { return Math.max(0, (now - previous) / 1e9); }
    static double follow(double value, double target, double seconds) {
        double next = value + (target - value) * (1 - Math.exp(-24 * seconds));
        return Math.abs(next - target) < .001 ? target : next;
    }
    static Color blend(Color from, Color to, double amount) {
        double t = Math.max(0, Math.min(1, amount));
        if(t==0)return from;if(t==1)return to;
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
