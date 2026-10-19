package com.starsea.nautilus;

import java.awt.*;
import java.awt.datatransfer.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.swing.*;

import com.starsea.nautilus.ChatService.MixtureStage;

/** Two-column role board. Assignments change only on a completed drop or explicit keyboard action. */
@SuppressWarnings("serial")
final class MixturePanel extends JPanel {
    private static final DataFlavor MODEL_FLAVOR = new DataFlavor(
        DataFlavor.javaJVMLocalObjectMimeType + ";class=java.lang.Object", "Nautilus model card");
    private record DraggedModel(MixturePanel owner, String modelId) {}
    private final Map<String, ChatService.Model> catalog = new LinkedHashMap<>();
    private final EnumMap<MixtureStage, List<String>> assignments = new EnumMap<>(MixtureStage.class);
    private final EnumMap<MixtureStage, RoleLane> lanes = new EnumMap<>(MixtureStage.class);
    private final Map<String, Card> paletteCards = new LinkedHashMap<>();
    private final List<Card> cards = new ArrayList<>();
    private final Theme.ScrollBody palette = new Theme.ScrollBody(null);
    private final JLabel summary = Theme.label("", 12, Theme.MINT, true);
    private final JLabel feedback = Theme.label("", 11, Theme.MUTED, false);
    private final JCheckBox allowFallback = new I18n.CheckBox("允许所选成员跨职责接替失败任务", false);
    private final Theme.Button confirm = new Theme.Button("开始协作", true);
    private final Consumer<ChatService.MixtureTeam> accept;
    private final Runnable cancel;
    private final Timer animation = new Timer(Motion.FRAME_MS, event -> tick());
    private final Theme.Button recommend = new Theme.Button("推荐", false);
    private final RecommendationTray recommendations;
    private final Motion.Tween reveal = new Motion.Tween(1);
    private String selectedModel;
    private boolean closing, nativeDragging, bankHover, motionEnabled = true;
    private double visibility = 1;
    private long lastTick;
    private Runnable afterClose;

    MixturePanel(ChatService.Credentials credentials, List<String> selected,
                 Consumer<List<String>> accept, Runnable cancel) {
        this(credentials, ChatService.MixtureTeam.suggested(selected.stream()
            .filter(id -> credentials != null && credentials.has(providerName(id))).toList()),
            team -> accept.accept(team.members().stream().map(ChatService.MixtureMember::modelId).toList()), cancel);
    }

    MixturePanel(ChatService.Credentials credentials, ChatService.MixtureTeam selected,
                 Consumer<ChatService.MixtureTeam> accept, Runnable cancel) {
        this.accept = accept; this.cancel = cancel;
        setName("mixture-team"); setBackground(Theme.BG); setLayout(new BorderLayout(0, 18));
        setBorder(BorderFactory.createEmptyBorder(24, 26, 22, 26));
        for (MixtureStage stage : MixtureStage.values()) assignments.put(stage, new ArrayList<>());
        models(credentials).forEach(model -> catalog.put(model.id(), model));
        if (selected != null) {
            allowFallback.setSelected(selected.allowRoleFallback());
            for (ChatService.MixtureMember member : selected.members()) {
                String id = normalize(member.modelId());
                if (canUse(id)) place(id, member.stage(), assignments.get(member.stage()).size());
            }
        }

        JPanel heading = column();
        heading.add(Theme.label("MIXTURE  /  实验性协作", 11, Theme.ACCENT, true));
        heading.add(Box.createVerticalStrut(8));
        heading.add(Theme.label("拖动模型，组建你的协作团队", 23, Theme.TEXT, true));
        heading.add(Box.createVerticalStrut(8));
        heading.add(Theme.label("拖到角色区分配职责，拖回模型列移除。", 12, Theme.MUTED, false));
        JPanel titleRow = new JPanel(new BorderLayout(12, 0)); titleRow.setOpaque(false); titleRow.add(heading);
        recommend.setName("mixture-recommend");
        JPanel entry = new JPanel(new FlowLayout(FlowLayout.RIGHT, 0, 0)); entry.setOpaque(false); entry.add(recommend);
        titleRow.add(entry, BorderLayout.EAST);
        recommendations = new RecommendationTray();
        recommend.addActionListener(event -> { if (!closing) recommendations.toggle(); });
        JPanel top = new JPanel(new BorderLayout()); top.setOpaque(false); top.add(titleRow, BorderLayout.NORTH);
        top.add(recommendations, BorderLayout.CENTER); add(top, BorderLayout.NORTH);

        JPanel board = new JPanel(new GridLayout(1, 2, 18, 0)); board.setOpaque(false); board.setName("mixture-board");
        palette.setLayout(new BoxLayout(palette, BoxLayout.Y_AXIS)); palette.setName("mixture-model-bank");
        palette.setTransferHandler(new BoardTransfer(null, null));
        String previous = "";
        for (ChatService.Model option : catalog.values()) {
            if (!previous.equals(option.provider())) {
                if (!previous.isEmpty()) palette.add(Box.createVerticalStrut(10));
                JLabel provider = Theme.label(option.provider(), 11, Theme.MUTED, true);
                provider.setBorder(BorderFactory.createEmptyBorder(2, 2, 7, 0)); provider.setAlignmentX(LEFT_ALIGNMENT);
                palette.add(provider); previous = option.provider();
            }
            Card card = new Card(option, null); paletteCards.put(option.id(), card); cards.add(card);
            palette.add(card); palette.add(Box.createVerticalStrut(6));
        }
        board.add(boardColumn("模型", "直接拖动任意子模型", palette, "mixture-models-scroll"));
        Theme.ScrollBody roleList = new Theme.ScrollBody(null); roleList.setLayout(new BoxLayout(roleList, BoxLayout.Y_AXIS));
        for (MixtureStage stage : MixtureStage.values()) {
            RoleLane lane = new RoleLane(stage); lanes.put(stage, lane); roleList.add(lane);
            if (stage != MixtureStage.SUMMARIZER) roleList.add(Box.createVerticalStrut(10));
        }
        board.add(boardColumn("角色", "规划 · 执行 · 复核 · 汇总", roleList, "mixture-roles-scroll"));
        add(board, BorderLayout.CENTER);

        JPanel footer = new JPanel(new BorderLayout(0, 12)); footer.setOpaque(false);
        JPanel notices = column(); summary.setName("mixture-validation"); notices.add(summary);
        notices.add(Box.createVerticalStrut(6)); feedback.setName("mixture-feedback"); notices.add(feedback);
        notices.add(Box.createVerticalStrut(8));
        allowFallback.setName("mixture-allow-fallback"); allowFallback.setOpaque(false); allowFallback.setForeground(Theme.TEXT);
        allowFallback.setFont(Theme.font(11, false)); allowFallback.setAlignmentX(LEFT_ALIGNMENT); notices.add(allowFallback);
        notices.add(Box.createVerticalStrut(6));
        JTextArea memoryNotice = new JTextArea(I18n.t("Mixture模式下，突然更换到独立模型，上下文记忆暂时无法生效。"));
        memoryNotice.setName("mixture-memory-notice"); memoryNotice.setEditable(false); memoryNotice.setFocusable(false);
        memoryNotice.setLineWrap(true); memoryNotice.setWrapStyleWord(true); memoryNotice.setRows(2);
        memoryNotice.setOpaque(false); memoryNotice.setForeground(Theme.ACCENT); memoryNotice.setFont(Theme.font(11, false));
        memoryNotice.setAlignmentX(LEFT_ALIGNMENT); notices.add(memoryNotice); footer.add(notices, BorderLayout.NORTH);
        JPanel buttons = new JPanel(new BorderLayout()); buttons.setOpaque(false);
        Theme.Button clear = new Theme.Button("清空分配", false); clear.setName("mixture-clear");
        clear.addActionListener(event -> { if (closing) return; assignments.values().forEach(List::clear); selectedModel = null; refresh(); });
        buttons.add(clear, BorderLayout.WEST);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0)); right.setOpaque(false);
        Theme.Button back = new Theme.Button("取消", false); back.setName("mixture-cancel"); back.addActionListener(event -> dismiss(cancel));
        confirm.setName("mixture-confirm"); confirm.addActionListener(event -> {
            if (closing || !validation().isEmpty()) return;
            ChatService.MixtureTeam team = configuration(); dismiss(() -> accept.accept(team));
        });
        right.add(back); right.add(confirm); buttons.add(right, BorderLayout.EAST); footer.add(buttons, BorderLayout.SOUTH);
        add(footer, BorderLayout.SOUTH);
        bind(this, WHEN_ANCESTOR_OF_FOCUSED_COMPONENT, "ESCAPE", "dismiss", () -> dismiss(cancel));
        for (MixtureStage stage : MixtureStage.values()) {
            bind(this, WHEN_ANCESTOR_OF_FOCUSED_COMPONENT, Integer.toString(stage.ordinal() + 1), "assign-" + stage.id,
                () -> { if (selectedModel != null) assignModel(selectedModel, stage, assignments.get(stage).size()); });
        }
        refresh();
    }

    private static List<ChatService.Model> models(ChatService.Credentials keys) {
        boolean deepseek = keys != null && keys.deepseek(), openai = keys != null && keys.openai();
        boolean gemini = keys != null && keys.gemini(), claude = keys != null && keys.claude();
        List<ChatService.Model> result = new ArrayList<>(List.of(
            new ChatService.Model("deepseek-flash", "DeepSeek V4.1-Flash", "Flash", "DeepSeek", deepseek),
            new ChatService.Model("deepseek-pro", "DeepSeek V4-Pro", "Pro", "DeepSeek", deepseek),
            new ChatService.Model("openai", "OpenAI", "OpenAI", "OpenAI", openai),
            new ChatService.Model("gemini-flash", "Gemini 3.8 Flash", "Flash", "Gemini", gemini),
            new ChatService.Model("gemini-argon", "Gemini 4 Argon", "Argon", "Gemini", gemini),
            new ChatService.Model("gemini-auto", "Gemini Auto", "Auto", "Gemini", gemini)));
        result.addAll(ClaudeModels.options(claude)); return result;
    }
    private static String normalize(String id) { return switch (id) { case "deepseek" -> "deepseek-flash"; case "gemini" -> "gemini-argon"; default -> id; }; }
    private static String providerName(String id) {
        return id.startsWith("deepseek") ? "DeepSeek" : id.startsWith("gemini") ? "Gemini" : id.startsWith("claude") ? "Claude" : "OpenAI";
    }
    private boolean canUse(String id) { return catalog.containsKey(id) && catalog.get(id).configured(); }
    MixtureStage assignedStage(String id) {
        for (MixtureStage stage : MixtureStage.values()) if (assignments.get(stage).contains(id)) return stage;
        return null;
    }
    private void place(String id, MixtureStage stage, int index) {
        List<String> destination = assignments.get(stage);
        int previous = destination.indexOf(id);
        if (previous >= 0 && previous < index) index--;
        for (List<String> list : assignments.values()) list.removeIf(other -> providerName(other).equals(providerName(id)));
        if (stage == MixtureStage.PLANNER || stage == MixtureStage.SUMMARIZER) destination.clear();
        destination.add(Math.max(0, Math.min(index, destination.size())), id);
    }
    boolean assignModel(String id, MixtureStage stage, int index) {
        if (closing || stage == null || !canUse(id)) return false;
        place(id, stage, index); selectedModel = id; clearDropTargets(); refresh();
        RoleLane lane = lanes.get(stage); lane.acceptedAt = System.nanoTime(); startAnimation();
        feedback.setText(catalog.get(id).name() + " → " + stage); return true;
    }
    boolean removeModel(String id) {
        if (closing || assignedStage(id) == null) return false;
        assignments.values().forEach(list -> list.remove(id)); clearDropTargets(); refresh();
        feedback.setText(catalog.get(id).name() + " · " + I18n.t("已移回模型列")); return true;
    }
    List<String> selectedIds() { return configuration().members().stream().map(ChatService.MixtureMember::modelId).toList(); }
    boolean applyPreset(MixturePresets.Kind kind) {
        if (closing || nativeDragging) return false;
        ChatService.MixtureTeam team = kind.team(catalog, allowFallback.isSelected());
        if (team.members().isEmpty()) return false;
        assignments.values().forEach(List::clear);
        for (ChatService.MixtureMember member : team.members()) place(member.modelId(), member.stage(), 0);
        selectedModel = null; clearDropTargets(); refresh();
        long now = System.nanoTime();
        for (RoleLane lane : lanes.values()) if (!assignments.get(lane.stage).isEmpty()) lane.acceptedAt = now;
        feedback.setText(I18n.t(kind.title) + " · " + I18n.t("已填入草稿，可继续拖拽调整"));
        return true;
    }
    ChatService.MixtureTeam configuration() {
        List<ChatService.MixtureMember> members = new ArrayList<>();
        for (MixtureStage stage : MixtureStage.values()) for (String id : assignments.get(stage)) {
            ChatService.MixtureMember preset = ChatService.MixtureTeam.suggestedMember(id, stage);
            members.add(new ChatService.MixtureMember(id, stage, I18n.t(stage.label), preset.goal()));
        }
        return new ChatService.MixtureTeam(members, allowFallback.isSelected());
    }
    private String validation() {
        if (assignments.values().stream().allMatch(List::isEmpty)) return "将至少一个模型拖到汇总区开始组队";
        if (assignments.get(MixtureStage.SUMMARIZER).isEmpty()) return "请将一个模型拖到汇总区";
        return "";
    }
    private void refresh() {
        for (RoleLane lane : lanes.values()) lane.rebuild();
        String error = validation(); confirm.setEnabled(!closing && error.isEmpty());
        summary.setForeground(error.isEmpty() ? Theme.MINT : Theme.MUTED);
        summary.setText(error.isEmpty() ? "已选择 " + selectedIds().size() + " 个模型 · 拖拽分工" : error);
        feedback.setText(I18n.t("同一平台一个子模型；规划最多一个，汇总需一个。"));
        paletteCards.values().forEach(Card::refreshAccessibility); revalidate(); repaint(); startAnimation();
    }
    private JPanel boardColumn(String title, String hint, JComponent body, String name) {
        Theme.Surface surface = new Theme.Surface(Theme.SIDE, Theme.LINE, 16); surface.setLayout(new BorderLayout(0, 10));
        surface.setBorder(BorderFactory.createEmptyBorder(14, 14, 10, 14));
        JPanel heading = new JPanel(new BorderLayout(8, 0)); heading.setOpaque(false);
        heading.add(Theme.label(title, 16, Theme.TEXT, true), BorderLayout.WEST);
        heading.add(Theme.label(hint, 10, Theme.MUTED, false), BorderLayout.EAST); surface.add(heading, BorderLayout.NORTH);
        JScrollPane scroll = new JScrollPane(body); Theme.scroll(scroll); scroll.setName(name);
        scroll.setOpaque(false); scroll.getViewport().setOpaque(false); surface.add(scroll); return surface;
    }
    private static JPanel column() {
        JPanel panel = new JPanel(); panel.setOpaque(false); panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS)); panel.setAlignmentX(LEFT_ALIGNMENT); return panel;
    }
    private void selectModel(String id) {
        if (closing || !canUse(id)) return;
        selectedModel = id; cards.forEach(Card::refreshAccessibility); startAnimation(); repaint();
    }
    private void clearDropTargets() {
        bankHover = false; for (RoleLane lane : lanes.values()) { lane.dropIndex = -1; lane.dropHover = false; } repaint();
    }
    private void markDrop(MixtureStage stage, int index) {
        bankHover = stage == null;
        for (RoleLane lane : lanes.values()) { lane.dropHover = lane.stage == stage; lane.dropIndex = lane.stage == stage ? index : -1; }
        startAnimation(); repaint();
    }
    private void startAnimation() { if (motionEnabled && !animation.isRunning()) { lastTick = System.nanoTime(); animation.start(); } }
    private void tick() {
        long now = System.nanoTime(); double dt = Motion.seconds(now, lastTick); lastTick = now;
        visibility = reveal.value(now); boolean running = reveal.moving(now);
        running |= recommendations.tick(now);
        for (Card card : cards) {
            double aim = card.isEnabled() && (card.getModel().isRollover() || card.hasFocus() || card.option.id().equals(selectedModel)) ? 1 : 0;
            card.hover = Motion.follow(card.hover, aim, dt);
            running |= Math.abs(aim - card.hover) > .001;
        }
        for (RoleLane lane : lanes.values()) running |= lane.acceptedAt != 0 && now - lane.acceptedAt < Motion.FEEDBACK_DURATION;
        if (closing && !reveal.moving(now)) running = false;
        repaint();
        if (!running) { animation.stop(); if (afterClose != null) { Runnable action = afterClose; afterClose = null; action.run(); } }
    }
    boolean isAnimating() { return animation.isRunning(); }
    void dismiss(Runnable action) {
        if (closing) return; closing = true; confirm.setEnabled(false); allowFallback.setEnabled(false);
        cards.forEach(card -> card.setEnabled(false)); recommend.setEnabled(false); recommendations.freeze();
        clearDropTargets(); afterClose = action;
        reveal.to(0, System.nanoTime()); startAnimation();
    }
    @Override public void addNotify() { motionEnabled = true; super.addNotify(); visibility = 0; reveal.reset(0); reveal.to(1, System.nanoTime()); startAnimation(); }
    @Override public void removeNotify() {
        motionEnabled = false; afterClose = null; nativeDragging = false; clearDropTargets(); recommendations.reset();
        for (Card card : cards) { Motion.reset(card); card.hover = 0; card.pressed = null; }
        lanes.values().forEach(lane -> lane.acceptedAt = 0); super.removeNotify(); animation.stop();
    }
    @Override protected void paintChildren(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create(); g.setComposite(AlphaComposite.SrcOver.derive((float) Math.max(0, Math.min(1, visibility))));
        g.translate(0, (1 - visibility) * 10); super.paintChildren(g);
        if (bankHover) {
            JViewport viewport = (JViewport) palette.getParent();
            Rectangle box = SwingUtilities.convertRectangle(viewport, new Rectangle(0, 0, viewport.getWidth(), viewport.getHeight()), this);
            g.setColor(Theme.MINT); g.setStroke(new BasicStroke(2)); g.drawRoundRect(box.x, box.y, box.width - 1, box.height - 1, 12, 12);
        }
        g.dispose();
    }
    static void show(Component owner, ChatService.Credentials credentials, ChatService.MixtureTeam selected, Consumer<ChatService.MixtureTeam> accepted) {
        JDialog dialog = new JDialog(SwingUtilities.getWindowAncestor(owner), I18n.t("Mixture · 协作团队"), Dialog.ModalityType.APPLICATION_MODAL);
        MixturePanel panel = new MixturePanel(credentials, selected, team -> { dialog.dispose(); accepted.accept(team); }, dialog::dispose);
        dialog.setContentPane(panel); dialog.setSize(1080, 860); dialog.setMinimumSize(new Dimension(940, 740));
        dialog.setLocationRelativeTo(owner); dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        dialog.addWindowListener(new WindowAdapter() { @Override public void windowClosing(WindowEvent event) { panel.dismiss(dialog::dispose); } });
        dialog.getRootPane().setDefaultButton(panel.confirm); dialog.setVisible(true);
    }

    /** Inline choices preserve the two-column board and never open a dropdown. */
    private final class RecommendationTray extends JPanel {
        final JPanel content = new JPanel(new GridLayout(1, 2, 12, 0));
        final List<Theme.Button> actions = new ArrayList<>();
        final Motion.Tween motion = new Motion.Tween(0);
        boolean open;
        double amount;
        RecommendationTray() {
            setName("mixture-recommendations"); setOpaque(false); setLayout(null);
            content.setOpaque(false); content.setBorder(BorderFactory.createEmptyBorder(14, 0, 0, 0)); add(content);
            for (MixturePresets.Kind kind : MixturePresets.Kind.values()) {
                Theme.Surface card = new Theme.Surface(Theme.SURFACE, Theme.LINE, 14);
                card.setLayout(new BorderLayout(0, 6)); card.setBorder(BorderFactory.createEmptyBorder(10, 12, 10, 12));
                Theme.Button apply = new Theme.Button(kind.title, false);
                apply.setName("mixture-preset-" + kind.name().toLowerCase(java.util.Locale.ROOT));
                apply.setFocusable(false); apply.setEnabled(!kind.available(catalog).isEmpty());
                apply.addActionListener(event -> applyPreset(kind)); actions.add(apply);
                JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0)); row.setOpaque(false); row.add(apply);
                card.add(row, BorderLayout.NORTH);
                JPanel variants = new JPanel(new GridLayout(2, 2, 8, 4)); variants.setOpaque(false);
                for (String id : kind.models) {
                    ChatService.Model model = catalog.get(id);
                    JLabel label = Theme.label(model.name(), 10, model.configured() ? Theme.TEXT : Theme.MUTED, false);
                    label.setToolTipText(model.name() + (model.configured() ? "" : " · " + I18n.t("未配置密钥")));
                    variants.add(label);
                }
                JPanel detail = new JPanel(new BorderLayout(0, 5)); detail.setOpaque(false);
                detail.add(Theme.label(kind.description, 10, Theme.MUTED, false), BorderLayout.NORTH);
                detail.add(variants); card.add(detail); content.add(card);
            }
            I18n.tooltip(recommend, "仅使用已配置密钥的平台；应用后仍可拖拽调整");
            recommend.getAccessibleContext().setAccessibleDescription(I18n.t("展开团队推荐"));
        }
        void toggle() {
            open = !open; motion.to(open ? 1 : 0, System.nanoTime());
            actions.forEach(button -> button.setFocusable(open));
            recommend.getAccessibleContext().setAccessibleDescription(I18n.t(open ? "收起团队推荐" : "展开团队推荐"));
            startAnimation();
        }
        boolean tick(long now) {
            double next = motion.value(now);
            if (amount != next) { amount = next; revalidate(); MixturePanel.this.revalidate(); }
            return motion.moving(now);
        }
        void freeze() { actions.forEach(button -> button.setEnabled(false)); }
        void reset() { open = false; amount = 0; motion.reset(0); actions.forEach(button -> button.setFocusable(false)); }
        @Override public Dimension getPreferredSize() { return new Dimension(600, (int) Math.round(amount * 140)); }
        @Override public void doLayout() { content.setBounds(0, 0, getWidth(), 140); }
        @Override protected void paintChildren(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            float inherited = g.getComposite() instanceof AlphaComposite alpha ? alpha.getAlpha() : 1;
            g.setComposite(AlphaComposite.SrcOver.derive(inherited * (float) amount)); super.paintChildren(g); g.dispose();
        }
    }

    private final class RoleLane extends Theme.Surface {
        final MixtureStage stage;
        final JPanel rows = column();
        final JLabel count = Theme.label("", 11, Theme.MUTED, true);
        final JButton header = new JButton();
        boolean dropHover;
        int dropIndex = -1;
        long acceptedAt;
        RoleLane(MixtureStage stage) {
            super(Theme.SURFACE, Theme.LINE, 12); this.stage = stage; setName("mixture-role-" + stage.id);
            setLayout(new BorderLayout(0, 4)); setAlignmentX(LEFT_ALIGNMENT); setBorder(BorderFactory.createEmptyBorder(7, 10, 7, 10));
            header.setName("mixture-role-" + stage.id + "-assign"); header.setText(I18n.t(stage.label));
            header.setHorizontalAlignment(SwingConstants.LEFT); header.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
            header.setOpaque(false); header.setContentAreaFilled(false); header.setFocusPainted(false); header.setForeground(Theme.TEXT); header.setFont(Theme.font(13, true));
            I18n.tooltip(header, "选中模型后点击分配；也可按数字键 1 至 4"); header.addActionListener(event -> {
                if (selectedModel != null) assignModel(selectedModel, stage, assignments.get(stage).size());
            });
            JPanel line = new JPanel(new BorderLayout()); line.setOpaque(false); line.setPreferredSize(new Dimension(100, 20));
            line.add(header); line.add(count, BorderLayout.EAST); add(line, BorderLayout.NORTH); add(rows);
            BoardTransfer transfer = new BoardTransfer(null, stage); setTransferHandler(transfer); rows.setTransferHandler(transfer); line.setTransferHandler(transfer); header.setTransferHandler(transfer);
            addMouseListener(new MouseAdapter() { @Override public void mouseClicked(MouseEvent e) { if (selectedModel != null) assignModel(selectedModel, stage, assignments.get(stage).size()); } });
        }
        void rebuild() {
            for (Component component : rows.getComponents()) if (component instanceof Card card) cards.remove(card);
            rows.removeAll(); List<String> ids = assignments.get(stage);
            count.setText(Integer.toString(ids.size()) + (stage == MixtureStage.PLANNER || stage == MixtureStage.SUMMARIZER ? " / 1" : ""));
            header.getAccessibleContext().setAccessibleName(I18n.t(stage.label) + " · " + ids.size());
            if (ids.isEmpty()) {
                JLabel hint = Theme.label("拖动模型到这里", 11, Theme.MUTED, false); hint.setPreferredSize(new Dimension(100, 24));
                hint.setMaximumSize(new Dimension(Integer.MAX_VALUE, 24)); rows.add(hint);
            } else for (int index = 0; index < ids.size(); index++) {
                if (index > 0) rows.add(Box.createVerticalStrut(5));
                Card card = new Card(catalog.get(ids.get(index)), stage); cards.add(card); rows.add(card);
            }
            invalidate(); Container parent = getParent(); if (parent != null) { parent.invalidate(); parent.revalidate(); }
            revalidate(); repaint();
        }
        int insertionAt(Point point) {
            int index = 0;
            for (Component component : rows.getComponents()) if (component instanceof Card) {
                Point top = SwingUtilities.convertPoint(rows, component.getLocation(), this);
                if (point.y < top.y + component.getHeight() / 2) return index;
                index++;
            }
            return index;
        }
        @Override public Dimension getPreferredSize() {
            int count = assignments.get(stage).size();
            return new Dimension(300, 38 + (count == 0 ? 24 : count * 42 + (count - 1) * 5));
        }
        @Override public Dimension getMinimumSize() { return new Dimension(180, getPreferredSize().height); }
        @Override public Dimension getMaximumSize() { return new Dimension(Integer.MAX_VALUE, getPreferredSize().height); }
        @Override protected void paintComponent(Graphics graphics) {
            super.paintComponent(graphics); Graphics2D g = (Graphics2D) graphics.create(); Theme.antialias(g);
            long elapsed = System.nanoTime() - acceptedAt;
            if (dropHover || (acceptedAt != 0 && elapsed < Motion.FEEDBACK_DURATION)) {
                g.setColor(dropHover ? Theme.ACCENT : new Color(114, 222, 191, (int) Math.max(0, 220 * (1 - elapsed / (double) Motion.FEEDBACK_DURATION)))); g.setStroke(new BasicStroke(2)); g.drawRoundRect(1, 1, getWidth() - 3, getHeight() - 3, 12, 12);
            }
            if (dropHover && dropIndex >= 0) {
                int index = 0, y = rows.getY() + rows.getHeight();
                for (Component child : rows.getComponents()) if (child instanceof Card) {
                    if (index++ == dropIndex) { y = rows.getY() + child.getY() - 3; break; }
                }
                g.setColor(Theme.ACCENT); g.setStroke(new BasicStroke(2)); g.drawLine(10, Math.min(y, getHeight() - 5), getWidth() - 10, Math.min(y, getHeight() - 5));
            }
            g.dispose();
        }
    }

    private final class Card extends JButton {
        final ChatService.Model option;
        final MixtureStage lane;
        double hover;
        Point pressed;
        Card(ChatService.Model option, MixtureStage lane) {
            this.option = option; this.lane = lane; setName("mixture-" + (lane == null ? "model-" : "assigned-") + option.id());
            setAlignmentX(LEFT_ALIGNMENT); setPreferredSize(new Dimension(280, lane == null ? 60 : 42));
            setMinimumSize(new Dimension(180, lane == null ? 60 : 42)); setMaximumSize(new Dimension(Integer.MAX_VALUE, lane == null ? 60 : 42));
            setOpaque(false); setContentAreaFilled(false); setBorderPainted(false); setFocusPainted(false); setRolloverEnabled(true);
            setEnabled(option.configured()); setCursor(Cursor.getPredefinedCursor(option.configured() ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
            setLayout(null); setTransferHandler(new BoardTransfer(this, lane));
            I18n.tooltip(this, option.configured() ? "拖动分配角色；按 1 / 2 / 3 / 4 分配，Delete 移除" : "请先在 API 密钥与隐私中配置此模型");
            addActionListener(event -> selectModel(option.id())); getModel().addChangeListener(event -> startAnimation());
            addFocusListener(new FocusAdapter() {
                @Override public void focusGained(FocusEvent event) { startAnimation(); }
                @Override public void focusLost(FocusEvent event) { startAnimation(); }
            });
            bind(this, WHEN_FOCUSED, "DELETE", "unassign", () -> removeModel(option.id()));
            bind(this, WHEN_FOCUSED, "BACK_SPACE", "unassign-backspace", () -> removeModel(option.id()));
            MouseAdapter drag = new MouseAdapter() {
                @Override public void mousePressed(MouseEvent event) {
                    if (!isEnabled() || closing || !SwingUtilities.isLeftMouseButton(event)) return;
                    pressed = event.getPoint(); selectModel(option.id());
                }
                @Override public void mouseDragged(MouseEvent event) {
                    if (pressed == null || nativeDragging || closing || !isEnabled() || pressed.distance(event.getPoint()) < 6 || GraphicsEnvironment.isHeadless()) return;
                    nativeDragging = true; getModel().setArmed(false);
                    BufferedImage ghost = new BufferedImage(Math.max(1, getWidth()), Math.max(1, getHeight()), BufferedImage.TYPE_INT_ARGB);
                    Graphics2D g = ghost.createGraphics(); printAll(g); g.dispose();
                    getTransferHandler().setDragImage(ghost); getTransferHandler().setDragImageOffset(new Point(-pressed.x, -pressed.y));
                    getTransferHandler().exportAsDrag(Card.this, event, TransferHandler.MOVE);
                }
                @Override public void mouseReleased(MouseEvent event) { pressed = null; }
            };
            addMouseListener(drag); addMouseMotionListener(drag);
            if (lane != null) {
                JButton remove = new JButton("×"); remove.setName("mixture-remove-" + option.id()); remove.setFocusable(true);
                remove.setBorder(BorderFactory.createEmptyBorder()); remove.setMargin(new Insets(0, 0, 0, 0));
                remove.setBorderPainted(false); remove.setContentAreaFilled(false); remove.setForeground(Theme.MUTED); remove.setFont(Theme.font(17, false));
                remove.getAccessibleContext().setAccessibleName(I18n.t("移除") + " " + option.name());
                remove.addActionListener(event -> removeModel(option.id())); add(remove);
            }
            refreshAccessibility();
        }
        void refreshAccessibility() {
            MixtureStage current = assignedStage(option.id());
            getAccessibleContext().setAccessibleName(option.name() + " · " + (!option.configured() ? I18n.t("未配置密钥") : current == null ? I18n.t("未分配") : current));
        }
        @Override public void doLayout() { for (Component child : getComponents()) child.setBounds(getWidth() - 34, 8, 28, 30); }
        @Override protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create(); Theme.antialias(g); int width = getWidth(), height = getHeight();
            MixtureStage current = assignedStage(option.id()); boolean active = option.id().equals(selectedModel), assigned = current != null;
            Color base = lane != null ? new Color(0x202A3C) : new Color(0x192232);
            g.setColor(Motion.blend(base, new Color(0x302B45), active ? 1 : hover));
            g.fillRoundRect(0, 0, width - 1, height - 1, 11, 11);
            g.setColor(Motion.blend(assigned ? new Color(0x46516D) : Theme.LINE, Theme.ACCENT, active ? 1 : hover)); g.drawRoundRect(0, 0, width - 1, height - 1, 11, 11);
            Color accent = switch (option.provider()) { case "DeepSeek" -> new Color(0x8AB2FF); case "OpenAI" -> Theme.MINT; case "Claude" -> new Color(0xDFA88C); default -> Theme.ACCENT; };
            g.setColor(option.configured() ? accent : Theme.MUTED); g.setFont(Theme.font(16, true)); g.drawString(option.provider().substring(0, 1), 14, lane == null ? 28 : 30);
            g.setColor(option.configured() ? Theme.TEXT : Theme.MUTED); g.setFont(Theme.font(12, true));
            g.drawString(fit(g, option.name(), width - 82), 42, lane == null ? 25 : 29);
            if (lane == null) {
                g.setFont(Theme.font(10, false)); g.setColor(assigned ? Theme.MINT : Theme.MUTED);
                String state = !option.configured() ? I18n.t("未配置密钥") : current == null ? I18n.t("拖动分配角色") : current + " · " + I18n.t("已分配");
                g.drawString(fit(g, state, width - 65), 42, 44);
                g.setFont(Theme.font(16, false)); g.setColor(Theme.MUTED); g.drawString("⠿", width - 24, 34);
            }
            if (hasFocus()) { g.setColor(Theme.MINT); g.drawRoundRect(2, 2, width - 5, height - 5, 10, 10); }
            g.dispose();
        }
    }

    private final class BoardTransfer extends TransferHandler {
        final Card source;
        final MixtureStage destination;
        BoardTransfer(Card source, MixtureStage destination) { this.source = source; this.destination = destination; }
        @Override public int getSourceActions(JComponent component) { return source != null && !closing && canUse(source.option.id()) ? MOVE : NONE; }
        @Override protected Transferable createTransferable(JComponent component) {
            if (source == null || closing || !canUse(source.option.id())) return null;
            DraggedModel payload = new DraggedModel(MixturePanel.this, source.option.id());
            return new Transferable() {
                public DataFlavor[] getTransferDataFlavors() { return new DataFlavor[] { MODEL_FLAVOR }; }
                public boolean isDataFlavorSupported(DataFlavor flavor) { return MODEL_FLAVOR.equals(flavor); }
                public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
                    if (!isDataFlavorSupported(flavor)) throw new UnsupportedFlavorException(flavor); return payload;
                }
            };
        }
        private DraggedModel payload(TransferSupport support) {
            try {
                if (!support.isDataFlavorSupported(MODEL_FLAVOR)) return null;
                Object value = support.getTransferable().getTransferData(MODEL_FLAVOR);
                if (value instanceof DraggedModel model && model.owner() == MixturePanel.this && canUse(model.modelId())) return model;
            } catch (UnsupportedFlavorException | IOException ignored) { /* External clipboard formats cannot assign roles. */ }
            return null;
        }
        private int insertion(TransferSupport support) {
            RoleLane lane = lanes.get(destination);
            if (!support.isDrop()) return assignments.get(destination).size();
            Point point = SwingUtilities.convertPoint(support.getComponent(), support.getDropLocation().getDropPoint(), lane);
            lane.scrollRectToVisible(new Rectangle(0, Math.max(0, point.y - 24), lane.getWidth(), 48));
            return lane.insertionAt(point);
        }
        @Override public boolean canImport(TransferSupport support) {
            if (closing) return false;
            DraggedModel model = payload(support);
            if (model == null || (destination == null && assignedStage(model.modelId()) == null)) return false;
            if (support.isDrop()) {
                if ((support.getSourceDropActions() & MOVE) == 0) return false;
                support.setDropAction(MOVE); support.setShowDropLocation(true);
            }
            markDrop(destination, destination == null ? -1 : insertion(support)); return true;
        }
        @Override public boolean importData(TransferSupport support) {
            if (!canImport(support)) return false;
            DraggedModel model = payload(support);
            return model != null && (destination == null ? removeModel(model.modelId()) : assignModel(model.modelId(), destination, insertion(support)));
        }
        @Override protected void exportDone(JComponent component, Transferable data, int action) {
            nativeDragging = false; if (source != null) source.pressed = null; clearDropTargets(); startAnimation();
        }
    }
    private static String fit(Graphics2D graphics, String text, int width) {
        if (graphics.getFontMetrics().stringWidth(text) <= width) return text;
        while (!text.isEmpty() && graphics.getFontMetrics().stringWidth(text + "…") > width) text = text.substring(0, text.offsetByCodePoints(text.length(), -1));
        return text + "…";
    }
    private static void bind(JComponent target, int condition, String key, String name, Runnable action) {
        target.getInputMap(condition).put(KeyStroke.getKeyStroke(key), name);
        target.getActionMap().put(name, new AbstractAction() { @Override public void actionPerformed(ActionEvent event) { action.run(); } });
    }
}
