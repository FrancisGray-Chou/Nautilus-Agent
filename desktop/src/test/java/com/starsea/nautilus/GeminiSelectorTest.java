package com.starsea.nautilus;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.swing.*;

/** Real EDT timer frames, interruption, keyboard dismissal and lifecycle cleanup. */
public final class GeminiSelectorTest {
    private GeminiSelectorTest() {}
    public static void main(String[] args) throws Exception {
        AtomicReference<GeminiSelector> holder = new AtomicReference<>();
        AtomicReference<String> requested = new AtomicReference<>("");
        SwingUtilities.invokeAndWait(() -> {
            GeminiSelector selector = new GeminiSelector(List.of(
                    new ChatService.Model("gemini-flash", "Gemini 3.8 Flash", "Thinking / Search", "Gemini", true),
                    new ChatService.Model("gemini-argon", "Gemini 4 Argon", "Thinking / Search", "Gemini", true),
                    new ChatService.Model("gemini-auto", "Gemini Auto", "Auto", "Gemini", true)), requested::set);
            holder.set(selector); selector.addNotify(); selector.setSize(236, 76); selector.doLayout();
            check(selector.getPreferredSize().height == 76, "collapsed card matches other models");
            button(selector, "gemini-toggle").doClick(0);
            check(selector.isExpanded() && requested.get().isEmpty(), "opening menu does not switch models");
        });
        GeminiSelector selector = holder.get();
        waitFor(() -> selector.getPreferredSize().height > 76 && selector.getPreferredSize().height < 232);
        SwingUtilities.invokeAndWait(() -> {
            int intermediate = selector.getPreferredSize().height;
            button(selector, "gemini-toggle").doClick(0);
            check(selector.getPreferredSize().height == intermediate, "reversing motion has no size jump");
        });
        waitFor(() -> !selector.isAnimating());
        SwingUtilities.invokeAndWait(() -> {
            check(selector.getPreferredSize().height == 76, "reverse returns to collapsed height");
            button(selector, "gemini-toggle").doClick(0);
        });
        waitFor(() -> !selector.isAnimating());
        SwingUtilities.invokeAndWait(() -> {
            check(selector.getPreferredSize().height == 232, "menu expands to full options height");
            selector.getActionMap().get("collapse").actionPerformed(new ActionEvent(selector, ActionEvent.ACTION_PERFORMED, "escape"));
            check(!selector.isExpanded() && requested.get().isEmpty(), "Escape dismisses without switching");
            button(selector, "gemini-toggle").doClick(0);
            button(selector, "gemini-option-gemini-argon").doClick(0);
            check(requested.get().equals("gemini-argon") && !selector.isExpanded(), "choice sends stable model ID and closes menu");
            check(selector.selectedModelId().equals("gemini-flash"), "pending choice does not replace confirmed model");
            selector.sync("deepseek"); check(selector.selectedModelId().equals("gemini-flash"), "failure keeps remembered preset");
            selector.sync("gemini-argon"); check(selector.selectedModelId().equals("gemini-argon"), "backend success confirms preset");
            selector.working(true); button(selector, "gemini-toggle").doClick(0);
            check(!selector.isExpanded() && !button(selector, "gemini-option-gemini-auto").isEnabled(), "busy state disables expansion and selection");
            selector.working(false); button(selector, "gemini-toggle").doClick(0);
            JPanel outside = new JPanel();
            outside.dispatchEvent(new MouseEvent(outside, MouseEvent.MOUSE_PRESSED, System.currentTimeMillis(), 0, 2, 2, 1, false));
            check(!selector.isExpanded(), "outside press dismisses inline menu");
            button(selector, "gemini-toggle").doClick(0);
            selector.removeNotify();
            check(!selector.isAnimating() && !selector.isExpanded(), "detaching stops timers and releases outside listener");
            button(selector, "gemini-toggle").getModel().setRollover(true);
            check(!selector.isAnimating(), "late rollover events cannot restart a detached selector");
        });
        System.out.println("PASS: smooth Gemini expansion/reversal, keyboard/outside dismissal, selection confirmation and timer lifecycle");
    }
    private static JButton button(GeminiSelector selector, String name) {
        for (Component component : selector.getComponents()) if (component instanceof JButton button && name.equals(button.getName())) return button;
        throw new AssertionError("missing button: " + name);
    }
    private static void waitFor(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
        while (System.nanoTime() < deadline) {
            boolean[] ready = {false}; SwingUtilities.invokeAndWait(() -> ready[0] = condition.getAsBoolean());
            if (ready[0]) return;
            Thread.sleep(10);
        }
        throw new AssertionError("animation did not reach expected state");
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
