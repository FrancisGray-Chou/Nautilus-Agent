package com.starsea.nautilus;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javax.swing.*;

/** Offline interaction tests and real Swing rendering, with a fake text service. */
public final class DesktopSmokeTest {
    private DesktopSmokeTest() {}

    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        testJson();
        FakeService service = new FakeService();
        AtomicReference<ChatPanel> holder = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> { ChatPanel panel = new ChatPanel(service); holder.set(panel); panel.connect(); });
        ChatPanel panel = holder.get();
        waitUntil(panel, () -> "deepseek-flash".equals(panel.selectedModelId()) && !panel.isBusy());
        Path previews = Path.of(args.length == 0 ? "build" : args[0]); Files.createDirectories(previews);
        render(panel, previews.resolve("preview-welcome.png"), 1240, 860);

        SwingUtilities.invokeAndWait(() -> ((JButton) find(panel, "deepseek-toggle")).doClick());
        waitUntil(panel, () -> !((ModelSelector) find(panel, "deepseek-models")).isAnimating());
        render(panel, previews.resolve("preview-deepseek-expanded.png"), 1240, 860);
        check(onEdt(() -> find(panel, "deepseek-models").getHeight() == 184), "DeepSeek expands to exactly two rows");
        SwingUtilities.invokeAndWait(() -> ((JButton) find(panel, "deepseek-option-deepseek-pro")).doClick()); waitIdle(panel);
        check(onEdt(() -> "deepseek-pro".equals(panel.selectedModelId())), "DeepSeek Pro selection reaches backend");
        service.rejectSelection = true;
        SwingUtilities.invokeAndWait(() -> panel.chooseModel("deepseek-flash")); waitIdle(panel);
        check(onEdt(() -> ((ModelSelector) find(panel, "deepseek-models")).selectedModelId().equals("deepseek-pro")), "failed DeepSeek switch retains confirmed Pro");
        service.rejectSelection = false;
        SwingUtilities.invokeAndWait(() -> panel.chooseModel("deepseek-flash")); waitIdle(panel);

        service.rejectSelection = true;
        selectGemini(panel, "gemini-argon");
        waitUntil(panel, () -> !panel.isBusy());
        check(onEdt(() -> "deepseek-flash".equals(panel.selectedModelId())), "failed switch keeps previous model");
        check(onEdt(() -> ((GeminiSelector) find(panel, "gemini-models")).selectedModelId().equals("gemini-flash")), "failed dropdown selection restores confirmed preset");
        service.rejectSelection = false;
        SwingUtilities.invokeAndWait(panel::newConversation); waitIdle(panel);
        selectGemini(panel, "gemini-auto"); waitIdle(panel);
        check(onEdt(() -> "gemini-auto".equals(panel.selectedModelId())), "dropdown auto selection reaches backend");
        selectGemini(panel, "gemini-flash"); waitIdle(panel);
        check(onEdt(() -> "gemini-flash".equals(panel.selectedModelId())), "switch succeeds");

        SwingUtilities.invokeAndWait(() -> ((JButton) find(panel, "claude-toggle")).doClick());
        waitUntil(panel, () -> !((ModelSelector) find(panel, "claude-models")).isAnimating());
        render(panel, previews.resolve("preview-claude-expanded.png"), 1240, 860);
        render(panel, previews.resolve("preview-claude-compact.png"), 1000, 720);
        check(onEdt(() -> {
            JScrollPane scroll = (JScrollPane) find(panel, "model-space");
            return scroll.getViewport().getView().getWidth() == scroll.getViewport().getExtentSize().width;
        }), "expanded sidebar tracks viewport width without clipping");
        check(onEdt(() -> find(panel, "claude-models").getHeight() == 280), "Claude expands to all four rows");
        SwingUtilities.invokeAndWait(() -> ((JButton) find(panel, "claude-option-claude-fable")).doClick()); waitIdle(panel);
        check(onEdt(() -> "claude-fable".equals(panel.selectedModelId())), "Claude child reaches backend");
        service.rejectSelection = true;
        SwingUtilities.invokeAndWait(() -> panel.chooseModel("claude-sonnet")); waitIdle(panel);
        check(onEdt(() -> ((ModelSelector) find(panel, "claude-models")).selectedModelId().equals("claude-fable")), "failed Claude switch preserves confirmed child");
        service.rejectSelection = false;
        selectGemini(panel, "gemini-flash"); waitIdle(panel);

        SwingUtilities.invokeAndWait(panel::newConversation); waitIdle(panel);
        String unicode = "中文问题：\"Hello\"\n第二行 🌊";
        SwingUtilities.invokeAndWait(() -> panel.submitText(unicode)); waitIdle(panel);
        check(unicode.equals(service.lastMessage), "multiline Unicode input preserved");
        SwingUtilities.invokeAndWait(() -> panel.submitText("继续解释")); waitIdle(panel);
        check(onEdt(() -> panel.messageCount() == 4), "two completed chat turns stay visible");

        service.failNext = true;
        SwingUtilities.invokeAndWait(() -> panel.submitText("模拟失败")); waitIdle(panel);
        SwingUtilities.invokeAndWait(() -> panel.submitText("失败后继续")); waitIdle(panel);
        check(onEdt(() -> panel.messageCount() == 8), "can send again after a failed request");

        service.release = new CountDownLatch(1);
        SwingUtilities.invokeAndWait(() -> panel.submitText("慢速回复"));
        check(onEdt(panel::isBusy), "request is pending without blocking the EDT");
        check(onEdt(() -> !find(panel, "gemini-models").isEnabled()), "dropdown disabled during a request");
        check(onEdt(() -> !find(panel, "claude-models").isEnabled()), "Claude dropdown disabled during a request");
        check(onEdt(() -> !find(panel, "deepseek-models").isEnabled()), "DeepSeek dropdown disabled during a request");
        SwingUtilities.invokeAndWait(() -> panel.chooseModel("openai"));
        check(onEdt(() -> "gemini-flash".equals(panel.selectedModelId())), "switch cannot race an in-flight request");
        service.release.countDown(); waitIdle(panel); service.release = null;
        SwingUtilities.invokeAndWait(panel::newConversation); waitIdle(panel);
        check(onEdt(() -> panel.messageCount() == 0) && service.resets == 3, "new chat resets UI and backend");

        SwingUtilities.invokeAndWait(() -> panel.submitText("帮我解释 Rust 的所有权，给一个简单例子。")); waitIdle(panel);
        render(panel, previews.resolve("preview-chat.png"), 1240, 860);
        render(panel, previews.resolve("preview-compact.png"), 1000, 720);
        SwingUtilities.invokeAndWait(() -> ((JButton) find(panel, "gemini-toggle")).doClick());
        waitUntil(panel, () -> !((GeminiSelector) find(panel, "gemini-models")).isAnimating());
        render(panel, previews.resolve("preview-gemini-expanded.png"), 1240, 860);
        render(panel, previews.resolve("preview-gemini-compact.png"), 1000, 720);
        check(onEdt(() -> find(panel, "gemini-models").getHeight() > 200), "animated height propagates through the sidebar layout");
        check(onEdt(() -> find(panel, "gemini-option-gemini-auto").getHeight() > 0), "all expanded options laid out in compact sidebar");
        SwingUtilities.invokeAndWait(() -> ((JButton) find(panel, "gemini-toggle")).doClick());
        SwingUtilities.invokeAndWait(panel::newConversation); waitIdle(panel);
        service.responseMarkdown = MarkdownTest.SAMPLE;
        SwingUtilities.invokeAndWait(() -> panel.submitText("请用 Markdown 解释 Rust 所有权，并给出代码和对比表格。")); waitIdle(panel);
        render(panel, previews.resolve("preview-markdown-chat.png"), 1240, 1100);
        int calls = service.chatCalls;
        SwingUtilities.invokeAndWait(() -> {
            Transcript transcript = (Transcript) descendants(panel).stream().filter(component -> component instanceof Transcript).findFirst().orElseThrow();
            CodeContext.Block block = transcript.codeBlocks().get(0); block.note = "把借用的生命周期解释清楚";
            for (Component component : descendants(transcript)) if (component instanceof MarkdownPane markdown) markdown.refresh();
            ((JTextArea) find(panel, "message-input")).setText("保留我的草稿");
            ((JButton) find(panel, "like-reply")).doClick();
            check(((JTextArea) find(panel, "message-input")).getText().equals("保留我的草稿"), "liking preserves user draft");
            ((JButton) find(panel, "use-suggestion")).doClick();
            check(((JTextArea) find(panel, "message-input")).getText().startsWith("保留我的草稿\n"), "using suggestion appends without losing draft");
        });
        check(service.chatCalls == calls, "liking and using suggestion do not send API requests");
        render(panel, previews.resolve("preview-context.png"), 1000, 850);
        SwingUtilities.invokeAndWait(() -> panel.submitText("请解释 C1 的批注")); waitIdle(panel);
        check(service.lastMessage.contains("用户保存的相关代码上下文") && service.lastMessage.contains("生命周期解释清楚"), "actual outgoing chat contains relevant annotation");
        SwingUtilities.invokeAndWait(() -> {
            ((JCheckBox) find(panel, "include-code-context")).setSelected(false);
            panel.submitText("请解释 C1 的批注");
        }); waitIdle(panel);
        check(service.lastMessage.equals("请解释 C1 的批注"), "user can exclude context for a message");
        SwingUtilities.invokeAndWait(() -> {
            ((JCheckBox) find(panel, "include-code-context")).setSelected(true);
            panel.submitText("帮我规划明天的旅行");
        }); waitIdle(panel);
        check(service.lastMessage.equals("帮我规划明天的旅行"), "unrelated chat remains unaugmented");
        SwingUtilities.invokeAndWait(panel::newConversation); waitIdle(panel);
        SwingUtilities.invokeAndWait(() -> panel.submitText("请解释 C1 的批注")); waitIdle(panel);
        check(service.lastMessage.equals("请解释 C1 的批注"), "reset removes prior code references from outgoing chat");
        SwingUtilities.invokeAndWait(panel::close);
        System.out.println("PASS: JSON, model switching, multi-turn chat, error recovery, reset, background work and Swing previews");
    }

    private static void testJson() throws Exception {
        String text = "中文 \"quotes\" \\ slash\n\t🌊";
        Map<String, Object> roundtrip = Json.object(Json.encode(Map.of("message", text, "items", List.of(1, true))));
        check(text.equals(roundtrip.get("message")), "JSON Unicode roundtrip");
        check("你好".equals(Json.object("{\"x\":\"\\u4f60\\u597d\"}").get("x")), "JSON Unicode escape");
        for (String bad : List.of("{", "{\"x\":01}", "{\"x\":true}garbage", "{\"x\":\"bad\nline\"}", "[]")) {
            try { Json.object(bad); throw new AssertionError("invalid JSON accepted"); } catch (IOException expected) { /* expected */ }
        }
    }
    private interface Condition { boolean get(); }
    private static boolean onEdt(Condition test) throws Exception {
        boolean[] result = {false}; SwingUtilities.invokeAndWait(() -> result[0] = test.get()); return result[0];
    }
    private static void waitIdle(ChatPanel panel) throws Exception { waitUntil(panel, () -> !panel.isBusy()); }
    private static void selectGemini(ChatPanel panel, String id) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            if (!((GeminiSelector) find(panel, "gemini-models")).isExpanded()) ((JButton) find(panel, "gemini-toggle")).doClick();
        });
        waitUntil(panel, () -> !((GeminiSelector) find(panel, "gemini-models")).isAnimating());
        SwingUtilities.invokeAndWait(() -> ((JButton) find(panel, "gemini-option-" + id)).doClick());
    }
    private static void waitUntil(ChatPanel panel, Condition test) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < deadline) { if (onEdt(test)) return; Thread.sleep(15); }
        throw new AssertionError("UI operation timed out; busy=" + onEdt(panel::isBusy));
    }
    private static void render(ChatPanel panel, Path path, int width, int height) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            if (!panel.isDisplayable()) panel.addNotify();
            panel.setSize(width, height); layout(panel); layout(panel);
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics(); panel.printAll(graphics); graphics.dispose();
            try { ImageIO.write(image, "png", path.toFile()); } catch (IOException ex) { throw new IllegalStateException(ex); }
        });
    }
    private static void layout(Container root) {
        root.doLayout();
        for (Component component : root.getComponents()) if (component instanceof Container child) layout(child);
    }
    private static List<Component> descendants(Container root) {
        java.util.ArrayList<Component> result = new java.util.ArrayList<>();
        for (Component child : root.getComponents()) { result.add(child); if (child instanceof Container container) result.addAll(descendants(container)); }
        return result;
    }
    private static Component find(Container root, String name) {
        return descendants(root).stream().filter(component -> name.equals(component.getName())).findFirst().orElseThrow(() -> new AssertionError("missing component: " + name));
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }

    private static final class FakeService implements ChatService {
        volatile boolean failNext, rejectSelection;
        volatile String lastMessage;
        volatile String responseMarkdown;
        volatile int resets;
        volatile int chatCalls;
        volatile CountDownLatch release;
        private String selected = "deepseek-flash";
        private static void background() { check(!SwingUtilities.isEventDispatchThread(), "backend ran on EDT"); }
        @Override public List<Model> initialize() {
            background();
            java.util.ArrayList<Model> catalog = new java.util.ArrayList<>(List.of(new Model("deepseek-flash", "DeepSeek V4.1-Flash", "Flash 模型", "DeepSeek", true),
                    new Model("deepseek-pro", "DeepSeek V4-Pro", "Pro 模型", "DeepSeek", true),
                    new Model("openai", "OpenAI", "通用对话", "OpenAI", true),
                    new Model("gemini-flash", "Gemini 3.8 Flash", "Extended Thinking · Search", "Gemini", true),
                    new Model("gemini-argon", "Gemini 4 Argon", "深度思考 · Search", "Gemini", true),
                    new Model("gemini-auto", "Gemini Auto", "根据任务选择 Flash / Argon", "Gemini", true)));
            catalog.addAll(ClaudeModels.options(true));
            catalog.add(new Model("mixture", "Mixture混合模式", "自由组队 · 分工协作", "Mixture", true));
            return catalog;
        }
        @Override public Selection select(String id) throws IOException {
            background(); if (rejectSelection) throw new IOException("测试：目标模型尚未配置密钥");
            selected = id; return new Selection(id, id.equals("gemini-argon") ? "gemini-4-argon" : id.startsWith("gemini") ? "gemini-3.8-flash" : "deepseek-flash", id.startsWith("gemini") ? "Gemini" : "DeepSeek", id.startsWith("gemini"));
        }
        @Override public Reply chat(String message) throws IOException {
            background(); lastMessage = message; chatCalls++;
            CountDownLatch latch = release;
            if (latch != null) {
                try { if (!latch.await(5, TimeUnit.SECONDS)) throw new IOException("test timeout"); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IOException(ex); }
            }
            if (failNext) { failNext = false; throw new IOException("测试：暂时无法连接模型，可以重试。"); }
            if (responseMarkdown != null) return new Reply(responseMarkdown, "gemini-3.8-flash", "Gemini");
            return new Reply("可以把所有权理解为：每个值都有一位负责管理它的主人。\n\n当变量离开作用域时，Rust 会自动释放对应的资源，因此通常不需要手动释放内存。\n\n例如，将 String 从 a 赋值给 b，会把所有权交给 b。此后继续使用 a，编译器会提醒你这个值已经被移动。\n\n想在不转移所有权的情况下读取它，可以使用借用：&a。", selected.startsWith("gemini") ? "gemini-3.8-flash" : "deepseek-flash", selected.startsWith("gemini") ? "Gemini" : "DeepSeek");
        }
        @Override public void reset() { background(); resets++; }
        @Override public Credentials credentials() { background(); return new Credentials("/test-account/Nautilus/.env", "test-account", true, true, true, true); }
        @Override public Credentials saveCredentials(Map<String, String> updates, boolean acknowledged) { background(); return new Credentials("/test-account/Nautilus/.env", "test-account", true, true, true, true); }
        @Override public Credentials deleteCredentials(String provider) { background(); return new Credentials("/test-account/Nautilus/.env", "test-account", false, false, false); }
        @Override public void close() {}
    }
}
