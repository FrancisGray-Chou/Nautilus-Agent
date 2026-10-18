package com.starsea.nautilus;

import java.awt.*;
import javax.swing.*;
import javax.swing.plaf.basic.BasicScrollBarUI;

final class Theme {
    static final Color BG = new Color(0x0D111B), SIDE = new Color(0x111724), SURFACE = new Color(0x171F30);
    static final Color LINE = new Color(0x2A3448), TEXT = new Color(0xEEF1F8), MUTED = new Color(0x94A2BA);
    static final Color ACCENT = new Color(0xB9ABFF), MINT = new Color(0x72DEBF), ERROR = new Color(0xFFA4AA);
    private Theme() {}

    static Font font(int size, boolean bold) { return new Font(Font.SANS_SERIF, bold ? Font.BOLD : Font.PLAIN, size); }
    static JLabel label(String text, int size, Color color, boolean bold) {
        JLabel label = new JLabel(text);
        label.setFont(font(size, bold)); label.setForeground(color);
        return label;
    }
    static void antialias(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    }
    static void scroll(JScrollPane scroll) {
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(BG);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        JScrollBar bar = scroll.getVerticalScrollBar();
        bar.setUnitIncrement(24); bar.setPreferredSize(new Dimension(8, 0));
        bar.setUI(new BasicScrollBarUI() {
            @Override protected void configureScrollBarColors() { thumbColor = LINE; trackColor = BG; }
            @Override protected JButton createDecreaseButton(int orientation) { return zero(); }
            @Override protected JButton createIncreaseButton(int orientation) { return zero(); }
            private JButton zero() { JButton b = new JButton(); b.setPreferredSize(new Dimension()); return b; }
        });
    }

    @SuppressWarnings("serial")
    static class Surface extends JPanel {
        final Color fill, stroke;
        final int radius;
        Surface(Color fill, Color stroke, int radius) {
            this.fill = fill; this.stroke = stroke; this.radius = radius; setOpaque(false);
        }
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create(); antialias(g);
            g.setColor(fill); g.fillRoundRect(0, 0, getWidth(), getHeight(), radius, radius);
            if (stroke != null) { g.setColor(stroke); g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, radius, radius); }
            g.dispose(); super.paintComponent(graphics);
        }
    }

    @SuppressWarnings("serial")
    static class Button extends JButton {
        private final boolean primary;
        Button(String text, boolean primary) {
            super(text); this.primary = primary;
            setFont(font(13, true)); setForeground(primary ? BG : TEXT);
            setBorder(BorderFactory.createEmptyBorder(10, 16, 10, 16));
            setContentAreaFilled(false); setFocusPainted(false); setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        }
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create(); antialias(g);
            Color fill = primary ? ACCENT : SURFACE;
            if (getModel().isRollover()) fill = fill.brighter();
            if (!isEnabled()) fill = primary ? new Color(0x49465E) : SIDE;
            g.setColor(fill); g.fillRoundRect(0, 0, getWidth(), getHeight(), 14, 14);
            if (!primary) { g.setColor(LINE); g.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 14, 14); }
            g.dispose(); super.paintComponent(graphics);
        }
    }

    @SuppressWarnings("serial")
    static final class Logo extends JComponent {
        Logo(int size) { setPreferredSize(new Dimension(size, size)); }
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create(); antialias(g);
            int w = Math.min(getWidth(), getHeight());
            g.setPaint(new GradientPaint(0, 0, MINT, w, w, ACCENT));
            g.fillRoundRect(0, 0, w, w, w / 3, w / 3);
            g.setColor(BG); g.setStroke(new BasicStroke(Math.max(2f, w / 22f), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            double center = w / 2.0;
            java.awt.geom.Path2D path = new java.awt.geom.Path2D.Double();
            for (int i = 0; i <= 90; i++) {
                double angle = i / 90.0 * Math.PI * 4.6;
                double radius = w * (0.04 + 0.28 * i / 90.0);
                double x = center + Math.cos(angle) * radius, y = center + Math.sin(angle) * radius;
                if (i == 0) path.moveTo(x, y); else path.lineTo(x, y);
            }
            g.draw(path); g.dispose();
        }
    }
}
