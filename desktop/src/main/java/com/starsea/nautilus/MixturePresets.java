package com.starsea.nautilus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.starsea.nautilus.ChatService.MixtureStage;

/** Curated choices within the supported catalog, not a live price optimizer. */
final class MixturePresets {
    enum Kind {
        EXPERT("专家团队", "优先能力，适合复杂任务", List.of("deepseek-pro", "openai", "gemini-argon", "claude-fable")),
        VALUE("推荐团队", "兼顾效果与成本，适合日常任务", List.of("deepseek-flash", "openai", "gemini-flash", "claude-sonnet"));
        final String title, description;
        final List<String> models;
        Kind(String title, String description, List<String> models) {
            this.title = title; this.description = description; this.models = models;
        }
        List<String> available(Map<String, ChatService.Model> catalog) {
            return models.stream().filter(id -> catalog.containsKey(id) && catalog.get(id).configured()).toList();
        }
        ChatService.MixtureTeam team(Map<String, ChatService.Model> catalog, boolean fallback) {
            List<String> available = available(catalog);
            List<ChatService.MixtureMember> members = new ArrayList<>();
            for (int i = 0; i < available.size(); i++) {
                MixtureStage stage = i == available.size() - 1 ? MixtureStage.SUMMARIZER
                    : i == 0 ? MixtureStage.PLANNER : i == 2 ? MixtureStage.REVIEWER : MixtureStage.WORKER;
                String id = available.get(i);
                members.add(new ChatService.MixtureMember(id, stage, I18n.t(stage.label),
                    ChatService.MixtureTeam.suggestedMember(id, stage).goal()));
            }
            return new ChatService.MixtureTeam(members, fallback);
        }
    }
    private MixturePresets() {}
}
