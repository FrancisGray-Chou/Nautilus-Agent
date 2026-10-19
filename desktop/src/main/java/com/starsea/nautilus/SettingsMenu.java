package com.starsea.nautilus;

import java.awt.*;
import java.awt.event.*;
import java.awt.geom.Path2D;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.*;

/** Upward disclosure anchored in the sidebar, with reversible motion and keyboard access. */
@SuppressWarnings("serial")
final class SettingsMenu extends JPanel implements I18n.Localized {
    private static final int HEADER=44, ROW=40;
    private static final Color MAGENTA=new Color(0xE448A5);
    private static final Shape GEAR=createGear();
    private final JPanel menu=new JPanel(null);
    private final Entry toggle, keys, languages, exit;
    private final List<Entry> choices=new ArrayList<>();
    private boolean expanded, languageOpen, listening, motionEnabled=true;
    private double reveal, languageReveal;
    private final Motion.Tween disclosure = new Motion.Tween(0), languageDisclosure = new Motion.Tween(0);
    private long last;
    private final Timer animation=new Timer(Motion.FRAME_MS,e->tick());
    private final AWTEventListener outside=e->{
        if(expanded && e instanceof MouseEvent m && m.getID()==MouseEvent.MOUSE_PRESSED
            && m.getSource() instanceof Component c && !SwingUtilities.isDescendingFrom(c,this))collapse();
    };
    SettingsMenu(Runnable credentials,Consumer<I18n.Language> change,Runnable quit) {
        setName("settings-menu");setOpaque(false);setLayout(null);setAlignmentX(LEFT_ALIGNMENT);
        menu.setOpaque(false);add(menu);
        toggle=new Entry("settings-toggle","设置",false,null); add(toggle);
        keys=new Entry("settings-credentials","API 密钥与隐私",false,null);
        languages=new Entry("settings-language","语言",false,null);
        exit=new Entry("settings-exit","退出",true,null);
        menu.add(keys);menu.add(languages);menu.add(exit);
        for(I18n.Language language:I18n.Language.values()) {
            Entry entry=new Entry("language-"+language.code,language.label,false,language);choices.add(entry);menu.add(entry);
            entry.addActionListener(e->{ change.accept(language);refreshLanguage(); });
        }
        toggle.addActionListener(e->{expanded=!expanded; if(!expanded)languageOpen=false;start();focusability();});
        keys.addActionListener(e->{collapse();credentials.run();});
        languages.addActionListener(e->{languageOpen=!languageOpen;start();focusability();});
        exit.addActionListener(e->{collapse();quit.run();});
        getInputMap(WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(KeyStroke.getKeyStroke("ESCAPE"),"dismiss");
        getActionMap().put("dismiss",new AbstractAction(){@Override public void actionPerformed(ActionEvent e){
            if(languageOpen){languageOpen=false;languages.requestFocusInWindow();start();focusability();}else{collapse();toggle.requestFocusInWindow();}
        }});
        for(Entry entry:all()) {
            entry.getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke("ENTER"),"activate");
            entry.getActionMap().put("activate",new AbstractAction(){@Override public void actionPerformed(ActionEvent e){entry.doClick(0);}});
            for(String direction:List.of("UP","DOWN")) {
                entry.getInputMap(WHEN_FOCUSED).put(KeyStroke.getKeyStroke(direction),direction);
                entry.getActionMap().put(direction,new AbstractAction(){@Override public void actionPerformed(ActionEvent e){
                    if(!expanded){expanded=true;start();focusability();}
                    List<Entry> available=all().stream().filter(c->c.isFocusable()&&c.isEnabled()).toList();
                    int index=available.indexOf(entry),step=direction.equals("UP")?-1:1;
                    available.get(Math.floorMod(index+step,available.size())).requestFocusInWindow();
                }});
            }
        }
        focusability();refreshLanguage();
    }
    private List<Entry> all(){List<Entry> entries=new ArrayList<>();entries.add(keys);entries.add(languages);entries.addAll(choices);entries.add(exit);entries.add(toggle);return entries;}
    void credentialsEnabled(boolean enabled){keys.setEnabled(enabled);}
    void collapse(){expanded=false;languageOpen=false;focusability();start();}
    boolean isExpanded(){return expanded;}
    boolean isAnimating(){return animation.isRunning();}
    Color exitColor(){return exit.color();}
    private void focusability(){keys.setFocusable(expanded);languages.setFocusable(expanded);exit.setFocusable(expanded);for(Entry e:choices)e.setFocusable(expanded&&languageOpen);}
    private void start(){
        if(!motionEnabled)return;
        long now=System.nanoTime();disclosure.to(expanded?1:0,now);languageDisclosure.to(languageOpen?1:0,now);
        if(!animation.isRunning()){last=now;animation.start();}
    }
    private void tick(){
        long now=System.nanoTime();double dt=Motion.seconds(now,last);last=now;
        double next=disclosure.value(now),nextLanguage=languageDisclosure.value(now);
        boolean layoutChanged=reveal!=next||languageReveal!=nextLanguage;reveal=next;languageReveal=nextLanguage;
        boolean moving=disclosure.moving(now)||languageDisclosure.moving(now);
        for(Entry entry:all()){
            boolean visible=entry==toggle||expanded&&(entry.language==null||languageOpen);
            double target=entry.isEnabled()&&visible&&(entry.getModel().isRollover()||entry.hasFocus())?1:0;
            entry.hover=Motion.follow(entry.hover,target,dt);moving|=entry.hover!=target;
        }
        if(layoutChanged){revalidate();doLayout();Container parent=getParent();if(parent!=null){parent.invalidate();parent.revalidate();}}
        repaint();
        if(!moving)animation.stop();
    }
    private int bodyHeight(){return ROW*3+(int)Math.round(languageReveal*ROW*4)+10;}
    @Override public Dimension getPreferredSize(){return new Dimension(236,HEADER+(int)Math.round(reveal*bodyHeight()));}
    @Override public Dimension getMinimumSize(){return new Dimension(180,getPreferredSize().height);}
    @Override public Dimension getMaximumSize(){return new Dimension(Integer.MAX_VALUE,getPreferredSize().height);}
    @Override public void doLayout(){
        int h=Math.max(0,getHeight()-HEADER);menu.setBounds(0,0,getWidth(),h);toggle.setBounds(0,h,getWidth(),HEADER);
        keys.setBounds(0,0,getWidth(),ROW);languages.setBounds(0,ROW,getWidth(),ROW);
        int visible=(int)Math.round(languageReveal*ROW*4);
        for(int i=0;i<choices.size();i++){Entry choice=choices.get(i);choice.setBounds(8,ROW*2+i*ROW,getWidth()-8,ROW);choice.setVisible(i*ROW<visible);}
        exit.setBounds(0,ROW*2+visible+5,getWidth(),ROW);
        // Exit stays above clipped language rows during reverse animation.
        menu.setComponentZOrder(exit,0);
    }
    @Override public void refreshLanguage(){
        for(Entry entry:all())entry.getAccessibleContext().setAccessibleName(entry.language==null?I18n.t(entry.source):entry.language.flag+" "+entry.language.label);
        toggle.getAccessibleContext().setAccessibleDescription(I18n.t("设置"));revalidate();repaint();
    }
    @Override public void addNotify(){motionEnabled=true;super.addNotify();if(!listening){Toolkit.getDefaultToolkit().addAWTEventListener(outside,AWTEvent.MOUSE_EVENT_MASK);listening=true;}}
    @Override public void removeNotify(){
        if(listening){Toolkit.getDefaultToolkit().removeAWTEventListener(outside);listening=false;}
        motionEnabled=false;expanded=languageOpen=false;reveal=languageReveal=0;disclosure.reset(0);languageDisclosure.reset(0);focusability();
        for(Entry entry:all()){Motion.reset(entry);entry.hover=0;}
        super.removeNotify();animation.stop();
    }
    private final class Entry extends JButton {
        final String source;final boolean danger;final I18n.Language language;double hover;
        Entry(String name,String source,boolean danger,I18n.Language language){
            this.source=source;this.danger=danger;this.language=language;setName(name);setOpaque(false);setContentAreaFilled(false);setBorderPainted(false);setFocusPainted(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));setRolloverEnabled(true);getModel().addChangeListener(e->start());
            addFocusListener(new FocusAdapter(){@Override public void focusGained(FocusEvent e){start();}@Override public void focusLost(FocusEvent e){start();}});
        }
        Color color(){return Motion.blend(Theme.SURFACE,danger?MAGENTA:new Color(0x33304C),hover);}
        @Override protected void paintComponent(Graphics graphics){
            Graphics2D g=(Graphics2D)graphics.create();Theme.antialias(g);int w=getWidth(),h=getHeight();
            boolean current=language!=null&&language==I18n.language();g.setColor(current?new Color(0x2D2943):color());g.fillRoundRect(0,2,w-1,h-4,12,12);
            if(hasFocus()){g.setColor(danger?MAGENTA:Theme.ACCENT);g.drawRoundRect(1,3,w-3,h-6,12,12);}
            int x=14;g.setFont(Theme.font(12,true));g.setColor(isEnabled()?Theme.TEXT:Theme.MUTED);
            if(this==toggle){paintGear(g,23,h/2.0,reveal*Math.PI/2);x=44;}
            if(language!=null){FlagIcon.paint(g,language,12,12);x=46;}
            String label=language==null?I18n.t(source):language.label;
            if(this==languages && I18n.language()!=I18n.Language.EN)label+=" / Language";
            // Long Hungarian labels remain readable inside the sidebar.
            if(g.getFontMetrics().stringWidth(label)>w-x-28)g.setFont(Theme.font(11,true));
            g.drawString(label,x,25);
            if(current){g.setColor(Theme.MINT);g.drawString("✓",w-22,25);}
            if(this==toggle||this==languages){g.setColor(Theme.MUTED);double angle=(this==toggle?reveal:languageReveal)*Math.PI;g.translate(w-17,21);g.rotate(angle);g.drawLine(-3,-2,0,1);g.drawLine(0,1,3,-2);}
            g.dispose();
        }
    }
    /** Geometric center stays fixed on every frame, independent of platform fonts. */
    static void paintGear(Graphics2D source,double x,double y,double angle){
        Graphics2D g=(Graphics2D)source.create();Theme.antialias(g);g.translate(x,y);g.rotate(angle);
        g.fill(GEAR);g.dispose();
    }
    private static Shape createGear(){
        Path2D outline=new Path2D.Double();
        for(int i=0;i<64;i++){
            double a=i*Math.PI/32,r=(i%8>=2&&i%8<=5)?11:8.5;
            double px=Math.cos(a)*r,py=Math.sin(a)*r;
            if(i==0)outline.moveTo(px,py);else outline.lineTo(px,py);
        }
        outline.closePath();Area gear=new Area(outline);gear.subtract(new Area(new Ellipse2D.Double(-4,-4,8,8)));
        return gear;
    }
    /** Vector flags keep the requested symbols visible even without an emoji font. */
    private static final class FlagIcon {
        static void paint(Graphics2D source,I18n.Language language,int x,int y){
            Graphics2D g=(Graphics2D)source.create();g.translate(x,y);g.setClip(0,0,26,17);
            switch(language){
                case HU -> {g.setColor(new Color(0xCE2939));g.fillRect(0,0,26,6);g.setColor(Color.WHITE);g.fillRect(0,6,26,5);g.setColor(new Color(0x477050));g.fillRect(0,11,26,6);}
                case EN -> {g.setColor(Color.WHITE);g.fillRect(0,0,26,17);g.setColor(new Color(0xB22234));for(int i=0;i<13;i+=2)g.fillRect(0,i*17/13,26,2);g.setColor(new Color(0x3C3B6E));g.fillRect(0,0,12,9);g.setColor(Color.WHITE);for(int row=0;row<3;row++)for(int col=0;col<4;col++)g.fillOval(1+col*3,1+row*3,1,1);}
                case ZH_CN -> {g.setColor(new Color(0xDE2910));g.fillRect(0,0,26,17);g.setColor(new Color(0xFFDE00));star(g,5,5,3);star(g,10,2,1);star(g,12,4,1);star(g,12,7,1);star(g,10,9,1);}
                case ZH_HK -> {g.setColor(new Color(0xDE2910));g.fillRect(0,0,26,17);g.translate(13,8.5);for(int i=0;i<5;i++){g.rotate(Math.PI*2/5);g.setColor(Color.WHITE);g.fillOval(-2,-7,5,7);g.setColor(new Color(0xDE2910));star(g,0,-4,.7);}}
            }g.dispose();
        }
        static void star(Graphics2D g,double x,double y,double radius){Path2D p=new Path2D.Double();for(int i=0;i<10;i++){double a=-Math.PI/2+i*Math.PI/5,r=i%2==0?radius:radius*.4,px=x+Math.cos(a)*r,py=y+Math.sin(a)*r;if(i==0)p.moveTo(px,py);else p.lineTo(px,py);}p.closePath();g.fill(p);}
    }
}
