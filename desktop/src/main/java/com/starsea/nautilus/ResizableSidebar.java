package com.starsea.nautilus;

import java.awt.*;
import java.awt.event.*;
import javax.swing.*;

/** Drag edge stays available in both the icon rail and the full sidebar. */
@SuppressWarnings("serial")
final class ResizableSidebar extends JPanel {
    static final int MIN_WIDTH=76, DEFAULT_WIDTH=278, MAX_WIDTH=440, FULL_WIDTH=252;
    private final CardLayout cards=new CardLayout();
    private final Motion.RevealPanel content=new Motion.RevealPanel(cards);
    private int width=DEFAULT_WIDTH;
    private boolean compact;
    private final Motion.Tween widthMotion=new Motion.Tween(DEFAULT_WIDTH);
    private final Motion.Loop widthAnimation=new Motion.Loop(e->{long now=System.nanoTime();applyWidth((int)Math.round(widthMotion.value(now)));if(!widthMotion.moving(now))stopWidthAnimation();});
    ResizableSidebar(JComponent full,JComponent rail) {
        super(new BorderLayout());setName("resizable-sidebar");setBackground(Theme.SIDE);
        content.setOpaque(false);content.add(full,"full");content.add(rail,"rail");add(content);
        JComponent handle=new JPanel(){
            @Override protected void paintComponent(Graphics source){Graphics2D g=(Graphics2D)source.create();g.setColor(Theme.LINE);g.drawLine(getWidth()-1,0,getWidth()-1,getHeight());g.setColor(Theme.MUTED);g.fillRoundRect(2,getHeight()/2-16,2,32,2,2);g.dispose();}
        };
        handle.setOpaque(false);handle.setName("sidebar-resize-handle");handle.setPreferredSize(new Dimension(7,1));handle.setCursor(Cursor.getPredefinedCursor(Cursor.E_RESIZE_CURSOR));
        handle.setToolTipText(I18n.t("拖动调整侧边栏；双击展开或收起"));
        MouseAdapter drag=new MouseAdapter(){
            private int startX,startWidth;
            @Override public void mousePressed(MouseEvent e){widthAnimation.stop();startX=e.getXOnScreen();startWidth=width;}
            @Override public void mouseDragged(MouseEvent e){setSidebarWidth(startWidth+e.getXOnScreen()-startX);}
            @Override public void mouseClicked(MouseEvent e){if(e.getClickCount()==2)animateWidth(compact?DEFAULT_WIDTH:MIN_WIDTH);}
        };
        handle.addMouseListener(drag);handle.addMouseMotionListener(drag);add(handle,BorderLayout.EAST);
        handle.setFocusable(true);handle.getAccessibleContext().setAccessibleName(I18n.t("拖动调整侧边栏；双击展开或收起"));
        for(String key:new String[]{"LEFT","RIGHT","HOME","END"}) {
            handle.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key),key);
            handle.getActionMap().put(key,new AbstractAction(){@Override public void actionPerformed(ActionEvent e){animateWidth(switch(key){case "LEFT"->width-24;case "RIGHT"->width+24;case "HOME"->MIN_WIDTH;default->MAX_WIDTH;});}});
        }
    }
    int sidebarWidth(){return width;}
    boolean isCompact(){return compact;}
    void expand(){animateWidth(Math.max(DEFAULT_WIDTH,width));}
    private int clamp(int requested){int available=getParent()==null||getParent().getWidth()==0?MAX_WIDTH:Math.max(MIN_WIDTH,getParent().getWidth()-560);return Math.max(MIN_WIDTH,Math.min(Math.min(MAX_WIDTH,available),requested));}
    void setSidebarWidth(int requested){widthAnimation.stop();applyWidth(clamp(requested));widthMotion.reset(width);}
    void animateWidth(int requested){int target=clamp(requested);if(target==width)return;widthMotion.reset(width);widthMotion.to(target,System.nanoTime());widthAnimation.start();}
    private void stopWidthAnimation(){widthAnimation.stop();}
    boolean isAnimating(){return widthAnimation.isRunning();}
    private void applyWidth(int next){
        if(width==next)return;width=next;
        boolean nextCompact=compact?width<FULL_WIDTH:width<FULL_WIDTH-16;
        if(compact!=nextCompact){compact=nextCompact;cards.show(content,compact?"rail":"full");content.reveal();}
        revalidate();if(getParent()!=null)getParent().revalidate();repaint();
    }
    @Override public void removeNotify(){widthAnimation.stop();widthMotion.reset(width);super.removeNotify();}
    @Override public Dimension getPreferredSize(){return new Dimension(width,700);}
    @Override public Dimension getMinimumSize(){return new Dimension(MIN_WIDTH,0);}
}
