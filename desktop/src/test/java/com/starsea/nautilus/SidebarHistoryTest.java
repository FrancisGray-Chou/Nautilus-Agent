package com.starsea.nautilus;

import java.awt.*;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import javax.swing.*;

/** Real drag events, restart from disk and resumed context, without paid model requests. */
public final class SidebarHistoryTest {
    private SidebarHistoryTest() {}
    public static void main(String[] args)throws Exception {
        Path directory=Files.createTempDirectory("nautilus-sidebar-history");System.setProperty("nautilus.history.path",directory.resolve("conversations").toString());
        ConversationStore store=new ConversationStore();Path previews=Path.of(args[0]);List<ChatPanel> panels=new ArrayList<>();
        try {
            Service first=new Service();ChatPanel panel=create(first,panels);waitIdle(panel);
            onEdt(()->{
                check(section(panel,"models-section").isExpanded(),"model space is expanded by default");check(!section(panel,"history-section").isExpanded(),"history is collapsed by default");
                ResizableSidebar sidebar=(ResizableSidebar)find(panel,"resizable-sidebar");Component handle=find(panel,"sidebar-resize-handle");
                drag(handle,1000,200);check(sidebar.sidebarWidth()==76&&sidebar.isCompact(),"drag clamps to icon rail minimum");
                SettingsMenuTest.render(panel,previews.resolve("sidebar-icons.png"),1000,720);
                check(find(panel,"model-icon-deepseek").getWidth()==48&&find(panel,"model-icon-claude").isShowing(),"all model families remain available as icons");
                drag(handle,200,1200);check(sidebar.sidebarWidth()==440&&!sidebar.isCompact(),"drag reaches full sidebar width");
                SettingsMenuTest.render(panel,previews.resolve("sidebar-full.png"),1240,860);
                button(panel,"models-section-toggle").doClick(0);
            });
            waitFor(()->!section(panel,"models-section").isAnimating());
            onEdt(()->{check(section(panel,"models-section").getPreferredSize().height==36,"model section collapses to header");button(panel,"models-section-toggle").doClick(0);button(panel,"history-section-toggle").doClick(0);});
            waitFor(()->!section(panel,"models-section").isAnimating()&&!section(panel,"history-section").isAnimating());
            onEdt(()->panel.submitText("保留聊天 🌊\n第二行 \"Unicode\""));waitIdle(panel);
            waitSaved(store,1,2);ConversationStore.Session firstSession=store.list().get(0);String firstId=firstSession.id();
            check(firstSession.context(false).size()==2&&firstSession.models().equals("DeepSeek"),"successful turn and model family persisted");
            Path file=directory.resolve("conversations").resolve(firstId+".json");
            if(Files.getFileAttributeView(file,java.nio.file.attribute.PosixFileAttributeView.class)!=null){check(Files.getPosixFilePermissions(file).equals(PosixFilePermissions.fromString("rw-------")),"history file is private");check(Files.getPosixFilePermissions(file.getParent()).equals(PosixFilePermissions.fromString("rwx------")),"history directory is private");}
            onEdt(()->{
                Transcript transcript=type(panel,Transcript.class);CodeContext.Block code=transcript.codeBlocks().get(0);code.code="fn edited() {}";code.note="保留代码批注";
                ((JTextArea)find(panel,"message-input")).setText("未发送草稿");
            });
            waitForDisk(()->store.list().get(0).draft().equals("未发送草稿")&&!store.list().get(0).edits().isEmpty());
            onEdt(()->panel.chooseModel("gemini-argon"));waitIdle(panel);onEdt(()->panel.submitText("请解释 C1 的批注"));waitIdle(panel);
            onEdt(()->panel.chooseModel("mixture"));waitIdle(panel);onEdt(()->panel.submitText("混合模式的任务"));waitIdle(panel);
            first.fail=true;onEdt(()->panel.submitText("这次失败"));waitIdle(panel);
            first.incomplete=true;onEdt(()->panel.submitText("这次未完成"));waitIdle(panel);
            onEdt(()->((JTextArea)find(panel,"message-input")).setText("会话 A 的草稿"));
            waitForDisk(()->store.list().get(0).entries().size()==10&&store.list().get(0).draft().equals("会话 A 的草稿"));
            firstSession=store.list().get(0);
            check(firstSession.models().equals("DeepSeek · Gemini · Mixture"),"history shows every used model family");
            check(firstSession.entries().stream().anyMatch(e->e.modelId().equals("gemini-argon")),"exact submodel saved");
            check(firstSession.context(false).size()==4&&firstSession.context(true).size()==2,"failed and incomplete turns excluded from both contexts");
            check(firstSession.context(false).get(2).get("text").contains("保留代码批注"),"actual augmented outgoing prompt saved for resume");
            onEdt(panel::newConversation);waitIdle(panel);onEdt(()->panel.chooseModel("openai"));waitIdle(panel);onEdt(()->panel.submitText("另一段独立对话"));waitIdle(panel);
            waitSaved(store,2,2);String secondId=store.list().get(0).id();
            first.block=new CountDownLatch(1);onEdt(()->panel.submitText("等待中的消息"));
            ConversationStore.Session archived=firstSession;
            onEdt(()->{panel.openConversation(archived);check(!button(panel,"conversation-"+firstId).isEnabled(),"history switching disabled during generation");check("openai".equals(panel.selectedModelId()),"history cannot replace an in-flight conversation");});
            first.block.countDown();waitIdle(panel);first.block=null;waitSaved(store,2,4);
            onEdt(()->{panel.close();panel.removeNotify();});
            // A new panel reads files written by the previous instance.
            Service resumed=new Service();ChatPanel reopened=create(resumed,panels);waitIdle(reopened);
            onEdt(()->{check(!section(reopened,"history-section").isExpanded(),"relaunch starts with history folded");button(reopened,"history-section-toggle").doClick(0);button(reopened,"models-section-toggle").doClick(0);});
            waitFor(()->!section(reopened,"history-section").isAnimating()&&!section(reopened,"models-section").isAnimating());
            onEdt(()->button(reopened,"conversation-"+firstId).doClick(0));waitIdle(reopened);
            onEdt(()->{
                check(reopened.messageCount()==10&&"mixture".equals(reopened.selectedModelId()),"restart restores all displayed messages and the selected model");
                check(((JTextArea)find(reopened,"message-input")).getText().equals("会话 A 的草稿"),"draft restored across restart");
                CodeContext.Block block=type(reopened,Transcript.class).codeBlocks().get(0);check(block.code.equals("fn edited() {}")&&block.note.equals("保留代码批注"),"local edits restored across restart");
                check(resumed.single.size()==4&&resumed.mixture.size()==2,"both contexts restored independently");
                check(button(reopened,"conversation-"+firstId).getAccessibleContext().getAccessibleName().contains("DeepSeek · Gemini · Mixture"),"model categories accessible in history list");
                ((ResizableSidebar)find(reopened,"resizable-sidebar")).setSidebarWidth(440);
                SettingsMenuTest.render(reopened,previews.resolve("sidebar-history.png"),1240,860);
                reopened.submitText("恢复后继续");
            });waitIdle(reopened);check(resumed.contextBeforeLast==2,"continued mixture request uses its restored context");
            waitForDisk(()->store.list().size()==2&&store.list().get(0).id().equals(firstId)&&store.list().get(0).entries().size()==12);
            // Missing credentials must still allow opening and reading the conversation.
            resumed.rejectRestore=true;ConversationStore.Session second=store.list().stream().filter(s->s.id().equals(secondId)).findFirst().orElseThrow();
            onEdt(()->reopened.openConversation(second));waitFor(()->!reopened.isBusy());
            onEdt(()->{check(reopened.selectedModelId()==null&&reopened.messageCount()==4,"unavailable original model opens history without an active client");reopened.chooseModel("deepseek-flash");});waitIdle(reopened);
            onEdt(()->reopened.submitText("换模型继续"));waitIdle(reopened);check(resumed.contextBeforeLast==4,"choosing another model retains imported single-model context");
            onEdt(()->button(reopened,"delete-conversation-"+secondId).doClick(0));waitIdle(reopened);
            waitForDisk(()->store.list().size()==1&&!Files.exists(directory.resolve("conversations").resolve(secondId+".json")));
            onEdt(()->check(reopened.messageCount()==0,"deleting current conversation also resets active chat"));
            Path broken=file.getParent().resolve(java.util.UUID.randomUUID()+".json");Files.writeString(broken,"{truncated");check(store.list().size()==1&&Files.exists(broken),"corrupt record is retained without hiding healthy history");
            try{ConversationStore.Session valid=store.list().get(0);store.save(new ConversationStore.Session("../escape",0,valid.modelId(),valid.team(),valid.entries(),valid.edits(),""));throw new AssertionError("path traversal accepted");}catch(IOException expected){ /* IDs cannot choose arbitrary filesystem paths. */ }
            System.out.println("PASS: real sidebar drag/icon/full layouts, folding defaults, private atomic history, model categories, restart/continue, draft/code edits, context isolation, errors, unavailable models and deletion");
        } finally {onEdt(()->{for(ChatPanel panel:panels){panel.close();if(panel.isDisplayable())panel.removeNotify();}});}
    }
    private static ChatPanel create(Service service,List<ChatPanel> panels)throws Exception{ChatPanel[] holder={null};onEdt(()->{ChatPanel panel=new ChatPanel(service);panels.add(panel);holder[0]=panel;panel.setSize(1240,860);panel.addNotify();panel.connect();});return holder[0];}
    private static void drag(Component handle,int start,int end){long now=System.currentTimeMillis();handle.dispatchEvent(new MouseEvent(handle,MouseEvent.MOUSE_PRESSED,now,0,2,20,start,200,1,false,MouseEvent.BUTTON1));handle.dispatchEvent(new MouseEvent(handle,MouseEvent.MOUSE_DRAGGED,now+1,MouseEvent.BUTTON1_DOWN_MASK,2,20,end,200,0,false,MouseEvent.NOBUTTON));handle.dispatchEvent(new MouseEvent(handle,MouseEvent.MOUSE_RELEASED,now+2,0,2,20,end,200,1,false,MouseEvent.BUTTON1));}
    private static void waitIdle(ChatPanel panel)throws Exception{waitFor(()->!panel.isBusy()&&panel.selectedModelId()!=null);}
    private static void waitFor(BooleanSupplier condition)throws Exception{long deadline=System.nanoTime()+8_000_000_000L;while(System.nanoTime()<deadline){boolean[] ready={false};onEdt(()->ready[0]=condition.getAsBoolean());if(ready[0])return;Thread.sleep(15);}throw new AssertionError("UI timed out");}
    private interface DiskCondition{boolean get()throws IOException;}
    private static void waitForDisk(DiskCondition condition)throws Exception{long deadline=System.nanoTime()+8_000_000_000L;while(System.nanoTime()<deadline){if(condition.get())return;Thread.sleep(15);}throw new AssertionError("history write timed out");}
    private static void waitSaved(ConversationStore store,int count,int latestEntries)throws Exception{waitForDisk(()->{List<ConversationStore.Session> list=store.list();return list.size()==count&&list.get(0).entries().size()==latestEntries;});}
    private static void onEdt(Runnable action)throws Exception{SwingUtilities.invokeAndWait(action);}
    private static FoldSection section(Container root,String name){return (FoldSection)find(root,name);}
    private static AbstractButton button(Container root,String name){return (AbstractButton)find(root,name);}
    private static Component find(Container root,String name){Component found=search(root,name);if(found==null)throw new AssertionError("missing "+name);return found;}
    private static Component search(Container root,String name){for(Component child:root.getComponents()){if(name.equals(child.getName()))return child;if(child instanceof Container container){Component result=search(container,name);if(result!=null)return result;}}return null;}
    private static <T> T type(Container root,Class<T> type){for(Component child:root.getComponents()){if(type.isInstance(child))return type.cast(child);if(child instanceof Container c){T result=searchType(c,type);if(result!=null)return result;}}throw new AssertionError("missing "+type);}
    private static <T> T searchType(Container root,Class<T> type){for(Component child:root.getComponents()){if(type.isInstance(child))return type.cast(child);if(child instanceof Container c){T result=searchType(c,type);if(result!=null)return result;}}return null;}
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private static final class Service implements ChatService {
        String selected="deepseek-flash";List<Map<String,String>> single=new ArrayList<>(),mixture=new ArrayList<>();boolean fail,incomplete,rejectRestore;CountDownLatch block;int contextBeforeLast;
        public List<Model> initialize(){return List.of(new Model("deepseek-flash","DeepSeek Flash","Flash","DeepSeek",true),new Model("deepseek-pro","DeepSeek Pro","Pro","DeepSeek",true),new Model("openai","OpenAI","对话","OpenAI",true),new Model("gemini-flash","Gemini Flash","Flash","Gemini",true),new Model("gemini-argon","Gemini Argon","Argon","Gemini",true),new Model("claude-sonnet","Claude Sonnet","Sonnet","Claude",true),new Model("claude-opus","Claude Opus","Opus","Claude",true),new Model("mixture","Mixture混合模式","协作","Mixture",true));}
        public Selection select(String id){selected=id;return new Selection(id,id,id.equals("mixture")?"Mixture":id.startsWith("gemini")?"Gemini":id.equals("openai")?"OpenAI":"DeepSeek",false);}
        public Reply chat(String message)throws IOException{if(block!=null)try{if(!block.await(5,TimeUnit.SECONDS))throw new IOException("blocked");}catch(InterruptedException e){throw new IOException(e);}if(fail){fail=false;throw new IOException("offline error");}List<Map<String,String>> context=selected.equals("mixture")?mixture:single;contextBeforeLast=context.size();String text="已恢复聊天上下文。\n\n```rust\nfn main() {}\n```";boolean complete=!incomplete;incomplete=false;if(complete){context.add(Map.of("role","user","text",message));context.add(Map.of("role","assistant","text",text));}return new Reply(text,selected,selected.equals("mixture")?"Mixture":"Test",complete);}
        public Selection restoreConversation(String id,List<Map<String,String>> single,List<Map<String,String>> mixture,MixtureTeam team)throws IOException{if(id!=null&&rejectRestore)throw new IOException("missing key");this.single=new ArrayList<>(single);this.mixture=new ArrayList<>(mixture);return id==null?null:select(id);}
        public void reset(){single.clear();mixture.clear();}
        public Credentials credentials(){return new Credentials("/test/.env","test",true,true,true,true);}
        public Credentials saveCredentials(Map<String,String> values,boolean acknowledge){return credentials();}
        public Credentials deleteCredentials(String provider){return credentials();}
        public void close(){}
    }
}
