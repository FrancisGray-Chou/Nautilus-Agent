package com.starsea.nautilus;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import javax.imageio.ImageIO;
import javax.swing.*;

/** Exercises actual embedded Swing buttons and the outgoing context selection. */
public final class ReplyActionsTest {
    private static final String SAMPLE = """
            ## 请求示例

            可以在代码块顶部复制、修改或批注。

            ```rust
            fn fetch_profile(user_id: u64) -> String {
                format!("profile/{}", user_id)
            }
            ```

            > 编辑保存在当前对话；原始回复保留。

            - 复制全文保留 Markdown。
            - 点赞后可编辑下一句建议。
            """;
    private ReplyActionsTest() {}
    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        testRelevance();
        Path output = Path.of(args.length == 0 ? "build" : args[0]); Files.createDirectories(output);
        AtomicReference<String> clipboard = new AtomicReference<>(""), suggestion = new AtomicReference<>("");
        AtomicReference<Transcript> holder = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> {
            Transcript transcript = new Transcript(suggestion::set, () -> {}, clipboard::set); holder.set(transcript);
            transcript.addMessage("Gemini / Flash", SAMPLE, false, false);
            render(transcript, output.resolve("preview-actions.png"), 860);
        });
        SwingUtilities.invokeAndWait(() -> {
            Transcript transcript = holder.get();
            find(transcript, "copy-reply").doClick(); check(SAMPLE.equals(clipboard.get()), "reply copies original Markdown");
            find(transcript, "C1-复制").doClick();
            CodeContext.Block block = transcript.codeBlocks().get(0);
            check(block.original.equals(clipboard.get()), "embedded code button copies code without fences");
            check(find(transcript, "C1-修改").getWidth() > 0 && find(transcript, "C1-批注").getWidth() > 0, "embedded editing buttons laid out");
            CodeEditorPanel draft = new CodeEditorPanel(block, false);
            draft.code.setText("fn fetch_profile(user_id: u64) -> String {\n    format!(\"users/{}\", user_id)\n}\n");
            draft.note.setText("需要为用户资料请求补充超时重试；第二行保留用户编号。");
            check(!block.edited() && block.note.isBlank(), "cancelled draft cannot mutate code context");
            draft.apply(block); check(block.edited(), "saved edit is conversation local");
            check(SAMPLE.contains("profile/{}"), "original reply is unchanged");
            CodeEditorPanel annotate = new CodeEditorPanel(block, true);
            check(!annotate.code.isEditable() && annotate.note.isEditable(), "annotation mode selects code without modifying it");
            annotate.note.setText(""); annotate.code.select(0, annotate.code.getText().indexOf('\n') + 1);
            find(annotate, "quote-code-lines").doClick();
            check(annotate.note.getText().equals("第 1–1 行："), "selected lines can anchor an annotation");
            for (Component component : descendants(transcript)) if (component instanceof MarkdownPane pane) pane.refresh();
            render(transcript, output.resolve("preview-actions-edited.png"), 860);
        });
        SwingUtilities.invokeAndWait(() -> {
            Transcript transcript = holder.get();
            find(transcript, "C1-复制").doClick(); check(clipboard.get().contains("users/{}"), "code copy follows saved edit");
            find(transcript, "copy-reply").doClick(); check(SAMPLE.equals(clipboard.get()), "whole copy still uses original Markdown");
            find(transcript, "like-reply").doClick(); check(suggestion.get().contains("C1"), "like suggests a related editable prompt");
            find(transcript, "like-reply").doClick(); check(suggestion.get().isEmpty(), "unlike removes suggestion");
            String augmented = transcript.prepare("请按 C1 的批注修改").message();
            check(augmented.contains("users/{}") && augmented.contains("超时重试"), "outgoing context uses edited code and annotation");
            check(transcript.prepare("法国首都是哪里？").description().isEmpty(), "unrelated prompt has no code context");
            transcript.clear(); check(transcript.codeBlocks().isEmpty() && transcript.prepare("C1").message().equals("C1"), "new conversation forgets notes and likes");
        });
        System.out.println("PASS: reply/code clipboard buttons, local edit drafts, annotations, relevance, context limits, likes and action previews");
    }
    private static void testRelevance() {
        CodeContext context = new CodeContext();
        List<CodeContext.Block> blocks = context.register("> ```rust\n> fn fetch_profile() {}\n> ```\n\n    second_code()\n\n```js\nfunction request_account() {}\n```");
        check(blocks.size() == 3, "nested, indented and fenced code registered");
        CodeContext.Block first = blocks.get(0), latest = blocks.get(2);
        check(context.prepare("请解释 C1").message().contains("fetch_profile"), "explicit code reference works before editing or annotating");
        first.note = "用户资料请求需要超时重试"; latest.note = "账户请求需要登录校验";
        check(context.prepare("fetch_profile 如何重试？").description().contains("C1"), "identifier overlap selects earlier block");
        check(context.prepare("用户资料请求的超时重试怎么写？").description().contains("C1"), "specific Chinese annotation overlap");
        check(context.prepare("请完善这段代码").description().contains("C3"), "deictic reference picks latest block");
        check(!context.prepare("C10 如何修改？").description().contains("C1"), "block IDs have token boundaries");
        check(context.prepare("let main return string").description().isEmpty(), "common code words do not trigger context");
        first.code = "x".repeat(60000); first.note = "note".repeat(3000);
        CodeContext.Prepared limited = context.prepare("请修改 C1");
        check(limited.message().length() < 15000 && limited.description().contains("截断"), "oversized context has visible bound and truncation marker");
        first.code = first.original; first.note = "";
        check(context.prepare("fetch_profile 如何重试？").description().isEmpty(), "restoring and removing note stops automatic enhancement");
    }
    private static JButton find(Container root, String name) {
        return descendants(root).stream().filter(component -> component instanceof JButton && name.equals(component.getName()))
                .map(component -> (JButton) component).findFirst().orElseThrow(() -> new AssertionError("missing button: " + name));
    }
    private static List<Component> descendants(Container root) {
        java.util.ArrayList<Component> result = new java.util.ArrayList<>();
        for (Component child : root.getComponents()) { result.add(child); if (child instanceof Container container) result.addAll(descendants(container)); }
        return result;
    }
    private static void render(Transcript transcript, Path file, int width) {
        try {
            if (!transcript.isDisplayable()) transcript.addNotify();
            transcript.setSize(width, 1000); transcript.doLayout();
            int height = transcript.getPreferredSize().height; transcript.setSize(width, height); layout(transcript);
            BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = image.createGraphics(); transcript.printAll(graphics); graphics.dispose();
            ImageIO.write(image, "png", file.toFile());
        } catch (Exception ex) { throw new IllegalStateException(ex); }
    }
    private static void layout(Container parent) {
        parent.doLayout(); for (Component child : parent.getComponents()) if (child instanceof Container container) layout(container);
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
