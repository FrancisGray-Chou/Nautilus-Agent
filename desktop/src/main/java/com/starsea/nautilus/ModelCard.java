package com.starsea.nautilus;

import java.awt.*;
import javax.swing.*;

@SuppressWarnings("serial")
final class ModelCard extends Theme.Button {
    final ChatService.Model option;
    private boolean active, iconOnly;

    ModelCard(ChatService.Model option, Runnable select) {
        super("",false);this.option = option;
        setAlignmentX(LEFT_ALIGNMENT);
        setPreferredSize(new Dimension(236, 76)); setMaximumSize(new Dimension(Integer.MAX_VALUE, 76));
        setContentAreaFilled(false); setBorderPainted(false); setFocusPainted(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        getAccessibleContext().setAccessibleName(I18n.t(option.name()));
        I18n.tooltip(this, option.name() + " · " + option.subtitle());
        addActionListener(event -> select.run());
    }
    @Override public void refreshLanguage(){super.refreshLanguage();getAccessibleContext().setAccessibleName(I18n.t(option.name()));repaint();}
    void iconOnly(boolean value){iconOnly=value;setPreferredSize(new Dimension(48,52));setMaximumSize(new Dimension(48,52));getAccessibleContext().setAccessibleName(option.name());}
    void active(boolean active) { if(this.active==active)return;this.active = active;setSelected(active);repaint(); }
    @Override protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create(); Theme.antialias(g);
        int w = getWidth(), h = getHeight();g.translate(0,pressProgress());
        g.setColor(Motion.blend(Motion.blend(Theme.SIDE,Theme.HOVER,hoverProgress()),Theme.SELECTED,selectionProgress()));
        g.fillRoundRect(0, 0, w - 1, h - 1, 16, 16);
        if (active) { g.setColor(Theme.SELECTED_LINE); g.drawRoundRect(0, 0, w - 1, h - 1, 16, 16); }
        Color icon = switch (option.provider()) {
            case "DeepSeek" -> Theme.color(0x8AB2FF,0x3566B8);
            case "OpenAI" -> Theme.MINT;
            case "Claude" -> Theme.color(0xDFA88C,0xA65832);
            default -> Theme.ACCENT;
        };
        if(iconOnly){
            int x=(w-34)/2,y=(h-34)/2;g.setColor(new Color(icon.getRed(),icon.getGreen(),icon.getBlue(),25));g.fillRoundRect(x,y,34,34,12,12);
            g.setFont(Theme.font(17,true));g.setColor(isEnabled()?icon:Theme.MUTED);g.drawString(option.provider().substring(0,1),x+10,y+23);
            if(active){g.setColor(Theme.ACCENT);g.fillOval(w-9,5,4,4);}if(hasFocus()){g.setColor(Theme.ACCENT);g.drawRoundRect(2,2,w-5,h-5,14,14);}g.dispose();return;
        }
        g.setColor(new Color(icon.getRed(), icon.getGreen(), icon.getBlue(), 25));
        g.fillRoundRect(12, 20, 34, 34, 12, 12);
        g.setFont(Theme.font(17, true)); g.setColor(isEnabled() ? icon : Theme.MUTED);
        g.drawString(option.provider().substring(0, 1), 22, 43);
        g.setColor(isEnabled() ? Theme.TEXT : Theme.MUTED); g.setFont(Theme.font(13, true));
        g.drawString(ConversationList.fit(g,I18n.t(option.name()),w-72), 58, 31);
        g.setColor(Theme.MUTED); g.setFont(Theme.font(10, false));
        g.drawString(ConversationList.fit(g,I18n.t(option.subtitle()),w-72), 58, 51);
        if (active) { g.setColor(Theme.ACCENT); g.fillOval(w - 22, 14, 6, 6); }
        if (hasFocus()) { g.setColor(Theme.ACCENT); g.drawRoundRect(2, 2, w - 5, h - 5, 14, 14); }
        g.dispose();
    }
}
