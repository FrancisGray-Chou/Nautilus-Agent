package com.starsea.nautilus;

import java.util.List;

/** Presets for the team picker; selection IDs match the Rust model catalog. */
final class ClaudeModels {
    private ClaudeModels() {}
    static List<ChatService.Model> options(boolean configured) {
        return List.of(
            new ChatService.Model("claude-opus", "Claude Opus 5.5", "长任务与代码", "Claude", configured),
            new ChatService.Model("claude-fable", "Claude Fable 5.1", "复杂推理", "Claude", configured),
            new ChatService.Model("claude-sonnet", "Claude Sonnet 5.5", "均衡对话与协作", "Claude", configured),
            new ChatService.Model("claude-haiku", "Claude Haiku 5.5", "快速响应", "Claude", configured));
    }
}
