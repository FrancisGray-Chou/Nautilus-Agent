package com.starsea.nautilus;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.imageio.ImageIO;
import javax.swing.*;

/** Exercises confirmed backend state, pending/failure behavior and actual rendered alpha. */
public final class SearchBubbleTest {
    private SearchBubbleTest() {}
    public static void main(String[] args) throws Exception {
        Path directory=Files.createTempDirectory("nautilus-search-ui");
        System.setProperty("nautilus.history.path",directory.resolve("history").toString());
        System.setProperty("nautilus.theme.path",directory.resolve("theme").toString());
        for(String provider:new String[]{"Gemini","DeepSeek","OpenAI","Claude"})for(boolean initial:new boolean[]{true,false}) exercise(provider,initial,Path.of(args[0]));
        testAnimation();
        waitFor(()->Motion.activeAnimations()==0&&!Motion.clockRunning());
        System.out.println("PASS: environment default on/off, confirmed search toggle, pending guard, rollback, preserved draft/transcript, sea-blue fade/reversal, transparent off, keyboard and light/dark previews");
    }
    private static void exercise(String provider,boolean initial,Path output) throws Exception {
        Service service=new Service(provider,initial);AtomicReference<ChatPanel> ref=new AtomicReference<>();
        edt(()->{ChatPanel panel=new ChatPanel(service);panel.addNotify();ref.set(panel);panel.connect();});
        ChatPanel panel=ref.get();waitFor(()->!panel.isBusy()&&service.id.equals(panel.selectedModelId()));
        edt(()->panel.submitText("保留已有对话"));waitFor(()->!panel.isBusy()&&panel.messageCount()==2);
        SearchBubble bubble=(SearchBubble)find(panel,"web-search");
        JTextArea draft=(JTextArea)find(panel,"message-input");
        edt(()->{
            check(bubble.isEnabled()&&bubble.isSelected()==initial,"configured default reflected");
            draft.setText("保留草稿");service.pending=new CountDownLatch(1);bubble.doClick(0);
            check(bubble.isSelected()!=initial&&!bubble.isEnabled(),"optimistic fade and pending guard");
        });
        service.pending.countDown();waitFor(()->!panel.isBusy());
        edt(()->{
            check(service.enabled!=initial&&bubble.isSelected()!=initial,"server-confirmed state");
            check(draft.getText().equals("保留草稿")&&panel.messageCount()==2,"draft and transcript preserved");
            service.fail=true;bubble.doClick(0);
        });
        waitFor(()->!panel.isBusy());
        edt(()->{
            check(bubble.isSelected()!=initial&&service.enabled!=initial,"failed update rolls back");
            check(draft.getText().equals("保留草稿"),"failure preserves draft");
            check(panel.messageCount()==2&&!labels(panel).contains("请求未完成"),"setting failure does not add chat message");
            service.fail=false;
            if(!bubble.isSelected())bubble.doClick(0);
        });
        waitFor(()->!panel.isBusy()&&!bubble.isAnimating());
        for(Appearance.Mode mode:new Appearance.Mode[]{Appearance.Mode.DARK,Appearance.Mode.LIGHT}) {
            edt(()->{try{Appearance.change(mode);render(panel,output.resolve("web-search-"+provider+"-"+mode.code+"-"+initial+".png"));}catch(IOException error){throw new RuntimeException(error);}});
        }
        edt(()->{panel.close();panel.removeNotify();});
    }
    private static void testAnimation() throws Exception {
        AtomicReference<SearchBubble> ref=new AtomicReference<>();
        edt(()->{
            SearchBubble bubble=new SearchBubble();ref.set(bubble);bubble.setSize(bubble.getPreferredSize());bubble.sync(new ChatService.WebSearch(false,true,false));bubble.setEnabled(true);
            check(alpha(bubble)==0,"off interior completely transparent");
            bubble.setSelected(true);
        });
        SearchBubble bubble=ref.get();
        waitFor(()->bubble.selectionProgress()>0&&bubble.selectionProgress()<1);
        edt(()->{check(alpha(bubble)>0&&alpha(bubble)<255,"intermediate sea-blue alpha");bubble.setSelected(false);});
        waitFor(()->!bubble.isAnimating());
        edt(()->{
            check(alpha(bubble)==0,"reversal ends transparent");
            bubble.addActionListener(event->bubble.setSelected(!bubble.isSelected()));
            bubble.getActionMap().get("search-toggle").actionPerformed(new java.awt.event.ActionEvent(bubble,0,"enter"));
            check(bubble.isSelected(),"Enter toggles search");
        });
        waitFor(()->!bubble.isAnimating());
        edt(()->{check(alpha(bubble)==255,"enabled sea-blue fully painted");bubble.removeNotify();});
    }
    private static int alpha(SearchBubble bubble){
        BufferedImage image=new BufferedImage(bubble.getWidth(),bubble.getHeight(),BufferedImage.TYPE_INT_ARGB);Graphics2D g=image.createGraphics();bubble.paint(g);g.dispose();return image.getRGB(bubble.getWidth()-12,bubble.getHeight()/2)>>>24;
    }
    private static void render(ChatPanel panel,Path path) throws IOException {
        panel.setSize(1240,850);layout(panel);layout(panel);BufferedImage image=new BufferedImage(1240,850,BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();panel.printAll(g);g.dispose();ImageIO.write(image,"png",path.toFile());
    }
    private static void layout(Container root){root.doLayout();for(Component child:root.getComponents())if(child instanceof Container nested)layout(nested);}
    private static Component find(Container root,String name){for(Component child:root.getComponents()){if(name.equals(child.getName()))return child;if(child instanceof Container nested){Component found=find(nested,name);if(found!=null)return found;}}return null;}
    private static List<String> labels(Container root){java.util.ArrayList<String> result=new java.util.ArrayList<>();for(Component child:root.getComponents()){if(child instanceof JLabel label)result.add(label.getText());if(child instanceof Container nested)result.addAll(labels(nested));}return result;}
    private static void check(boolean value,String reason){if(!value)throw new AssertionError(reason);}
    private static void edt(Runnable task)throws Exception{SwingUtilities.invokeAndWait(task);}
    private static void waitFor(BooleanSupplier condition)throws Exception{long end=System.nanoTime()+8_000_000_000L;while(System.nanoTime()<end){boolean[] ready={false};edt(()->ready[0]=condition.getAsBoolean());if(ready[0])return;Thread.sleep(10);}throw new AssertionError("UI timed out");}
    private static final class Service implements ChatService {
        final String provider,id;final boolean defaultEnabled;boolean enabled;volatile boolean fail;volatile CountDownLatch pending;
        Service(String provider,boolean initial){this.provider=provider;id=switch(provider){case "Gemini"->"gemini-flash";case "DeepSeek"->"deepseek-flash";case "Claude"->"claude-opus";default->"openai";};enabled=initial;defaultEnabled=initial;}
        public List<Model> initialize(){return List.of(new Model(id,provider,"联网搜索",provider,true));}
        public WebSearch webSearch(){return new WebSearch(enabled,true,defaultEnabled);}
        public WebSearch setWebSearch(boolean next)throws IOException{
            try{if(pending!=null&&!pending.await(4,TimeUnit.SECONDS))throw new IOException("timeout");}catch(InterruptedException error){Thread.currentThread().interrupt();throw new IOException(error);}
            if(fail)throw new IOException("offline failure");enabled=next;return webSearch();
        }
        public Selection select(String id){return new Selection(id,id,provider,enabled,webSearch());}
        public Reply chat(String message){return new Reply("reply",id,provider);}
        public void reset(){}
        public Credentials credentials(){return new Credentials("/test/Nautilus/.env","test",provider.equals("DeepSeek"),provider.equals("OpenAI"),provider.equals("Gemini"),provider.equals("Claude"));}
        public Credentials saveCredentials(Map<String,String> updates,boolean acknowledged){return credentials();}
        public Credentials deleteCredentials(String provider){return credentials();}
        public void close(){}
    }
}
