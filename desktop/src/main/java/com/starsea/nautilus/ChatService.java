package com.starsea.nautilus;

import java.io.IOException;
import java.util.List;
import java.util.Map;

interface ChatService extends AutoCloseable {
    record Model(String id, String name, String subtitle, String provider, boolean configured) {}
    record Selection(String modelId, String model, String provider, boolean search) {}
    record Progress(String stepId, String role, String model, String status, String message, long elapsedMs, Integer nodeCount, String roleTitle) {
        Progress(String stepId, String role, String model, String status, String message, long elapsedMs, Integer nodeCount) {
            this(stepId, role, model, status, message, elapsedMs, nodeCount, "");
        }
    }
    enum MixtureStage {
        PLANNER("planner", "规划"), WORKER("worker", "执行"), REVIEWER("reviewer", "复核"), SUMMARIZER("summarizer", "汇总");
        final String id, label;
        MixtureStage(String id, String label) { this.id = id; this.label = label; }
        @Override public String toString() { return I18n.t(label); }
    }
    record MixtureMember(String modelId, MixtureStage stage, String role, String goal) {}
    record MixtureTeam(List<MixtureMember> members, boolean allowRoleFallback) {
        public MixtureTeam { members = List.copyOf(members); }
        static MixtureTeam suggested(List<String> models) {
            java.util.ArrayList<MixtureMember> members = new java.util.ArrayList<>();
            for (int i = 0; i < models.size(); i++) {
                String model = switch (models.get(i)) { case "deepseek" -> "deepseek-flash"; case "gemini" -> "gemini-argon"; default -> models.get(i); };
                MixtureStage stage = i == models.size() - 1 ? MixtureStage.SUMMARIZER : i == 0 ? MixtureStage.PLANNER : MixtureStage.WORKER;
                members.add(suggestedMember(model, stage));
            }
            return new MixtureTeam(members, false);
        }
        static MixtureMember suggestedMember(String model, MixtureStage stage) {
            return switch (stage) {
                case PLANNER -> new MixtureMember(model, stage, I18n.t("方案设计"), I18n.t("拆解需求与约束，形成可执行方案或初稿。"));
                case WORKER -> new MixtureMember(model, stage, I18n.t("执行与复核"), I18n.t("执行分配的子任务，补充实现与验证建议。"));
                case REVIEWER -> new MixtureMember(model, stage, I18n.t("独立复核"), I18n.t("检查漏洞、边界与分歧，补充修正和验证建议。"));
                case SUMMARIZER -> new MixtureMember(model, stage, I18n.t("综合交付"), I18n.t("核实多方意见，整合成完整答案、代码或文稿。"));
            };
        }
    }
    record Reply(String text, String model, String provider, boolean complete) {
        Reply(String text,String model,String provider){this(text,model,provider,true);}
    }
    record Credentials(String storagePath, String account, boolean deepseek, boolean openai, boolean gemini, boolean claude) {
        Credentials(String storagePath, String account, boolean deepseek, boolean openai, boolean gemini) {
            this(storagePath, account, deepseek, openai, gemini, false);
        }
        boolean has(String provider) { return switch (provider) { case "DeepSeek" -> deepseek; case "OpenAI" -> openai; case "Gemini" -> gemini; case "Claude" -> claude; default -> false; }; }
    }

    default void setProgressListener(java.util.function.Consumer<Progress> listener) {}
    default void setNoticeListener(java.util.function.Consumer<String> listener) {}

    List<Model> initialize() throws IOException;
    Selection select(String id) throws IOException;
    default Selection configureMixture(List<String> models) throws IOException { return select("mixture"); }
    default Selection configureMixture(MixtureTeam team) throws IOException { throw new IOException("当前聊天服务不支持自定义协作角色"); }
    Reply chat(String message) throws IOException;
    void reset() throws IOException;
    Credentials credentials() throws IOException;
    Credentials saveCredentials(Map<String, String> updates, boolean acknowledged) throws IOException;
    Credentials deleteCredentials(String provider) throws IOException;
    @Override void close();
}
