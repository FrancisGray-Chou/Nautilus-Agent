package com.starsea.nautilus;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

@SuppressWarnings("serial")
final class ChatPanel extends JPanel {
    private final ChatService backend;
    private final List<ModelCard> models = new ArrayList<>();
    private List<ChatService.Model> catalog = List.of();
    private GeminiSelector gemini;
    private final JPanel modelList = new JPanel();
    private final Transcript transcript = new Transcript(this::offerSuggestion, this::refreshContextPreview);
    private final JScrollPane history = new JScrollPane(transcript);
    private final CardLayout contentLayout = new CardLayout();
    private final JPanel content = new JPanel(contentLayout);
    private final PromptArea composer = new PromptArea();
    private final JPanel suggestions = new JPanel(new BorderLayout(10, 0));
    private final JTextArea suggestionText = new JTextArea();
    private final JPanel contextPreview = new JPanel(new BorderLayout(8, 0));
    private final JLabel contextLabel = Theme.label("", 11, Theme.ACCENT, false);
    private final JCheckBox includeContext = new JCheckBox("附带", true);
    private String nextSuggestion = "";
    private final Theme.Button send = new Theme.Button("发送  ↑", true);
    private final Theme.Button newChat = new Theme.Button("＋  新对话", false);
    private final Theme.Button keySettings = new Theme.Button("API 密钥与隐私", false);
    private final JLabel title = Theme.label("开始一段新对话", 20, Theme.TEXT, true);
    private final JLabel subtitle = Theme.label("选择模型，带着你的问题出发", 12, Theme.MUTED, false);
    private final JLabel status = Theme.label("正在准备…", 11, Theme.MUTED, false);
    private final JProgressBar progress = new JProgressBar();
    private String selectedId;
    private boolean connected, busy;
    private JDialog credentialDialog;
    private CredentialsPanel credentialPanel;
    private ChatService.Credentials credentials;
    private boolean credentialsChanged;
    private record Startup(List<ChatService.Model> models, ChatService.Credentials credentials) {}

    ChatPanel(ChatService backend) {
        this.backend = backend;
        setLayout(new BorderLayout()); setBackground(Theme.BG);
        add(sidebar(), BorderLayout.WEST);
        JPanel main = new JPanel(new BorderLayout()); main.setBackground(Theme.BG);
        main.add(header(), BorderLayout.NORTH);
        content.setBackground(Theme.BG);
        content.add(welcome(), "welcome");
        Theme.scroll(history); content.add(history, "history");
        main.add(content, BorderLayout.CENTER); main.add(composer(), BorderLayout.SOUTH);
        add(main, BorderLayout.CENTER);
        setBusy(false);
    }

    private JPanel sidebar() {
        JPanel side = new JPanel(new BorderLayout()); side.setBackground(Theme.SIDE);
        side.setPreferredSize(new Dimension(278, 700));
        side.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 0, 1, Theme.LINE),
                BorderFactory.createEmptyBorder(26, 20, 22, 20)));
        JPanel top = new JPanel(); top.setOpaque(false); top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        JPanel brand = new JPanel(new BorderLayout(12, 0)); brand.setOpaque(false);
        brand.add(new Theme.Logo(42), BorderLayout.WEST);
        JPanel names = new JPanel(new GridLayout(2, 1)); names.setOpaque(false);
        names.add(Theme.label("Nautilus", 21, Theme.TEXT, true));
        names.add(Theme.label("让想法自由流动", 11, Theme.MUTED, false));
        brand.add(names); brand.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));
        brand.setAlignmentX(LEFT_ALIGNMENT);
        top.add(brand); top.add(Box.createVerticalStrut(30));
        newChat.setAlignmentX(LEFT_ALIGNMENT); newChat.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
        newChat.addActionListener(event -> newConversation()); top.add(newChat);
        top.add(Box.createVerticalStrut(28));
        JLabel section = Theme.label("模型空间", 11, Theme.MUTED, true); section.setAlignmentX(LEFT_ALIGNMENT);
        top.add(section); top.add(Box.createVerticalStrut(12));
        modelList.setOpaque(false); modelList.setLayout(new BoxLayout(modelList, BoxLayout.Y_AXIS));
        modelList.setAlignmentX(LEFT_ALIGNMENT);
        side.add(top, BorderLayout.NORTH);
        JPanel modelSpace = new JPanel(new BorderLayout()); modelSpace.setOpaque(false); modelSpace.add(modelList, BorderLayout.NORTH);
        JScrollPane modelScroll = new JScrollPane(modelSpace); Theme.scroll(modelScroll);
        modelScroll.setName("model-space"); modelScroll.setOpaque(false); modelScroll.getViewport().setBackground(Theme.SIDE);
        modelScroll.setBorder(BorderFactory.createEmptyBorder(0, 0, 14, 0)); side.add(modelScroll, BorderLayout.CENTER);
        JPanel bottom = new JPanel(); bottom.setLayout(new BoxLayout(bottom, BoxLayout.Y_AXIS)); bottom.setOpaque(false);
        keySettings.setAlignmentX(LEFT_ALIGNMENT); keySettings.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
        keySettings.addActionListener(event -> openCredentials()); bottom.add(keySettings);
        bottom.add(Box.createVerticalStrut(10));
        bottom.add(Theme.label("TEXT CONVERSATIONS", 9, new Color(0x687792), true));
        bottom.add(Theme.label("StarSea Community", 11, new Color(0x687792), false));
        side.add(bottom, BorderLayout.SOUTH); return side;
    }

    private JPanel header() {
        JPanel header = new JPanel(new BorderLayout(16, 0)); header.setOpaque(false);
        header.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.LINE),
                BorderFactory.createEmptyBorder(24, 30, 22, 30)));
        JPanel heading = new JPanel(new GridLayout(2, 1, 0, 7)); heading.setOpaque(false);
        heading.add(title); heading.add(subtitle); header.add(heading);
        Theme.Surface pill = new Theme.Surface(new Color(0x172A28), new Color(0x294A43), 22);
        pill.setLayout(new FlowLayout(FlowLayout.CENTER, 12, 9));
        pill.add(Theme.label("●  纯文本对话", 11, Theme.MINT, true));
        JPanel right = new JPanel(new GridBagLayout()); right.setOpaque(false); right.add(pill);
        header.add(right, BorderLayout.EAST); return header;
    }

    private JPanel welcome() {
        JPanel outer = new JPanel(new GridBagLayout()); outer.setBackground(Theme.BG);
        JPanel hero = new JPanel(); hero.setOpaque(false); hero.setLayout(new BoxLayout(hero, BoxLayout.Y_AXIS));
        Theme.Logo logo = new Theme.Logo(72); logo.setMaximumSize(new Dimension(72, 72)); logo.setAlignmentX(CENTER_ALIGNMENT);
        hero.add(logo); hero.add(Box.createVerticalStrut(24));
        JLabel eyebrow = Theme.label("A LITTLE CURIOSITY GOES A LONG WAY", 10, Theme.MINT, true);
        eyebrow.setAlignmentX(CENTER_ALIGNMENT); hero.add(eyebrow); hero.add(Box.createVerticalStrut(14));
        JLabel heading = Theme.label("把想法，聊清楚。", 34, Theme.TEXT, true); heading.setAlignmentX(CENTER_ALIGNMENT); hero.add(heading);
        hero.add(Box.createVerticalStrut(14));
        JLabel hint = Theme.label("从一个问题开始，让思路慢慢展开。", 14, Theme.MUTED, false); hint.setAlignmentX(CENTER_ALIGNMENT); hero.add(hint);
        hero.add(Box.createVerticalStrut(32));
        JPanel suggestions = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 0)); suggestions.setOpaque(false);
        String[][] starters = {{"读懂一段代码", "请帮我解释下面这段代码的逻辑：\n"}, {"梳理一个想法", "我想和你一起梳理一个想法：\n"}, {"制定学习计划", "请帮我制定一份学习计划：\n"}};
        for (String[] starter : starters) {
            Theme.Button chip = new Theme.Button(starter[0], false);
            chip.addActionListener(event -> { composer.setText(starter[1]); composer.requestFocusInWindow(); });
            suggestions.add(chip);
        }
        hero.add(suggestions); outer.add(hero); return outer;
    }

    private JPanel composer() {
        JPanel outer = new JPanel(new BorderLayout(0, 10)); outer.setOpaque(false);
        outer.setBorder(BorderFactory.createEmptyBorder(10, 28, 20, 28));
        Theme.Surface box = new Theme.Surface(Theme.SURFACE, new Color(0x3B425B), 24);
        box.setLayout(new BorderLayout(16, 0)); box.setBorder(BorderFactory.createEmptyBorder(16, 18, 14, 14));
        composer.setRows(3); composer.setLineWrap(true); composer.setWrapStyleWord(true);
        composer.setName("message-input");
        composer.setFont(Theme.font(15, false)); composer.setForeground(Theme.TEXT); composer.setCaretColor(Theme.ACCENT);
        composer.setOpaque(false); composer.setBorder(BorderFactory.createEmptyBorder());
        composer.setSelectionColor(new Color(0x5B507F));
        JScrollPane input = new JScrollPane(composer); Theme.scroll(input); input.getViewport().setOpaque(false); input.setOpaque(false);
        input.setPreferredSize(new Dimension(100, 80)); box.add(input);
        JPanel sendWrap = new JPanel(new BorderLayout()); sendWrap.setOpaque(false); sendWrap.add(send, BorderLayout.SOUTH);
        send.addActionListener(event -> submit()); box.add(sendWrap, BorderLayout.EAST);
        composer.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "send-message");
        composer.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.SHIFT_DOWN_MASK), "insert-break");
        composer.getActionMap().put("send-message", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { submit(); }
        });
        JPanel assistance = new JPanel(); assistance.setOpaque(false); assistance.setLayout(new BoxLayout(assistance, BoxLayout.Y_AXIS));
        suggestions.setOpaque(false); suggestions.setBorder(BorderFactory.createEmptyBorder(0, 0, 10, 0));
        suggestionText.setEditable(false); suggestionText.setLineWrap(true); suggestionText.setWrapStyleWord(true);
        suggestionText.setFont(Theme.font(12, false)); suggestionText.setForeground(Theme.MUTED); suggestionText.setOpaque(false);
        suggestionText.setRows(2); suggestions.add(suggestionText);
        JPanel suggestionActions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0)); suggestionActions.setOpaque(false);
        Theme.Button use = new Theme.Button("填入建议", false); use.setName("use-suggestion");
        use.addActionListener(event -> {
            composer.setText(composer.getText().isBlank() ? nextSuggestion : composer.getText() + "\n" + nextSuggestion);
            composer.requestFocusInWindow(); offerSuggestion("");
        });
        Theme.Button dismiss = new Theme.Button("关闭", false); dismiss.addActionListener(event -> offerSuggestion(""));
        suggestionActions.add(use); suggestionActions.add(dismiss); suggestions.add(suggestionActions, BorderLayout.EAST);
        suggestions.setVisible(false); assistance.add(suggestions);
        contextPreview.setOpaque(false); contextPreview.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
        includeContext.setOpaque(false); includeContext.setForeground(Theme.ACCENT); includeContext.setFont(Theme.font(11, false));
        includeContext.setName("include-code-context");
        includeContext.addActionListener(event -> refreshContextPreview());
        contextPreview.add(contextLabel); contextPreview.add(includeContext, BorderLayout.EAST); contextPreview.setVisible(false);
        assistance.add(contextPreview); outer.add(assistance, BorderLayout.NORTH);
        composer.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent event) { refreshContextPreview(); }
            @Override public void removeUpdate(DocumentEvent event) { refreshContextPreview(); }
            @Override public void changedUpdate(DocumentEvent event) { refreshContextPreview(); }
        });
        outer.add(box);
        JPanel footer = new JPanel(new BorderLayout()); footer.setOpaque(false);
        footer.add(Theme.label("Enter 发送 · Shift + Enter 换行", 10, Theme.MUTED, false));
        JPanel activity = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0)); activity.setOpaque(false);
        progress.setPreferredSize(new Dimension(36, 4)); progress.setBorderPainted(false);
        progress.setForeground(Theme.ACCENT); progress.setBackground(Theme.SURFACE); progress.setVisible(false);
        activity.add(progress); activity.add(status); footer.add(activity, BorderLayout.EAST);
        outer.add(footer, BorderLayout.SOUTH); return outer;
    }

    void connect() {
        runAsync("正在准备模型…", () -> new Startup(backend.initialize(), backend.credentials()), startup -> {
            connected = true;
            credentials = startup.credentials();
            catalog = startup.models();
            for (ChatService.Model option : catalog) {
                if (option.id().startsWith("gemini-")) continue;
                ModelCard card = new ModelCard(option, () -> chooseModel(option.id()));
                models.add(card); modelList.add(card); modelList.add(Box.createVerticalStrut(8));
            }
            List<ChatService.Model> presets = catalog.stream().filter(model -> model.id().startsWith("gemini-")).toList();
            if (!presets.isEmpty()) { gemini = new GeminiSelector(presets, this::chooseModel); modelList.add(gemini); }
            modelList.revalidate(); modelList.repaint(); setBusy(false);
            if (GraphicsEnvironment.isHeadless()) selectConfiguredModel();
            else showCredentials();
        });
    }

    private void selectConfiguredModel() {
        if (!connected || busy) return;
        String choice = credentials.deepseek() ? "deepseek" : credentials.openai() ? "openai" : credentials.gemini() ? "gemini-flash" : null;
        if (choice != null) chooseModel(choice);
        else { setStatus("请在 API 密钥与隐私中配置一个平台", false); setBusy(false); }
    }

    private void openCredentials() {
        if (!connected || busy || credentialDialog != null) return;
        runAsync("正在读取密钥状态…", backend::credentials, state -> { credentials = state; showCredentials(); });
    }

    private void showCredentials() {
        if (GraphicsEnvironment.isHeadless()) return;
        credentialsChanged = false;
        credentialDialog = new JDialog(SwingUtilities.getWindowAncestor(this), "API 密钥与隐私", Dialog.ModalityType.APPLICATION_MODAL);
        credentialPanel = new CredentialsPanel(credentials, new CredentialsPanel.Actions() {
            @Override public void save(java.util.Map<String, String> keys) { credentialTask(() -> backend.saveCredentials(keys, true)); }
            @Override public void delete(String provider) { credentialTask(() -> backend.deleteCredentials(provider)); }
            @Override public void close() { credentialDialog.dispose(); }
        });
        credentialDialog.setContentPane(credentialPanel); credentialDialog.setSize(820, 760);
        credentialDialog.setMinimumSize(new Dimension(780, 720)); credentialDialog.setLocationRelativeTo(this);
        credentialDialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        credentialDialog.addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent event) {
                credentialPanel.clearInputs(); credentialPanel = null; credentialDialog = null;
                if (connected && (credentialsChanged || selectedId == null)) selectConfiguredModel();
            }
        });
        credentialDialog.setVisible(true);
    }

    private void credentialTask(Callable<ChatService.Credentials> task) {
        CredentialsPanel panel = credentialPanel;
        JDialog dialog = credentialDialog;
        panel.setWorking(true); setBusy(true);
        dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        new SwingWorker<ChatService.Credentials, Void>() {
            @Override protected ChatService.Credentials doInBackground() throws Exception { return task.call(); }
            @Override protected void done() {
                try {
                    credentials = get(); credentialsChanged = true; selectedId = null;
                    transcript.clear(); composer.setText(""); contentLayout.show(content, "welcome");
                    models.forEach(card -> card.active(false)); title.setText("开始一段新对话"); subtitle.setText("密钥配置已更新，旧会话已清空");
                    if (gemini != null) gemini.sync(null);
                    panel.apply(credentials); panel.message("配置已更新；关闭此窗口后即可使用已配置的模型。", false);
                } catch (InterruptedException ex) { panel.message("操作被中断，请重试", true); }
                catch (ExecutionException ex) { panel.message(ex.getCause().getMessage(), true); }
                finally {
                    panel.setWorking(false); setBusy(false);
                    dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
                }
            }
        }.execute();
    }

    void chooseModel(String id) {
        if (busy || !connected) return;
        runAsync("正在切换模型…", () -> backend.select(id), selection -> {
            selectedId = selection.modelId();
            for (ModelCard card : models) card.active(card.option.id().equals(selectedId));
            String name = catalog.stream().filter(option -> option.id().equals(selectedId)).map(ChatService.Model::name).findFirst().orElse(selection.provider());
            title.setText(name);
            subtitle.setText(selection.search() ? "思考模式 + Google Search · 切换时保留上下文" : selection.model() + " · 切换时保留上下文");
            setStatus("就绪", false); setBusy(false); composer.requestFocusInWindow();
        });
    }

    void submitText(String text) { composer.setText(text); submit(); }

    private void submit() {
        String message = composer.getText().strip();
        if (busy || selectedId == null || message.isEmpty()) return;
        CodeContext.Prepared prepared = includeContext.isSelected() ? transcript.prepare(message) : new CodeContext.Prepared(message, "");
        transcript.addMessage(prepared.description().isEmpty() ? "你" : "你 · 附带代码批注", message, true, false); contentLayout.show(content, "history");
        composer.setText(""); offerSuggestion(""); scrollToBottom();
        runAsync("正在思考…", () -> backend.chat(prepared.message()), reply -> {
            transcript.addMessage(reply.provider() + "  /  " + reply.model(), reply.text(), false, false);
            setStatus("就绪 · " + reply.model(), false); scrollToBottom(); composer.requestFocusInWindow();
        });
    }

    void newConversation() {
        if (busy || !connected) return;
        runAsync("正在新建对话…", () -> { backend.reset(); return true; }, ignored -> {
            transcript.clear(); composer.setText(""); contentLayout.show(content, "welcome");
            includeContext.setSelected(true);
            setStatus("新对话已就绪", false); composer.requestFocusInWindow();
        });
    }

    private <T> void runAsync(String activity, Callable<T> task, Consumer<T> success) {
        setBusy(true); setStatus(activity, false);
        new SwingWorker<T, Void>() {
            @Override protected T doInBackground() throws Exception { return task.call(); }
            @Override protected void done() {
                setBusy(false);
                try { success.accept(get()); }
                catch (InterruptedException ex) { showError("操作被中断，请重试"); }
                catch (ExecutionException ex) {
                    Throwable cause = ex.getCause(); showError(cause.getMessage() == null ? "操作失败，请重试" : cause.getMessage());
                }
                finally { if (gemini != null) gemini.sync(selectedId); }
            }
        }.execute();
    }

    private void showError(String error) {
        transcript.addMessage("请求未完成", error, false, true); contentLayout.show(content, "history");
        setStatus("请求未完成 · 可以继续重试或切换模型", true); scrollToBottom(); composer.requestFocusInWindow();
    }
    private void setStatus(String text, boolean error) { status.setText(text); status.setForeground(error ? Theme.ERROR : Theme.MUTED); }
    private void setBusy(boolean value) {
        busy = value; progress.setVisible(value); progress.setIndeterminate(value);
        send.setEnabled(connected && selectedId != null && !value); newChat.setEnabled(connected && !value);
        keySettings.setEnabled(connected && !value);
        for (ModelCard card : models) card.setEnabled(connected && !value);
        if (gemini != null) gemini.working(!connected || value);
    }
    private void offerSuggestion(String text) {
        nextSuggestion = text;
        suggestionText.setText(text.isEmpty() ? "" : "基于点赞的后续建议（可编辑）：" + text);
        suggestions.setVisible(!text.isEmpty()); revalidate(); repaint();
    }
    private void refreshContextPreview() {
        CodeContext.Prepared prepared = transcript.prepare(composer.getText().strip());
        contextLabel.setText(includeContext.isSelected() ? prepared.description() : "本次不附带代码批注");
        contextLabel.setToolTipText(prepared.description()); contextPreview.setVisible(!prepared.description().isEmpty());
        revalidate(); repaint();
    }
    private void scrollToBottom() {
        transcript.revalidate();
        SwingUtilities.invokeLater(() -> history.getVerticalScrollBar().setValue(history.getVerticalScrollBar().getMaximum()));
    }
    boolean isBusy() { return busy; }
    int messageCount() { return transcript.messageCount(); }
    String selectedModelId() { return selectedId; }
    void close() { connected = false; if (credentialDialog != null) credentialDialog.dispose(); backend.close(); }

    private static final class PromptArea extends JTextArea {
        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            if (getText().isEmpty()) {
                Graphics2D g = (Graphics2D) graphics.create(); Theme.antialias(g);
                g.setFont(getFont()); g.setColor(new Color(0x7F8CA4));
                g.drawString("向 Nautilus 发送消息…", 0, getFontMetrics(getFont()).getAscent() + 1); g.dispose();
            }
        }
    }
}
