package com.starsea.nautilus;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javax.swing.*;

public final class CredentialsPanelTest {
    private CredentialsPanelTest() {}
    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        SwingUtilities.invokeAndWait(() -> {
            AtomicReference<Map<String, String>> saved = new AtomicReference<>();
            AtomicReference<String> deleted = new AtomicReference<>();
            ChatService.Credentials empty = new ChatService.Credentials("/Users/demo/Library/Application Support/Nautilus/.env", "demo", false, false, false);
            CredentialsPanel panel = new CredentialsPanel(empty, new CredentialsPanel.Actions() {
                @Override public void save(Map<String, String> keys) { saved.set(Map.copyOf(keys)); }
                @Override public void delete(String provider) { deleted.set(provider); }
                @Override public void close() {}
            });
            List<Component> components = children(panel);
            List<JPasswordField> fields = components.stream().filter(JPasswordField.class::isInstance).map(JPasswordField.class::cast).toList();
            check(fields.size() == 3, "three independent platform fields");
            check(fields.stream().allMatch(field -> field.getPassword().length == 0 && field.getEchoChar() != 0), "keys are masked and never prefilled");
            JButton save = components.stream().filter(JButton.class::isInstance).map(JButton.class::cast).filter(button -> button.getText().equals("保存密钥")).findFirst().orElseThrow();
            JCheckBox acknowledgement = components.stream().filter(JCheckBox.class::isInstance).map(JCheckBox.class::cast).findFirst().orElseThrow();
            fields.get(0).setText("fake-test-key"); check(!save.isEnabled(), "save requires disclosure acknowledgement");
            acknowledgement.doClick(); check(save.isEnabled(), "acknowledged save enabled"); save.doClick();
            check("fake-test-key".equals(saved.get().get("deepseek")) && saved.get().size() == 1, "only edited keys sent");
            check(fields.stream().allMatch(field -> field.getPassword().length == 0), "sensitive fields cleared after submission");
            panel.apply(new ChatService.Credentials(empty.storagePath(), "demo", true, false, true));
            JButton removeAll = components.stream().filter(JButton.class::isInstance).map(JButton.class::cast).filter(button -> button.getText().equals("删除全部本机密钥")).findFirst().orElseThrow();
            panel.setWorking(true); check(!removeAll.isEnabled(), "edits disabled during save");
            panel.setWorking(false); removeAll.doClick(); check("all".equals(deleted.get()), "delete all action exposed");
            panel.setSize(820, 730); layout(panel); layout(panel);
            BufferedImage image = new BufferedImage(820, 730, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics(); panel.printAll(graphics); graphics.dispose();
            try { ImageIO.write(image, "png", Path.of(args[0], "preview-credentials.png").toFile()); }
            catch (Exception ex) { throw new IllegalStateException(ex); }
        });
        System.out.println("PASS: masked credential form, explicit plaintext acknowledgement, partial updates, field clearing and deletion controls");
    }
    private static List<Component> children(Container root) {
        List<Component> result = new ArrayList<>();
        for (Component child : root.getComponents()) { result.add(child); if (child instanceof Container container) result.addAll(children(container)); }
        return result;
    }
    private static void layout(Container root) { root.doLayout(); for (Component child : root.getComponents()) if (child instanceof Container container) layout(container); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
