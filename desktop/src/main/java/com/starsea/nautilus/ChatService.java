package com.starsea.nautilus;

import java.io.IOException;
import java.util.List;
import java.util.Map;

interface ChatService extends AutoCloseable {
    record Model(String id, String name, String subtitle, String provider, boolean configured) {}
    record Selection(String modelId, String model, String provider, boolean search) {}
    record Reply(String text, String model, String provider) {}
    record Credentials(String storagePath, String account, boolean deepseek, boolean openai, boolean gemini) {
        boolean has(String provider) { return switch (provider) { case "DeepSeek" -> deepseek; case "OpenAI" -> openai; case "Gemini" -> gemini; default -> false; }; }
    }

    List<Model> initialize() throws IOException;
    Selection select(String id) throws IOException;
    Reply chat(String message) throws IOException;
    void reset() throws IOException;
    Credentials credentials() throws IOException;
    Credentials saveCredentials(Map<String, String> updates, boolean acknowledged) throws IOException;
    Credentials deleteCredentials(String provider) throws IOException;
    @Override void close();
}
