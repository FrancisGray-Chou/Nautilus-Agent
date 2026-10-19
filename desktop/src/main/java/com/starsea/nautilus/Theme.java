package com.starsea.nautilus;

import java.awt.*;
import javax.swing.*;
import javax.swing.plaf.basic.BasicScrollBarUI;

final class Theme {
    private static final java.util.Map<Long,Color> COLORS=new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<Integer,Font> FONTS=new java.util.concurrent.ConcurrentHashMap<>();
    static final int CONTROL_RADIUS=14, CARD_RADIUS=20;
    static final Color BG = color(0x0D111B,0xF6F7FC), SIDE = color(0x111724,0xEEF0F8), SURFACE = color(0x171F30,0xFFFFFF);
    static final Color LINE = color(0x2A3448,0xD7DCEB), TEXT = color(0xEEF1F8,0x222A40), MUTED = color(0x94A2BA,0x65718A);
    static final Color ACCENT = color(0xB9ABFF,0x6C52C7), MINT = color(0x72DEBF,0x157A63), ERROR = color(0xFFA4AA,0xB83451);
    static final Color HOVER=color(0x252D42,0xE8EAF6), SELECTED=color(0x29283F,0xE5DEF6), SELECTED_LINE=color(0x7769A6,0x9C87CE);
    static final Color ON_ACCENT = color(0x0D111B,0xFFFFFF);
    private Theme() {}

    /** Stable palette references also update colors already held by Swing and borders. */
    static Color color(int dark, int light) { long key=((long)dark<<32)|(light&0xffffffffL);return COLORS.computeIfAbsent(key,k->new PaletteColor(dark,light)); }
    static String css(Color color) { return String.format("#%06x", color.getRGB() & 0xffffff); }
    @SuppressWarnings("serial")
    private static final class PaletteColor extends Color {
        private final int dark, light;
        PaletteColor(int dark, int light) { super(dark); this.dark=dark; this.light=light; }
        @Override public int getRGB() { return 0xff000000 | (Appearance.isDark() ? dark : light); }
        @Override public int getRed() { return (getRGB() >> 16) & 255; }
        @Override public int getGreen() { return (getRGB() >> 8) & 255; }
        @Override public int getBlue() { return getRGB() & 255; }
        @Override public int hashCode() { return getRGB(); }
        @Override public float[] getRGBComponents(float[] values) { return new Color(getRGB(),true).getRGBComponents(values); }
        @Override public float[] getRGBColorComponents(float[] values) { return new Color(getRGB(),true).getRGBColorComponents(values); }
        @Override public float[] getComponents(float[] values) { return getRGBComponents(values); }
        @Override public float[] getColorComponents(float[] values) { return getRGBColorComponents(values); }
        @Override public float[] getComponents(java.awt.color.ColorSpace space,float[] values) { return new Color(getRGB(),true).getComponents(space,values); }
        @Override public float[] getColorComponents(java.awt.color.ColorSpace space,float[] values) { return new Color(getRGB(),true).getColorComponents(space,values); }
    }

    static Font font(int size, boolean bold) { return FONTS.computeIfAbsent(size*2+(bold?1:0),key->new Font(Font.SANS_SERIF,bold?Font.BOLD:Font.PLAIN,size)); }
    static JLabel label(String text, int size, Color color, boolean bold) {
        JLabel label = new I18n.Label(text);
        label.setFont(font(size, bold)); label.setForeground(color);
        return label;
    }
    static void antialias(Graphics2D g) {
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
    }
    static AlphaComposite alpha(Graphics2D graphics,double amount){float inherited=graphics.getComposite() instanceof AlphaComposite composite?composite.getAlpha():1;return AlphaComposite.SrcOver.derive((float)Math.max(0,Math.min(1,inherited*amount)));}
    static void scroll(JScrollPane scroll) {
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.getViewport().setBackground(BG);
        scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        JScrollBar bar = scroll.getVerticalScrollBar();
        bar.setUnitIncrement(24); bar.setPreferredSize(new Dimension(8, 0));
        bar.setUI(new BasicScrollBarUI() {
            @Override protected void configureScrollBarColors() { thumbColor = LINE; trackColor = BG; }
            @Override protected void paintTrack(Graphics source,JComponent component,Rectangle bounds){source.setColor(scroll.getViewport().getBackground());source.fillRect(bounds.x,bounds.y,bounds.width,bounds.height);}
            @Override protected void paintThumb(Graphics source,JComponent component,Rectangle bounds){
                if(bounds.isEmpty()||!bar.isEnabled())return;Graphics2D g=(Graphics2D)source.create();antialias(g);
                g.setColor(isThumbRollover()?SELECTED_LINE:LINE);g.fillRoundRect(bounds.x+2,bounds.y+2,Math.max(3,bounds.width-4),Math.max(6,bounds.height-4),5,5);g.dispose();
            }
            @Override protected JButton createDecreaseButton(int orientation) { return zero(); }
            @Override protected JButton createIncreaseButton(int orientation) { return zero(); }
            private JButton zero() { JButton b = new JButton(); b.setPreferredSize(new Dimension()); return b; }
        });
        SmoothScroll.install(scroll);
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
    static final class FocusSurface extends Surface {
        private double focus;
        private boolean focused,attached=true;
        private long last;
        private final Motion.Loop animation=new Motion.Loop(e->{long now=System.nanoTime();focus=Motion.follow(focus,focused?1:0,Motion.seconds(now,last));last=now;repaint();if(focus==(focused?1:0))stop();});
        FocusSurface(Color fill,Color stroke,int radius,JComponent input){
            super(fill,stroke,radius);input.addFocusListener(new java.awt.event.FocusAdapter(){
                @Override public void focusGained(java.awt.event.FocusEvent e){focused=true;start();}
                @Override public void focusLost(java.awt.event.FocusEvent e){focused=false;start();}
            });
        }
        private void start(){if(attached&&!animation.isRunning()){last=System.nanoTime();animation.start();}}
        private void stop(){animation.stop();}
        @Override protected void paintComponent(Graphics source){super.paintComponent(source);if(focus<=0)return;Graphics2D g=(Graphics2D)source.create();antialias(g);g.setColor(Motion.blend(stroke,ACCENT,focus));g.drawRoundRect(0,0,getWidth()-1,getHeight()-1,radius,radius);g.dispose();}
        @Override public void removeNotify(){attached=false;focused=false;focus=0;stop();super.removeNotify();}
        @Override public void addNotify(){attached=true;super.addNotify();}
    }

    @SuppressWarnings("serial")
    static class Button extends JButton implements I18n.Localized {
        private String source, restoreSource;
        private final boolean primary;
        private double hover,press,selection;
        private boolean quiet;
        private boolean motionEnabled=true;
        private long last, pulseStart, feedbackUntil;
        private final Motion.Loop animation = new Motion.Loop(event -> tick());
        @Override public void setText(String text) { source=text; super.setText(I18n.t(text)); }
        @Override public void refreshLanguage() { super.setText(I18n.t(source)); }
        Button(String text, boolean primary) {
            super(text); this.primary = primary;
            setFont(font(13, true)); setForeground(primary ? ON_ACCENT : TEXT);
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
        Button quiet(){quiet=true;return this;}
        protected double hoverProgress(){return hover;}
        protected double pressProgress(){return press;}
        protected double selectionProgress(){return selection;}
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
            double down=isEnabled()&&getModel().isPressed()?1:0,chosen=isSelected()?1:0;
            press=Motion.follow(press,down,dt);selection=Motion.follow(selection,chosen,dt);
            if(restoreSource!=null&&now>=feedbackUntil){String old=restoreSource;restoreSource=null;setText(old);}
            repaint();
            if(hover==target&&press==down&&selection==chosen&&(pulseStart==0||now-pulseStart>Motion.FEEDBACK_DURATION)&&restoreSource==null)animation.stop();
        }
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create(); antialias(g);
            Color base=primary?ACCENT:SURFACE,target=primary?ACCENT.brighter():HOVER;
            Color fill=Motion.blend(base,target,hover);
            if(!isEnabled())fill=primary?Theme.color(0x49465E,0xD6CFE7):SIDE;
            g.translate(0,press);
            if(quiet){
                Composite original=g.getComposite();g.setComposite(alpha(g,Math.max(selection*.65,hover*.85)));
                g.setColor(selection>0?SELECTED:HOVER);g.fillRoundRect(0,0,getWidth(),getHeight(),CONTROL_RADIUS,CONTROL_RADIUS);g.setComposite(original);
            }else{
                g.setColor(fill);g.fillRoundRect(0,0,getWidth(),getHeight(),CONTROL_RADIUS,CONTROL_RADIUS);
                if(!primary){g.setColor(Motion.blend(LINE,SELECTED_LINE,hover));g.drawRoundRect(0,0,getWidth()-1,getHeight()-1,CONTROL_RADIUS,CONTROL_RADIUS);}
            }
            if(hasFocus()){g.setColor(primary?ON_ACCENT:ACCENT);g.drawRoundRect(2,2,getWidth()-5,getHeight()-5,12,12);}
            double pulse=(System.nanoTime()-pulseStart)/(double)Motion.FEEDBACK_DURATION;
            if(pulseStart>0&&pulse<1){g.clip(new java.awt.geom.RoundRectangle2D.Double(0,0,getWidth(),getHeight(),CONTROL_RADIUS,CONTROL_RADIUS));Color accent=ACCENT;g.setColor(new Color(accent.getRed(),accent.getGreen(),accent.getBlue(),(int)(45*(1-pulse))));double r=(getWidth()+getHeight())*pulse;g.fillOval((int)(getWidth()/2.0-r),(int)(getHeight()/2.0-r),(int)(r*2),(int)(r*2));}
            super.paintComponent(g);g.dispose();
        }
        @Override public void removeNotify(){
            motionEnabled=false;Motion.reset(this);hover=press=0;selection=isSelected()?1:0;pulseStart=last=feedbackUntil=0;
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
            NautilusIcon.paint(g,w,MINT,ACCENT,BG);g.dispose();
        }
    }
}
