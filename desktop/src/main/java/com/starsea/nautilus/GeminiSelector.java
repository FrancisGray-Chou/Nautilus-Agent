package com.starsea.nautilus;

import java.util.List;
import java.util.function.Consumer;

/** Gemini presets share the same animated disclosure as DeepSeek. */
@SuppressWarnings("serial")
final class GeminiSelector extends ModelSelector {
    GeminiSelector(List<ChatService.Model> options, Consumer<String> select) {
        super("Gemini", "gemini", options, select);
    }
}
