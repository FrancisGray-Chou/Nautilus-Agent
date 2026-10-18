package com.starsea.nautilus;

import java.awt.*;
import javax.swing.*;

@SuppressWarnings("serial")
final class ModelCard extends JButton {
    final ChatService.Model option;
    private boolean active;

    ModelCard(ChatService.Model option, Runnable select) {
        this.option = option;
        setAlignmentX(LEFT_ALIGNMENT);
        setPreferredSize(new Dimension(236, 76)); setMaximumSize(new Dimension(Integer.MAX_VALUE, 76));
        setContentAreaFilled(false); setBorderPainted(false); setFocusPainted(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setToolTipText(option.name() + " · " + option.subtitle());
        addActionListener(event -> select.run());
    }
    void active(boolean active) { this.active = active; repaint(); }
    @Override protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create(); Theme.antialias(g);
        int w = getWidth(), h = getHeight();
        g.setColor(active ? new Color(0x29283F) : getModel().isRollover() ? Theme.SURFACE : Theme.SIDE);
        g.fillRoundRect(0, 0, w - 1, h - 1, 16, 16);
        if (active) { g.setColor(new Color(0x7769A6)); g.drawRoundRect(0, 0, w - 1, h - 1, 16, 16); }
        Color icon = switch (option.provider()) {
            case "DeepSeek" -> new Color(0x8AB2FF);
            case "OpenAI" -> Theme.MINT;
            default -> Theme.ACCENT;
        };
        g.setColor(new Color(icon.getRed(), icon.getGreen(), icon.getBlue(), 25));
        g.fillRoundRect(12, 20, 34, 34, 12, 12);
        g.setFont(Theme.font(17, true)); g.setColor(isEnabled() ? icon : Theme.MUTED);
        g.drawString(option.provider().substring(0, 1), 22, 43);
        g.setColor(isEnabled() ? Theme.TEXT : Theme.MUTED); g.setFont(Theme.font(13, true));
        g.drawString(option.name(), 58, 31);
        g.setColor(Theme.MUTED); g.setFont(Theme.font(10, false));
        g.drawString(option.subtitle(), 58, 51);
        if (active) { g.setColor(Theme.ACCENT); g.fillOval(w - 22, 14, 6, 6); }
        if (hasFocus()) { g.setColor(Theme.ACCENT); g.drawRoundRect(2, 2, w - 5, h - 5, 14, 14); }
        g.dispose();
    }
}
