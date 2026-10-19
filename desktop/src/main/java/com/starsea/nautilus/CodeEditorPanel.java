package com.starsea.nautilus;

import java.awt.*;
import javax.swing.*;

/** Draft edits are applied only when the dialog is saved. */
@SuppressWarnings("serial")
final class CodeEditorPanel extends JPanel {
    final JTextArea code = new JTextArea();
    final JTextArea note = new JTextArea();
    CodeEditorPanel(CodeContext.Block block, boolean annotate) {
        super(new BorderLayout(0, 12)); setBackground(Theme.SURFACE);
        setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));
        add(Theme.label(block.id + " · " + block.language + " · 本地版本（原回复保留）", 13, Theme.MINT, true), BorderLayout.NORTH);
        code.setText(block.code); code.setEditable(!annotate); code.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        code.setTabSize(4); style(code); code.setCaretPosition(0);
        JScrollPane source = new JScrollPane(code); source.setPreferredSize(new Dimension(640, 300)); add(source);
        JPanel notes = new JPanel(new BorderLayout(0, 8)); notes.setOpaque(false);
        JPanel caption = new JPanel(new BorderLayout()); caption.setOpaque(false);
        caption.add(Theme.label("批注 · 可注明行号、问题和修改意图", 12, Theme.MUTED, false));
        Theme.Button mark = new Theme.Button("引用所选代码行", false); mark.setFont(Theme.font(11, false));
        mark.setName("quote-code-lines");
        mark.addActionListener(event -> {
            try {
                int start = code.getSelectionStart(), end = code.getSelectionEnd();
                if (end > start) note.append((note.getText().isBlank() ? "" : "\n") + I18n.t("第 ") + (code.getLineOfOffset(start) + 1)
                        + "–" + (code.getLineOfOffset(end - 1) + 1) + I18n.t(" 行："));
                note.requestFocusInWindow();
            } catch (javax.swing.text.BadLocationException ex) { throw new IllegalStateException(ex); }
        });
        caption.add(mark, BorderLayout.EAST); notes.add(caption, BorderLayout.NORTH);
        note.setText(block.note); note.setRows(4); note.setLineWrap(true); note.setWrapStyleWord(true); style(note);
        notes.add(new JScrollPane(note));
        notes.add(Theme.label("相关追问会附带当前代码与批注；恢复原代码并清空批注可取消自动增强。", 11, Theme.MUTED, false), BorderLayout.SOUTH);
        add(notes, BorderLayout.SOUTH);
        if (annotate) SwingUtilities.invokeLater(note::requestFocusInWindow);
    }
    void apply(CodeContext.Block block) { block.code = code.getText(); block.note = note.getText().strip(); }
    private static void style(JTextArea area) {
        area.setBackground(Theme.BG); area.setForeground(Theme.TEXT); area.setCaretColor(Theme.ACCENT);
        area.setSelectionColor(new Color(0x5B507F)); area.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
    }
}
