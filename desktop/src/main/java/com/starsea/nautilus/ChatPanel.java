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
final class ChatPanel extends JPanel implements I18n.Localized {
    private final ChatService backend;
    private final List<ModelCard> models = new ArrayList<>();
    private List<ChatService.Model> catalog = List.of();
    private GeminiSelector gemini;
    private ModelSelector deepseek;
    private ModelSelector claude;
    private final JPanel modelList = new JPanel();
    private final JPanel railModels = new JPanel();
    private final List<ModelCard> railCards = new ArrayList<>();
    private final ConversationStore conversationStore = new ConversationStore();
    private final java.util.concurrent.ExecutorService historyIO = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {Thread thread=new Thread(r,"nautilus-history");thread.setDaemon(false);return thread;});
    private final List<ConversationStore.Session> conversations = new ArrayList<>();
    private final List<ConversationStore.Entry> entries = new ArrayList<>();
    private final ConversationList conversationList = new ConversationList(this::openConversation,this::deleteConversation);
    private final Timer draftSave = new Timer(350,e->persistCurrent());
    private ResizableSidebar sidebar;
    private FoldSection modelSection, historySection;
    private String conversationId = java.util.UUID.randomUUID().toString();
    private boolean restoring;

    private final Transcript transcript = new Transcript(this::offerSuggestion, this::refreshContextPreview);
    private final JScrollPane history = new JScrollPane(transcript);
    private final CardLayout contentLayout = new CardLayout();
    private final Motion.RevealPanel content = new Motion.RevealPanel(contentLayout);
    private String visibleContent="welcome";
    private boolean scrollQueued,latestQueued;
    private long scrollVersion;
    private final Theme.Button latest=new Theme.Button("回到最新 ↓",false).quiet();
    private final Timer contextUpdate=new Timer(90,e->updateContextPreview());
    private ConversationStore.Session lastSnapshot;
    private final java.util.concurrent.ConcurrentMap<String,ConversationStore.Session> pendingSaves=new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.atomic.AtomicBoolean saving=new java.util.concurrent.atomic.AtomicBoolean();
    private final PromptArea composer = new PromptArea();
    private final JPanel suggestions = new JPanel(new BorderLayout(10, 0));
    private final JTextArea suggestionText = new JTextArea();
    private final JPanel contextPreview = new JPanel(new BorderLayout(8, 0));
    private final JLabel contextLabel = Theme.label("", 11, Theme.ACCENT, false);
    private final JCheckBox includeContext = new I18n.CheckBox("附带", true);
    private String nextSuggestion = "";
    private final SearchBubble searchBubble = new SearchBubble();
    private ChatService.WebSearch searchState = new ChatService.WebSearch(false, false, false);
    private ChatService.Selection currentSelection;
    private final Theme.Button send = new Theme.Button("发送  ↑", true);
    private final Theme.Button newChat = new Theme.Button("＋  新对话", false);
    private final SettingsMenu settings;
    private final JLabel title = Theme.label("开始一段新对话", 20, Theme.TEXT, true);
    private final JLabel subtitle = Theme.label("选择模型，带着你的问题出发", 12, Theme.MUTED, false);
    private final JLabel status = Theme.label("正在准备…", 11, Theme.MUTED, false);
    private final JProgressBar progress = new JProgressBar();
    private String selectedId, savedModelId="";
    private ChatService.MixtureTeam mixtureTeam;
    private boolean connected, busy, closed;
    private JDialog credentialDialog;
    private CredentialsPanel credentialPanel;
    private ChatService.Credentials credentials;
    private boolean credentialsChanged;
    private record Startup(List<ChatService.Model> models, ChatService.Credentials credentials,List<ConversationStore.Session> history,String historyError, ChatService.WebSearch search) {}
    private record Restored(ChatService.Selection selection,String warning) {}

    ChatPanel(ChatService backend) {
        this.backend = backend;
        settings = new SettingsMenu(this::openCredentials, this::changeLanguage, this::exitApplication);
        setLayout(new BorderLayout()); setBackground(Theme.BG);
        sidebar=sidebar();add(sidebar, BorderLayout.WEST);draftSave.setRepeats(false);contextUpdate.setRepeats(false);
        JPanel main = new JPanel(new BorderLayout()); main.setBackground(Theme.BG);
        main.add(header(), BorderLayout.NORTH);
        content.setBackground(Theme.BG);
        content.add(welcome(), "welcome");
        Theme.scroll(history); content.add(history, "history");
        history.getVerticalScrollBar().addAdjustmentListener(e->queueLatestUpdate());
        main.add(content, BorderLayout.CENTER); main.add(composer(), BorderLayout.SOUTH);
        add(main, BorderLayout.CENTER);
        setBusy(false);
    }

    @Override public void addNotify() { super.addNotify(); Appearance.attach(this); }
    @Override public void removeNotify() { Appearance.detach(this); super.removeNotify(); }

    private ResizableSidebar sidebar() {
        JPanel side = new JPanel(new BorderLayout()); side.setBackground(Theme.SIDE);
        side.setBorder(BorderFactory.createEmptyBorder(24, 20, 20, 14));
        JPanel top = new JPanel(); top.setOpaque(false); top.setLayout(new BoxLayout(top, BoxLayout.Y_AXIS));
        JPanel brand = new JPanel(new BorderLayout(12, 0)); brand.setOpaque(false);
        brand.add(new Theme.Logo(42), BorderLayout.WEST);
        JPanel names = new JPanel(new GridLayout(2, 1)); names.setOpaque(false);
        names.add(Theme.label("Nautilus", 21, Theme.TEXT, true));names.add(Theme.label("让想法自由流动", 11, Theme.MUTED, false));
        brand.add(names); brand.setMaximumSize(new Dimension(Integer.MAX_VALUE, 44));brand.setAlignmentX(LEFT_ALIGNMENT);
        top.add(brand); top.add(Box.createVerticalStrut(24));
        newChat.setAlignmentX(LEFT_ALIGNMENT); newChat.setMaximumSize(new Dimension(Integer.MAX_VALUE, 42));
        newChat.addActionListener(event -> newConversation()); top.add(newChat);top.add(Box.createVerticalStrut(20));side.add(top,BorderLayout.NORTH);
        modelList.setOpaque(false);modelList.setLayout(new BoxLayout(modelList,BoxLayout.Y_AXIS));modelList.setAlignmentX(LEFT_ALIGNMENT);
        modelSection=new FoldSection("models-section","模型空间",modelList,true);
        historySection=new FoldSection("history-section","历史对话",conversationList,false);
        Theme.ScrollBody sections=new Theme.ScrollBody(null);sections.setLayout(new BoxLayout(sections,BoxLayout.Y_AXIS));
        sections.add(modelSection);sections.add(Box.createVerticalStrut(12));sections.add(historySection);
        JScrollPane sectionScroll=new JScrollPane(sections);Theme.scroll(sectionScroll);sectionScroll.setName("model-space");sectionScroll.setOpaque(false);sectionScroll.getViewport().setBackground(Theme.SIDE);
        sectionScroll.setBorder(BorderFactory.createEmptyBorder(0,0,12,0));side.add(sectionScroll);
        JPanel bottom=new JPanel();bottom.setLayout(new BoxLayout(bottom,BoxLayout.Y_AXIS));bottom.setOpaque(false);bottom.add(settings);bottom.add(Box.createVerticalStrut(10));
        bottom.add(Theme.label("文字对话",9,Theme.MUTED,true));bottom.add(Theme.label("StarSea Community",11,Theme.MUTED,false));side.add(bottom,BorderLayout.SOUTH);
        JPanel rail=new JPanel(new BorderLayout(0,14));rail.setBackground(Theme.SIDE);rail.setBorder(BorderFactory.createEmptyBorder(24,9,20,9));
        JPanel railTop=new JPanel();railTop.setOpaque(false);railTop.setLayout(new BoxLayout(railTop,BoxLayout.Y_AXIS));
        Theme.Logo logo=new Theme.Logo(40);logo.setAlignmentX(CENTER_ALIGNMENT);railTop.add(logo);railTop.add(Box.createVerticalStrut(24));
        Theme.Button add=railButton("＋","new-chat-icon","新对话",this::newConversation);railTop.add(add);rail.add(railTop,BorderLayout.NORTH);
        railModels.setOpaque(false);railModels.setLayout(new BoxLayout(railModels,BoxLayout.Y_AXIS));
        JPanel railBody=new Theme.ScrollBody(new BorderLayout());railBody.add(railModels,BorderLayout.NORTH);
        JScrollPane icons=new JScrollPane(railBody);Theme.scroll(icons);icons.setName("model-icons");icons.getViewport().setBackground(Theme.SIDE);rail.add(icons);
        JPanel railBottom=new JPanel();railBottom.setOpaque(false);railBottom.setLayout(new BoxLayout(railBottom,BoxLayout.Y_AXIS));
        railBottom.add(railButton("◷","history-icon","历史对话",()->{sidebar.expand();historySection.setExpanded(true);}));
        railBottom.add(Box.createVerticalStrut(8));
        railBottom.add(railButton("⚙","settings-icon","设置",()->{sidebar.expand();if(!settings.isExpanded())findButton(settings,"settings-toggle").doClick(0);}));
        rail.add(railBottom,BorderLayout.SOUTH);
        return new ResizableSidebar(side,rail);
    }
    private Theme.Button railButton(String icon,String name,String tooltip,Runnable action){
        Theme.Button button=new Theme.Button(icon,false);button.setName(name);button.setFont(Theme.font(20,true));button.setBorder(BorderFactory.createEmptyBorder());button.setAlignmentX(CENTER_ALIGNMENT);
        button.setPreferredSize(new Dimension(48,44));button.setMaximumSize(new Dimension(48,44));I18n.tooltip(button,tooltip);button.getAccessibleContext().setAccessibleName(I18n.t(tooltip));button.addActionListener(e->action.run());return button;
    }
    private static AbstractButton findButton(Container root,String name){for(Component child:root.getComponents()){if(name.equals(child.getName()))return (AbstractButton)child;if(child instanceof Container c){AbstractButton found=findButton(c,name);if(found!=null)return found;}}return null;}
    private void buildRail(){
        railModels.removeAll();railCards.clear();
        for(String provider:List.of("DeepSeek","OpenAI","Gemini","Claude","Mixture")){
            List<ChatService.Model> options=catalog.stream().filter(m->m.provider().equals(provider)).toList();if(options.isEmpty())continue;
            ChatService.Model first=options.get(0);ChatService.Model icon=new ChatService.Model(first.id(),provider,first.subtitle(),provider,options.stream().anyMatch(ChatService.Model::configured));
            ModelCard card=new ModelCard(icon,()->{
                if(busy||!connected)return;
                if(options.size()==1){chooseModel(first.id());return;}
                JPopupMenu popup=new JPopupMenu();popup.setBackground(Theme.SURFACE);popup.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Theme.LINE),BorderFactory.createEmptyBorder(5,5,5,5)));
                for(ChatService.Model model:options){Theme.Button item=new Theme.Button(model.name()+(model.id().equals(selectedId)?"  ✓":""),false);item.setEnabled(model.configured());item.addActionListener(e->{popup.setVisible(false);chooseModel(model.id());});popup.add(item);}
                if(railModels.isShowing())popup.show(railModels,railModels.getWidth(),railCards.stream().filter(c->c.option.provider().equals(provider)).mapToInt(Component::getY).findFirst().orElse(0));
            });
            card.setName("model-icon-"+provider.toLowerCase(java.util.Locale.ROOT));card.iconOnly(true);card.setAlignmentX(CENTER_ALIGNMENT);railCards.add(card);railModels.add(card);railModels.add(Box.createVerticalStrut(8));
        }
        syncRail();railModels.revalidate();railModels.repaint();
    }
    private void syncRail(){for(ModelCard card:railCards){boolean active=catalog.stream().anyMatch(m->m.id().equals(selectedId)&&m.provider().equals(card.option.provider()));card.active(active);card.setEnabled(connected&&!busy&&card.option.configured());}}

    private JPanel header() {
        JPanel header = new JPanel(new BorderLayout(16, 0)); header.setOpaque(false);
        header.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 1, 0, Theme.LINE),
                BorderFactory.createEmptyBorder(24, 30, 22, 30)));
        JPanel heading = new JPanel(new GridLayout(2, 1, 0, 7)); heading.setOpaque(false);
        heading.add(title); heading.add(subtitle); header.add(heading);
        Theme.Surface pill = new Theme.Surface(Theme.color(0x172A28,0xE4F3ED), Theme.color(0x294A43,0xB3D9CC), 22);
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
        JLabel eyebrow = Theme.label("好奇心，让想法走得更远", 10, Theme.MINT, true);
        eyebrow.setAlignmentX(CENTER_ALIGNMENT); hero.add(eyebrow); hero.add(Box.createVerticalStrut(14));
        JLabel heading = Theme.label("把想法，聊清楚。", 34, Theme.TEXT, true); heading.setAlignmentX(CENTER_ALIGNMENT); hero.add(heading);
        hero.add(Box.createVerticalStrut(14));
        JLabel hint = Theme.label("从一个问题开始，让思路慢慢展开。", 14, Theme.MUTED, false); hint.setAlignmentX(CENTER_ALIGNMENT); hero.add(hint);
        hero.add(Box.createVerticalStrut(32));
        JPanel suggestions = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 0)); suggestions.setOpaque(false);
        String[][] starters = {{"读懂一段代码", "请帮我解释下面这段代码的逻辑：\n"}, {"梳理一个想法", "我想和你一起梳理一个想法：\n"}, {"制定学习计划", "请帮我制定一份学习计划：\n"}};
        for (String[] starter : starters) {
            Theme.Button chip = new Theme.Button(starter[0], false);
            chip.addActionListener(event -> { composer.setText(I18n.t(starter[1])); composer.requestFocusInWindow(); });
            suggestions.add(chip);
        }
        hero.add(suggestions); outer.add(hero); return outer;
    }

    private JPanel composer() {
        JPanel outer = new JPanel(new BorderLayout(0, 10)); outer.setOpaque(false);
        outer.setBorder(BorderFactory.createEmptyBorder(10, 28, 20, 28));
        Theme.Surface box = new Theme.FocusSurface(Theme.SURFACE, Theme.color(0x3B425B,0xC4CADD), 24,composer);
        box.setLayout(new BorderLayout(16, 10)); box.setBorder(BorderFactory.createEmptyBorder(16, 18, 14, 14));
        JPanel tools = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0)); tools.setOpaque(false);
        tools.add(searchBubble); box.add(tools, BorderLayout.NORTH);
        searchBubble.addActionListener(event -> changeWebSearch());
        composer.setRows(3); composer.setLineWrap(true); composer.setWrapStyleWord(true);
        composer.setName("message-input");
        composer.setFont(Theme.font(15, false)); composer.setForeground(Theme.TEXT); composer.setCaretColor(Theme.ACCENT);
        composer.setOpaque(false); composer.setBorder(BorderFactory.createEmptyBorder());
        composer.setSelectionColor(Theme.color(0x5B507F,0x7C68BB));
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
            private void changed(){refreshContextPreview();if(!restoring&&!closed)draftSave.restart();}
            @Override public void insertUpdate(DocumentEvent event) { changed(); }
            @Override public void removeUpdate(DocumentEvent event) { changed(); }
            @Override public void changedUpdate(DocumentEvent event) { changed(); }
        });
        outer.add(box);
        JPanel footer = new JPanel(new BorderLayout()); footer.setOpaque(false);
        footer.add(Theme.label("Enter 发送 · Shift + Enter 换行", 10, Theme.MUTED, false));
        JPanel activity = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0)); activity.setOpaque(false);
        progress.setPreferredSize(new Dimension(36, 4)); progress.setBorderPainted(false);
        progress.setForeground(Theme.ACCENT); progress.setBackground(Theme.SURFACE); progress.setVisible(false);
        latest.setName("scroll-to-latest");latest.setFont(Theme.font(11,false));latest.setBorder(BorderFactory.createEmptyBorder(3,8,3,8));latest.setVisible(false);latest.addActionListener(e->scrollToBottom());activity.add(latest);
        activity.add(progress); activity.add(status); footer.add(activity, BorderLayout.EAST);
        outer.add(footer, BorderLayout.SOUTH); return outer;
    }

    void connect() {
        backend.setProgressListener(event -> SwingUtilities.invokeLater(() -> {
            if(closed)return;
            boolean follow=SmoothScroll.install(history).canFollow();
            transcript.progress(event);if(follow)scrollToBottom();
        }));
        backend.setNoticeListener(message -> SwingUtilities.invokeLater(() -> {
            if (closed) return;
            recordMessage("Mixture 调度", message, false, false,"",false);
            showContent("history"); setStatus(message, false); followLatest();
        }));
        runAsync("正在准备模型…", () -> {
            List<ChatService.Model> models=backend.initialize();ChatService.Credentials keys=backend.credentials();ChatService.WebSearch search=backend.webSearch();
            try{return new Startup(models,keys,conversationStore.list(),"",search);}
            catch(java.io.IOException error){return new Startup(models,keys,List.of(),"读取历史对话失败",search);}
        }, startup -> {
            connected = true;
            updateSearch(startup.search());
            credentials = startup.credentials();
            catalog = startup.models();conversations.addAll(startup.history());refreshHistoryList();
            List<ChatService.Model> deepseekPresets = catalog.stream().filter(model -> model.id().startsWith("deepseek-")).toList();
            if (!deepseekPresets.isEmpty()) {
                deepseek = new ModelSelector("DeepSeek", "deepseek", deepseekPresets, this::chooseModel);
                modelList.add(deepseek); modelList.add(Box.createVerticalStrut(8));
            }
            for (ChatService.Model option : catalog) {
                if (option.id().startsWith("gemini-") || option.id().startsWith("deepseek-") || option.id().startsWith("claude-") || option.id().equals("mixture")) continue;
                ModelCard card = new ModelCard(option, () -> chooseModel(option.id()));
                models.add(card); modelList.add(card); modelList.add(Box.createVerticalStrut(8));
            }
            List<ChatService.Model> presets = catalog.stream().filter(model -> model.id().startsWith("gemini-")).toList();
            if (!presets.isEmpty()) { gemini = new GeminiSelector(presets, this::chooseModel); modelList.add(gemini); }
            List<ChatService.Model> claudePresets = catalog.stream().filter(model -> model.id().startsWith("claude-")).toList();
            if (!claudePresets.isEmpty()) {
                modelList.add(Box.createVerticalStrut(8));
                claude = new ModelSelector("Claude", "claude", claudePresets, this::chooseModel); modelList.add(claude);
            }
            catalog.stream().filter(model -> model.id().equals("mixture")).findFirst().ifPresent(option -> {
                modelList.add(Box.createVerticalStrut(12));
                ModelCard card = new ModelCard(option, () -> chooseModel(option.id()));
                models.add(card); modelList.add(card);
            });
            buildRail();modelList.revalidate(); modelList.repaint(); setBusy(false);
            if(!startup.historyError().isEmpty())setStatus(startup.historyError(),true);
            if (GraphicsEnvironment.isHeadless()) selectConfiguredModel();
            else showCredentials();
        });
    }

    private void selectConfiguredModel() {
        if (!connected || busy) return;
        String choice = credentials.deepseek() ? (deepseek == null ? "deepseek" : "deepseek-flash") : credentials.openai() ? "openai" : credentials.gemini() ? "gemini-flash" : credentials.claude() ? "claude-opus" : null;
        if (choice != null) chooseModel(choice);
        else { setStatus("请在 API 密钥与隐私中配置一个平台", false); setBusy(false); }
    }

    @Override public void refreshLanguage() {
        suggestionText.setText(nextSuggestion.isEmpty() ? "" : I18n.t("基于点赞的后续建议（可编辑）：") + nextSuggestion);
        composer.repaint();
    }

    private void changeLanguage(I18n.Language language) {
        try { I18n.change(language, this); }
        catch (java.io.IOException | SecurityException ex) { setStatus("语言偏好保存失败，本次切换仍有效", true); }
    }

    private void exitApplication() {
        Window window = SwingUtilities.getWindowAncestor(this);
        close();
        if (window != null) window.dispose();
    }

    private void openCredentials() {
        if (!connected || busy || credentialDialog != null) return;
        runAsync("正在读取密钥状态…", backend::credentials, state -> { credentials = state; showCredentials(); });
    }

    private void showCredentials() {
        if (GraphicsEnvironment.isHeadless()) return;
        credentialsChanged = false;
        credentialDialog = new JDialog(SwingUtilities.getWindowAncestor(this), I18n.t("API 密钥与隐私"), Dialog.ModalityType.APPLICATION_MODAL);
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
        persistCurrent();
        CredentialsPanel panel = credentialPanel;
        JDialog dialog = credentialDialog;
        panel.setWorking(true); setBusy(true);
        dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        new SwingWorker<ChatService.Credentials, Void>() {
            @Override protected ChatService.Credentials doInBackground() throws Exception { return task.call(); }
            @Override protected void done() {
                try {
                    credentials = get(); credentialsChanged = true; selectedId = null;
                    currentSelection = null; updateSearch(new ChatService.WebSearch(false, false, searchState.defaultEnabled()));
                    freshConversation();
                    models.forEach(card -> card.active(false)); title.setText("开始一段新对话"); subtitle.setText("密钥配置已更新，旧会话已清空");
                    if (gemini != null) gemini.sync(null);
                    if (deepseek != null) deepseek.sync(null);
                    if (claude != null) claude.sync(null);
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
        if ("mixture".equals(id) && !GraphicsEnvironment.isHeadless()) {
            MixturePanel.show(this, credentials, mixtureTeam, team -> {
                runAsync("正在组建协作团队…", () -> backend.configureMixture(team), selection -> {
                    mixtureTeam = team; applySelection(selection);
                });
            });
            return;
        }
        runAsync("正在切换模型…", () -> "mixture".equals(id)&&mixtureTeam!=null?backend.configureMixture(mixtureTeam):backend.select(id), this::applySelection);
    }

    private void applySelection(ChatService.Selection selection) {
            currentSelection = selection;
            selectedId = selection.modelId();savedModelId=selectedId;
            for (ModelCard card : models) card.active(card.option.id().equals(selectedId));
            String name = catalog.stream().filter(option -> option.id().equals(selectedId)).map(ChatService.Model::name).findFirst().orElse(selection.provider());
            title.setText(name);
            updateSearch(selection.searchState() == null ? new ChatService.WebSearch(selection.search(), true, searchState.defaultEnabled()) : selection.searchState());
            setStatus("就绪", false); setBusy(false);syncRail();persistCurrent(); composer.requestFocusInWindow();
    }

    private void updateSearch(ChatService.WebSearch state) {
        searchState = state;
        searchBubble.sync(state);
        if (currentSelection != null) {
            subtitle.setText(currentSelection.model() + (state.enabled() ? " · " + I18n.t("联网搜索") : "")
                    + ("mixture".equals(selectedId) ? " · 与单模型会话独立" : " · 切换时保留上下文"));
        }
    }

    private void changeWebSearch() {
        if (busy || !connected || !searchState.available()) return;
        ChatService.WebSearch previous = searchState;
        boolean desired = !previous.enabled();
        searchBubble.setSelected(desired);
        setBusy(true); setStatus("正在切换联网搜索…", false);
        new SwingWorker<ChatService.WebSearch, Void>() {
            @Override protected ChatService.WebSearch doInBackground() throws Exception { return backend.setWebSearch(desired); }
            @Override protected void done() {
                if (closed) return;
                try {
                    updateSearch(get());
                    setStatus(searchState.enabled() ? "联网搜索已开启" : "联网搜索已关闭", false);
                } catch (InterruptedException error) {
                    updateSearch(previous); setStatus("切换联网搜索失败，请重试", true);
                } catch (ExecutionException error) {
                    updateSearch(previous); setStatus("切换联网搜索失败，请重试", true);
                    I18n.tooltip(searchBubble, error.getCause().getMessage());
                } finally { setBusy(false); }
            }
        }.execute();
    }

    void submitText(String text) { composer.setText(text); submit(); }

    private void submit() {
        String message = composer.getText().strip();
        if (busy || selectedId == null || message.isEmpty()) return;
        CodeContext.Prepared prepared = includeContext.isSelected() ? transcript.prepare(message) : new CodeContext.Prepared(message, "");
        int userIndex=entries.size();
        recordMessage(prepared.description().isEmpty() ? "你" : "你 · 附带代码批注", message, true, false,prepared.message(),false); showContent("history");
        composer.setText(""); offerSuggestion(""); scrollToBottom();
        transcript.beginProgress();
        runAsync("正在思考…", () -> backend.chat(prepared.message()), reply -> {
            transcript.finishProgress(reply.complete());
            if(reply.complete()){
                ConversationStore.Entry entry=entries.get(userIndex);
                entries.set(userIndex,new ConversationStore.Entry(entry.role(),entry.text(),true,false,entry.modelId(),entry.provider(),entry.prompt(),true,entry.time()));
            }
            recordMessage(reply.provider() + "  /  " + reply.model(), reply.text(), false, false,"",reply.complete());
            setStatus("就绪 · " + reply.model(), false); followLatest(); composer.requestFocusInWindow();
        });
    }

    void newConversation() {
        if (busy || !connected) return;
        persistCurrent();
        runAsync("正在新建对话…", () -> { backend.reset(); return true; }, ignored -> {
            freshConversation();
            includeContext.setSelected(true);
            setStatus("新对话已就绪", false); composer.requestFocusInWindow();
        });
    }

    private void recordMessage(String role,String text,boolean user,boolean error,String prompt,boolean committed){
        String time=java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm"));
        transcript.addMessage(role,text,user,error,time);
        String provider=catalog.stream().filter(m->m.id().equals(selectedId)).map(ChatService.Model::provider).findFirst().orElse("");
        entries.add(new ConversationStore.Entry(role,text,user,error,selectedId==null?"":selectedId,provider,prompt,committed,time));persistCurrent();
    }
    private ConversationStore.Session snapshot(){return new ConversationStore.Session(conversationId,System.currentTimeMillis(),selectedId==null?savedModelId:selectedId,mixtureTeam,List.copyOf(entries),transcript.codeBlocks().stream().filter(CodeContext.Block::hasContext).map(b->java.util.Map.of("id",b.id,"code",b.code,"note",b.note)).toList(),composer.getText());}
    private void persistCurrent(){
        if(closed||restoring||historyIO.isShutdown()||entries.stream().noneMatch(ConversationStore.Entry::user))return;
        ConversationStore.Session session=snapshot();
        if(lastSnapshot!=null&&lastSnapshot.id().equals(session.id())&&lastSnapshot.modelId().equals(session.modelId())&&java.util.Objects.equals(lastSnapshot.team(),session.team())&&lastSnapshot.entries().equals(session.entries())&&lastSnapshot.edits().equals(session.edits())&&lastSnapshot.draft().equals(session.draft()))return;
        lastSnapshot=session;conversations.removeIf(s->s.id().equals(session.id()));conversations.add(0,session);refreshHistoryList();
        pendingSaves.put(session.id(),session);if(saving.compareAndSet(false,true))historyIO.execute(this::writeHistory);
    }
    private void writeHistory(){
        while(true){
            for(ConversationStore.Session session:List.copyOf(pendingSaves.values()))if(pendingSaves.remove(session.id(),session))try{conversationStore.save(session);}catch(java.io.IOException | SecurityException error){SwingUtilities.invokeLater(()->{if(!closed){if(lastSnapshot==session)lastSnapshot=null;setStatus("聊天记录保存失败",true);}});}
            saving.set(false);if(pendingSaves.isEmpty()||!saving.compareAndSet(false,true))return;
        }
    }
    private void refreshHistoryList(){conversationList.update(conversations,conversationId);conversationList.available(connected&&!busy);}
    private void freshConversation(){
        restoring=true;draftSave.stop();entries.clear();savedModelId=selectedId==null?"":selectedId;conversationId=java.util.UUID.randomUUID().toString();transcript.clear();composer.setText("");restoring=false;showContent("welcome");refreshHistoryList();
    }
    void openConversation(ConversationStore.Session session){
        if(busy||!connected||session.id().equals(conversationId))return;
        persistCurrent();
        runAsync("正在恢复对话…",()->{
            try{return new Restored(backend.restoreConversation(session.modelId().isEmpty()?null:session.modelId(),session.context(false),session.context(true),session.team()),"");}
            catch(java.io.IOException unavailable){return new Restored(backend.restoreConversation(null,session.context(false),session.context(true),session.team()),"历史已打开，请选择可用模型继续");}
        },restored->{
            restoring=true;draftSave.stop();entries.clear();conversationId=session.id();transcript.clear();entries.addAll(session.entries());
            for(ConversationStore.Entry entry:entries)transcript.addMessage(entry.role(),entry.text(),entry.user(),entry.error(),entry.time());
            for(java.util.Map<String,String> edit:session.edits())for(CodeContext.Block block:transcript.codeBlocks())if(block.id.equals(edit.get("id"))){block.code=edit.get("code");block.note=edit.get("note");}
            refreshMarkdown(transcript);composer.setText(session.draft());mixtureTeam=session.team();savedModelId=session.modelId();selectedId=null;
            if(restored.selection()!=null)applySelection(restored.selection());
            else {currentSelection=null;updateSearch(new ChatService.WebSearch(false,false,searchState.defaultEnabled()));models.forEach(card->card.active(false));title.setText(session.title());subtitle.setText("历史已打开，请选择可用模型继续");}
            restoring=false;setBusy(false);showContent("history");refreshContextPreview();refreshHistoryList();syncRail();setStatus(restored.warning().isEmpty()?"历史对话已恢复":restored.warning(),!restored.warning().isEmpty());scrollToBottom();
        });
    }
    private static void refreshMarkdown(Container root){for(Component child:root.getComponents()){if(child instanceof MarkdownPane markdown)markdown.refresh();else if(child instanceof Container c)refreshMarkdown(c);}}
    private void deleteConversation(ConversationStore.Session session){
        if(busy||!connected)return;draftSave.stop();boolean active=session.id().equals(conversationId);
        runAsync("正在删除对话…",()->{if(active)backend.reset();try{historyIO.submit(()->{conversationStore.delete(session.id());return true;}).get();}catch(java.util.concurrent.ExecutionException e){throw new java.io.IOException("删除历史对话失败",e.getCause());}return true;},ignored->{
            if(active)freshConversation();conversations.removeIf(s->s.id().equals(session.id()));refreshHistoryList();setStatus("对话已删除",false);
        });
    }

    private <T> void runAsync(String activity, Callable<T> task, Consumer<T> success) {
        setBusy(true); setStatus(activity, false);
        new SwingWorker<T, Void>() {
            @Override protected T doInBackground() throws Exception { return task.call(); }
            @Override protected void done() {
                if (closed) return;
                setBusy(false);
                try { success.accept(get()); }
                catch (InterruptedException ex) { showError("操作被中断，请重试"); }
                catch (ExecutionException ex) {
                    Throwable cause = ex.getCause(); showError(cause.getMessage() == null ? "操作失败，请重试" : cause.getMessage());
                }
                finally { if (gemini != null) gemini.sync(selectedId); if (deepseek != null) deepseek.sync(selectedId); if (claude != null) claude.sync(selectedId); }
            }
        }.execute();
    }

    private void showError(String error) {
        transcript.finishProgress(false);
        recordMessage("请求未完成", error, false, true,"",false); showContent("history");
        setStatus("请求未完成 · 可以继续重试或切换模型", true); followLatest(); composer.requestFocusInWindow();
    }
    private void setStatus(String text, boolean error) { status.setText(text); status.setForeground(error ? Theme.ERROR : Theme.MUTED); }
    private void setBusy(boolean value) {
        searchBubble.setEnabled(connected && selectedId != null && !value && searchState.available());
        busy = value; progress.setVisible(value); progress.setIndeterminate(value);
        send.setEnabled(connected && selectedId != null && !value); newChat.setEnabled(connected && !value);
        settings.credentialsEnabled(connected && !value);conversationList.available(connected&&!value);syncRail();
        AbstractButton railNew=findButton(sidebar,"new-chat-icon");if(railNew!=null)railNew.setEnabled(connected&&!value);
        for (ModelCard card : models) card.setEnabled(connected && !value);
        if (gemini != null) gemini.working(!connected || value);
        if (deepseek != null) deepseek.working(!connected || value);
        if (claude != null) claude.working(!connected || value);
    }
    private void offerSuggestion(String text) {
        nextSuggestion = text;
        suggestionText.setText(text.isEmpty() ? "" : I18n.t("基于点赞的后续建议（可编辑）：") + text);
        suggestions.setVisible(!text.isEmpty()); revalidate(); repaint();
    }
    private void refreshContextPreview() {
        if(!restoring&&!closed&&!entries.isEmpty())draftSave.restart();
        if(!closed)contextUpdate.restart();
    }
    private void updateContextPreview(){
        String description=transcript.describe(composer.getText().strip());
        String text=includeContext.isSelected()?description:"本次不附带代码批注";
        boolean visible=!description.isEmpty(),changed=!I18n.t(text).equals(contextLabel.getText())||contextPreview.isVisible()!=visible;
        contextLabel.setText(text);I18n.tooltip(contextLabel,description);contextPreview.setVisible(visible);
        if(changed){revalidate();repaint();}
    }
    private void showContent(String key){
        if(visibleContent.equals(key))return;visibleContent=key;contentLayout.show(content,key);content.reveal();
        if(key.equals("welcome"))SmoothScroll.install(history).cancel();updateLatestButton();
    }
    private void queueLatestUpdate(){if(latestQueued||closed)return;latestQueued=true;SwingUtilities.invokeLater(()->{latestQueued=false;if(!closed)updateLatestButton();});}
    private void updateLatestButton(){
        if(latest==null)return;SmoothScroll motion=SmoothScroll.install(history);
        boolean show=visibleContent.equals("history")&&!motion.atEnd()&&!motion.canFollow();
        if(latest.isVisible()!=show){latest.setVisible(show);latest.getParent().revalidate();}
    }
    private void followLatest(){SmoothScroll motion=SmoothScroll.install(history);if(motion.canFollow())scrollToBottom();}
    private void scrollToBottom() {
        if(closed)return;scrollVersion=SmoothScroll.install(history).interactionVersion();
        if(scrollQueued)return;scrollQueued=true;transcript.revalidate();
        SwingUtilities.invokeLater(()->{scrollQueued=false;if(!closed){SmoothScroll motion=SmoothScroll.install(history);if(motion.interactionVersion()==scrollVersion)motion.toEnd();updateLatestButton();}});
    }
    boolean isBusy() { return busy; }
    int messageCount() { return transcript.messageCount(); }
    String selectedModelId() { return selectedId; }
    void close() { if (closed) return;draftSave.stop();contextUpdate.stop();SmoothScroll.install(history).cancel();persistCurrent();historyIO.shutdown(); closed = true; connected = false; transcript.finishProgress(false); if (credentialDialog != null) credentialDialog.dispose(); backend.close(); }

    private static final class PromptArea extends JTextArea {
        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics);
            if (getText().isEmpty()) {
                Graphics2D g = (Graphics2D) graphics.create(); Theme.antialias(g);
                g.setFont(getFont()); g.setColor(Theme.color(0x7F8CA4,0x6B7690));
                g.drawString(I18n.t("向 Nautilus 发送消息…"), 0, getFontMetrics(getFont()).getAscent() + 1); g.dispose();
            }
        }
    }
}
