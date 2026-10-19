package com.starsea.nautilus;

import java.awt.*;
import java.awt.event.MouseWheelEvent;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import javax.swing.*;

/** Test observable layout reuse, real wheel input, interruption, reversal and idle cleanup. */
public final class UiFluencyTest {
    private UiFluencyTest() {}
    public static void main(String[] args)throws Exception {
        Path directory=Files.createTempDirectory("nautilus-fluency");
        System.setProperty("nautilus.history.path",directory.resolve("history").toString());System.setProperty("nautilus.theme.path",directory.resolve("theme.txt").toString());
        testLayoutReuse();testHistoryReuse();testScrollAndWidth();testReadingAndPreviews(Path.of(args[0]));
        waitFor(()->Motion.activeAnimations()==0&&!Motion.clockRunning());
        System.out.println("PASS: stable history row identity/actions, Markdown cache invalidation, native wheel direction/nested scroll, interrupted follow, width reversal, preserved reading position and shared clock idle cleanup");
    }
    private static void testLayoutReuse()throws Exception {
        edt(()->{
            CodeContext context=new CodeContext();List<CodeContext.Block> blocks=context.register(MarkdownTest.SAMPLE);
            MarkdownPane pane=new MarkdownPane(MarkdownTest.SAMPLE,blocks,(block,action)->false);
            int height=pane.heightFor(720),passes=pane.measurementPasses();
            for(int i=0;i<80;i++){pane.setSize(720,height);check(pane.heightFor(720)==height,"stable measurement produces same layout");}
            check(pane.measurementPasses()==passes,"existing Markdown is not remeasured during same-width parent layouts");
            pane.heightFor(340);check(pane.measurementPasses()==passes+1,"new width invalidates measurement");
            CodeContext.Block block=blocks.get(0);block.note="追加批注\n".repeat(20);block.code="changed code\n".repeat(20);pane.refresh();pane.heightFor(340);
            check(pane.measurementPasses()==passes+2,"local code edit invalidates measurement");
            pane.refreshTheme();pane.heightFor(340);check(pane.measurementPasses()==passes+3,"theme refresh invalidates measurement");
            check(context.describe("请解释 C1 的批注").equals(context.prepare("请解释 C1 的批注").description()),"lightweight preview matches the actual augmented prompt");
            pane.addNotify();pane.removeNotify();
        });
    }
    private static void testHistoryReuse()throws Exception {
        edt(()->{
            List<ConversationStore.Session> sessions=new ArrayList<>();
            for(int i=0;i<100;i++)sessions.add(session("历史 "+i,i));
            AtomicReference<ConversationStore.Session> opened=new AtomicReference<>();ConversationList list=new ConversationList(opened::set,s->{});list.addNotify();list.available(true);list.update(sessions,sessions.get(0).id());
            Component first=find(list,"conversation-"+sessions.get(0).id());Component last=find(list,"conversation-"+sessions.get(99).id());
            ConversationStore.Session original=sessions.get(0);
            ConversationStore.Session updated=new ConversationStore.Session(original.id(),100,original.modelId(),null,original.entries(),List.of(),"新版草稿");
            sessions.set(0,updated);long start=System.nanoTime();for(int i=0;i<20;i++)list.update(sessions,updated.id());
            System.out.printf(java.util.Locale.ROOT,"CHECK: 20 updates of 100 existing history rows: %.2f ms%n",(System.nanoTime()-start)/1e6);
            check(find(list,first.getName())==first&&find(list,last.getName())==last,"autosave retains row identity and focus targets");
            ((AbstractButton)first).doClick(0);check(opened.get()==updated,"reused row opens latest saved data");
            List<ConversationStore.Session> reversed=new ArrayList<>(sessions);java.util.Collections.reverse(reversed);list.update(reversed,updated.id());
            check(find(list,first.getName())==first&&list.getComponent(99)==first.getParent(),"reordering reuses components");
            list.removeNotify();
        });
    }
    private static ConversationStore.Session session(String title,int index){
        List<ConversationStore.Entry> entries=List.of(new ConversationStore.Entry("You",title,true,false,"deepseek-pro","DeepSeek",title,true,"12:00"),new ConversationStore.Entry("DeepSeek","回答",false,false,"deepseek-pro","DeepSeek","",true,"12:00"));
        return new ConversationStore.Session(UUID.randomUUID().toString(),index,"deepseek-pro",null,entries,List.of(),"");
    }
    private static void testScrollAndWidth()throws Exception {
        AtomicReference<JScrollPane> holder=new AtomicReference<>();AtomicReference<JPanel> root=new AtomicReference<>();AtomicReference<ResizableSidebar> sidebar=new AtomicReference<>();
        edt(()->{
            JPanel view=new JPanel();view.setPreferredSize(new Dimension(500,5000));JScrollPane pane=new JScrollPane(view);Theme.scroll(pane);holder.set(pane);
            JPanel panel=new JPanel(new BorderLayout());panel.add(pane);panel.setSize(1000,720);panel.addNotify();layout(panel);root.set(panel);
            ResizableSidebar side=new ResizableSidebar(new JPanel(),new JPanel());side.addNotify();sidebar.set(side);side.animateWidth(76);
            wheel(pane,1);check(pane.getVerticalScrollBar().getValue()>0&&!SmoothScroll.install(pane).isAnimating(),"native wheel moves immediately without a stale animation target");
        });
        waitFor(()->sidebar.get().sidebarWidth()>76&&sidebar.get().sidebarWidth()<278);
        edt(()->{
            check(sidebar.get().isAnimating(),"width animation has intermediate geometry");sidebar.get().animateWidth(440);
            JScrollPane pane=holder.get();int previous=pane.getVerticalScrollBar().getValue();wheel(pane,-1);
            check(pane.getVerticalScrollBar().getValue()<previous,"wheel reversal immediately follows native direction");SmoothScroll.install(pane).toEnd();
        });
        waitFor(()->holder.get().getVerticalScrollBar().getValue()>100);
        edt(()->{holder.get().getVerticalScrollBar().setValue(100);check(!SmoothScroll.install(holder.get()).followingEnd()&&!SmoothScroll.install(holder.get()).isAnimating(),"manual scroll interrupts automatic follow");});
        waitFor(()->!sidebar.get().isAnimating());
        edt(()->{check(sidebar.get().sidebarWidth()==440,"reversed width reaches exact target");SmoothScroll.install(holder.get()).toEnd();});
        waitFor(()->!SmoothScroll.install(holder.get()).isAnimating());
        edt(()->{
            JScrollBar bar=holder.get().getVerticalScrollBar();check(bar.getValue()==bar.getMaximum()-bar.getVisibleAmount(),"smooth follow reaches exact bottom");
            wheel(holder.get(),-1);check(!SmoothScroll.install(holder.get()).followingEnd(),"wheel-up cancels follow mode");root.get().removeNotify();sidebar.get().removeNotify();check(!SmoothScroll.install(holder.get()).isAnimating(),"detached scroll animation stops");
            JPanel view=new JPanel(null);view.setPreferredSize(new Dimension(400,3000));JPanel shortView=new JPanel();shortView.setPreferredSize(new Dimension(200,40));JScrollPane inner=new JScrollPane(shortView);inner.setBounds(10,10,240,160);Theme.scroll(inner);view.add(inner);
            JScrollPane outer=new JScrollPane(view);Theme.scroll(outer);outer.setSize(300,240);outer.addNotify();layout(outer);wheel(inner,1);check(outer.getVerticalScrollBar().getValue()>0,"nested non-scrollable pane forwards wheel travel to parent");outer.removeNotify();
        });
    }
    private static void testReadingAndPreviews(Path output)throws Exception {
        Service service=new Service();AtomicReference<ChatPanel> holder=new AtomicReference<>();java.util.concurrent.atomic.AtomicInteger readingPosition=new java.util.concurrent.atomic.AtomicInteger();
        try {
            edt(()->{ChatPanel panel=new ChatPanel(service);holder.set(panel);panel.setSize(1240,860);panel.addNotify();layout(panel);panel.connect();});
            ChatPanel panel=holder.get();waitFor(()->!panel.isBusy()&&panel.selectedModelId()!=null);
            edt(()->panel.submitText("请给出包含代码与表格的完整解释。"));waitFor(()->!panel.isBusy()&&panel.messageCount()==2);
            edt(()->layout(panel));JScrollPane history=ancestor(type(panel,Transcript.class),JScrollPane.class);
            waitFor(()->!SmoothScroll.install(history).isAnimating()&&SmoothScroll.install(history).nearEnd());
            service.block=new CountDownLatch(1);edt(()->panel.submitText("继续完善这个方案。"));waitFor(panel::isBusy);
            edt(()->{layout(panel);JScrollBar bar=history.getVerticalScrollBar();readingPosition.set(bar.getMaximum()-bar.getVisibleAmount()-20);bar.setValue(readingPosition.get());check(SmoothScroll.install(history).nearEnd()&&!SmoothScroll.install(history).canFollow(),"manual movement near bottom suspends auto-follow");});
            service.progress.accept(new ChatService.Progress("worker","Worker","deepseek-pro","running","正在生成回复",0,null));service.notice.accept("协作阶段继续运行");service.block.countDown();
            waitFor(()->!panel.isBusy()&&panel.messageCount()==5);
            edt(()->{layout(panel);check(history.getVerticalScrollBar().getValue()==readingPosition.get(),"progress, notices and reply preserve reading position even within the bottom threshold");check(find(panel,"scroll-to-latest").isVisible(),"latest button offers explicit return");((AbstractButton)find(panel,"scroll-to-latest")).doClick(0);history.getVerticalScrollBar().setValue(120);});
            waitFor(()->!SmoothScroll.install(history).isAnimating()&&!SmoothScroll.install(history).followingEnd()&&history.getVerticalScrollBar().getValue()==120);
            edt(()->((AbstractButton)find(panel,"scroll-to-latest")).doClick(0));
            waitFor(()->!SmoothScroll.install(history).isAnimating()&&SmoothScroll.install(history).nearEnd());
            edt(()->{
                ResizableSidebar side=(ResizableSidebar)find(panel,"resizable-sidebar");side.setSidebarWidth(278);
                ((FoldSection)find(panel,"models-section")).setExpanded(false);((FoldSection)find(panel,"history-section")).setExpanded(true);
            });
            waitFor(()->Motion.activeAnimations()==0);
            edt(()->{
                try{Appearance.change(Appearance.Mode.DARK);}catch(java.io.IOException e){throw new RuntimeException(e);}
                SettingsMenuTest.render(panel,output.resolve("ui-polished-dark.png"),1240,860);
                try{Appearance.change(Appearance.Mode.LIGHT);}catch(java.io.IOException e){throw new RuntimeException(e);}
                SettingsMenuTest.render(panel,output.resolve("ui-polished-light.png"),1240,860);
                ((ResizableSidebar)find(panel,"resizable-sidebar")).setSidebarWidth(76);
            });
            waitFor(()->Motion.activeAnimations()==0);
            edt(()->SettingsMenuTest.render(panel,output.resolve("ui-polished-rail.png"),1000,720));
        } finally {edt(()->{ChatPanel panel=holder.get();if(panel!=null){panel.close();panel.removeNotify();}});}
    }
    private static void wheel(JScrollPane pane,double rotation){pane.dispatchEvent(new MouseWheelEvent(pane,MouseWheelEvent.MOUSE_WHEEL,System.currentTimeMillis(),0,10,10,10,10,0,false,MouseWheelEvent.WHEEL_UNIT_SCROLL,3,(int)Math.signum(rotation),rotation));}
    private static void layout(Container root){root.doLayout();for(Component child:root.getComponents())if(child instanceof Container container)layout(container);}
    private static <T> T ancestor(Component child,Class<T> type){for(Container parent=child.getParent();parent!=null;parent=parent.getParent())if(type.isInstance(parent))return type.cast(parent);throw new AssertionError("missing ancestor");}
    private static Component find(Container root,String name){Component found=search(root,name);if(found==null)throw new AssertionError("missing "+name);return found;}
    private static Component search(Container root,String name){for(Component child:root.getComponents()){if(name.equals(child.getName()))return child;if(child instanceof Container c){Component result=search(c,name);if(result!=null)return result;}}return null;}
    private static <T> T type(Container root,Class<T> type){for(Component child:root.getComponents()){if(type.isInstance(child))return type.cast(child);if(child instanceof Container c){T result=searchType(c,type);if(result!=null)return result;}}throw new AssertionError("missing "+type);}
    private static <T> T searchType(Container root,Class<T> type){for(Component child:root.getComponents()){if(type.isInstance(child))return type.cast(child);if(child instanceof Container c){T result=searchType(c,type);if(result!=null)return result;}}return null;}
    private static void waitFor(BooleanSupplier condition)throws Exception{long deadline=System.nanoTime()+8_000_000_000L;while(System.nanoTime()<deadline){boolean[] ready={false};edt(()->ready[0]=condition.getAsBoolean());if(ready[0])return;Thread.sleep(10);}throw new AssertionError("UI animation timed out");}
    private static void edt(Runnable action)throws Exception{SwingUtilities.invokeAndWait(action);}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static final class Service implements ChatService {
        Consumer<Progress> progress=e->{};Consumer<String> notice=e->{};CountDownLatch block;
        public void setProgressListener(Consumer<Progress> listener){progress=listener;}
        public void setNoticeListener(Consumer<String> listener){notice=listener;}
        public List<Model> initialize(){return List.of(new Model("deepseek-flash","DeepSeek Flash","快速响应","DeepSeek",true),new Model("deepseek-pro","DeepSeek Pro","复杂推理","DeepSeek",true),new Model("openai","OpenAI","通用对话","OpenAI",true));}
        public Selection select(String id){return new Selection(id,id,"DeepSeek",false);}
        public Reply chat(String message)throws java.io.IOException{if(block!=null)try{if(!block.await(5,TimeUnit.SECONDS))throw new java.io.IOException("timeout");}catch(InterruptedException e){throw new java.io.IOException(e);}return new Reply(MarkdownTest.SAMPLE.repeat(3),"deepseek-flash","DeepSeek");}
        public void reset(){}
        public Credentials credentials(){return new Credentials("/test/.env","test",true,true,true,true);}
        public Credentials saveCredentials(Map<String,String> updates,boolean acknowledged){return credentials();}
        public Credentials deleteCredentials(String provider){return credentials();}
        public void close(){}
    }
}
