package com.starsea.nautilus;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.commonmark.Extension;
import org.commonmark.ext.gfm.strikethrough.Strikethrough;
import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension;
import org.commonmark.ext.gfm.tables.TablesExtension;
import org.commonmark.node.*;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.NodeRenderer;
import org.commonmark.renderer.html.HtmlNodeRendererContext;
import org.commonmark.renderer.html.HtmlRenderer;
import org.commonmark.renderer.html.HtmlWriter;

/** CommonMark plus GFM tables/strikethrough, adapted to Swing's HTML support. */
final class MarkdownRenderer {
    private static final List<Extension> EXTENSIONS = List.of(TablesExtension.create(), StrikethroughExtension.create());
    private static final Parser PARSER = Parser.builder().extensions(EXTENSIONS).build();
    private static final HtmlRenderer RENDERER = HtmlRenderer.builder().nodeRendererFactory(SwingRenderer::new).extensions(EXTENSIONS)
            .escapeHtml(true).sanitizeUrls(true).softbreak("<br>\n")
            .attributeProviderFactory(context -> (node, tag, attributes) -> {
                if (tag.equals("table")) {
                    attributes.put("width", "100%"); attributes.put("cellspacing", "0");
                    attributes.put("cellpadding", "7"); attributes.put("border", "1");
                }
            }).build();

    private MarkdownRenderer() {}
    static String render(String markdown) { return RENDERER.render(PARSER.parse(markdown)); }
    record Code(String language, String text) {}
    static List<Code> codes(String markdown) {
        List<Code> result = new ArrayList<>();
        PARSER.parse(markdown).accept(new AbstractVisitor() {
            @Override public void visit(FencedCodeBlock node) { result.add(new Code(language(node.getInfo()), node.getLiteral())); }
            @Override public void visit(IndentedCodeBlock node) { result.add(new Code("code", node.getLiteral())); }
        });
        return List.copyOf(result);
    }
    static String render(String markdown, List<CodeContext.Block> blocks) {
        if (blocks.isEmpty()) return render(markdown);
        return HtmlRenderer.builder().nodeRendererFactory(context -> new SwingRenderer(context, blocks))
                .extensions(EXTENSIONS).escapeHtml(true).sanitizeUrls(true).softbreak("<br>\n")
                .attributeProviderFactory(context -> (node, tag, attributes) -> {
                    if (tag.equals("table")) {
                        attributes.put("width", "100%"); attributes.put("cellspacing", "0");
                        attributes.put("cellpadding", "7"); attributes.put("border", "1");
                    }
                }).build().render(PARSER.parse(markdown));
    }
    private static String language(String info) {
        String name = info == null || info.isBlank() ? "code" : info.strip().split("\\s+", 2)[0];
        return name.matches("[a-zA-Z0-9_+#.\\-]{1,30}") ? name : "code";
    }

    static boolean isWebLink(String destination) {
        if (destination == null) return false;
        try {
            URI uri = new URI(destination);
            return ("https".equalsIgnoreCase(uri.getScheme()) || "http".equalsIgnoreCase(uri.getScheme()))
                    && uri.getRawAuthority() != null && !uri.getRawAuthority().isBlank();
        } catch (URISyntaxException ex) { return false; }
    }

    private static final class SwingRenderer implements NodeRenderer {
        private static final Pattern URL = Pattern.compile("https?://[^\\s<>\"']+", Pattern.CASE_INSENSITIVE);
        private final HtmlNodeRendererContext context;
        private final HtmlWriter html;
        private final List<CodeContext.Block> blocks;
        private int codeIndex;
        SwingRenderer(HtmlNodeRendererContext context) { this(context, List.of()); }
        SwingRenderer(HtmlNodeRendererContext context, List<CodeContext.Block> blocks) {
            this.context = context; html = context.getWriter(); this.blocks = blocks;
        }
        @Override public Set<Class<? extends Node>> getNodeTypes() {
            return Set.of(Image.class, Link.class, Text.class, FencedCodeBlock.class, IndentedCodeBlock.class, Strikethrough.class);
        }
        @Override public void render(Node node) {
            if (node instanceof Image) {
                html.text("[图片："); children(node); html.text("]");
            } else if (node instanceof Link link) {
                boolean safe = isWebLink(link.getDestination());
                if (safe) html.tag("a", Map.of("href", context.encodeUrl(link.getDestination())));
                children(node);
                if (safe) html.tag("/a");
            } else if (node instanceof Text text) {
                if (insideLink(text)) html.text(text.getLiteral());
                else autoLinks(text.getLiteral());
            } else if (node instanceof FencedCodeBlock code) {
                codeBlock(code.getLiteral(), code.getInfo());
            } else if (node instanceof IndentedCodeBlock code) {
                codeBlock(code.getLiteral(), "code");
            } else if (node instanceof Strikethrough) {
                html.tag("strike"); children(node); html.tag("/strike");
            }
        }
        private void children(Node node) {
            for (Node child = node.getFirstChild(); child != null; child = child.getNext()) context.render(child);
        }
        private boolean insideLink(Node node) {
            for (Node parent = node.getParent(); parent != null; parent = parent.getParent()) {
                if (parent instanceof Link || parent instanceof Image) return true;
            }
            return false;
        }
        private void autoLinks(String text) {
            Matcher matcher = URL.matcher(text);
            int previous = 0;
            while (matcher.find()) {
                int end = matcher.end();
                while (end > matcher.start() && ".,;:!?，。；！）]".indexOf(text.charAt(end - 1)) >= 0) end--;
                int balance = text.substring(matcher.start(), end).chars().map(ch -> ch == '(' ? 1 : ch == ')' ? -1 : 0).sum();
                while (end > matcher.start() && text.charAt(end - 1) == ')' && balance < 0) { end--; balance++; }
                String url = text.substring(matcher.start(), end);
                html.text(text.substring(previous, matcher.start()));
                if (isWebLink(url)) { html.tag("a", Map.of("href", context.encodeUrl(url))); html.text(url); html.tag("/a"); }
                else html.text(url);
                html.text(text.substring(end, matcher.end()));
                previous = matcher.end();
            }
            html.text(text.substring(previous));
        }
        private void codeBlock(String literal, String info) {
            String language = language(info);
            CodeContext.Block block = codeIndex < blocks.size() ? blocks.get(codeIndex) : null;
            int index = codeIndex++;
            if (block != null) literal = block.code;
            html.line(); html.tag("div", Map.of("class", "codeblock"));
            html.tag("p", Map.of("class", "language"));
            if (block == null) html.text(language.toUpperCase(java.util.Locale.ROOT));
            else html.tag("input", Map.of("type", "button", "name", "code-" + index, "value", block.id), true);
            html.tag("/p");
            html.tag("p", Map.of("class", "code"));
            String code = literal.endsWith("\n") ? literal.substring(0, literal.length() - 1) : literal;
            for (int i = 0; i < code.length(); i++) {
                char ch = code.charAt(i);
                if (ch == ' ') html.raw("&#160;");
                else if (ch == '\t') html.raw("&#160;&#160;&#160;&#160;");
                else if (ch == '\n') html.tag("br");
                else html.text(String.valueOf(ch));
            }
            html.tag("/p");
            if (block != null && block.hasContext()) {
                html.tag("p", Map.of("class", "note"));
                if (block.edited()) html.text("已修改 · 原回复保留。 ");
                if (!block.note.isBlank()) html.text("批注：" + block.note);
                html.tag("/p");
            }
            html.tag("/div"); html.line();
        }
    }
}
