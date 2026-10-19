package com.starsea.nautilus;

import java.awt.*;
import java.awt.event.*;
import javax.swing.*;

/** Native wheel input, with interruptible animated follow-to-end on the shared clock. */
final class SmoothScroll {
    private static final String KEY="nautilus.smooth-scroll";
    private final JScrollPane pane;
    private final JScrollBar bar;
    private final Motion.Loop animation=new Motion.Loop(e->tick());
    private double target;
    private int observedValue,observedLimit;
    private boolean applying,following,interrupted,enabled=true;
    private long last,interactionVersion;
    static SmoothScroll install(JScrollPane pane){
        if(pane.getClientProperty(KEY) instanceof SmoothScroll existing)return existing;
        SmoothScroll motion=new SmoothScroll(pane);pane.putClientProperty(KEY,motion);return motion;
    }
    private SmoothScroll(JScrollPane pane){
        this.pane=pane;bar=pane.getVerticalScrollBar();observedValue=bar.getValue();observedLimit=limit();target=observedValue;
        // Preserve the platform's acceleration, precise deltas and immediate direction changes.
        // Observe input before the UI delegate; only the delegate changes the wheel position.
        MouseWheelListener[] nativeListeners=pane.getMouseWheelListeners();
        for(MouseWheelListener listener:nativeListeners)pane.removeMouseWheelListener(listener);
        pane.addMouseWheelListener(this::wheel);
        for(MouseWheelListener listener:nativeListeners)pane.addMouseWheelListener(listener);
        pane.setWheelScrollingEnabled(true);
        bar.addAdjustmentListener(e->{
            int current=bar.getValue(),maximum=limit();boolean resized=maximum!=observedLimit;
            if(!applying&&current!=observedValue&&!resized){
                int previous=observedValue;cancel();
                if(current>=maximum&&current>previous){interrupted=false;following=true;}
            }
            observedValue=current;observedLimit=maximum;
            if(resized&&following){target=maximum;start();}
        });
        bar.addMouseListener(new MouseAdapter(){@Override public void mousePressed(MouseEvent e){cancel();}});
        pane.addHierarchyListener(e->{if((e.getChangeFlags()&HierarchyEvent.DISPLAYABILITY_CHANGED)!=0){enabled=pane.isDisplayable();if(!enabled)cancel();}});
    }
    private int limit(){return Math.max(bar.getMinimum(),bar.getMaximum()-bar.getVisibleAmount());}
    boolean atEnd(){return limit()-bar.getValue()<=1;}
    boolean nearEnd(){return limit()-bar.getValue()<64;}
    boolean canFollow(){return following||!interrupted&&nearEnd();}
    long interactionVersion(){return interactionVersion;}
    boolean followingEnd(){return following;}
    boolean isAnimating(){return animation.isRunning();}
    void toEnd(){interrupted=false;following=true;target=limit();start();}
    void cancel(){interactionVersion++;interrupted=true;following=false;target=bar.getValue();animation.stop();}
    private void start(){
        if(!enabled)return;
        target=Math.max(bar.getMinimum(),Math.min(limit(),target));
        if(Math.abs(target-bar.getValue())<.5){animation.stop();return;}
        if(!animation.isRunning()){last=System.nanoTime();animation.start();}
    }
    private void wheel(MouseWheelEvent event){
        if(event.isConsumed()||!enabled||!pane.isEnabled()||event.getPreciseWheelRotation()==0)return;
        int direction=event.getPreciseWheelRotation()<0?-1:1;cancel();
        JScrollBar axis=event.isShiftDown()?pane.getHorizontalScrollBar():bar;
        int minimum=axis.getMinimum(),maximum=Math.max(minimum,axis.getMaximum()-axis.getVisibleAmount()),current=axis.getValue();
        boolean canMove=maximum>minimum&&(direction<0?current>minimum:current<maximum);
        if(canMove)return; // Let the native listener process this event exactly once.
        if(axis==bar&&maximum>minimum&&current>=maximum&&direction>0){interrupted=false;following=true;}
        // Native delegates may consume wheel events even when the inner pane is at its boundary.
        for(Container ancestor=pane.getParent();ancestor!=null;ancestor=ancestor.getParent())if(ancestor instanceof JScrollPane parent){
            Point point=SwingUtilities.convertPoint(pane,event.getPoint(),parent);
            MouseWheelEvent forwarded=new MouseWheelEvent(parent,event.getID(),event.getWhen(),event.getModifiersEx(),point.x,point.y,event.getXOnScreen(),event.getYOnScreen(),event.getClickCount(),event.isPopupTrigger(),event.getScrollType(),event.getScrollAmount(),event.getWheelRotation(),event.getPreciseWheelRotation());
            parent.dispatchEvent(forwarded);event.consume();return;
        }
    }
    private void tick(){
        if(following)target=limit();
        target=Math.max(bar.getMinimum(),Math.min(limit(),target));
        long now=System.nanoTime();double dt=Math.min(.05,Motion.seconds(now,last));last=now;
        double current=bar.getValue(),next=Motion.follow(current,target,dt);
        if(Math.abs(next-target)<1)next=target;
        int value=(int)Math.round(next);
        if(value==bar.getValue()&&Math.abs(target-current)>=1)value+=(int)Math.signum(target-current);
        applying=true;try{bar.setValue(value);}finally{applying=false;}
        observedValue=bar.getValue();observedLimit=limit();
        if(Math.abs(target-bar.getValue())<1)animation.stop();
    }
}
