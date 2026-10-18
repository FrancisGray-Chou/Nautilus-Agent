package com.starsea.nautilus;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/** Owns one backend process and its in-memory conversation. Only called by UI workers. */
final class BridgeClient implements ChatService {
    private final Path root;
    private volatile Process process;
    private BufferedReader input;
    private BufferedWriter output;

    BridgeClient(Path root) { this.root = root.toAbsolutePath().normalize(); }

    @Override public synchronized List<Model> initialize() throws IOException {
        if (process != null) throw new IOException("聊天服务已经启动");
        String executable = System.getProperty("os.name").startsWith("Windows")
                ? "nautilus-chat-bridge.exe" : "nautilus-chat-bridge";
        Path backend = root.resolve("target/debug").resolve(executable);
        if (!Files.isRegularFile(backend)) throw new IOException("未找到聊天服务，请使用 desktop/run.sh 启动");
        process = new ProcessBuilder(backend.toString()).directory(root.toFile())
                .redirectError(ProcessBuilder.Redirect.INHERIT).start();
        input = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        output = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        Map<String, Object> ready = read("ready");
        if (!(ready.get("protocol") instanceof Number version) || version.intValue() != 2) {
            throw new IOException("聊天服务协议版本不兼容");
        }
        if (!(ready.get("models") instanceof List<?> list)) throw new IOException("模型列表缺失");
        List<Model> models = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof Map<?, ?> model)) throw new IOException("模型列表无效");
            models.add(new Model(text(model, "id"), text(model, "name"), text(model, "subtitle"),
                    text(model, "provider"), Boolean.TRUE.equals(model.get("configured"))));
        }
        return List.copyOf(models);
    }

    @Override public synchronized Selection select(String id) throws IOException {
        Map<String, Object> selected = request(Map.of("type", "select_model", "model_id", id), "selected");
        return new Selection(text(selected, "model_id"), text(selected, "model"),
                text(selected, "provider"), Boolean.TRUE.equals(selected.get("web_search")));
    }

    @Override public synchronized Reply chat(String message) throws IOException {
        Map<String, Object> reply = request(Map.of("type", "chat", "message", message), "reply");
        return new Reply(text(reply, "text"), text(reply, "model"), text(reply, "provider"));
    }

    @Override public synchronized void reset() throws IOException {
        request(Map.of("type", "reset"), "reset");
    }

    @Override public synchronized Credentials credentials() throws IOException {
        return credentialResponse(request(Map.of("type", "get_credentials"), "credentials"));
    }

    @Override public synchronized Credentials saveCredentials(Map<String, String> updates, boolean acknowledged) throws IOException {
        Map<String, Object> command = new LinkedHashMap<>();
        command.put("type", "save_credentials"); command.put("plaintext_acknowledged", acknowledged);
        command.putAll(updates);
        return credentialResponse(request(command, "credentials"));
    }

    @Override public synchronized Credentials deleteCredentials(String provider) throws IOException {
        return credentialResponse(request(Map.of("type", "delete_credentials", "provider", provider), "credentials"));
    }

    private static Credentials credentialResponse(Map<String, Object> response) throws IOException {
        if (!(response.get("credentials") instanceof Map<?, ?> values)) throw new IOException("密钥状态响应无效");
        return new Credentials(text(values, "storage_path"), text(values, "account"),
                Boolean.TRUE.equals(values.get("deepseek")), Boolean.TRUE.equals(values.get("openai")), Boolean.TRUE.equals(values.get("gemini")));
    }

    private Map<String, Object> request(Map<String, Object> command, String expected) throws IOException {
        if (process == null || !process.isAlive()) throw new IOException("聊天服务已停止，请重新打开应用");
        output.write(Json.encode(command));
        output.newLine();
        output.flush();
        return read(expected);
    }

    private Map<String, Object> read(String expected) throws IOException {
        String line = input.readLine();
        if (line == null) throw new IOException("聊天服务连接已关闭，请重新打开应用");
        Map<String, Object> response = Json.object(line);
        if ("error".equals(response.get("type"))) throw new IOException(text(response, "message"));
        if (!expected.equals(response.get("type"))) throw new IOException("聊天服务返回了意外响应");
        return response;
    }

    private static String text(Map<?, ?> map, String key) throws IOException {
        if (map.get(key) instanceof String text) return text;
        throw new IOException("响应缺少字段：" + key);
    }

    @Override public void close() {
        Process running = process;
        if (running != null) {
            running.destroy();
            if (running.isAlive()) running.destroyForcibly();
        }
    }
}
