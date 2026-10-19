package com.starsea.nautilus;

import java.awt.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import java.util.concurrent.*;
import javax.swing.*;

/** Application appearance. Native probes run off the EDT and stop with the last UI root. */
final class Appearance {
    enum Mode {
        LIGHT("light", "明亮"), DARK("dark", "黑暗"), SYSTEM("system", "随系统");
        final String code, label;
        Mode(String code, String label) { this.code=code; this.label=label; }
    }
    interface Aware { void refreshTheme(); }
    private static volatile Mode mode=load();
    private static boolean systemDark=true;
    private static final Set<Component> roots=Collections.newSetFromMap(new IdentityHashMap<>());
    private static ScheduledExecutorService watcher;
    private Appearance() {}
    static Mode mode() { return mode; }
    static boolean isDark() { return mode==Mode.DARK || mode==Mode.SYSTEM && systemDark; }
    static Path preferencePath() {
        String override=System.getProperty("nautilus.theme.path");
        return override==null ? I18n.preferencePath().resolveSibling("theme.txt") : Path.of(override).toAbsolutePath();
    }
    static Mode load() {
        try {
            String code=Files.readString(preferencePath()).strip();
            for(Mode value:Mode.values())if(value.code.equals(code))return value;
        } catch(IOException | SecurityException ignored) { /* Follow system when unset or unreadable. */ }
        return Mode.SYSTEM;
    }
    static void change(Mode next) throws IOException {
        Path path=preferencePath();Files.createDirectories(path.getParent());
        Path temporary=Files.createTempFile(path.getParent(),"theme-",".tmp");
        try { Files.writeString(temporary,next.code,StandardCharsets.UTF_8);Files.move(temporary,path,StandardCopyOption.REPLACE_EXISTING); }
        finally { Files.deleteIfExists(temporary); }
        mode=next;refreshAll();
        if(next==Mode.SYSTEM && watcher!=null)watcher.execute(Appearance::probe);
    }
    static void attach(Component root) {
        roots.add(root);
        if(watcher==null) {
            watcher=Executors.newSingleThreadScheduledExecutor(r->{Thread thread=new Thread(r,"nautilus-appearance");thread.setDaemon(true);return thread;});
            watcher.scheduleWithFixedDelay(Appearance::probe,0,2,TimeUnit.SECONDS);
        }
        refreshAll();
    }
    static void detach(Component root) {
        roots.remove(root);
        if(roots.isEmpty() && watcher!=null){watcher.shutdownNow();watcher=null;}
    }
    static boolean isWatching() { return watcher!=null; }
    private static void probe() {
        if(mode!=Mode.SYSTEM)return;
        Boolean dark=detectSystemDark();
        if(dark!=null)SwingUtilities.invokeLater(()->{if(!roots.isEmpty())systemChanged(dark);});
    }
    static void systemChanged(boolean dark) {
        boolean previous=isDark();systemDark=dark;
        if(previous!=isDark())refreshAll();
    }
    private static void refreshAll() {
        for(String key:new String[]{"Panel.background","OptionPane.background","TextField.background","PasswordField.background","TextArea.background","ComboBox.background"})UIManager.put(key,Theme.SURFACE);
        for(String key:new String[]{"Label.foreground","OptionPane.messageForeground","TextField.foreground","PasswordField.foreground","TextArea.foreground","ComboBox.foreground","CheckBox.foreground"})UIManager.put(key,Theme.TEXT);
        for(Component root:roots)refresh(root);
        for(Window window:Window.getWindows())if(window.isDisplayable())refresh(window);
    }
    private static void refresh(Component component) {
        if(component instanceof Aware aware)aware.refreshTheme();
        if(component instanceof Container container)for(Component child:container.getComponents())refresh(child);
        component.invalidate();if(component instanceof JComponent c)c.revalidate();component.repaint();
    }
    static Boolean detectSystemDark() {
        // Headless tests have no desktop appearance to query.
        if(GraphicsEnvironment.isHeadless())return null;
        String os=System.getProperty("os.name","");
        if(os.startsWith("Mac")) {
            String result=command("defaults","read","-g","AppleInterfaceStyle");
            return result==null ? null : result.toLowerCase(java.util.Locale.ROOT).contains("dark");
        }
        if(os.startsWith("Windows")) {
            String result=command("reg","query","HKCU\\Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize","/v","AppsUseLightTheme");
            if(result==null || !result.contains("REG_DWORD"))return null;
            return result.strip().endsWith("0x0");
        }
        String result=command("gsettings","get","org.gnome.desktop.interface","color-scheme");
        if(result!=null && result.contains("prefer-dark"))return true;
        if(result!=null && result.contains("prefer-light"))return false;
        String gtk=command("gsettings","get","org.gnome.desktop.interface","gtk-theme");
        if(gtk!=null && !gtk.isBlank())return gtk.toLowerCase(java.util.Locale.ROOT).contains("dark");
        return null;
    }
    private static String command(String... args) {
        Process process=null;
        try {
            process=new ProcessBuilder(args).redirectError(ProcessBuilder.Redirect.DISCARD).start();
            if(!process.waitFor(1200,TimeUnit.MILLISECONDS))return null;
            // macOS represents light mode by the absence of AppleInterfaceStyle (exit 1).
            if(process.exitValue()!=0)return args[0].equals("defaults") && process.exitValue()==1 ? "light" : null;
            return new String(process.getInputStream().readAllBytes(),StandardCharsets.UTF_8);
        } catch(IOException ignored) { return null; }
        catch(InterruptedException interrupted) { Thread.currentThread().interrupt();return null; }
        finally { if(process!=null){process.destroy();try{process.getInputStream().close();}catch(IOException ignored){ /* closed */ }} }
    }
}
