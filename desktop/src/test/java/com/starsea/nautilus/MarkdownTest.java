package com.starsea.nautilus;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import javax.swing.*;

public final class MarkdownTest {
    static final String SAMPLE = """
            ## Rust 所有权

            **每个值都有唯一的所有者**，借用允许我们在不转移所有权的情况下读取数据。

            - 移动：将值的所有权交给另一个变量。
            - 借用：通过 `&value` 访问值，也可以包含 *强调* 与 **加粗**。

            > 编译器会在编译阶段帮助检查生命周期与借用规则。

            ### 一个小例子

            ```rust
            let message = String::from("Hello, Rust!");
            let borrowed = &message;
            println!("{}", borrowed);
            ```

            | 操作 | 所有权转移 | 原变量能否继续使用 |
            | --- | :---: | --- |
            | `let b = a` | 是 | 否 |
            | `let b = &a` | 否 | 是 |

            ~~手动释放所有内存~~ → 让所有权规则管理资源。

            参考：[The Rust Book](https://doc.rust-lang.org/book/ch04-00-understanding-ownership.html)
            """;

    private MarkdownTest() {}
    public static void main(String[] args) throws Exception {
        System.setProperty("java.awt.headless", "true");
        String html = MarkdownRenderer.render(SAMPLE);
        for (String tag : new String[]{"<h2>", "<strong>", "<em>", "<ul>", "<blockquote>", "class=\"code\"", "<table", "<strike>", "<a href="}) {
            check(html.contains(tag), "missing Markdown structure: " + tag);
        }
        check(html.contains("&#160;"), "code indentation is preserved");
        String code = MarkdownRenderer.render("```html\n<script>**literal**</script>\n```\n\n    indented_code()");
        check(!code.contains("<script>") && code.contains("&lt;script&gt;") && code.contains("**literal**"), "code remains literal");
        String unsafe = MarkdownRenderer.render("<script>alert(1)</script>\n\n![alt](https://example.com/image.png)\n\n[x](javascript:alert(1)) [y](file:///tmp/test) [z](data:text/html,test)");
        check(!unsafe.contains("<script") && !unsafe.contains("<img") && !unsafe.contains("href="), "raw HTML, image loading and executable links disabled");
        check(MarkdownRenderer.isWebLink("https://example.com") && !MarkdownRenderer.isWebLink(null), "valid web links");
        String links = MarkdownRenderer.render("裸链接 https://example.com/path. [显式链接](https://example.com/other)\n\n[引用][ref]\n\n[ref]: https://example.com/ref");
        check(links.contains("href=\"https://example.com/path\"") && links.contains("href=\"https://example.com/ref\""), "bare URLs and reference links");
        String nested = MarkdownRenderer.render("1. outer\n   - nested **item**\n2. next\n\n---");
        check(nested.contains("<ol>") && nested.contains("<ul>") && nested.contains("<hr"), "nested lists and horizontal rules");

        Path output = Path.of(args.length == 0 ? "build" : args[0]); Files.createDirectories(output);
        SwingUtilities.invokeAndWait(() -> {
            try {
                MarkdownPane pane = new MarkdownPane(SAMPLE);
                String visible = pane.getDocument().getText(0, pane.getDocument().getLength());
                check(visible.contains("Rust 所有权") && !visible.contains("##") && !visible.contains("**每个"), "visible document is rendered Markdown");
                check(!pane.isEditable() && pane.getHyperlinkListeners().length > 0, "selectable read-only rich text with links");
                int wide = pane.heightFor(760), narrow = pane.heightFor(420);
                check(wide > 200 && narrow >= wide, "height follows rendered content and window width");
                MarkdownPane lines = new MarkdownPane("```rust\nfirst_line();\nsecond_line();\n```");
                lines.setSize(400, lines.heightFor(400));
                String lineText = lines.getDocument().getText(0, lines.getDocument().getLength());
                check(lines.modelToView2D(lineText.indexOf("second_line")).getY() >
                        lines.modelToView2D(lineText.indexOf("first_line")).getY(), "code newlines occupy separate visual lines");
                MarkdownPane longCode = new MarkdownPane("```text\n" + "very_long_identifier_".repeat(55) + "\n```");
                int height = longCode.heightFor(300); longCode.setSize(300, height);
                String document = longCode.getDocument().getText(0, longCode.getDocument().getLength());
                int end = document.lastIndexOf("identifier_") + "identifier_".length();
                check(longCode.modelToView2D(end).getMaxX() <= 305, "long code wraps inside the message");
                render(output.resolve("preview-markdown.png"), pane, 820);
                render(output.resolve("preview-markdown-compact.png"), pane, 500);
            } catch (Exception ex) { throw new IllegalStateException(ex); }
        });
        System.out.println("PASS: CommonMark/GFM structures, literal code, links, HTML escaping, text-only images, responsive Markdown layout");
    }
    private static void render(Path file, MarkdownPane pane, int width) throws Exception {
        int contentWidth = width - 48;
        int height = pane.heightFor(contentWidth) + 48;
        JPanel root = new JPanel(null); root.setBackground(Theme.SURFACE); root.setSize(width, height);
        root.add(pane); pane.setBounds(24, 24, contentWidth, height - 48); root.doLayout(); pane.doLayout();
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics(); root.printAll(graphics); graphics.dispose();
        ImageIO.write(image, "png", file.toFile()); root.remove(pane);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
