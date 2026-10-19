package com.starsea.nautilus;

import java.awt.*;
import javax.swing.*;

/** Uses the shared button clock: reversible sea-blue selection, transparent at rest. */
@SuppressWarnings("serial")
final class SearchBubble extends Theme.Button {
    private boolean available;
    SearchBubble() {
        super("联网搜索", false);
        setName("web-search");
        setFont(Theme.font(12, true));
        setBorder(BorderFactory.createEmptyBorder(7, 34, 7, 14));
        setEnabled(false);
        getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("ENTER"), "search-toggle");
        getActionMap().put("search-toggle", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent event) { doClick(); }
        });
    }
    void sync(ChatService.WebSearch state) {
        available = state.available();
        setSelected(state.enabled() && available);
        refreshLanguage();
    }
    @Override public void refreshLanguage() {
        super.refreshLanguage();
        String description = !available ? "选择模型后可使用联网搜索"
                : isSelected() ? "联网搜索已开启" : "联网搜索已关闭";
        I18n.tooltip(this, description);
        getAccessibleContext().setAccessibleName(I18n.t("联网搜索"));
        getAccessibleContext().setAccessibleDescription(I18n.t(description));
        revalidate(); repaint();
    }
    @Override protected void paintComponent(Graphics source) {
        Graphics2D g = (Graphics2D) source.create(); Theme.antialias(g);
        double chosen = selectionProgress();
        g.translate(0, pressProgress());
        int w = getWidth(), h = getHeight();
        Composite original = g.getComposite();
        g.setComposite(AlphaComposite.SrcOver.derive((float) chosen));
        g.setPaint(new GradientPaint(0, 0, new Color(0x087DA9), w, h, new Color(0x1267A7)));
        g.fillRoundRect(0, 0, w, h, h, h);
        g.setComposite(original);
        // Off-state fill remains transparent; hover and focus use a fine outline.
        g.setColor(Motion.blend(Theme.LINE, new Color(0x45B9E5), Math.max(chosen, hoverProgress())));
        g.drawRoundRect(0, 0, w - 1, h - 1, h, h);
        Color ink = Motion.blend(Theme.MUTED, Color.WHITE, chosen);
        if (!isEnabled() && chosen == 0) ink = Theme.MUTED;
        g.setColor(ink); g.setStroke(new BasicStroke(1.25f));
        int x = 13, y = (h - 14) / 2;
        g.drawOval(x, y, 14, 14); g.drawOval(x + 4, y, 6, 14);
        g.drawLine(x, y + 7, x + 14, y + 7);
        g.setFont(getFont()); FontMetrics metrics = g.getFontMetrics();
        g.drawString(getText(), 35, (h - metrics.getHeight()) / 2 + metrics.getAscent());
        if (hasFocus()) { g.setColor(Theme.ACCENT); g.drawRoundRect(3, 3, w - 7, h - 7, h - 6, h - 6); }
        g.dispose();
    }
}
