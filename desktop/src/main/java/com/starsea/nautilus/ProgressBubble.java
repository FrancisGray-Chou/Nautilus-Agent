package com.starsea.nautilus;

import java.awt.*;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import javax.swing.*;

/** One persistent trace per turn. Only operational milestones are displayed. */
@SuppressWarnings("serial")
final class ProgressBubble extends Theme.Surface {
    private static final class Step {
        ChatService.Progress event;long received;
        Step(ChatService.Progress e){update(e);}
        void update(ChatService.Progress e){event=e;received=System.nanoTime();}
        long elapsed(){return event.elapsedMs()+(event.status().equals("running")?(System.nanoTime()-received)/1_000_000:0);}
    }
    private final LinkedHashMap<String,Step> steps=new LinkedHashMap<>();
    private final long started=System.nanoTime();
    private final Timer timer=new Timer(Motion.FRAME_MS,e->tick());
    private boolean finished,success;
    private long total,lastClockSecond=-1;
    ProgressBubble(){super(new Color(0x171D2D),new Color(0x3C3652),20);setName("chat-progress");I18n.tooltip(this,"实时协作进度");timer.start();}
    void update(ChatService.Progress event){
        if(finished)return;
        steps.compute(event.stepId(),(key,step)->{if(step==null)return new Step(event);step.update(event);return step;});
        revalidate();repaint();if(getParent()!=null)getParent().revalidate();
    }
    void finish(boolean ok){
        finished=true;success=ok;total=(System.nanoTime()-started)/1_000_000;timer.stop();
        for(Step s:steps.values())if(s.event.status().equals("running"))s.update(new ChatService.Progress(s.event.stepId(),s.event.role(),s.event.model(),"interrupted","阶段未完成",s.elapsed(),s.event.nodeCount(),s.event.roleTitle()));
        revalidate();repaint();
    }
    boolean isAnimating(){return timer.isRunning();}
    private void tick(){
        long second=(System.nanoTime()-started)/1_000_000_000L;
        if(second!=lastClockSecond){lastClockSecond=second;repaint();}
        else repaint(14,14,Math.max(1,getWidth()-28),42);
    }
    int stepCount(){return steps.size();}
    private String phase(){
        if(finished)return success?"已完成":"未完成";
        String role="";for(Step s:steps.values())if(s.event.status().equals("running"))role=s.event.role();
        return role.equals("Summarizer")?"总结中":role.equals("Reviewer")?"复核中":role.equals("Worker")?"生成中":"思考中💭";
    }
    static String duration(long ms){long seconds=Math.max(0,ms)/1000;return String.format(java.util.Locale.ROOT,"%02d:%02d:%02d",seconds/3600,(seconds/60)%60,seconds%60);}
    private static List<String> wrap(String text,FontMetrics fm,int width){
        List<String> lines=new ArrayList<>();StringBuilder line=new StringBuilder();
        for(int cp:text.codePoints().toArray()){
            String c=new String(Character.toChars(cp));
            if(cp=='\n'||(!line.isEmpty()&&fm.stringWidth(line+c)>width)){lines.add(line.toString());line.setLength(0);if(cp=='\n')continue;}
            line.append(c);
        }if(!line.isEmpty())lines.add(line.toString());return lines;
    }
    private String message(Step step){return step.event.nodeCount()!=null?I18n.t("已将任务拆分为 ")+step.event.nodeCount()+I18n.t(" 个子节点"):I18n.t(step.event.message());}
    int heightFor(int width){int height=66;FontMetrics fm=getFontMetrics(Theme.font(12,false));for(Step s:steps.values())height+=34+wrap(message(s),fm,Math.max(100,width-72)).size()*18;return height+8;}
    @Override protected void paintComponent(Graphics graphics){
        super.paintComponent(graphics);Graphics2D g=(Graphics2D)graphics.create();Theme.antialias(g);int width=getWidth();
        g.setColor(finished?(success?Theme.MINT:Theme.ERROR):Theme.ACCENT);g.setStroke(new BasicStroke(2f));
        if(finished)g.drawString(success?"✓":"!",20,32);else g.drawArc(18,18,18,18,(int)((System.nanoTime()-started)/5_000_000%360),260);
        g.setFont(Theme.font(14,true));g.drawString(I18n.t(phase()),48,33);
        g.setFont(Theme.font(11,false));g.setColor(Theme.MUTED);String time=I18n.t("耗时: ")+duration(finished?total:(System.nanoTime()-started)/1_000_000);
        g.drawString(time,Math.max(180,width-g.getFontMetrics().stringWidth(time)-22),32);
        if(!finished){for(int i=0;i<3;i++){g.setColor(new Color(185,171,255,(int)(70+160*((Math.sin((System.nanoTime()-started)/280_000_000.0-i)+1)/2))));g.fillOval(48+i*8,44,4,4);}}
        Rectangle clip=g.getClipBounds();if(clip!=null&&clip.y+clip.height<=56){g.dispose();return;}
        int y=72;FontMetrics body=getFontMetrics(Theme.font(12,false));
        for(Step step:steps.values()){
            String state=step.event.status();Color color=state.equals("running")?Theme.ACCENT:state.equals("completed")?Theme.MINT:Theme.ERROR;
            g.setColor(color);g.fillOval(21,y-10,6,6);g.setFont(Theme.font(12,true));String label="["+step.event.role()+(step.event.roleTitle().isBlank()?"":" · "+step.event.roleTitle())+" | "+step.event.model()+"]";
            String elapsed=I18n.t("耗时: ")+duration(step.elapsed());int available=width-90-g.getFontMetrics(Theme.font(10,false)).stringWidth(elapsed);
            while(label.length()>4&&g.getFontMetrics().stringWidth(label)>available)label=label.substring(0,label.length()-2)+"…";
            g.drawString(label,40,y);g.setFont(Theme.font(10,false));g.setColor(Theme.MUTED);g.drawString(elapsed,width-22-g.getFontMetrics().stringWidth(elapsed),y);
            g.setFont(Theme.font(12,false));g.setColor(Theme.TEXT);y+=20;
            for(String line:wrap(message(step),body,Math.max(100,width-72))){g.drawString(line,40,y);y+=18;}
            y+=14;
        }
        g.dispose();
    }
    @Override public void removeNotify(){timer.stop();super.removeNotify();}
    @Override public void addNotify(){super.addNotify();if(!finished)timer.start();}
}
