package com.starsea.nautilus;

import java.awt.*;
import java.awt.event.AWTEventListener;
import java.awt.event.ActionEvent;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.accessibility.AccessibleContext;
import javax.accessibility.AccessibleState;
import javax.accessibility.AccessibleStateSet;
import javax.swing.*;

/** Inline, animated model disclosure; shares the sidebar's card geometry. */
@SuppressWarnings("serial")
class ModelSelector extends JPanel implements I18n.Localized {
    private static final int ROW_HEIGHT = 48;
    private final int headerHeight;
    private final String provider, prefix;
    private final Color accent;
    private final List<ChatService.Model> options;
    private final List<OptionButton> rows = new ArrayList<>();
    private String confirmedId;
    private boolean active, expanded, listening, motionEnabled = true;
    private double reveal;
    private final Motion.Tween disclosure = new Motion.Tween(0);
    private long lastTick;
    private final Header header = new Header();
    private final Motion.Loop animation = new Motion.Loop(event -> tick());
    private final AWTEventListener outsideClick = event -> {
        if (expanded && event instanceof MouseEvent mouse && mouse.getID() == MouseEvent.MOUSE_PRESSED
                && mouse.getSource() instanceof Component source && !SwingUtilities.isDescendingFrom(source, this)) setExpanded(false);
    };

    ModelSelector(String provider, String prefix, List<ChatService.Model> options, Consumer<String> select) {
        this(provider, prefix, options, select, false);
    }
    ModelSelector(String provider, String prefix, List<ChatService.Model> options, Consumer<String> select, boolean compact) {
        headerHeight = compact ? 44 : 76;
        this.provider = provider; this.prefix = prefix;
        accent = prefix.endsWith("deepseek") ? Theme.color(0x8AB2FF,0x3566B8) : prefix.endsWith("claude") ? Theme.color(0xDFA88C,0xA65832) : Theme.ACCENT;
        this.options = List.copyOf(options);
        confirmedId = options.isEmpty() ? null : options.get(0).id();
        setName(prefix + "-models"); setOpaque(false); setLayout(null); setAlignmentX(LEFT_ALIGNMENT);
        header.setName(prefix + "-toggle"); add(header);
        for (ChatService.Model option : options) {
            OptionButton row = new OptionButton(option);
            row.setName(prefix + "-option-" + option.id()); I18n.tooltip(row, option.subtitle());
            row.getAccessibleContext().setAccessibleName(provider + " " + shortName(option));
            row.addActionListener(event -> {
                if (!isEnabled() || !expanded) return;
                setExpanded(false); header.requestFocusInWindow(); select.accept(option.id());
            });
            rows.add(row); add(row);
        }
        header.addActionListener(event -> { if (isEnabled() && !options.isEmpty()) setExpanded(!expanded); });
        bind(this, JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT, "ESCAPE", "collapse", () -> { setExpanded(false); header.requestFocusInWindow(); });
        bind(header, JComponent.WHEN_FOCUSED, "DOWN", "first-option", () -> focusOption(0));
        bind(header, JComponent.WHEN_FOCUSED, "UP", "last-option", () -> focusOption(rows.size() - 1));
        for (int i = 0; i < rows.size(); i++) {
            int index = i;
            bind(rows.get(i), JComponent.WHEN_FOCUSED, "DOWN", "next-option", () -> focusOption((index + 1) % rows.size()));
            bind(rows.get(i), JComponent.WHEN_FOCUSED, "UP", "previous-option", () -> focusOption((index + rows.size() - 1) % rows.size()));
            bind(rows.get(i), JComponent.WHEN_FOCUSED, "HOME", "first-option", () -> focusOption(0));
            bind(rows.get(i), JComponent.WHEN_FOCUSED, "END", "last-option", () -> focusOption(rows.size() - 1));
        }
        updateAccessibility();
    }

    @Override public void refreshLanguage() { updateAccessibility(); repaint(); }

    void sync(String modelId) {
        active = modelId != null && options.stream().anyMatch(option -> option.id().equals(modelId));
        if (active) confirmedId = modelId;
        updateAccessibility(); repaint();
    }
    void working(boolean busy) {
        setEnabled(!busy); header.setEnabled(!busy);
        for (OptionButton row : rows) row.setEnabled(!busy);
        if (busy) setExpanded(false);
        repaint();
    }
    String selectedModelId() { return confirmedId; }
    boolean isExpanded() { return expanded; }
    boolean isAnimating() { return animation.isRunning(); }

    private void focusOption(int index) {
        if (!isEnabled() || rows.isEmpty()) return;
        setExpanded(true); rows.get(index).requestFocusInWindow();
    }
    private void setExpanded(boolean value) {
        if (value == expanded) return;
        expanded = value; disclosure.to(value ? 1 : 0, System.nanoTime());
        for (OptionButton row : rows) row.setFocusable(value);
        updateAccessibility(); startAnimation();
    }
    private void startAnimation() {
        if (motionEnabled && !animation.isRunning()) { lastTick = System.nanoTime(); animation.start(); }
    }
    private void tick() {
        long now = System.nanoTime();
        double step = Motion.seconds(now, lastTick); lastTick = now;
        double next = disclosure.value(now);
        if (reveal != next) {
            reveal = next;
            // BoxLayout caches child size limits; refresh them on every animation frame.
            Container parent = getParent();
            if (parent != null) { parent.invalidate(); parent.revalidate(); }
            revalidate(); doLayout();
        }
        boolean fading = header.fade(step);
        for (OptionButton row : rows) fading |= row.fade(step);
        repaint();
        if (!disclosure.moving(now) && !fading) animation.stop();
    }
    private void updateAccessibility() {
        String name = options.stream().filter(option -> option.id().equals(confirmedId)).map(ModelSelector::shortName).findFirst().orElse("未配置");
        header.getAccessibleContext().setAccessibleName(I18n.t(provider + " 模型选择，" + name + (active ? "，当前使用" : "")));
        header.getAccessibleContext().setAccessibleDescription(I18n.t(expanded ? "已展开；上下方向键选择，Enter 确认，Escape 收起" : "点击或按 Enter 展开模型分支"));
        I18n.tooltip(header, provider + " · " + name + " · 点击" + (expanded ? "收起" : "选择分支"));
    }
    @Override public Dimension getPreferredSize() { return new Dimension(236, headerHeight + (int) Math.round(reveal * (rows.size() * ROW_HEIGHT + 12))); }
    @Override public Dimension getMinimumSize() { return new Dimension(160, getPreferredSize().height); }
    @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
    @Override public void doLayout() {
        header.setBounds(0, 0, getWidth(), headerHeight);
        for (int i = 0; i < rows.size(); i++) rows.get(i).setBounds(42, headerHeight + 4 + i * ROW_HEIGHT, Math.max(1, getWidth() - 54), ROW_HEIGHT - 4);
    }
    @Override protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create(); Theme.antialias(g);
        Color fill = active ? Theme.SELECTED : blend(Theme.SIDE, Theme.SURFACE, Math.max(header.hover, reveal));
        g.setColor(fill); g.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 16, 16);
        if (active || reveal > 0) {
            g.setColor(active ? Theme.SELECTED_LINE : blend(Theme.SIDE, Theme.LINE, reveal));
            g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 16, 16);
        }
        if (reveal > 0) {
            g.setColor(Theme.color(0x49415F,0xC6BBDC)); g.drawLine(28, headerHeight + 9, 28, getHeight() - 18);
        }
        g.dispose();
    }
    @Override public void addNotify() {
        motionEnabled = true; super.addNotify();
        if (!listening) { Toolkit.getDefaultToolkit().addAWTEventListener(outsideClick, AWTEvent.MOUSE_EVENT_MASK); listening = true; }
    }
    @Override public void removeNotify() {
        if (listening) { Toolkit.getDefaultToolkit().removeAWTEventListener(outsideClick); listening = false; }
        motionEnabled = false; expanded = false; reveal = 0; disclosure.reset(0);
        Motion.reset(header); header.hover = 0;
        for (OptionButton row : rows) { row.setFocusable(false); Motion.reset(row); row.hover = 0; }
        super.removeNotify(); animation.stop();
    }
    private abstract class HoverButton extends JButton {
        double hover;
        HoverButton() {
            setOpaque(false); setContentAreaFilled(false); setBorderPainted(false); setFocusPainted(false);
            setRolloverEnabled(true);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            getModel().addChangeListener(event -> startAnimation());
            addFocusListener(new java.awt.event.FocusAdapter() {
                @Override public void focusGained(java.awt.event.FocusEvent event) { startAnimation(); }
                @Override public void focusLost(java.awt.event.FocusEvent event) { startAnimation(); }
            });
            bind(this, JComponent.WHEN_FOCUSED, "ENTER", "confirm", this::doClick);
        }
        boolean fade(double step) {
            double target = isEnabled() && (this == header || expanded) && (getModel().isRollover() || hasFocus()) ? 1 : 0;
            hover = Motion.follow(hover, target, step);
            return Math.abs(target - hover) > 0.001;
        }
    }
    private final class Header extends HoverButton {
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create(); Theme.antialias(g);
            if (headerHeight == 44) {
                String current = options.stream().filter(option -> option.id().equals(confirmedId)).map(ModelSelector::shortName).findFirst().orElse("选择分支");
                g.setColor(isEnabled() ? accent : Theme.MUTED); g.setFont(Theme.font(12, true));
                g.drawString(fit(g, I18n.t("子模型") + " · " + I18n.t(current), getWidth() - 60), 18, 27);
                g.translate(getWidth() - 24, 22); g.rotate(Math.PI * reveal);
                g.setStroke(new BasicStroke(1.6f)); g.drawLine(-4,-2,0,2); g.drawLine(0,2,4,-2); g.dispose();
                if (hasFocus()) { Graphics2D focus=(Graphics2D)graphics.create();focus.setColor(accent);focus.drawRoundRect(2,2,getWidth()-5,getHeight()-5,12,12);focus.dispose(); }
                return;
            }
            g.setColor(new Color(accent.getRed(), accent.getGreen(), accent.getBlue(), 25)); g.fillRoundRect(12, 20, 34, 34, 12, 12);
            g.setFont(Theme.font(17, true)); g.setColor(isEnabled() ? accent : Theme.MUTED); g.drawString(provider.substring(0, 1), 22, 43);
            g.setColor(isEnabled() ? Theme.TEXT : Theme.MUTED); g.setFont(Theme.font(13, true)); g.drawString(provider, 58, 31);
            String current = options.stream().filter(option -> option.id().equals(confirmedId)).map(ModelSelector::shortName).findFirst().orElse("选择分支");
            g.setFont(Theme.font(10, false)); g.setColor(Theme.MUTED);
            g.drawString(fit(g, I18n.t(isEnabled() ? current + (prefix.equals("gemini") ? " · Search" : "") : "正在处理…"), getWidth() - 98), 58, 51);
            if (active) { g.setColor(accent); g.fillOval(getWidth() - 43, 27, 5, 5); }
            g.setColor(isEnabled() ? Theme.MUTED : Theme.LINE); g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.translate(getWidth() - 23, 38); g.rotate(Math.PI * reveal);
            Path2D arrow = new Path2D.Double(); arrow.moveTo(-4, -2); arrow.lineTo(0, 2); arrow.lineTo(4, -2); g.draw(arrow);
            g.dispose();
            if (hasFocus()) {
                Graphics2D focus = (Graphics2D) graphics.create(); Theme.antialias(focus); focus.setColor(accent);
                focus.drawRoundRect(2, 2, getWidth() - 5, getHeight() - 5, 14, 14); focus.dispose();
            }
        }
        @Override public AccessibleContext getAccessibleContext() {
            if (accessibleContext == null) accessibleContext = new AccessibleJButton() {
                @Override public AccessibleStateSet getAccessibleStateSet() {
                    AccessibleStateSet states = super.getAccessibleStateSet(); states.add(AccessibleState.EXPANDABLE);
                    states.add(expanded ? AccessibleState.EXPANDED : AccessibleState.COLLAPSED); return states;
                }
            };
            return accessibleContext;
        }
    }
    private final class OptionButton extends HoverButton {
        final ChatService.Model option;
        OptionButton(ChatService.Model option) { this.option = option; setFocusable(false); }
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create(); Theme.antialias(g);
            boolean selected = option.id().equals(confirmedId);
            g.setComposite(AlphaComposite.SrcOver.derive((float) reveal));
            g.setColor(selected ? Theme.color(0x38324F,0xDDD3F1) : blend(Theme.color(0x202637,0xF8F9FD), Theme.color(0x303349,0xEBE8F6), hover));
            g.fillRoundRect(0, 0, getWidth(), getHeight(), 10, 10);
            g.setFont(Theme.font(12, true)); g.setColor(selected ? accent : Theme.TEXT);
            g.drawString(fit(g, I18n.t(shortName(option)), getWidth() - 38), 12, 18);
            g.setFont(Theme.font(9, false)); g.setColor(Theme.MUTED);
            String hint = !prefix.equals("gemini") ? option.subtitle() : option.id().endsWith("auto") ? "按任务选择 Flash / Argon" : option.id().endsWith("argon") ? "深度思考 · Search" : "扩展思考 · Search";
            g.drawString(fit(g, I18n.t(hint), getWidth() - 24), 12, 33);
            if (selected) {
                g.setColor(accent); g.setStroke(new BasicStroke(1.6f));
                Path2D check = new Path2D.Double(); check.moveTo(getWidth() - 24, 18); check.lineTo(getWidth() - 21, 21); check.lineTo(getWidth() - 16, 15); g.draw(check);
            }
            if (hasFocus()) { g.setColor(accent); g.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, 10, 10); }
            g.dispose();
        }
    }
    private static String shortName(ChatService.Model model) { return model.id().endsWith("auto") ? "自动" : model.name().replaceFirst("^(Gemini|DeepSeek|Claude)\\s*", ""); }
    private static String fit(Graphics2D graphics, String text, int width) {
        if (graphics.getFontMetrics().stringWidth(text) <= width) return text;
        while (!text.isEmpty() && graphics.getFontMetrics().stringWidth(text + "…") > width) text = text.substring(0, text.length() - 1);
        return text + "…";
    }
    private static Color blend(Color from, Color to, double fraction) {
        return Motion.blend(from, to, fraction);
    }
    private static void bind(JComponent target, int condition, String key, String name, Runnable action) {
        target.getInputMap(condition).put(KeyStroke.getKeyStroke(key), name);
        target.getActionMap().put(name, new AbstractAction() { @Override public void actionPerformed(ActionEvent event) { action.run(); } });
    }
}
