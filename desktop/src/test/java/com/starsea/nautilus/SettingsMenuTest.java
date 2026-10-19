package com.starsea.nautilus;

import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import javax.imageio.ImageIO;
import javax.swing.*;

public final class SettingsMenuTest {
    private SettingsMenuTest() {}
    public static void main(String[] args) throws Exception {
        Path folder=Path.of(args[0]);Files.createDirectories(folder);
        Path preference=Files.createTempDirectory("nautilus-language-test").resolve("language.txt");
        System.setProperty("nautilus.language.path",preference.toString());
        AtomicReference<ChatPanel> holder=new AtomicReference<>();Service service=new Service();
        SwingUtilities.invokeAndWait(()->{ChatPanel p=new ChatPanel(service);holder.set(p);p.setSize(1240,860);p.addNotify();p.connect();});
        ChatPanel panel=holder.get();waitFor(()->!panel.isBusy());
        SettingsMenu menu=(SettingsMenu)find(panel,"settings-menu");
        testGear();
        SwingUtilities.invokeAndWait(()->button(menu,"settings-toggle").doClick(0));
        waitFor(()->menu.getPreferredSize().height>44&&menu.getPreferredSize().height<174);
        SwingUtilities.invokeAndWait(()->menu.collapse());
        waitFor(()->!menu.isAnimating());
        SwingUtilities.invokeAndWait(()->{
            check(menu.getPreferredSize().height==44,"rapid reversal returns to exact collapsed geometry");
            button(menu,"settings-toggle").doClick(0);menu.collapse();button(menu,"settings-toggle").doClick(0);
        });
        waitFor(()->!menu.isAnimating());
        SwingUtilities.invokeAndWait(()->{
            check(menu.getPreferredSize().height==174,"repeated toggles reach the expanded geometry");
            menu.collapse();
        });
        waitFor(()->!menu.isAnimating());
        SwingUtilities.invokeAndWait(()->{
            ((JTextArea)find(panel,"message-input")).setText("保留我的草稿 · draft · vázlat");
            button(menu,"settings-toggle").doClick(0);
        });
        waitFor(()->!menu.isAnimating());
        SwingUtilities.invokeAndWait(()->button(menu,"settings-language").doClick(0));waitFor(()->!menu.isAnimating());
        for(I18n.Language language:I18n.Language.values()) {
            SwingUtilities.invokeAndWait(()->{
                button(menu,"language-"+language.code).doClick(0);
                check(I18n.language()==language,"language switched");
                check(((JTextArea)find(panel,"message-input")).getText().equals("保留我的草稿 · draft · vázlat"),"draft unchanged");
                check(labels(panel).contains(I18n.t("＋  新对话")),"existing buttons relocalized");
                check(labels(panel).contains(I18n.t("把想法，聊清楚。")),"existing welcome relocalized");
                render(panel,folder.resolve("settings-"+language.code+".png"),1240,860);
                CredentialsPanel keys=new CredentialsPanel(service.state(),new CredentialsPanel.Actions(){public void save(Map<String,String> m){}public void delete(String s){}public void close(){}});
                render(keys,folder.resolve("credentials-"+language.code+".png"),820,760);
                check(labels(keys).contains(I18n.t("保存密钥")),"new dialog localized");
            });
            check(Files.readString(preference).equals(language.code),"language persisted");
        }
        SwingUtilities.invokeAndWait(()->button(menu,"settings-exit").getModel().setRollover(true));
        waitFor(()->menu.exitColor().getRed()>30 && menu.exitColor().getRed()<220);
        waitFor(()->!menu.isAnimating());
        SwingUtilities.invokeAndWait(()->{
            check(menu.exitColor().equals(new Color(0xE448A5)),"exit reaches magenta");
            render(panel,folder.resolve("settings-exit.png"),1240,860);
            button(menu,"settings-exit").getModel().setRollover(false);
        });waitFor(()->!menu.isAnimating());
        SwingUtilities.invokeAndWait(()->{
            check(menu.exitColor().equals(Theme.SURFACE),"exit returns smoothly");
            menu.getActionMap().get("dismiss").actionPerformed(new ActionEvent(menu,0,"escape"));
            check(menu.isExpanded(),"Escape closes language submenu first");
            menu.getActionMap().get("dismiss").actionPerformed(new ActionEvent(menu,0,"escape"));
            check(!menu.isExpanded(),"Escape closes settings");
        });waitFor(()->!menu.isAnimating());
        SwingUtilities.invokeAndWait(()->{
            button(menu,"settings-toggle").doClick(0);
            new JPanel().dispatchEvent(new MouseEvent(new JPanel(),MouseEvent.MOUSE_PRESSED,System.currentTimeMillis(),0,0,0,1,false));
            check(!menu.isExpanded(),"outside click closes menu");
            button(menu,"settings-toggle").doClick(0);
            button(menu,"settings-exit").doClick(0);
            check(service.closes==1,"exit closes backend once");panel.close();check(service.closes==1,"close is idempotent");
            button(menu,"settings-toggle").getModel().setRollover(true);
            button(menu,"settings-exit").getModel().setRollover(true);
            panel.removeNotify();check(!menu.isAnimating(),"disposal releases timer");
            button(menu,"settings-toggle").getModel().setRollover(true);
            check(!menu.isAnimating(),"late model events cannot restart a detached menu timer");
            button(menu,"settings-toggle").getModel().setRollover(false);
            check(menu.exitColor().equals(Theme.SURFACE),"disposal clears stale hover colors");
            panel.addNotify();check(!menu.isExpanded()&&menu.getPreferredSize().height==44,"reattachment begins collapsed");
            check(!button(menu,"settings-toggle").getModel().isRollover(),"reattachment has no stale gear hover");
            panel.removeNotify();
        });
        Files.deleteIfExists(preference);Files.deleteIfExists(preference.getParent());
        System.out.println("PASS: four languages, live updates, persistent choice, preserved draft, animated menu, magenta hover, dismissal and exit cleanup");
    }
    private static void testGear(){
        for(double angle:new double[]{0,.12,.31,.58,.91,1.24,Math.PI/2}){
            BufferedImage image=new BufferedImage(64,64,BufferedImage.TYPE_INT_ARGB);Graphics2D g=image.createGraphics();
            g.setColor(Theme.TEXT);SettingsMenu.paintGear(g,32,32,angle);g.dispose();
            double mass=0,xTotal=0,yTotal=0;
            for(int y=0;y<64;y++)for(int x=0;x<64;x++){
                int alpha=image.getRGB(x,y)>>>24;mass+=alpha;xTotal+=(x+.5)*alpha;yTotal+=(y+.5)*alpha;
                if(alpha>0)check(x>=20&&x<44&&y>=20&&y<44,"rotating gear stays inside its icon box");
            }
            check(mass>20_000,"vector gear is visible without a symbol font");
            check(Math.abs(xTotal/mass-32)<.2&&Math.abs(yTotal/mass-32)<.2,"gear rotation stays centered");
            check(image.getRGB(32,32)>>>24==0,"gear center remains open throughout rotation");
        }
    }
    static void render(JComponent c,Path file,int w,int h){
        c.setSize(w,h);layout(c);layout(c);BufferedImage image=new BufferedImage(w,h,BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();c.printAll(g);g.dispose();
        try{ImageIO.write(image,"png",file.toFile());}catch(java.io.IOException e){throw new RuntimeException(e);}
    }
    private static void layout(Container c){c.doLayout();for(Component child:c.getComponents())if(child instanceof Container nested)layout(nested);}
    private static java.util.List<String> labels(Container c){java.util.List<String> all=new java.util.ArrayList<>();for(Component child:c.getComponents()){if(child instanceof JLabel l)all.add(l.getText());if(child instanceof AbstractButton b)all.add(b.getText());if(child instanceof Container nested)all.addAll(labels(nested));}return all;}
    private static Component find(Container c,String name){Component found=search(c,name);if(found==null)throw new AssertionError("missing "+name);return found;}
    private static Component search(Container c,String name){for(Component child:c.getComponents()){if(name.equals(child.getName()))return child;if(child instanceof Container nested){Component found=search(nested,name);if(found!=null)return found;}}return null;}
    private static AbstractButton button(Container c,String name){return(AbstractButton)find(c,name);}
    private static void check(boolean value,String message){if(!value)throw new AssertionError(message);}
    private static void waitFor(BooleanSupplier condition)throws Exception{long deadline=System.nanoTime()+5_000_000_000L;while(System.nanoTime()<deadline){boolean[] ready={false};SwingUtilities.invokeAndWait(()->ready[0]=condition.getAsBoolean());if(ready[0])return;Thread.sleep(12);}throw new AssertionError("UI timed out");}
    private static final class Service implements ChatService {
        int closes;
        Credentials state(){return new Credentials("/test/Nautilus/.env","test",true,true,true);}
        public List<Model> initialize(){return List.of(new Model("deepseek-flash","DeepSeek V4.1-Flash","Flash 模型","DeepSeek",true),new Model("deepseek-pro","DeepSeek V4-Pro","Pro 模型","DeepSeek",true),new Model("openai","OpenAI","通用对话","OpenAI",true),new Model("mixture","Mixture混合模式","自由组队 · 分工协作","Mixture",true));}
        public Selection select(String id){return new Selection(id,"deepseek-flash","DeepSeek",false);}
        public Reply chat(String message){return new Reply(message,"deepseek-flash","DeepSeek");}
        public void reset(){}
        public Credentials credentials(){return state();}
        public Credentials saveCredentials(Map<String,String> m,boolean a){return state();}
        public Credentials deleteCredentials(String p){return state();}
        public void close(){closes++;}
    }
}
