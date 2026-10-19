package com.starsea.nautilus;

import java.awt.*;
import java.io.*;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.swing.*;

public final class ProgressInteractionTest {
    private ProgressInteractionTest(){}
    public static void main(String[] args)throws Exception{
        String sse=": heartbeat\n\nevent: progress\ndata: {\ndata: \"type\":\"progress\",\"message\":\"思考中💭\"}\n\nevent: result\ndata: {\"type\":\"reply\",\"text\":\"ok\"}\n\n";
        Reader fragmented=new FilterReader(new StringReader(sse)){@Override public int read(char[] c,int o,int l)throws IOException{return super.read(c,o,Math.min(1,l));}};
        BufferedReader reader=new BufferedReader(fragmented);
        check(SseReader.next(reader).data().get("message").equals("思考中💭"),"fragmented Unicode and multiline SSE");
        check(SseReader.next(reader).name().equals("result"),"terminal event read after progress");
        check(SseReader.next(reader)==null,"EOF after final frame");
        check(SseReader.next(new BufferedReader(new StringReader("data: {\"text\":\"cut\"}")))==null,"truncated frame not accepted");
        AtomicReference<Transcript> ref=new AtomicReference<>();AtomicReference<JPanel> root=new AtomicReference<>();AtomicReference<String> copied=new AtomicReference<>();
        AtomicReference<ProgressBubble> bubble=new AtomicReference<>();AtomicReference<Theme.Button> copy=new AtomicReference<>();
        SwingUtilities.invokeAndWait(()->{
            Transcript transcript=new Transcript(s->{},()->{},copied::set);ref.set(transcript);
            transcript.addMessage("你","设计一个 Rust 任务调度器，并验证任务的执行顺序。",true,false);transcript.beginProgress();
            transcript.progress(new ChatService.Progress("planner","Planner","deepseek-flash","completed","任务拆分完成",1320,3));
            transcript.progress(new ChatService.Progress("worker-1","Worker","gpt-5-mini","completed","调度器实现已完成",2840,null));
            transcript.progress(new ChatService.Progress("worker-2","Worker","gpt-5-mini","completed","边界条件检查已完成",1690,null));
            transcript.progress(new ChatService.Progress("summary","Summarizer","gemini-4-argon","running","正在汇总最终产物",2500,null));
            JPanel panel=new JPanel(new BorderLayout());panel.setBackground(Theme.BG);panel.add(new JScrollPane(transcript));panel.addNotify();root.set(panel);
            bubble.set((ProgressBubble)find(transcript,"chat-progress"));
            check(bubble.get().stepCount()==4,"same stage updates one trace row");
            check(bubble.get().isAnimating(),"pending bubble animation active");
            check(ProgressBubble.duration(3_661_000).equals("01:01:01"),"elapsed HH:mm:ss");
            SettingsMenuTest.render(panel,Path.of(args[0],"preview-live-progress.png"),1000,720);
            transcript.progress(new ChatService.Progress("summary","Summarizer","gemini-4-argon","completed","阶段已返回结果",4120,null));
            transcript.finishProgress(true);check(!bubble.get().isAnimating(),"completion freezes timer");
            check(bubble.get().stepCount()==4,"completed progress persists");
            copy.set((Theme.Button)find(transcript,"copy-reply"));copy.get().doClick(0);
            check(copied.get().contains("Rust"),"actual copy preserved");
            check(copy.get().getText().equals("已复制 ✓"),"copy confirmation shown");
            check(copy.get().isAnimating(),"copy pulse active");
        });
        waitFor(()->copy.get().getText().equals("复制全文")&&!copy.get().isAnimating());
        SwingUtilities.invokeAndWait(()->{
            ref.get().beginProgress();ProgressBubble active=(ProgressBubble)ref.get().getComponent(ref.get().getComponentCount()-1);
            ref.get().progress(new ChatService.Progress("worker","Worker","deepseek-v4-pro","running","正在生成回复",0,null));
            ref.get().finishProgress(false);check(!active.isAnimating(),"failure stops progress animation");
            active.removeNotify();active.addNotify();check(!active.isAnimating(),"finished bubbles never restart on reattachment");
            ProgressBubble pending=new ProgressBubble();pending.addNotify();pending.removeNotify();
            check(!pending.isAnimating(),"detached pending progress stops its timer");pending.addNotify();
            check(pending.isAnimating(),"pending progress resumes when shown again");pending.finish(true);pending.removeNotify();
            root.get().removeNotify();check(!copy.get().isAnimating(),"detached buttons release timers");
            copy.get().getModel().setRollover(true);check(!copy.get().isAnimating(),"late rollover cannot restart a detached button");
        });
        System.out.println("PASS: SSE packet fragmentation, Unicode, heartbeat, incomplete EOF, live trace, elapsed clock, copy feedback and timer cleanup");
    }
    private static Component find(Container parent,String name){for(Component c:parent.getComponents()){if(name.equals(c.getName()))return c;if(c instanceof Container nested){Component found=search(nested,name);if(found!=null)return found;}}throw new AssertionError("missing "+name);}
    private static Component search(Container parent,String name){for(Component c:parent.getComponents()){if(name.equals(c.getName()))return c;if(c instanceof Container nested){Component found=search(nested,name);if(found!=null)return found;}}return null;}
    private static void waitFor(BooleanSupplier test)throws Exception{long deadline=System.nanoTime()+4_000_000_000L;while(System.nanoTime()<deadline){boolean[] ok={false};SwingUtilities.invokeAndWait(()->ok[0]=test.getAsBoolean());if(ok[0])return;Thread.sleep(15);}throw new AssertionError("animation timed out");}
    private static void check(boolean b,String message){if(!b)throw new AssertionError(message);}
}
