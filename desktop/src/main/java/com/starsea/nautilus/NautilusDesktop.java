package com.starsea.nautilus;

import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.nio.file.Path;
import javax.swing.*;

public final class NautilusDesktop {
    private NautilusDesktop() {}
    public static void main(String[] args) {
        System.setProperty("apple.awt.application.name", "Nautilus");
        if (GraphicsEnvironment.isHeadless()) {
            System.err.println("Nautilus 桌面端需要图形桌面环境。"); return;
        }
        Path root = args.length == 0 ? Path.of(".") : Path.of(args[0]);
        SwingUtilities.invokeLater(() -> {
            UIManager.put("Label.font", Theme.font(13, false));
            JFrame frame = new JFrame("Nautilus · AI Chat");
            ChatPanel panel = new ChatPanel(new BridgeClient(root));
            frame.setContentPane(panel); frame.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
            frame.setMinimumSize(new Dimension(1000, 720)); frame.setSize(1240, 860); frame.setLocationRelativeTo(null);
            frame.addWindowListener(new WindowAdapter() {
                @Override public void windowClosed(WindowEvent event) { panel.close(); }
            });
            frame.setVisible(true); panel.connect();
        });
    }
}
