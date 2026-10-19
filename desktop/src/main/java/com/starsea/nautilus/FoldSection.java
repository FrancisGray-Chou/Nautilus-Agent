package com.starsea.nautilus;

import java.awt.*;
import javax.swing.*;

/** Inline disclosure with clipped content and reversible height animation. */
@SuppressWarnings("serial")
final class FoldSection extends JPanel implements I18n.Localized {
    private final JComponent body;
    private final Theme.Button toggle;
    private final String title;
    private boolean expanded, motionEnabled=true;
    private double reveal;
    private final Motion.Tween disclosure;
    private final Motion.Loop animation=new Motion.Loop(e->tick());
    FoldSection(String name,String title,JComponent body,boolean expanded) {
        this.body=body;this.title=title;this.expanded=expanded;reveal=expanded?1:0;disclosure=new Motion.Tween(reveal);
        setName(name);setOpaque(false);setLayout(null);setAlignmentX(LEFT_ALIGNMENT);
        toggle=new Theme.Button(title,false) {
            @Override protected void paintComponent(Graphics source) {
                Graphics2D g=(Graphics2D)source.create();Theme.antialias(g);
                Composite original=g.getComposite();g.setComposite(Theme.alpha(g,hoverProgress()));g.setColor(Theme.HOVER);g.fillRoundRect(0,0,getWidth(),getHeight(),12,12);g.setComposite(original);
                g.setFont(Theme.font(11,true));g.setColor(Theme.MUTED);g.drawString(I18n.t(title),8,23);
                g.translate(getWidth()-16,18);g.rotate(reveal*Math.PI);g.drawLine(-3,-2,0,1);g.drawLine(0,1,3,-2);g.dispose();
            }
        };
        toggle.setName(name+"-toggle");toggle.addActionListener(e->setExpanded(!this.expanded));
        add(toggle);add(body);body.setVisible(expanded);updateAccessibility();
    }
    @Override public void refreshLanguage(){updateAccessibility();repaint();}
    boolean isExpanded(){return expanded;}
    boolean isAnimating(){return animation.isRunning();}
    void setExpanded(boolean value){
        if(value==expanded)return;expanded=value;body.setVisible(true);disclosure.to(value?1:0,System.nanoTime());
        updateAccessibility();if(motionEnabled)animation.start();
    }
    private void updateAccessibility(){toggle.getAccessibleContext().setAccessibleName(I18n.t(title));toggle.getAccessibleContext().setAccessibleDescription(I18n.t(expanded?"已展开":"已折叠"));}
    private void tick(){long now=System.nanoTime();reveal=disclosure.value(now);revalidate();doLayout();if(getParent()!=null){getParent().invalidate();getParent().revalidate();}repaint();if(!disclosure.moving(now)){animation.stop();body.setVisible(expanded);}}
    @Override public Dimension getPreferredSize(){return new Dimension(236,36+(int)Math.round(reveal*body.getPreferredSize().height));}
    @Override public Dimension getMinimumSize(){return new Dimension(0,getPreferredSize().height);}
    @Override public Dimension getMaximumSize(){return new Dimension(Integer.MAX_VALUE,getPreferredSize().height);}
    @Override public void doLayout(){toggle.setBounds(0,0,getWidth(),36);body.setBounds(0,36,getWidth(),body.getPreferredSize().height);}
    @Override public void addNotify(){motionEnabled=true;super.addNotify();}
    @Override public void removeNotify(){motionEnabled=false;super.removeNotify();animation.stop();reveal=expanded?1:0;disclosure.reset(reveal);body.setVisible(expanded);}
}
