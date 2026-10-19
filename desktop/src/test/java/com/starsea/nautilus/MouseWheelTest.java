package com.starsea.nautilus;

import java.awt.*;
import java.awt.event.MouseWheelEvent;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;

/** Compare real wheel dispatch against the platform delegate rather than assuming a pixel stride. */
public final class MouseWheelTest {
    private MouseWheelTest() {}
    public static void main(String[] args)throws Exception {
        AtomicReference<JScrollPane> actual=new AtomicReference<>(),nativePane=new AtomicReference<>();
        SwingUtilities.invokeAndWait(()->{
            JScrollPane enhanced=pane(true),control=pane(false);actual.set(enhanced);nativePane.set(control);
            enhanced.getVerticalScrollBar().setValue(500);control.getVerticalScrollBar().setValue(500);
            for(double rotation:new double[]{6,-2,-1,3,-6,.25,.25,-.25}){
                wheel(enhanced,rotation,MouseWheelEvent.WHEEL_UNIT_SCROLL);wheel(control,rotation,MouseWheelEvent.WHEEL_UNIT_SCROLL);
                check(enhanced.getVerticalScrollBar().getValue()==control.getVerticalScrollBar().getValue(),"wheel direction, acceleration and precise deltas match native handling");
                check(!SmoothScroll.install(enhanced).isAnimating(),"wheel does not leave a pending manual animation");
            }
            wheel(enhanced,1,MouseWheelEvent.WHEEL_BLOCK_SCROLL);wheel(control,1,MouseWheelEvent.WHEEL_BLOCK_SCROLL);
            check(enhanced.getVerticalScrollBar().getValue()==control.getVerticalScrollBar().getValue(),"page scrolling is native");
            enhanced.getVerticalScrollBar().setValue(0);wheel(enhanced,3,MouseWheelEvent.WHEEL_UNIT_SCROLL);wheel(enhanced,-3,MouseWheelEvent.WHEEL_UNIT_SCROLL);
            check(enhanced.getVerticalScrollBar().getValue()==0,"rapid reversal at the top has no lingering downward target");
            JScrollBar bar=enhanced.getVerticalScrollBar();bar.setValue(bar.getMaximum()-bar.getVisibleAmount());SmoothScroll.install(enhanced).toEnd();
            wheel(enhanced,-1,MouseWheelEvent.WHEEL_UNIT_SCROLL);
            check(!SmoothScroll.install(enhanced).canFollow(),"wheel-up interrupts automatic follow");
            bar.setValue(bar.getMaximum()-bar.getVisibleAmount()-20);check(SmoothScroll.install(enhanced).nearEnd()&&!SmoothScroll.install(enhanced).canFollow(),"near-end threshold cannot override a manual interruption");
            wheel(enhanced,3,MouseWheelEvent.WHEEL_UNIT_SCROLL);check(SmoothScroll.install(enhanced).followingEnd(),"manually reaching the bottom resumes future follow");
            testNested();
        });
        Thread.sleep(120);
        SwingUtilities.invokeAndWait(()->{
            check(!SmoothScroll.install(actual.get()).isAnimating(),"native input never resumes a stale animation later");actual.get().removeNotify();nativePane.get().removeNotify();
            check(Motion.activeAnimations()==0,"scroll test leaves shared clock idle");
        });
        System.out.println("PASS: native wheel/precision/page handling, rapid reversal, boundary forwarding exactly once, near-bottom follow interruption and native-bottom resume");
    }
    private static JScrollPane pane(boolean enhanced){
        JPanel view=new JPanel();view.setPreferredSize(new Dimension(400,5000));JScrollPane pane=new JScrollPane(view);pane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);pane.getVerticalScrollBar().setUnitIncrement(24);
        if(enhanced)SmoothScroll.install(pane);pane.setSize(300,240);pane.addNotify();layout(pane);return pane;
    }
    private static void testNested(){
        JPanel view=new JPanel(null);view.setPreferredSize(new Dimension(400,5000));JScrollPane inner=pane(true);inner.setBounds(8,8,240,150);view.add(inner);
        JScrollPane outer=new JScrollPane(view);outer.getVerticalScrollBar().setUnitIncrement(24);SmoothScroll.install(outer);outer.setSize(300,240);outer.addNotify();layout(outer);
        JScrollPane control=pane(false);
        inner.getVerticalScrollBar().setValue(inner.getVerticalScrollBar().getMaximum()-inner.getVerticalScrollBar().getVisibleAmount());
        wheel(inner,1,MouseWheelEvent.WHEEL_UNIT_SCROLL);wheel(control,1,MouseWheelEvent.WHEEL_UNIT_SCROLL);
        check(outer.getVerticalScrollBar().getValue()==control.getVerticalScrollBar().getValue(),"wheel at child bottom forwards to parent exactly once");
        int old=outer.getVerticalScrollBar().getValue();inner.getVerticalScrollBar().setValue(100);
        wheel(inner,1,MouseWheelEvent.WHEEL_UNIT_SCROLL);check(outer.getVerticalScrollBar().getValue()==old,"scrollable child handles wheel without moving its parent");
        inner.getVerticalScrollBar().setValue(0);wheel(inner,-1,MouseWheelEvent.WHEEL_UNIT_SCROLL);wheel(control,-1,MouseWheelEvent.WHEEL_UNIT_SCROLL);
        check(outer.getVerticalScrollBar().getValue()==control.getVerticalScrollBar().getValue(),"wheel at child top forwards upward exactly once");
        outer.removeNotify();control.removeNotify();
    }
    private static void wheel(JScrollPane pane,double rotation,int type){pane.dispatchEvent(new MouseWheelEvent(pane,MouseWheelEvent.MOUSE_WHEEL,System.currentTimeMillis(),0,10,10,10,10,0,false,type,3,(int)rotation,rotation));}
    private static void layout(Container root){root.doLayout();for(Component child:root.getComponents())if(child instanceof Container c)layout(c);}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
}
