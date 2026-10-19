package com.starsea.nautilus;

import java.awt.Color;
import java.awt.Desktop;
import java.awt.GraphicsEnvironment;
import java.net.URI;
import java.util.List;
import java.util.function.BiFunction;
import javax.swing.*;
import javax.swing.event.HyperlinkEvent;
import javax.swing.text.Element;
import javax.swing.text.ComponentView;
import javax.swing.text.View;
import javax.swing.text.ViewFactory;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.HTML;
import javax.swing.text.html.InlineView;
import javax.swing.text.html.StyleSheet;

/** Selectable Markdown output; URLs open only after an explicit click. */
@SuppressWarnings("serial")
final class MarkdownPane extends JEditorPane implements I18n.Localized {
    private final String markdown;
    private final List<CodeContext.Block> blocks;
    MarkdownPane(String markdown) { this(markdown, List.of(), (block, action) -> false); }
    MarkdownPane(String markdown, List<CodeContext.Block> blocks, BiFunction<CodeContext.Block, String, Boolean> action) {
        this.markdown = markdown; this.blocks = blocks;
        setEditable(false); setOpaque(false); setBorder(BorderFactory.createEmptyBorder());
        setFont(Theme.font(15, false)); setForeground(Theme.TEXT);
        setSelectionColor(new Color(0x5B507F)); setSelectedTextColor(Color.WHITE);
        putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.FALSE);
        putClientProperty(JEditorPane.W3C_LENGTH_UNITS, Boolean.TRUE);
        HTMLEditorKit kit = new WrappingHtmlKit(blocks, action);
        StyleSheet styles = new StyleSheet(); styles.addStyleSheet(kit.getStyleSheet());
        styles.addRule("body { font-family: SansSerif; font-size: 15px; color: #eef1f8; margin: 0; }");
        styles.addRule("p { margin-top: 0; margin-bottom: 10px; }");
        styles.addRule("h1 { font-size: 25px; margin-top: 10px; margin-bottom: 12px; color: #f5f2ff; }");
        styles.addRule("h2 { font-size: 21px; margin-top: 14px; margin-bottom: 10px; color: #f5f2ff; }");
        styles.addRule("h3, h4, h5, h6 { font-size: 17px; margin-top: 12px; margin-bottom: 8px; color: #e3dcff; }");
        styles.addRule("ul, ol { margin-top: 4px; margin-bottom: 12px; margin-left: 22px; }");
        styles.addRule("li { margin-bottom: 5px; }");
        styles.addRule("blockquote { margin-left: 8px; margin-right: 0; padding-left: 12px; border-left: 3px solid #b9abff; color: #b9c6d9; }");
        styles.addRule("code { font-family: Monospaced; font-size: 14px; color: #d5c8ff; background-color: #252c40; }");
        styles.addRule("div.codeblock { background-color: #101622; padding: 10px; margin-top: 8px; margin-bottom: 12px; }");
        styles.addRule("p.language { font-size: 10px; color: #72debf; margin-top: 0; margin-bottom: 7px; }");
        styles.addRule("p.code { font-family: Monospaced; font-size: 13px; color: #dfe6f3; margin: 0; }");
        styles.addRule("p.note { font-size: 12px; color: #b9abff; margin-top: 10px; margin-bottom: 0; }");
        styles.addRule("a { color: #9dcbff; text-decoration: underline; }");
        styles.addRule("table { border-color: #4a5771; margin-top: 8px; margin-bottom: 12px; }");
        styles.addRule("th { background-color: #29334a; color: #f0eaff; font-weight: bold; text-align: left; }");
        styles.addRule("td { color: #eef1f8; }");
        styles.addRule("hr { color: #4a5771; margin-top: 12px; margin-bottom: 12px; }");
        kit.setStyleSheet(styles); setEditorKit(kit);
        refresh();
        addHyperlinkListener(event -> {
            if (event.getEventType() == HyperlinkEvent.EventType.ENTERED) setToolTipText(event.getDescription());
            else if (event.getEventType() == HyperlinkEvent.EventType.EXITED) setToolTipText(null);
            else if (event.getEventType() == HyperlinkEvent.EventType.ACTIVATED && MarkdownRenderer.isWebLink(event.getDescription())) {
                openLink(event.getDescription());
            }
        });
    }

    @Override public void refreshLanguage() {
        int caret = getCaretPosition(); refresh(); setCaretPosition(Math.min(caret, getDocument().getLength()));
    }

    void refresh() {
        setText("<html><body>" + MarkdownRenderer.render(markdown, blocks) + "</body></html>"); setCaretPosition(0);
        revalidate(); repaint();
    }

    int heightFor(int width) {
        setSize(Math.max(40, width), Short.MAX_VALUE);
        return getPreferredSize().height;
    }

    private void openLink(String address) {
        new SwingWorker<Void, Void>() {
            @Override protected Void doInBackground() throws Exception {
                if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                    throw new UnsupportedOperationException("系统无法打开浏览器");
                }
                Desktop.getDesktop().browse(URI.create(address)); return null;
            }
            @Override protected void done() {
                try { get(); }
                catch (Exception ex) {
                    if (!GraphicsEnvironment.isHeadless()) JOptionPane.showMessageDialog(MarkdownPane.this,
                            I18n.t("无法打开浏览器，请复制链接地址：\n") + address, I18n.t("打开链接"), JOptionPane.INFORMATION_MESSAGE);
                }
            }
        }.execute();
    }

    private static final class WrappingHtmlKit extends HTMLEditorKit {
        private final ViewFactory factory;
        WrappingHtmlKit(List<CodeContext.Block> blocks, BiFunction<CodeContext.Block, String, Boolean> action) {
            factory = new HTMLFactory() {
            @Override public View create(Element element) {
                Object name = element.getAttributes().getAttribute(HTML.Attribute.NAME);
                if (element.getName().equals(HTML.Tag.INPUT.toString()) && name instanceof String value && value.matches("code-[0-9]+")) {
                    int index = Integer.parseInt(value.substring(5));
                    if (index < blocks.size()) return new ComponentView(element) {
                        @Override protected java.awt.Component createComponent() {
                            CodeContext.Block block = blocks.get(index);
                            JPanel toolbar = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 6, 0)) {
                                @Override public void setBounds(int x, int y, int width, int height) {
                                    super.setBounds(x, y, width, height); doLayout();
                                }
                            };
                            toolbar.setOpaque(false);
                            toolbar.add(Theme.label(block.id + " · " + block.language.toUpperCase(java.util.Locale.ROOT), 10, Theme.MINT, true));
                            for (String label : List.of("复制", "修改", "批注")) {
                                Theme.Button button = new Theme.Button(label, false);
                                button.setFont(Theme.font(11, false)); button.setBorder(BorderFactory.createEmptyBorder(4, 9, 4, 9));
                                button.setName(block.id + "-" + label);
                                button.getAccessibleContext().setAccessibleName(block.id + " " + label);
                                button.addActionListener(event -> {
                                    boolean applied=Boolean.TRUE.equals(action.apply(block,label));
                                    if(label.equals("复制")&&applied)button.feedback("已复制 ✓");
                                }); toolbar.add(button);
                            }
                            return toolbar;
                        }
                    };
                }
                View view = super.create(element);
                // Preserve BRView and other specialized InlineView subclasses.
                if (view.getClass() == InlineView.class) return new InlineView(element) {
                    @Override public float getMinimumSpan(int axis) { return axis == View.X_AXIS ? 0 : super.getMinimumSpan(axis); }
                    @Override public int getBreakWeight(int axis, float pos, float len) {
                        int weight = super.getBreakWeight(axis, pos, len);
                        return axis == View.X_AXIS && len > 0 && weight == View.BadBreakWeight ? View.GoodBreakWeight : weight;
                    }
                };
                return view;
            }
            };
        }
        @Override public ViewFactory getViewFactory() { return factory; }
    }
}
