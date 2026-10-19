package com.starsea.nautilus;

import java.awt.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import javax.swing.*;

/** Updates row data in place so autosave preserves focus, hover, scroll and component identity. */
@SuppressWarnings("serial")
final class ConversationList extends JPanel implements I18n.Localized {
    private static final DateTimeFormatter DATE=DateTimeFormatter.ofPattern("MM-dd HH:mm");
    private final Map<String,Row> rows=new LinkedHashMap<>();
    private final JLabel empty=Theme.label("暂无历史对话",11,Theme.MUTED,false);
    private final Consumer<ConversationStore.Session> open,delete;
    private boolean available;
    ConversationList(Consumer<ConversationStore.Session> open,Consumer<ConversationStore.Session> delete){
        this.open=open;this.delete=delete;setOpaque(false);setLayout(new BoxLayout(this,BoxLayout.Y_AXIS));setAlignmentX(LEFT_ALIGNMENT);
        empty.setBorder(BorderFactory.createEmptyBorder(8,8,16,8));add(empty);
    }
    void update(List<ConversationStore.Session> sessions,String current){
        boolean structural=false;
        Set<String> ids=sessions.stream().map(ConversationStore.Session::id).collect(Collectors.toSet());
        for(String id:List.copyOf(rows.keySet()))if(!ids.contains(id)){remove(rows.remove(id));structural=true;}
        if(!sessions.isEmpty()&&empty.getParent()==this){remove(empty);structural=true;}
        for(int i=0;i<sessions.size();i++){
            ConversationStore.Session session=sessions.get(i);Row row=rows.get(session.id());
            if(row==null){row=new Row(session);rows.put(session.id(),row);add(row,i);structural=true;}
            row.update(session);row.select.setSelected(session.id().equals(current));
            if(getComponentZOrder(row)!=i){setComponentZOrder(row,i);structural=true;}
        }
        if(sessions.isEmpty()&&empty.getParent()!=this){add(empty);structural=true;}
        if(structural){revalidate();if(getParent()!=null){getParent().invalidate();getParent().revalidate();}}
    }
    void available(boolean value){if(available==value)return;available=value;for(Row row:rows.values()){row.select.setEnabled(value);row.remove.setEnabled(value);}}
    private final class Row extends JPanel {
        private ConversationStore.Session session;
        private String title="",models="",time="";
        private final Theme.Button select=new Theme.Button("",false){
            @Override protected void paintComponent(Graphics source){
                Graphics2D g=(Graphics2D)source.create();Theme.antialias(g);g.translate(0,pressProgress());
                g.setColor(Motion.blend(Motion.blend(Theme.SIDE,Theme.HOVER,hoverProgress()),Theme.SELECTED,selectionProgress()));g.fillRoundRect(0,2,getWidth(),getHeight()-4,Theme.CONTROL_RADIUS,Theme.CONTROL_RADIUS);
                if(selectionProgress()>.01){g.setColor(Theme.ACCENT);Composite original=g.getComposite();g.setComposite(Theme.alpha(g,selectionProgress()));g.fillRoundRect(0,14,3,Math.max(12,getHeight()-28),3,3);g.setComposite(original);}
                g.setFont(Theme.font(12,true));g.setColor(isEnabled()?Theme.TEXT:Theme.MUTED);g.drawString(fit(g,title,getWidth()-24),12,23);
                g.setFont(Theme.font(10,false));g.setColor(Theme.MINT);g.drawString(fit(g,models,getWidth()-24),12,43);g.setColor(Theme.MUTED);g.drawString(time,12,62);
                if(hasFocus()){g.setColor(Theme.ACCENT);g.drawRoundRect(2,4,getWidth()-5,getHeight()-9,12,12);}g.dispose();
            }
        };
        private final Theme.Button remove=new Theme.Button("×",false).quiet();
        Row(ConversationStore.Session session){
            super(new BorderLayout(4,0));setOpaque(false);setAlignmentX(LEFT_ALIGNMENT);setMaximumSize(new Dimension(Integer.MAX_VALUE,78));setPreferredSize(new Dimension(236,78));
            select.setName("conversation-"+session.id());select.setEnabled(available);select.addActionListener(e->open.accept(this.session));add(select);
            remove.setName("delete-conversation-"+session.id());remove.setPreferredSize(new Dimension(28,32));remove.setBorder(BorderFactory.createEmptyBorder());I18n.tooltip(remove,"删除此对话");remove.addActionListener(e->delete.accept(this.session));remove.setEnabled(available);
            JPanel wrap=new JPanel(new GridBagLayout());wrap.setOpaque(false);wrap.add(remove);add(wrap,BorderLayout.EAST);
        }
        void update(ConversationStore.Session next){
            if(session==next)return;
            if(session==null||!session.entries().equals(next.entries())){
                title=next.title();models=next.models();
                String variants=String.join(" · ",next.entries().stream().filter(ConversationStore.Entry::user).map(ConversationStore.Entry::modelId).distinct().toList());
                select.setToolTipText("<html>"+escape(title)+"<br>"+escape(models)+"<br>"+escape(variants)+"</html>");select.getAccessibleContext().setAccessibleName(title+" · "+models);
            }
            time=DATE.format(Instant.ofEpochMilli(next.updated()).atZone(ZoneId.systemDefault()));session=next;select.repaint();
        }
    }
    private static String escape(String text){return text.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;");}
    static String fit(Graphics2D g,String text,int width){
        if(width<=0)return "";if(g.getFontMetrics().stringWidth(text)<=width)return text;
        int count=text.codePointCount(0,text.length()),low=0,high=count;
        while(low<high){int mid=(low+high+1)/2,end=text.offsetByCodePoints(0,mid);if(g.getFontMetrics().stringWidth(text.substring(0,end)+"…")<=width)low=mid;else high=mid-1;}
        return text.substring(0,text.offsetByCodePoints(0,low))+"…";
    }
    @Override public void refreshLanguage(){((I18n.Localized)empty).refreshLanguage();repaint();}
}
