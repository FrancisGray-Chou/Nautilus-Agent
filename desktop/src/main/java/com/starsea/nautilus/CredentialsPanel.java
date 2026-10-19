package com.starsea.nautilus;

import java.awt.*;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/** Local credential settings. Never pre-fills or retrieves saved API key values. */
@SuppressWarnings("serial")
final class CredentialsPanel extends JPanel {
    interface Actions {
        void save(Map<String, String> keys);
        void delete(String provider);
        void close();
    }
    private final Actions actions;
    private final Map<String, JPasswordField> fields = new LinkedHashMap<>();
    private final Map<String, JLabel> states = new LinkedHashMap<>();
    private final Map<String, Theme.Button> deletes = new LinkedHashMap<>();
    private final JCheckBox acknowledged = new I18n.CheckBox("我已阅读上述说明，并选择将输入的密钥明文保存在本机账号下", false);
    private final Theme.Button save = new Theme.Button("保存密钥", true);
    private final Theme.Button removeAll = new Theme.Button("删除全部本机密钥", false);
    private final Theme.Button close = new Theme.Button("完成 / 稍后设置", false);
    private final JLabel result = Theme.label("留空表示保持已保存的密钥不变；只需填写需要使用的平台。", 11, Theme.MUTED, false);
    private final JLabel account = Theme.label("", 12, Theme.MUTED, false);
    private final JTextArea location = new JTextArea();
    private ChatService.Credentials status;
    private boolean working;

    CredentialsPanel(ChatService.Credentials status, Actions actions) {
        this.actions = actions; setBackground(Theme.BG); setLayout(new BorderLayout(0, 16));
        setBorder(BorderFactory.createEmptyBorder(24, 26, 22, 26));
        JPanel heading = new JPanel(new GridLayout(3, 1, 0, 7)); heading.setOpaque(false);
        heading.add(Theme.label("你的模型，你的 API key", 23, Theme.TEXT, true));
        heading.add(Theme.label("密钥仅由本机 Rust 服务用于对应平台的模型调用", 12, Theme.MUTED, false));
        heading.add(account); add(heading, BorderLayout.NORTH);

        JPanel body = new Theme.ScrollBody(null); body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
        String[][] providers = {{"deepseek", "DeepSeek"}, {"openai", "OpenAI"}, {"gemini", "Gemini"}, {"claude", "Claude · Anthropic"}};
        for (String[] provider : providers) {
            JPanel row = new JPanel(new BorderLayout(12, 8)); row.setOpaque(false);
            JPanel labels = new JPanel(new BorderLayout()); labels.setOpaque(false);
            labels.add(Theme.label(provider[1], 13, Theme.TEXT, true));
            JLabel state = Theme.label("", 11, Theme.MUTED, false); states.put(provider[0], state); labels.add(state, BorderLayout.EAST);
            row.add(labels, BorderLayout.NORTH);
            JPasswordField field = new JPasswordField(); fields.put(provider[0], field);
            field.setName("credential-" + provider[0]); field.getAccessibleContext().setAccessibleName(provider[1] + " API key");
            field.setFont(Theme.font(14, false)); field.setBackground(Theme.SURFACE); field.setForeground(Theme.TEXT); field.setCaretColor(Theme.ACCENT);
            field.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(Theme.LINE), BorderFactory.createEmptyBorder(10, 12, 10, 12)));
            field.getDocument().addDocumentListener(new DocumentListener() {
                @Override public void insertUpdate(DocumentEvent event) { updateButtons(); }
                @Override public void removeUpdate(DocumentEvent event) { updateButtons(); }
                @Override public void changedUpdate(DocumentEvent event) { updateButtons(); }
            });
            row.add(field);
            Theme.Button delete = new Theme.Button("删除", false); deletes.put(provider[0], delete);
            delete.addActionListener(event -> actions.delete(provider[0])); row.add(delete, BorderLayout.EAST);
            row.setPreferredSize(new Dimension(680, 78)); row.setMinimumSize(new Dimension(0, 78));
            row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 78)); row.setAlignmentX(LEFT_ALIGNMENT);
            body.add(row); body.add(Box.createVerticalStrut(12));
        }
        JTextArea notice = new JTextArea(I18n.t("明文存储与隐私说明\n"
                + "• 密钥不加密保存在下方 .env；同一系统账号下的程序或管理员仍可能读取。文件权限限制为当前用户，应用不会上传密钥给开发者。\n"
                + "• 发送消息时，密钥用于对应平台鉴权；问题与当前会话历史会发送至所选平台。切换平台后，已有上下文也会发送给新平台；Gemini 搜索还会使用 Google Search。相关保存期限、地域和费用由平台条款及你的 API 账号设置决定。\n"
                + "• 本应用不自动持久保存聊天。密钥保留至你主动删除；删除本机副本不会撤销平台密钥，也不会删除平台已处理的数据或系统备份。\n"
                + "• 保存或删除密钥将开始新对话，避免在不同凭据之间继续发送旧历史。这是本机账号配置，不是云端账号登录。"));
        notice.setEditable(false); notice.setLineWrap(true); notice.setWrapStyleWord(true); notice.setOpaque(false);
        notice.setFont(Theme.font(12, false)); notice.setForeground(Theme.MUTED);
        notice.setRows(10); notice.setBorder(BorderFactory.createEmptyBorder(6, 0, 6, 0));
        JScrollPane noticeScroll = new JScrollPane(notice); Theme.scroll(noticeScroll); noticeScroll.setOpaque(false); noticeScroll.getViewport().setOpaque(false);
        noticeScroll.setAlignmentX(LEFT_ALIGNMENT); body.add(noticeScroll); body.add(Box.createVerticalStrut(10));
        location.setEditable(false); location.setLineWrap(true); location.setWrapStyleWord(true); location.setOpaque(false);
        location.setFont(Theme.font(11, false)); location.setForeground(Theme.MINT); location.setRows(2);
        location.setAlignmentX(LEFT_ALIGNMENT); body.add(location); body.add(Box.createVerticalStrut(8));
        acknowledged.setOpaque(false); acknowledged.setForeground(Theme.TEXT); acknowledged.setFont(Theme.font(11, false));
        acknowledged.setAlignmentX(LEFT_ALIGNMENT); acknowledged.addActionListener(event -> updateButtons()); body.add(acknowledged);
        body.add(Box.createVerticalStrut(8)); result.setAlignmentX(LEFT_ALIGNMENT); body.add(result);
        JScrollPane bodyScroll = new JScrollPane(body); Theme.scroll(bodyScroll); bodyScroll.setName("credentials-scroll");
        bodyScroll.setOpaque(false); bodyScroll.getViewport().setOpaque(false); add(bodyScroll);
        JPanel buttons = new JPanel(new BorderLayout(10, 0)); buttons.setOpaque(false);
        removeAll.addActionListener(event -> actions.delete("all")); buttons.add(removeAll, BorderLayout.WEST);
        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0)); right.setOpaque(false);
        close.addActionListener(event -> actions.close()); right.add(close);
        save.addActionListener(event -> {
            if (!save.isEnabled()) return;
            Map<String, String> updates = new LinkedHashMap<>();
            fields.forEach((provider, field) -> {
                char[] value = field.getPassword();
                try { if (value.length > 0) updates.put(provider, new String(value)); }
                finally { Arrays.fill(value, '\0'); }
            });
            clearInputs(); actions.save(updates);
        });
        right.add(save); buttons.add(right, BorderLayout.EAST); add(buttons, BorderLayout.SOUTH); apply(status);
    }

    void apply(ChatService.Credentials next) {
        status = next; account.setText("当前本机账号：" + next.account()); location.setText(I18n.t("存储位置：") + next.storagePath());
        states.get("deepseek").setText(next.deepseek() ? "已保存 · 留空保持不变" : "未配置");
        states.get("openai").setText(next.openai() ? "已保存 · 留空保持不变" : "未配置");
        states.get("gemini").setText(next.gemini() ? "已保存 · 留空保持不变" : "未配置");
        states.get("claude").setText(next.claude() ? "已保存 · 留空保持不变" : "未配置");
        clearInputs(); updateButtons();
    }
    void clearInputs() { fields.values().forEach(field -> field.setText("")); acknowledged.setSelected(false); updateButtons(); }
    void setWorking(boolean value) { working = value; fields.values().forEach(field -> field.setEnabled(!value)); updateButtons(); }
    void message(String text, boolean error) { result.setText(text); result.setForeground(error ? Theme.ERROR : Theme.MINT); }
    private void updateButtons() {
        boolean entered = fields.values().stream().anyMatch(field -> field.getDocument().getLength() > 0);
        save.setEnabled(!working && acknowledged.isSelected() && entered); close.setEnabled(!working);
        if (status != null) {
            deletes.get("deepseek").setEnabled(!working && status.deepseek());
            deletes.get("openai").setEnabled(!working && status.openai());
            deletes.get("gemini").setEnabled(!working && status.gemini());
            deletes.get("claude").setEnabled(!working && status.claude());
            removeAll.setEnabled(!working && (status.deepseek() || status.openai() || status.gemini() || status.claude()));
        }
    }
}
