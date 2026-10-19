package com.starsea.nautilus;

import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.text.JTextComponent;

@SuppressWarnings("serial")
final class Transcript extends JPanel implements Scrollable {
    private final List<MessageCard> messages = new ArrayList<>();
    private final List<JComponent> timeline = new ArrayList<>();
    private ProgressBubble pending;
    void beginProgress(){pending=new ProgressBubble();timeline.add(pending);add(pending);revalidate();repaint();}
    void progress(ChatService.Progress event){if(pending!=null)pending.update(event);}
    void finishProgress(boolean success){if(pending!=null){pending.finish(success);pending=null;}revalidate();repaint();}
    private final CodeContext codeContext = new CodeContext();
    private final Consumer<String> suggest, clipboard;
    private final Runnable contextChanged;
    Transcript() { this(text -> {}, () -> {}); }
    Transcript(Consumer<String> suggest, Runnable contextChanged) {
        this(suggest, contextChanged, text -> Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null));
    }
    Transcript(Consumer<String> suggest, Runnable contextChanged, Consumer<String> clipboard) {
        this.suggest = suggest; this.contextChanged = contextChanged; this.clipboard = clipboard;
        setLayout(null); setBackground(Theme.BG);
    }

    void addMessage(String role, String text, boolean user, boolean error) {
        MessageCard card = new MessageCard(role, text, user, error);
        messages.add(card); timeline.add(card); add(card); revalidate(); repaint();
    }
    void clear() { finishProgress(false);timeline.clear();messages.clear(); codeContext.clear(); suggest.accept(""); contextChanged.run(); removeAll(); revalidate(); repaint(); }
    int messageCount() { return messages.size(); }
    CodeContext.Prepared prepare(String prompt) { return codeContext.prepare(prompt); }
    List<CodeContext.Block> codeBlocks() { return codeContext.blocks(); }

    private int widthFor(MessageCard card, int available) {
        int maximum = Math.max(120, Math.min(820, available - 56));
        if (!card.user) return maximum;
        String firstLine = card.text.lines().findFirst().orElse("");
        int desired = getFontMetrics(Theme.font(15, false)).stringWidth(firstLine) + 42;
        return Math.min(maximum, Math.max(260, Math.min(650, desired)));
    }
    private int contentHeight(int width, boolean layout) {
        int y = 24;
        for (JComponent item : timeline) {
            int w = item instanceof MessageCard card ? widthFor(card,width) : Math.max(120,Math.min(820,width-56));
            int h = item instanceof MessageCard card ? card.heightFor(w) : ((ProgressBubble)item).heightFor(w);
            if (layout) item.setBounds(item instanceof MessageCard card && card.user ? width - w - 28 : 28,y,w,h);
            y += h + 20;
        }
        return y + 12;
    }
    @Override public void doLayout() { contentHeight(getWidth(), true); }
    @Override public Dimension getPreferredSize() {
        int width = getParent() == null ? 850 : Math.max(160, getParent().getWidth());
        return new Dimension(width, contentHeight(width, false));
    }
    @Override public Dimension getPreferredScrollableViewportSize() { return new Dimension(850, 500); }
    @Override public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) { return 24; }
    @Override public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) { return Math.max(24, visible.height - 24); }
    @Override public boolean getScrollableTracksViewportWidth() { return true; }
    @Override public boolean getScrollableTracksViewportHeight() { return false; }

    private final class MessageCard extends Theme.Surface {
        final boolean user;
        final String text;
        final JTextComponent body;
        private final JLabel feedback = Theme.label("", 11, Theme.MUTED, false);
        private boolean liked;

        MessageCard(String role, String text, boolean user, boolean error) {
            super(error ? new Color(0x2C1C29) : user ? new Color(0x2B2941) : Theme.SURFACE,
                    error ? new Color(0x67404F) : user ? new Color(0x4B426B) : Theme.LINE, 20);
            this.user = user; this.text = text;
            setLayout(new BorderLayout(0, 10)); setBorder(BorderFactory.createEmptyBorder(16, 18, 16, 18));
            JPanel header = new JPanel(new BorderLayout()); header.setOpaque(false);
            JLabel roleLabel = Theme.label(role, 11, error ? Theme.ERROR : user ? Theme.ACCENT : Theme.MINT, true);
            roleLabel.setToolTipText(role); header.add(roleLabel, BorderLayout.CENTER);
            JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0)); actions.setOpaque(false);
            actions.add(Theme.label(LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm")), 10, Theme.MUTED, false));
            Theme.Button copy = smallButton("复制全文"); copy.setName("copy-reply");
            I18n.tooltip(copy, "复制原始 Markdown 全文；代码块按钮复制当前编辑版本");
            copy.addActionListener(event -> {if(copyText(text))copy.feedback("已复制 ✓");});
            actions.add(copy); header.add(actions, BorderLayout.EAST); add(header, BorderLayout.NORTH);
            if (!user && !error) {
                List<CodeContext.Block> blocks = codeContext.register(text);
                MarkdownPane markdown = new MarkdownPane(text, blocks, this::codeAction); body = markdown;
                Theme.Button like = smallButton("点赞"); like.setName("like-reply");
                I18n.tooltip(like, "作为本轮偏好，生成可编辑的后续问题建议；不会自动发送");
                like.addActionListener(event -> {
                    liked = !liked; like.setText(liked ? "已点赞 ✓" : "点赞");
                    String suggestion = blocks.isEmpty() ? "请沿着刚才的思路，给出一个具体示例，并说明注意事项。"
                            : "请结合 " + blocks.get(blocks.size() - 1).id + " 的代码和批注，解释关键实现，并补充边界情况与测试示例。";
                    suggest.accept(liked ? I18n.t(suggestion) : "");
                    feedback.setText(liked ? "已生成下一句建议 · 点击输入区的建议后可编辑" : "已取消点赞");
                });
                actions.add(like);
            } else {
                JTextArea plain = new JTextArea(text); plain.setLineWrap(true); plain.setWrapStyleWord(true);
                body = plain;
            }
            body.setEditable(false);
            body.setFont(Theme.font(15, false)); body.setForeground(Theme.TEXT); body.setOpaque(false);
            body.setSelectionColor(new Color(0x5B507F)); body.setSelectedTextColor(Color.WHITE);
            body.setBorder(BorderFactory.createEmptyBorder()); add(body, BorderLayout.CENTER);
            add(feedback, BorderLayout.SOUTH);
        }
        private Theme.Button smallButton(String label) {
            Theme.Button button = new Theme.Button(label, false); button.setFont(Theme.font(11, false));
            button.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8)); return button;
        }
        private boolean copyText(String value) {
            try { clipboard.accept(value); feedback.setText("已复制到剪贴板"); return true; }
            catch (RuntimeException ex) { feedback.setText("剪贴板不可用，请选中文本后复制"); return false; }
        }
        private boolean codeAction(CodeContext.Block block, String action) {
            if (action.equals("复制")) return copyText(block.code);
            if (GraphicsEnvironment.isHeadless()) return false;
            CodeEditorPanel editor = new CodeEditorPanel(block, action.equals("批注"));
            Object[] options = {I18n.t("保存"), I18n.t("恢复原代码并清空批注"), I18n.t("取消")};
            int choice = JOptionPane.showOptionDialog(this, editor, block.id + " · " + I18n.t(action),
                    JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null, options, options[0]);
            if (choice == 0) editor.apply(block);
            else if (choice == 1) { block.code = block.original; block.note = ""; }
            else return false;
            ((MarkdownPane) body).refresh(); contextChanged.run();
            feedback.setText(block.hasContext() ? block.id + " 已保存 · 相关追问时附带；也可直接在问题中写 " + block.id : block.id + " 已恢复原始版本");
            Transcript.this.revalidate(); Transcript.this.repaint(); return true;
        }
        int heightFor(int width) {
            if (body instanceof MarkdownPane markdown) return Math.max(104, markdown.heightFor(width - 36) + 88);
            body.setSize(Math.max(40, width - 36), Short.MAX_VALUE);
            return Math.max(104, body.getPreferredSize().height + 88);
        }
    }
}
