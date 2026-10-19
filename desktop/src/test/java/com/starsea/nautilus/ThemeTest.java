package com.starsea.nautilus;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;
import javax.swing.text.html.HTMLEditorKit;

/** Exercise the live component tree, persistence and OS events without a real API or desktop. */
public final class ThemeTest {
    private ThemeTest() {}
    public static void main(String[] args) throws Exception {
        Path preference=Files.createTempDirectory("nautilus-theme-test").resolve("theme.txt");
        System.setProperty("nautilus.theme.path",preference.toString());
        Path previews=Path.of(args[0]);Files.createDirectories(previews);
        AtomicReference<ChatPanel> holder=new AtomicReference<>();
        SwingUtilities.invokeAndWait(()->{
            check(Appearance.mode()==Appearance.Mode.SYSTEM,"first launch follows system");
            ChatPanel panel=new ChatPanel(new Service());holder.set(panel);panel.setSize(1240,860);panel.addNotify();
            check(Appearance.isWatching(),"native appearance watcher starts with UI");
            ((JTextArea)find(panel,"message-input")).setText("保留草稿 · theme draft");
            Transcript transcript=findType(panel,Transcript.class);
            transcript.addMessage("You","保留消息",true,false);
            transcript.addMessage("Nautilus","# Theme preview\n\nText with **bold**, `inline code` and [a link](https://example.com).\n\n> A quoted line\n\n```rust\nfn main() { println!(\"Nautilus\"); }\n```\n\n| Theme | Status |\n| --- | --- |\n| Light | Ready |",false,false);
            Container content=transcript.getParent().getParent().getParent();
            ((CardLayout)content.getLayout()).show(content,"history");
            MarkdownPane markdown=findType(panel,MarkdownPane.class);
            markdown.select(1,7);
            Object document=markdown.getDocument();
            String selection=markdown.getSelectedText();
            CodeContext.Block block=transcript.codeBlocks().get(0);block.code="edited code";block.note="keep note";
            button(panel,"settings-toggle").doClick(0);button(panel,"settings-theme").doClick(0);
            change(panel,"light");
            check(!Appearance.isDark(),"light selection applies");
            check((Theme.BG.getRGB()&0xffffff)==0xF6F7FC,"light palette");
            BufferedImage image=paint(panel);
            check((image.getRGB(300,100)&0xffffff)==0xF6F7FC,"existing Swing background repaints light");
            check((image.getRGB(10,100)&0xffffff)==0xEEF0F8,"existing sidebar repaints light");
            check(cssText(markdown).equals(new Color(0x222A40)),"existing markdown switches to readable dark text");
            check(markdown.getDocument()==document&&selection.equals(markdown.getSelectedText()),"theme preserves document and selection");
            check(block.code.equals("edited code")&&block.note.equals("keep note"),"theme preserves edited code and notes");
            check(panel.messageCount()==2,"theme preserves conversation");
            check(((JTextArea)find(panel,"message-input")).getText().equals("保留草稿 · theme draft"),"theme preserves draft");
            Appearance.systemChanged(true);check(!Appearance.isDark(),"explicit light ignores dark system event");
            change(panel,"dark");check(Appearance.isDark(),"dark selection applies");
            image=paint(panel);check((image.getRGB(300,100)&0xffffff)==0x0D111B,"existing background returns to dark");
            check(cssText(markdown).equals(new Color(0xEEF1F8)),"existing markdown returns to bright text");
            Appearance.systemChanged(false);check(Appearance.isDark(),"explicit dark ignores light system event");
            change(panel,"system");check(!Appearance.isDark(),"system mode immediately uses cached current appearance");
            Appearance.systemChanged(true);check(Appearance.isDark(),"system dark event applies live");
            Appearance.systemChanged(false);check(!Appearance.isDark(),"system light event applies live");
            try { check(Files.readString(preference).equals("system"),"automatic updates preserve system preference"); }
            catch(java.io.IOException e){throw new RuntimeException(e);}
        });
        Thread.sleep(400);
        SwingUtilities.invokeAndWait(()->{
            ChatPanel panel=holder.get();SettingsMenu menu=findType(panel,SettingsMenu.class);
            check(menu.getPreferredSize().height==334,"theme dropdown reaches exact expanded geometry");
            SettingsMenuTest.render(panel,previews.resolve("theme-light.png"),1240,860);
            check(find(panel,"theme-light").isVisible()&&find(panel,"theme-system").isVisible(),"all three rounded choices visible");
            change(panel,"dark");SettingsMenuTest.render(panel,previews.resolve("theme-dark.png"),1240,860);
            menu.getActionMap().get("dismiss").actionPerformed(new ActionEvent(menu,0,"escape"));
            check(menu.isExpanded()&&!find(panel,"theme-light").isFocusable(),"Escape closes submenu before settings");
            button(panel,"settings-theme").doClick(0);button(panel,"settings-language").doClick(0);
            check(!find(panel,"theme-light").isFocusable()&&find(panel,"language-en").isFocusable(),"language and theme submenus are exclusive");
            check(Appearance.load()==Appearance.Mode.DARK,"saved explicit choice reloads");
            panel.close();panel.removeNotify();check(!Appearance.isWatching(),"disposal releases native watcher");
        });
        Files.delete(preference);Files.delete(preference.getParent());
        System.out.println("PASS: light/dark/system, live OS events, preference reload, existing Swing/Markdown repaint, preserved chat/draft/code/selection, dropdown and watcher cleanup");
    }
    private static Color cssText(MarkdownPane pane){return ((HTMLEditorKit)pane.getEditorKit()).getStyleSheet().getForeground(((HTMLEditorKit)pane.getEditorKit()).getStyleSheet().getRule("body"));}
    private static void change(ChatPanel panel,String code){button(panel,"theme-"+code).doClick(0);try{check(Files.readString(Appearance.preferencePath()).equals(code),"choice persisted");}catch(java.io.IOException e){throw new RuntimeException(e);}}
    private static BufferedImage paint(JComponent component){component.doLayout();BufferedImage image=new BufferedImage(component.getWidth(),component.getHeight(),BufferedImage.TYPE_INT_RGB);Graphics2D g=image.createGraphics();layout(component);component.printAll(g);g.dispose();return image;}
    private static void layout(Container c){c.doLayout();for(Component child:c.getComponents())if(child instanceof Container nested)layout(nested);}
    private static AbstractButton button(Container c,String name){return (AbstractButton)find(c,name);}
    private static Component find(Container c,String name){for(Component child:c.getComponents()){if(name.equals(child.getName()))return child;if(child instanceof Container nested){Component match=search(nested,name);if(match!=null)return match;}}throw new AssertionError("missing "+name);}
    private static Component search(Container c,String name){for(Component child:c.getComponents()){if(name.equals(child.getName()))return child;if(child instanceof Container nested){Component match=search(nested,name);if(match!=null)return match;}}return null;}
    private static <T> T findType(Container c,Class<T> type){for(Component child:c.getComponents()){if(type.isInstance(child))return type.cast(child);if(child instanceof Container nested){T found=searchType(nested,type);if(found!=null)return found;}}throw new AssertionError("missing "+type);}
    private static <T> T searchType(Container c,Class<T> type){for(Component child:c.getComponents()){if(type.isInstance(child))return type.cast(child);if(child instanceof Container nested){T found=searchType(nested,type);if(found!=null)return found;}}return null;}
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);}
    private static final class Service implements ChatService {
        public List<Model> initialize(){return List.of();}
        public Selection select(String id){return new Selection(id,id,id,false);}
        public Reply chat(String message){return new Reply(message,"test","Test");}
        public void reset(){}
        public Credentials credentials(){return new Credentials("/test/.env","test",true,true,true);}
        public Credentials saveCredentials(Map<String,String> values,boolean allow){return credentials();}
        public Credentials deleteCredentials(String provider){return credentials();}
        public void close(){}
    }
}
