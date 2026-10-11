package com.darkrich.blog.module.assistant;

import com.darkrich.blog.common.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import java.io.BufferedReader;
import java.io.Closeable;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Streams content and fragmented function arguments from an OpenAI-compatible provider. */
@Slf4j
@Component
public class AssistantModelClient {
    public record ToolCall(String id, String name, JsonNode arguments) {}
    public record Reply(ObjectNode message, List<ToolCall> calls) {}
    public static final class Control {
        private final AtomicBoolean cancelled = new AtomicBoolean();
        private volatile Closeable upstream;
        public void check() {
            if (cancelled.get() || Thread.currentThread().isInterrupted()) throw new CancellationException();
        }
        public void bind(Closeable value) {
            upstream = value;
            if (cancelled.get()) close();
        }
        public void cancel() { cancelled.set(true); close(); }
        private void close() { try { if (upstream != null) upstream.close(); } catch (Exception ignored) {} }
    }
    private static final class Fragment {
        String id = "";
        String name = "";
        final StringBuilder arguments = new StringBuilder();
    }
    private final AssistantProperties properties;
    private final ObjectMapper json;
    private final AssistantTools tools;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public AssistantModelClient(AssistantProperties properties, ObjectMapper json, AssistantTools tools) {
        this.properties = properties; this.json = json; this.tools = tools;
    }
    public Reply complete(ArrayNode messages, Consumer<String> onText, Consumer<String> onTool, Control control) {
        control.check();
        try {
            ObjectNode body = json.createObjectNode().put("model", properties.model())
                    .put("stream", true).put("temperature", 0.35).put("max_tokens", 3000);
            body.set("messages", messages); body.set("tools", tools.definitions());
            HttpRequest request = HttpRequest.newBuilder(URI.create(properties.baseUrl().replaceAll("/+$", "") + "/chat/completions"))
                    .timeout(Duration.ofSeconds(150)).header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + properties.apiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            control.bind(response.body());
            try (var stream = response.body()) {
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    log.warn("小R模型接口返回 HTTP {}，model={}", response.statusCode(), properties.model());
                    throw new BusinessException(HttpStatus.BAD_GATEWAY,
                            "模型服务暂时不可用（HTTP " + response.statusCode() + "），请稍后重试");
                }
                String contentType = response.headers().firstValue("content-type").orElse("");
                if (!contentType.contains("text/event-stream")) {
                    byte[] bytes = stream.readNBytes(100_001);
                    if (bytes.length > 100_000) throw new IllegalStateException("模型结果过长");
                    JsonNode root = json.readTree(bytes).path("choices").path(0).path("message");
                    if (!root.isObject()) throw new IllegalStateException("模型返回格式不正确");
                    String text = root.path("content").asText("");
                    if (text.length() > 24000) throw new IllegalStateException("模型回答过长");
                    if (text.isBlank() && root.path("tool_calls").isEmpty()) throw new IllegalStateException("模型返回空结果");
                    if (!text.isEmpty()) onText.accept(text);
                    ObjectNode normalized = ((ObjectNode) root).deepCopy();
                    normalized.put("role", "assistant");
                    return parseMessage(normalized);
                }
                StringBuilder content = new StringBuilder();
                TreeMap<Integer, Fragment> fragments = new TreeMap<>();
                boolean finished = false;
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                    String line;
                    StringBuilder frame = new StringBuilder();
                    while ((line = reader.readLine()) != null) {
                        control.check();
                        if (line.length() > 100_000) throw new IllegalStateException("模型分段过长");
                        if (line.startsWith("data:")) {
                            if (!frame.isEmpty()) frame.append('\n');
                            frame.append(line.substring(5).stripLeading());
                            if (frame.length() > 100_000) throw new IllegalStateException("模型事件过长");
                        }
                        if (!line.isEmpty() || frame.isEmpty()) continue;
                        String data = frame.toString(); frame.setLength(0);
                        if (data.equals("[DONE]")) { finished = true; break; }
                        JsonNode packet = json.readTree(data);
                        if (packet.has("error")) throw new IllegalStateException("模型流返回错误");
                        JsonNode choice = packet.path("choices").path(0);
                        if (choice.hasNonNull("finish_reason")) {
                            if (choice.path("finish_reason").asText().equals("length")) throw new IllegalStateException("模型输出被截断");
                            finished = true;
                        }
                        JsonNode delta = choice.path("delta");
                        String text = delta.path("content").asText("");
                        if (!text.isEmpty()) {
                            content.append(text);
                            if (content.length() > 24000) throw new IllegalStateException("模型回答过长");
                            onText.accept(text);
                        }
                        for (JsonNode c : delta.path("tool_calls")) {
                            int index = c.path("index").asInt();
                            if (index < 0 || index > 9) throw new IllegalStateException("模型工具数量过多");
                            Fragment f = fragments.computeIfAbsent(index, k -> new Fragment());
                            if (c.hasNonNull("id")) f.id = c.path("id").asText();
                            String name = c.path("function").path("name").asText("");
                            if (!name.isEmpty()) { f.name += name; onTool.accept(f.name); }
                            f.arguments.append(c.path("function").path("arguments").asText(""));
                            if (f.arguments.length() > 40000) throw new IllegalStateException("工具参数过长");
                        }
                    }
                }
                control.check();
                if (!finished) throw new IllegalStateException("模型流连接提前中断");
                ObjectNode message = json.createObjectNode().put("role", "assistant");
                if (content.isEmpty()) message.putNull("content"); else message.put("content", content.toString());
                ArrayNode calls = json.createArrayNode();
                for (Fragment f : fragments.values()) {
                    if (f.id.isBlank() || f.name.isBlank()) throw new IllegalStateException("工具响应不完整");
                    ObjectNode fn = json.createObjectNode().put("name", f.name).put("arguments", f.arguments.toString());
                    ObjectNode call = json.createObjectNode().put("id", f.id).put("type", "function");
                    call.set("function", fn); calls.add(call);
                }
                if (!calls.isEmpty()) message.set("tool_calls", calls);
                if (content.isEmpty() && calls.isEmpty()) throw new IllegalStateException("模型返回空结果");
                return parseMessage(message);
            }
        } catch (BusinessException | CancellationException e) { throw e; }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new CancellationException(); }
        catch (Exception e) {
            control.check();
            log.warn("小R模型流失败: {}", e.getClass().getSimpleName());
            throw new BusinessException(HttpStatus.BAD_GATEWAY, "小R的回复暂时中断，请重试这一条消息");
        } finally { control.bind(null); }
    }
    private Reply parseMessage(ObjectNode message) throws Exception {
        List<ToolCall> calls = new ArrayList<>();
        for (JsonNode c : message.path("tool_calls")) {
            if (calls.size() >= 10) throw new IllegalStateException("工具数量过多");
            if (c.path("id").asText().isBlank() || c.path("function").path("name").asText().isBlank()
                    || c.path("function").path("arguments").asText().length() > 40000) throw new IllegalStateException("工具响应不完整");
            JsonNode arguments = json.readTree(c.path("function").path("arguments").asText("{}"));
            if (!arguments.isObject()) throw new IllegalStateException("工具参数格式不正确");
            calls.add(new ToolCall(c.path("id").asText(), c.path("function").path("name").asText(),
                    arguments));
        }
        return new Reply(message, calls);
    }
}
