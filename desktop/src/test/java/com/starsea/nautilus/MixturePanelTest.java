package com.starsea.nautilus;

import java.awt.*;
import java.awt.datatransfer.*;
import java.awt.event.ActionEvent;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.*;

import com.starsea.nautilus.ChatService.MixtureStage;

/** Exercise the actual Swing transfer handlers with local clipboards; never touches the system clipboard. */
public final class MixturePanelTest {
    private MixturePanelTest() {}
    public static void main(String[] args) throws Exception {
        AtomicReference<Throwable> edtFailure = new AtomicReference<>();
        SwingUtilities.invokeAndWait(() -> Thread.currentThread().setUncaughtExceptionHandler((thread, error) -> edtFailure.compareAndSet(null, error)));
        AtomicReference<MixturePanel> ref = new AtomicReference<>();
        AtomicReference<ChatService.MixtureTeam> accepted = new AtomicReference<>();
        AtomicInteger cancelled = new AtomicInteger();
        ChatService.Credentials allKeys = new ChatService.Credentials("", "", true, true, true, true);
        SwingUtilities.invokeAndWait(() -> {
            testPresetCredentials();
            MixturePanel panel = new MixturePanel(allKeys, (ChatService.MixtureTeam) null, accepted::set, cancelled::incrementAndGet);
            ref.set(panel); panel.setSize(1080, 860); panel.addNotify(); layout(panel);
            check(panel.configuration().members().isEmpty(), "new teams begin with user-assigned roles");
            check(component(panel, "mixture-recommendations").getHeight() == 0, "recommendations begin collapsed");
            button(panel, "mixture-recommend").doClick(0);
            button(panel, "mixture-preset-expert").doClick(0);
            check(panel.selectedIds().equals(List.of("deepseek-pro", "openai", "gemini-argon", "claude-fable")), "expert team uses supported high-capability variants");
            button(panel, "mixture-allow-fallback").doClick(0);
            button(panel, "mixture-preset-value").doClick(0);
            check(panel.selectedIds().equals(List.of("deepseek-flash", "openai", "gemini-flash", "claude-sonnet")), "recommended team uses balanced variants");
            check(panel.configuration().allowRoleFallback(), "applying presets preserves explicit fallback consent");
            check(accepted.get() == null, "presets never submit or run a task");
            panel.assignModel("claude-sonnet", MixtureStage.WORKER, 0);
            check(panel.assignedStage("claude-sonnet") == MixtureStage.WORKER && !button(panel, "mixture-confirm").isEnabled(), "preset roles remain freely editable");
            button(panel, "mixture-allow-fallback").doClick(0); button(panel, "mixture-clear").doClick(0);
            check(!button(panel, "mixture-confirm").isEnabled(), "an empty board cannot submit");
            check(children(panel).stream().noneMatch(c -> c instanceof JComboBox<?> || c instanceof JSpinner || c instanceof JTextField
                || c instanceof JTextArea text && text.isEditable()), "board has no dropdowns or editable goal fields");
            check(children(panel).stream().filter(c -> c instanceof AbstractButton && c.getName() != null && c.getName().startsWith("mixture-model-")).count() == 10,
                "all ten model variants are directly draggable");
            Container board = (Container) component(panel, "mixture-board");
            check(board.getComponentCount() == 2 && board.getComponent(0).getX() < board.getComponent(1).getX(), "models and roles form exactly two columns");
            Transferable deepseek = drag(panel, "deepseek-pro", false);
            TransferHandler.TransferSupport preview = support(panel, "mixture-role-planner", deepseek);
            check(target(panel, "mixture-role-planner").canImport(preview), "planner accepts a local model drag");
            check(panel.configuration().members().isEmpty(), "hovering or cancelling a drag does not assign a model");
            drop(panel, "mixture-role-planner", deepseek);
            check(panel.assignedStage("deepseek-pro") == MixtureStage.PLANNER, "dropping assigns the requested role");
            check(!button(panel, "mixture-confirm").isEnabled(), "a summary role is required");
            drop(panel, "mixture-role-summarizer", drag(panel, "claude-fable", false));
            drop(panel, "mixture-role-worker", drag(panel, "openai", false));
            drop(panel, "mixture-role-planner", drag(panel, "gemini-argon", false));
            check(panel.assignedStage("deepseek-pro") == null, "occupied planner is replaced and returned to the palette");
            drop(panel, "mixture-role-worker", drag(panel, "deepseek-pro", false));
            check(workerIds(panel).equals(List.of("openai", "deepseek-pro")), "worker drops preserve their order");
            drop(panel, "mixture-role-worker", drag(panel, "openai", true));
            check(workerIds(panel).equals(List.of("deepseek-pro", "openai")), "dragging within the same role reorders cards");
            panel.assignModel("openai", MixtureStage.WORKER, 0);
            check(workerIds(panel).equals(List.of("openai", "deepseek-pro")), "insertion at the start is honored");
            drop(panel, "mixture-role-reviewer", drag(panel, "deepseek-flash", false));
            check(panel.assignedStage("deepseek-pro") == null && panel.assignedStage("deepseek-flash") == MixtureStage.REVIEWER,
                "switching a variant keeps one model per provider across all roles");
            check(button(panel, "mixture-confirm").isEnabled(), "four user-assigned roles can submit");
            check(panel.configuration().members().stream().allMatch(m -> !m.role().isBlank() && !m.goal().isBlank()),
                "role defaults satisfy the backend without extra user forms");
            check(accepted.get() == null, "dragging only edits the local draft");
            ChatService.MixtureTeam before = panel.configuration();
            check(!target(panel, "mixture-role-worker").importData(support(panel, "mixture-role-worker", new StringSelection("claude-opus"))),
                "plain text cannot forge model drags");
            MixturePanel other = new MixturePanel(allKeys, (ChatService.MixtureTeam) null, team -> {}, () -> {});
            check(!target(panel, "mixture-role-worker").importData(support(panel, "mixture-role-worker", drag(other, "openai", false))),
                "another dialog cannot mutate this board");
            check(panel.configuration().equals(before), "rejected drops preserve the draft");
            drop(panel, "mixture-model-bank", drag(panel, "claude-fable", true));
            check(panel.assignedStage("claude-fable") == null && !button(panel, "mixture-confirm").isEnabled(), "dragging back removes the assignment");
            drop(panel, "mixture-role-summarizer", drag(panel, "claude-fable", false));
            button(panel, "mixture-remove-openai").doClick(0);
            check(panel.assignedStage("openai") == null, "remove button is a drag-free alternative");
            button(panel, "mixture-model-openai").doClick(0);
            button(panel, "mixture-role-worker-assign").doClick(0);
            check(panel.assignedStage("openai") == MixtureStage.WORKER, "select then click a role is accessible without dragging");
            panel.getActionMap().get("assign-reviewer").actionPerformed(new ActionEvent(panel, 0, "3"));
            check(panel.assignedStage("openai") == MixtureStage.REVIEWER, "numeric keyboard shortcut assigns the chosen model");
            drop(panel, "mixture-role-worker", drag(panel, "openai", true));
            button(panel, "mixture-allow-fallback").doClick(0);
            check(panel.configuration().allowRoleFallback(), "fallback remains an explicit choice");
        });
        settle(ref.get());
        SwingUtilities.invokeAndWait(() -> button(ref.get(), "mixture-recommend").doClick(0));
        long reversalDeadline = System.nanoTime() + 2_000_000_000L;
        boolean[] intermediate = { false };
        while (!intermediate[0] && System.nanoTime() < reversalDeadline) {
            SwingUtilities.invokeAndWait(() -> {
                int height = component(ref.get(), "mixture-recommendations").getPreferredSize().height;
                intermediate[0] = height > 0 && height < 140;
                if (intermediate[0]) {
                    button(ref.get(), "mixture-recommend").doClick(0);
                    check(component(ref.get(), "mixture-recommendations").getPreferredSize().height == height, "recommendation reversal has no immediate layout jump");
                }
            });
            if (!intermediate[0]) Thread.sleep(12);
        }
        check(intermediate[0], "recommendation tray paints intermediate frames"); settle(ref.get());
        SwingUtilities.invokeAndWait(() -> {
            MixturePanel panel = ref.get();
            SettingsMenuTest.render(panel, Path.of(args[0], "mixture-drag-board.png"), 1080, 860); checkCardBounds(panel);
            SettingsMenuTest.render(panel, Path.of(args[0], "mixture-drag-compact.png"), 940, 740); checkCardBounds(panel);
            check(component(panel, "mixture-recommendations").getHeight() == 140, "inline recommendations finish expanding");
            for (String id : List.of("mixture-preset-expert", "mixture-preset-value")) {
                Component action = component(panel, id);
                check(action.getHeight() > 30 && action.getWidth() <= action.getParent().getWidth(), "preset buttons fit compact cards");
            }
            for (String scrollName : List.of("mixture-models-scroll", "mixture-roles-scroll")) {
                JScrollPane scroll = (JScrollPane) component(panel, scrollName);
                check(scroll.getViewport().getView().getWidth() == scroll.getViewport().getExtentSize().width, "columns track their viewport width");
            }
            button(panel, "mixture-confirm").doClick(0);
            check(!panel.assignModel("claude-opus", MixtureStage.WORKER, 0), "closing freezes assignments");
        });
        settle(ref.get());
        SwingUtilities.invokeAndWait(() -> {
            check(accepted.get() != null && accepted.get().members().size() == 4, "confirmation submits the whole board");
            ref.get().removeNotify(); check(!ref.get().isAnimating(), "disposal stops every animation");
            MixturePanel reopened = new MixturePanel(allKeys, accepted.get(), team -> {}, () -> {});
            check(reopened.configuration().equals(accepted.get()), "reopen restores model variants, stages, order and fallback");
            button(reopened, "mixture-clear").doClick(0);
            check(reopened.configuration().members().isEmpty() && !button(reopened, "mixture-confirm").isEnabled(), "clear unassigns all cards");
            reopened.removeNotify();
            MixturePanel missing = new MixturePanel(new ChatService.Credentials("", "", true, false, false, false), (ChatService.MixtureTeam) null, team -> {}, () -> {});
            JComponent claude = (JComponent) component(missing, "mixture-model-claude-opus");
            check(!claude.isEnabled() && claude.getTransferHandler().getSourceActions(claude) == TransferHandler.NONE, "missing keys disable native drag sources");
            check(!missing.assignModel("claude-opus", MixtureStage.SUMMARIZER, 0), "disabled models cannot be assigned programmatically");
            check(!missing.assignModel("unknown", MixtureStage.WORKER, 0), "unknown cards are rejected");
            missing.assignModel("deepseek-flash", MixtureStage.SUMMARIZER, 0);
            check(button(missing, "mixture-confirm").isEnabled(), "one summary model can run without a planner");
            missing.removeNotify();
            MixturePanel cancel = new MixturePanel(allKeys, accepted.get(), team -> { throw new AssertionError("cancel submitted a draft"); }, cancelled::incrementAndGet);
            ref.set(cancel); cancel.addNotify(); cancel.applyPreset(MixturePresets.Kind.VALUE); cancel.assignModel("claude-haiku", MixtureStage.WORKER, 0);
            cancel.getActionMap().get("dismiss").actionPerformed(new ActionEvent(cancel, 0, "escape"));
        });
        settle(ref.get());
        SwingUtilities.invokeAndWait(() -> { check(cancelled.get() == 1, "Escape cancels without applying the draft"); ref.get().removeNotify(); });
        testRecommendationLanguages(Path.of(args[0]), allKeys);
        if (edtFailure.get() != null) throw new AssertionError("Uncaught EDT failure", edtFailure.get());
        System.out.println("PASS: two-column model board, real Swing transfer payloads, role drops, replacement, removal, reordering, keyboard access, credential guards, confirmation, cancellation and previews");
    }
    private static void testRecommendationLanguages(Path folder, ChatService.Credentials keys) throws Exception {
        I18n.Language previous = I18n.language();
        AtomicReference<MixturePanel> holder = new AtomicReference<>();
        for (I18n.Language language : I18n.Language.values()) {
            SwingUtilities.invokeAndWait(() -> {
                try { I18n.change(language, new JPanel()); } catch (java.io.IOException e) { throw new AssertionError(e); }
                MixturePanel panel = new MixturePanel(keys, (ChatService.MixtureTeam) null, team -> {}, () -> {});
                holder.set(panel); panel.addNotify(); button(panel, "mixture-recommend").doClick(0); button(panel, "mixture-preset-value").doClick(0);
            });
            settle(holder.get());
            SwingUtilities.invokeAndWait(() -> {
                MixturePanel panel = holder.get();
                SettingsMenuTest.render(panel, folder.resolve("mixture-recommend-" + language.code + ".png"), 940, 740);
                check(button(panel, "mixture-preset-expert").getText().equals(I18n.t("专家团队")), "expert preset is localized");
                check(button(panel, "mixture-preset-value").getText().equals(I18n.t("推荐团队")), "value preset is localized");
                checkCardBounds(panel); panel.removeNotify();
            });
        }
        SwingUtilities.invokeAndWait(() -> { try { I18n.change(previous, new JPanel()); } catch (java.io.IOException e) { throw new AssertionError(e); } });
    }
    private static void testPresetCredentials() {
        for (int mask = 0; mask < 16; mask++) {
            ChatService.Credentials keys = new ChatService.Credentials("", "", (mask & 1) != 0, (mask & 2) != 0, (mask & 4) != 0, (mask & 8) != 0);
            MixturePanel panel = new MixturePanel(keys, (ChatService.MixtureTeam) null, team -> { throw new AssertionError("preset submitted automatically"); }, () -> {});
            button(panel, "mixture-recommend").doClick(0);
            for (MixturePresets.Kind kind : MixturePresets.Kind.values()) {
                AbstractButton action = button(panel, "mixture-preset-" + kind.name().toLowerCase(java.util.Locale.ROOT));
                check(action.isEnabled() == (mask != 0), "no-key presets are disabled"); action.doClick(0);
                ChatService.MixtureTeam team = panel.configuration();
                check(team.members().size() == Integer.bitCount(mask), "preset includes exactly the configured providers");
                check(!team.allowRoleFallback(), "presets do not silently enable role fallback");
                if (mask != 0) {
                    check(team.members().stream().filter(member -> member.stage() == MixtureStage.SUMMARIZER).count() == 1, "every available subset has exactly one summarizer");
                    check(button(panel, "mixture-confirm").isEnabled(), "partial-key team is valid");
                } else check(!panel.applyPreset(kind) && team.members().isEmpty(), "no-key preset leaves the draft untouched");
            }
            panel.removeNotify(); check(!panel.isAnimating(), "preset disposal stops the board timer");
        }
    }
    private static void checkCardBounds(Container panel) {
        for (Component card : children(panel)) if (card.getName() != null && card.getName().startsWith("mixture-assigned-")) {
            check(card.getHeight() == 42 && card.getY() + card.getHeight() <= card.getParent().getHeight(), "assigned cards fit their role lane without clipping");
        }
    }
    private static List<String> workerIds(MixturePanel panel) {
        return panel.configuration().members().stream().filter(m -> m.stage() == MixtureStage.WORKER).map(ChatService.MixtureMember::modelId).toList();
    }
    private static Transferable drag(MixturePanel panel, String id, boolean assigned) {
        JComponent source = (JComponent) component(panel, "mixture-" + (assigned ? "assigned-" : "model-") + id);
        Clipboard clipboard = new Clipboard("nautilus-local-test"); source.getTransferHandler().exportToClipboard(source, clipboard, TransferHandler.MOVE);
        Transferable payload = clipboard.getContents(null); check(payload != null, "native handler exports a model payload"); return payload;
    }
    private static TransferHandler target(MixturePanel panel, String name) { return ((JComponent) component(panel, name)).getTransferHandler(); }
    private static TransferHandler.TransferSupport support(MixturePanel panel, String target, Transferable data) {
        return new TransferHandler.TransferSupport(component(panel, target), data);
    }
    private static void drop(MixturePanel panel, String target, Transferable data) {
        check(target(panel, target).importData(support(panel, target, data)), "drop accepted: " + target);
    }
    private static void settle(MixturePanel panel) throws Exception {
        long deadline = System.nanoTime() + 4_000_000_000L;
        while (System.nanoTime() < deadline) {
            boolean[] idle = { false }; SwingUtilities.invokeAndWait(() -> idle[0] = !panel.isAnimating());
            if (idle[0]) return; Thread.sleep(15);
        }
        throw new AssertionError("animation did not settle");
    }
    private static AbstractButton button(Container parent, String name) { return (AbstractButton) component(parent, name); }
    private static Component component(Container parent, String name) {
        return children(parent).stream().filter(c -> name.equals(c.getName())).findFirst().orElseThrow(() -> new AssertionError("missing " + name));
    }
    private static List<Component> children(Container parent) {
        List<Component> result = new ArrayList<>();
        for (Component child : parent.getComponents()) { result.add(child); if (child instanceof Container nested) result.addAll(children(nested)); }
        return result;
    }
    private static void layout(Container parent) { parent.doLayout(); for (Component child : parent.getComponents()) if (child instanceof Container nested) layout(nested); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
