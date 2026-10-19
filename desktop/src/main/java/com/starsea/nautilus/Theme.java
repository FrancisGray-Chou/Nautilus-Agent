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
        JLabel label = new I18n.Label(text);
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

    /** Keep cards within the viewport when a vertical scrollbar appears. */
    @SuppressWarnings("serial")
    static final class ScrollBody extends JPanel implements Scrollable {
        ScrollBody(LayoutManager layout) { super(layout); setOpaque(false); }
        @Override public Dimension getPreferredScrollableViewportSize() { return getPreferredSize(); }
        @Override public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) { return 24; }
        @Override public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) { return Math.max(24, visible.height - 24); }
        @Override public boolean getScrollableTracksViewportWidth() { return true; }
        @Override public boolean getScrollableTracksViewportHeight() { return false; }
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
    static class Button extends JButton implements I18n.Localized {
        private String source, restoreSource;
        private final boolean primary;
        private double hover;
        private boolean motionEnabled=true;
        private long last, pulseStart, feedbackUntil;
        private final Timer animation = new Timer(Motion.FRAME_MS,event -> tick());
        @Override public void setText(String text) { source=text; super.setText(I18n.t(text)); }
        @Override public void refreshLanguage() { super.setText(I18n.t(source)); }
        Button(String text, boolean primary) {
            super(text); this.primary = primary;
            setFont(font(13, true)); setForeground(primary ? BG : TEXT);
            setBorder(BorderFactory.createEmptyBorder(10, 16, 10, 16));
            setContentAreaFilled(false); setFocusPainted(false); setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));setRolloverEnabled(true);
            getModel().addChangeListener(event -> start());
            addActionListener(event -> {pulseStart=System.nanoTime();start();});
            addFocusListener(new java.awt.event.FocusAdapter() {
                @Override public void focusGained(java.awt.event.FocusEvent e){start();}
                @Override public void focusLost(java.awt.event.FocusEvent e){start();}
            });
        }
        void feedback(String text) {
            if(restoreSource==null)restoreSource=source;
            setText(text);feedbackUntil=System.nanoTime()+1_200_000_000L;start();
        }
        boolean isAnimating(){return animation.isRunning();}
        private void start(){if(motionEnabled&&!animation.isRunning()){last=System.nanoTime();animation.start();}}
        private void tick(){
            long now=System.nanoTime();double dt=Motion.seconds(now,last);last=now;
            double target=isEnabled()&&(getModel().isRollover()||hasFocus())?1:0;
            hover=Motion.follow(hover,target,dt);
            if(restoreSource!=null&&now>=feedbackUntil){String old=restoreSource;restoreSource=null;setText(old);}
            repaint();
            if(hover==target&&(pulseStart==0||now-pulseStart>Motion.FEEDBACK_DURATION)&&restoreSource==null)animation.stop();
        }
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create(); antialias(g);
            Color base=primary?ACCENT:SURFACE,target=primary?ACCENT.brighter():new Color(0x302D46);
            Color fill=Motion.blend(base,target,hover);
            if(!isEnabled())fill=primary?new Color(0x49465E):SIDE;
            if(getModel().isPressed())g.translate(0,1);
            g.setColor(fill);g.fillRoundRect(0,0,getWidth(),getHeight(),14,14);
            if(!primary){g.setColor(Motion.blend(LINE,new Color(0x7769A6),hover));g.drawRoundRect(0,0,getWidth()-1,getHeight()-1,14,14);}
            if(hasFocus()){g.setColor(primary?BG:ACCENT);g.drawRoundRect(2,2,getWidth()-5,getHeight()-5,12,12);}
            double pulse=(System.nanoTime()-pulseStart)/(double)Motion.FEEDBACK_DURATION;
            if(pulseStart>0&&pulse<1){g.setClip(new java.awt.geom.RoundRectangle2D.Double(0,0,getWidth(),getHeight(),14,14));g.setColor(new Color(185,171,255,(int)(70*(1-pulse))));double r=(getWidth()+getHeight())*pulse;g.fillOval((int)(getWidth()/2.0-r),(int)(getHeight()/2.0-r),(int)(r*2),(int)(r*2));}
            super.paintComponent(g);g.dispose();
        }
        @Override public void removeNotify(){
            motionEnabled=false;Motion.reset(this);hover=0;pulseStart=last=feedbackUntil=0;
            if(restoreSource!=null){String old=restoreSource;restoreSource=null;setText(old);}
            super.removeNotify();animation.stop();
        }
        @Override public void addNotify(){motionEnabled=true;super.addNotify();}
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
