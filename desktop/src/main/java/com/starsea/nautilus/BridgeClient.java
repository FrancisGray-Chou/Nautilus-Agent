package com.starsea.nautilus;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;

/** Owns one backend process and its in-memory conversation. Only called by UI workers. */
final class BridgeClient implements ChatService {
    private volatile java.util.function.Consumer<String> noticeListener = message -> {};
    @Override public void setNoticeListener(java.util.function.Consumer<String> listener) { noticeListener = listener; }
    private volatile java.util.function.Consumer<Progress> progressListener = progress -> {};
    @Override public void setProgressListener(java.util.function.Consumer<Progress> listener) { progressListener = listener; }
    private String baseUrl, sessionToken;
    private volatile java.net.HttpURLConnection activeConnection;
    String transportName() { return baseUrl == null ? "stdio" : "sse"; }
    private final Path root;
    private volatile Process process;
    private BufferedReader input;
    private BufferedWriter output;

    BridgeClient(Path root) { this.root = root.toAbsolutePath().normalize(); }

    @Override public synchronized List<Model> initialize() throws IOException {
        if (process != null) throw new IOException("聊天服务已经启动");
        Path backend = PlatformPaths.backend(root, System.getProperty("os.name"));
        boolean sse = !"stdio".equals(System.getProperty("nautilus.transport"));
        ProcessBuilder builder = new ProcessBuilder(sse ? List.of(backend.toString(),"--sse") : List.of(backend.toString())).directory(root.toFile()).redirectError(ProcessBuilder.Redirect.INHERIT);
        byte[] secret=new byte[32];new java.security.SecureRandom().nextBytes(secret);
        sessionToken=java.util.HexFormat.of().formatHex(secret);builder.environment().put("NAUTILUS_BRIDGE_TOKEN",sessionToken);
        process = builder.start();
        input = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
        output = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        Map<String, Object> ready = read("ready");
        if ("sse".equals(ready.get("transport"))) {
            baseUrl = text(ready,"base_url");
            if (!baseUrl.matches("http://127\\.0\\.0\\.1:[0-9]{1,5}")) throw new IOException("Invalid local SSE endpoint");
        }
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

    @Override public synchronized WebSearch webSearch() throws IOException {
        return searchState(request(Map.of("type", "get_web_search"), "web_search"));
    }
    @Override public synchronized WebSearch setWebSearch(boolean enabled) throws IOException {
        return searchState(request(Map.of("type", "set_web_search", "enabled", enabled), "web_search"));
    }
    private static WebSearch searchState(Map<String, Object> response) throws IOException {
        if (!(response.get("search") instanceof Map<?, ?> state)
                || !(state.get("enabled") instanceof Boolean enabled)
                || !(state.get("available") instanceof Boolean available)
                || !(state.get("default_enabled") instanceof Boolean defaultEnabled)) throw new IOException("联网搜索状态响应无效");
        return new WebSearch(enabled, available, defaultEnabled);
    }
    private static Selection selection(Map<String, Object> response) throws IOException {
        return new Selection(text(response,"model_id"), text(response,"model"), text(response,"provider"),
                Boolean.TRUE.equals(response.get("web_search")), searchState(response));
    }

    @Override public synchronized Selection select(String id) throws IOException {
        Map<String, Object> selected = request(Map.of("type", "select_model", "model_id", id), "selected");
        return selection(selected);
    }

    @Override public synchronized Selection configureMixture(List<String> models) throws IOException {
        Map<String, Object> selected = request(Map.of("type", "configure_mixture", "models", models), "selected");
        return selection(selected);
    }

    @Override public synchronized Selection configureMixture(MixtureTeam team) throws IOException {
        List<Map<String, Object>> members = team.members().stream().map(member -> Map.<String, Object>of(
            "model_id", member.modelId(), "stage", member.stage().id, "role", member.role(), "goal", member.goal())).toList();
        Map<String, Object> selected = request(Map.of("type", "configure_mixture", "members", members,
            "allow_role_fallback", team.allowRoleFallback()), "selected");
        return selection(selected);
    }

    @Override public synchronized Reply chat(String message) throws IOException {
        Map<String, Object> reply = request(Map.of("type", "chat", "message", message), "reply");
        return new Reply(text(reply, "text"), text(reply, "model"), text(reply, "provider"), !Boolean.FALSE.equals(reply.get("complete")));
    }

    @Override public synchronized Selection restoreConversation(String modelId,List<Map<String,String>> single,List<Map<String,String>> mixture,MixtureTeam team) throws IOException {
        Map<String,Object> command=new LinkedHashMap<>();command.put("type","restore_conversation");command.put("model_id",modelId);command.put("single",single);command.put("mixture",mixture);
        command.put("members",team==null?null:team.members().stream().map(m->Map.of("model_id",m.modelId(),"stage",m.stage().id,"role",m.role(),"goal",m.goal())).toList());
        command.put("allow_role_fallback",team!=null&&team.allowRoleFallback());
        Map<String,Object> response=request(command,"restored");
        return response.get("model_id")==null?null:selection(response);
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
                Boolean.TRUE.equals(values.get("deepseek")), Boolean.TRUE.equals(values.get("openai")), Boolean.TRUE.equals(values.get("gemini")), Boolean.TRUE.equals(values.get("claude")));
    }

    private Map<String, Object> request(Map<String, Object> command, String expected) throws IOException {
        if (process == null || !process.isAlive()) throw new IOException("聊天服务已停止，请重新打开应用");
        if (baseUrl != null) return httpRequest(command,expected);
        output.write(Json.encode(command));
        output.newLine();
        output.flush();
        return read(expected);
    }

    private Map<String, Object> read(String expected) throws IOException {
        String line = input.readLine();
        while (line != null) {
            Map<String, Object> event = Json.object(line);
            if (!dispatch(event)) break;
            line = input.readLine();
        }
        if (line == null) throw new IOException("聊天服务连接已关闭，请重新打开应用");
        Map<String, Object> response = Json.object(line);
        if ("error".equals(response.get("type"))) throw new IOException(text(response, "message"));
        if (!expected.equals(response.get("type"))) throw new IOException("聊天服务返回了意外响应");
        return response;
    }

    private boolean dispatch(Map<String,Object> event) throws IOException {
        if ("notice".equals(event.get("type"))) { noticeListener.accept(text(event,"message"));return true; }
        if ("progress".equals(event.get("type"))) {
            if (!(event.get("progress") instanceof Map<?,?> p)) throw new IOException("Invalid progress event");
            progressListener.accept(new Progress(text(p,"step_id"),text(p,"role"),text(p,"model"),text(p,"status"),text(p,"message"),
                p.get("elapsed_ms") instanceof Number n ? Math.max(0,n.longValue()) : 0,
                p.get("node_count") instanceof Number n ? n.intValue() : null,
                p.get("role_title") instanceof String title ? title : ""));return true;
        }
        return false;
    }
    private Map<String,Object> httpRequest(Map<String,Object> command,String expected) throws IOException {
        java.net.HttpURLConnection connection=(java.net.HttpURLConnection)java.net.URI.create(baseUrl+"/command").toURL().openConnection(java.net.Proxy.NO_PROXY);
        activeConnection=connection;
        try {
            connection.setConnectTimeout(5000);connection.setReadTimeout(240000);connection.setRequestMethod("POST");connection.setDoOutput(true);
            connection.setRequestProperty("Authorization","Bearer "+sessionToken);connection.setRequestProperty("Content-Type","application/json; charset=utf-8");
            connection.setRequestProperty("Accept","text/event-stream");
            byte[] payload=Json.encode(command).getBytes(StandardCharsets.UTF_8);connection.setFixedLengthStreamingMode(payload.length);
            try(java.io.OutputStream stream=connection.getOutputStream()){stream.write(payload);}
            if(connection.getResponseCode()!=200 || connection.getContentType()==null || !connection.getContentType().startsWith("text/event-stream"))throw new IOException("Local SSE connection failed");
            try(BufferedReader reader=new BufferedReader(new InputStreamReader(connection.getInputStream(),StandardCharsets.UTF_8))) {
                for(SseReader.Event event;(event=SseReader.next(reader))!=null;) {
                    Map<String,Object> value=event.data();if(dispatch(value))continue;
                    if("error".equals(value.get("type")))throw new IOException(text(value,"message"));
                    if(!"result".equals(event.name()) || !expected.equals(value.get("type")))throw new IOException("Unexpected SSE response");
                    return value;
                }
                throw new IOException("SSE stream ended before the final response");
            }
        } finally {connection.disconnect();activeConnection=null;}
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
        // Closing the process first releases any reader waiting on the SSE socket.
        java.net.HttpURLConnection connection=activeConnection;if(connection!=null)connection.disconnect();
    }
}
