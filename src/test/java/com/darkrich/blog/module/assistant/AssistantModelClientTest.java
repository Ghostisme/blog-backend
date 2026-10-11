package com.darkrich.blog.module.assistant;

import com.darkrich.blog.common.BusinessException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class AssistantModelClientTest {
    final ObjectMapper json = new ObjectMapper();
    HttpServer server;
    @AfterEach void close() { if (server != null) server.stop(0); }
    AssistantModelClient client(String response, int code, String contentType, AtomicReference<String> request) throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", e -> {
            request.set(new String(e.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            e.getResponseHeaders().add("Content-Type", contentType);
            byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
            e.sendResponseHeaders(code, bytes.length);
            // Send byte by byte, including Chinese multibyte sequences and split event separators.
            try (var out = e.getResponseBody()) { for (byte b : bytes) { out.write(b); out.flush(); } }
        });
        server.start();
        AssistantTools tools = mock(AssistantTools.class);
        when(tools.definitions()).thenReturn(json.createArrayNode());
        return new AssistantModelClient(new AssistantProperties(true, "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "test-key", "test-model", 12), json, tools);
    }
    @Test void readsRealStreamAndReassemblesFragmentedFunctionArguments() throws Exception {
        String stream = """
                : heartbeat

                data: {"choices":[{"delta":{"content":"你好，小R"}}]}

                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"id":"call1","function":{"name":"navigate","arguments":"{\\\"path\\\":\\\"/res"}}]}}]}

                data: {"choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"ume\\\",\\\"label\\\":\\\"简历\\\"}"}}]},"finish_reason":"tool_calls"}]}

                data: [DONE]

                """;
        AtomicReference<String> request = new AtomicReference<>();
        var client = client(stream, 200, "text/event-stream", request);
        var text = new ArrayList<String>();
        var names = new ArrayList<String>();
        var reply = client.complete(json.createArrayNode(), text::add, names::add, new AssistantModelClient.Control());
        assertThat(String.join("", text)).isEqualTo("你好，小R");
        assertThat(reply.calls()).hasSize(1);
        assertThat(reply.calls().get(0).arguments().path("path").asText()).isEqualTo("/resume");
        assertThat(names).containsExactly("navigate");
        assertThat(json.readTree(request.get()).path("stream").asBoolean()).isTrue();
    }
    @Test void failsOnAnAbruptStreamInsteadOfClaimingCompletion() throws Exception {
        var client = client("data: {\"choices\":[{\"delta\":{\"content\":\"partial\"}}]}\n\n", 200, "text/event-stream", new AtomicReference<>());
        assertThatThrownBy(() -> client.complete(json.createArrayNode(), t -> {}, t -> {}, new AssistantModelClient.Control())).isInstanceOf(BusinessException.class);
    }
    @Test void reportsProviderFailureWithoutExposingProviderBodyOrKey() throws Exception {
        var client = client("secret-provider-detail", 403, "application/json", new AtomicReference<>());
        assertThatThrownBy(() -> client.complete(json.createArrayNode(), t -> {}, t -> {}, new AssistantModelClient.Control()))
                .isInstanceOf(BusinessException.class).hasMessageContaining("HTTP 403").hasMessageNotContaining("secret");
    }
    @Test void supportsProvidersThatReturnNonStreamingJson() throws Exception {
        var client = client("{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"Hello\"}}]}", 200, "application/json", new AtomicReference<>());
        var received = new ArrayList<String>();
        assertThat(client.complete(json.createArrayNode(), received::add, t -> {}, new AssistantModelClient.Control()).calls()).isEmpty();
        assertThat(received).containsExactly("Hello");
    }
    @Test void checksCancellationBeforeStartingAPaidCall() throws Exception {
        var client = client("", 200, "text/event-stream", new AtomicReference<>());
        var control = new AssistantModelClient.Control(); control.cancel();
        assertThatThrownBy(() -> client.complete(json.createArrayNode(), t -> {}, t -> {}, control)).isInstanceOf(CancellationException.class);
    }
}
