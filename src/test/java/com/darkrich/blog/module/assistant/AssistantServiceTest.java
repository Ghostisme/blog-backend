package com.darkrich.blog.module.assistant;

import com.darkrich.blog.common.BusinessException;
import com.darkrich.blog.module.article.ArticleQueryService;
import com.darkrich.blog.module.resume.ResumeService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import static org.assertj.core.api.Assertions.*;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AssistantServiceTest {
    final ObjectMapper json = new ObjectMapper();
    final MemoryRepository repo = new MemoryRepository();
    final AssistantProperties config = new AssistantProperties(true, "http://test/v1", "key", "model", 12);
    final AssistantModelClient model = mock(AssistantModelClient.class);
    final AssistantTools tools = new AssistantTools(mock(ArticleQueryService.class), mock(ResumeService.class), json, repo);
    final AssistantService service = new AssistantService(config, repo, model, tools, new AssistantRateLimiter(config), json);
    final String token = "a".repeat(64);
    @AfterEach void close() { service.shutdown(); }
    String begin(String text) {
        service.chat(token, "127.0.0.1", new AssistantRequest(text, "zh", "/", List.of()));
        return repo.runs.keySet().stream().findFirst().orElseThrow();
    }
    AssistantModelClient.Reply reply(String text) {
        return new AssistantModelClient.Reply(json.createObjectNode().put("role", "assistant").put("content", text), List.of());
    }
    AssistantModelClient.Reply tool(String name, String args) throws Exception {
        JsonNode arguments = json.readTree(args);
        var msg = json.createObjectNode().put("role", "assistant");
        msg.set("tool_calls", json.createArrayNode().add(json.createObjectNode().put("id", "call-" + name)
                .put("type", "function").set("function", json.createObjectNode().put("name", name).put("arguments", args))));
        return new AssistantModelClient.Reply(msg, List.of(new AssistantModelClient.ToolCall("call-" + name, name, arguments)));
    }
    void status(String id, String expected) {
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> {
            assertThat(repo.find(id).status()).isEqualTo(expected);
            assertThat(repo.events(id, 0)).anyMatch(e -> e.type().equals("done") && e.data().path("status").asText().equals(expected));
        });
    }
    @Test void ordinaryConversationDoesNotRequireHumanApproval() {
        when(model.complete(any(), any(), any(), any())).thenAnswer(invocation -> {
            invocation.<Consumer<String>>getArgument(1).accept("你好，欢迎来聊技术。"); return reply("你好，欢迎来聊技术。");
        });
        String id = begin("你好"); status(id, "COMPLETED");
        assertThat(repo.events(id, 0)).extracting(AssistantRunRepository.Event::type).contains("text_delta", "done").doesNotContain("approval", "navigation");
        assertThat(repo.find(id).pendingJson()).isNull();
    }
    @Test void pausesAndResumesExactlyOnceAfterARealHumanDecision() throws Exception {
        when(model.complete(any(), any(), any(), any())).thenReturn(
                tool("ask_user", "{\"question\":\"想看哪个方向？\",\"options\":[{\"label\":\"前端\"},{\"label\":\"后端\"}]}"), reply("已选择前端"));
        String id = begin("帮我选择一篇文章"); status(id, "WAITING");
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(repo.events(id, 0)).anyMatch(e -> e.type().equals("done")));
        JsonNode pending = json.readTree(repo.find(id).pendingJson());
        assertThat(repo.events(id, 0)).noneMatch(e -> e.type().equals("navigation"));
        var choice = new AssistantRequest.Decision(pending.path("id").asText(), "choice-0");
        service.resume(token, "127.0.0.1", id, choice); status(id, "COMPLETED");
        assertThatThrownBy(() -> service.resume(token, "127.0.0.1", id, choice)).isInstanceOf(BusinessException.class);
        assertThat(repo.events(id, 0)).filteredOn(e -> e.type().equals("decision")).hasSize(1);
        assertThat(repo.find(id).contextJson()).contains("前端", "tool_call_id");
    }
    @Test void verifiesOwnershipBeforeReplayOrApproval() throws Exception {
        when(model.complete(any(), any(), any(), any())).thenReturn(tool("ask_user", "{\"question\":\"哪个？\",\"options\":[{\"label\":\"A\"},{\"label\":\"B\"}]}"));
        String id = begin("选择"); status(id, "WAITING");
        assertThatThrownBy(() -> service.replay("b".repeat(64), id, 0)).isInstanceOf(BusinessException.class).hasMessageContaining("过期");
        assertThatThrownBy(() -> service.resume("b".repeat(64), "ip", id, new AssistantRequest.Decision("x", "choice-0"))).isInstanceOf(BusinessException.class);
        JsonNode pending = json.readTree(repo.find(id).pendingJson());
        assertThatThrownBy(() -> service.resume(token, "ip", id, new AssistantRequest.Decision(pending.path("id").asText(), "forged"))).isInstanceOf(BusinessException.class);
        assertThat(repo.find(id).status()).isEqualTo("WAITING");
    }
    @Test void explicitPublicNavigationRunsWithoutUnnecessaryApproval() throws Exception {
        when(model.complete(any(), any(), any(), any())).thenReturn(tool("navigate", "{\"path\":\"/resume\",\"label\":\"简历\"}"), reply("已打开"));
        String id = begin("打开作者简历"); status(id, "COMPLETED");
        assertThat(repo.events(id, 0)).filteredOn(e -> e.type().equals("navigation")).hasSize(1);
        assertThat(repo.events(id, 0)).noneMatch(e -> e.type().equals("approval"));
    }
    @Test void ordinaryQuestionDoesNotMoveTheReaderEvenIfTheModelRequestsNavigation() throws Exception {
        when(model.complete(any(), any(), any(), any())).thenReturn(tool("navigate", "{\"path\":\"/resume\",\"label\":\"简历\"}"), reply("你可以查看链接"));
        String id = begin("介绍一下作者"); status(id, "COMPLETED");
        assertThat(repo.events(id, 0)).noneMatch(e -> e.type().equals("navigation"));
        assertThat(repo.events(id, 0)).anyMatch(e -> e.type().equals("block"));
    }
    @Test void aDocumentDownloadOccursOnlyAfterConfirmation() throws Exception {
        when(model.complete(any(), any(), any(), any())).thenAnswer(invocation -> {
            ArrayNode messages = invocation.getArgument(0);
            if (messages.size() == 2) return tool("create_document", "{\"title\":\"阅读笔记\",\"content\":\"# 阅读笔记\\n测试内容\"}");
            JsonNode last = messages.get(messages.size() - 1);
            JsonNode result = json.readTree(last.path("content").asText("{}"));
            if (result.has("documentId")) return tool("request_download", "{\"documentId\":\"" + result.path("documentId").asText() + "\"}");
            return reply("下载已确认");
        });
        String id = begin("生成并下载阅读笔记"); status(id, "WAITING");
        assertThat(repo.events(id, 0)).noneMatch(e -> e.type().equals("action"));
        JsonNode pending = json.readTree(repo.find(id).pendingJson());
        service.resume(token, "ip", id, new AssistantRequest.Decision(pending.path("id").asText(), "approve")); status(id, "COMPLETED");
        assertThat(repo.events(id, 0)).filteredOn(e -> e.type().equals("action")).hasSize(1);
    }
    @Test void stoppingAReplyTerminatesTheStreamOnce() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        when(model.complete(any(), any(), any(), any())).thenAnswer(invocation -> {
            started.countDown(); new CountDownLatch(1).await(10, TimeUnit.SECONDS); return reply("late");
        });
        String id = begin("hello"); assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
        service.cancel(token, id); status(id, "CANCELLED");
        await().atMost(Duration.ofSeconds(3)).untilAsserted(() -> assertThat(repo.events(id, 0)).filteredOn(e -> e.type().equals("done")).hasSize(1));
    }
    @Test void cancelledDownloadCannotBeRequestedAgainByTheModel() throws Exception {
        when(model.complete(any(), any(), any(), any())).thenAnswer(invocation -> {
            ArrayNode messages = invocation.getArgument(0);
            if (messages.size() == 2) return tool("create_document", "{\"title\":\"笔记\",\"content\":\"# 笔记\"}");
            JsonNode result = json.readTree(messages.get(messages.size() - 1).path("content").asText("{}"));
            if (result.has("documentId")) return tool("request_download", "{\"documentId\":\"" + result.path("documentId").asText() + "\"}");
            return reply("保留预览");
        });
        String id = begin("生成一份文档"); status(id, "WAITING");
        JsonNode pending = json.readTree(repo.find(id).pendingJson());
        service.resume(token, "ip", id, new AssistantRequest.Decision(pending.path("id").asText(), "cancel")); status(id, "COMPLETED");
        assertThat(repo.events(id, 0)).filteredOn(e -> e.type().equals("approval")).hasSize(1);
        assertThat(repo.events(id, 0)).noneMatch(e -> e.type().equals("action"));
        assertThat(repo.find(id).contextJson()).contains("alreadyDecided");
    }
    @Test void startingOverCanCancelADurableWaitingDecision() throws Exception {
        when(model.complete(any(), any(), any(), any())).thenReturn(tool("ask_user", "{\"question\":\"哪个？\",\"options\":[{\"label\":\"A\"},{\"label\":\"B\"}]}"));
        String id = begin("选择"); status(id, "WAITING");
        service.cancel(token, id); status(id, "CANCELLED");
        assertThat(repo.find(id).pendingJson()).isNull();
        assertThatThrownBy(() -> service.resume(token, "ip", id, new AssistantRequest.Decision("old", "choice-0")))
                .isInstanceOf(BusinessException.class);
    }
    @Test void failedStartupReleasesTheSessionAndWorkerCapacity() {
        AssistantRunRepository faulty = spy(repo);
        doThrow(new IllegalStateException("temporary write failure")).doCallRealMethod().when(faulty).append(anyString(), any());
        AssistantService recovering = new AssistantService(config, faulty, model, tools, new AssistantRateLimiter(config), json);
        try {
            assertThatThrownBy(() -> recovering.chat(token, "ip", new AssistantRequest("first", "zh", "/", List.of())))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(repo.runs.values()).allMatch(r -> r.status().equals("FAILED"));
            when(model.complete(any(), any(), any(), any())).thenReturn(reply("recovered"));
            recovering.chat(token, "ip", new AssistantRequest("second", "zh", "/", List.of()));
            String id = repo.runs.values().stream().filter(r -> r.message().equals("second")).findFirst().orElseThrow().id();
            status(id, "COMPLETED");
        } finally { recovering.shutdown(); }
    }
    @Test void waitingDecisionSurvivesAServiceRestart() throws Exception {
        when(model.complete(any(), any(), any(), any())).thenReturn(tool("ask_user", "{\"question\":\"选择领域\",\"options\":[{\"label\":\"前端\"},{\"label\":\"后端\"}]}"));
        String id = begin("选择"); status(id, "WAITING");
        service.shutdown();
        AssistantModelClient nextModel = mock(AssistantModelClient.class);
        when(nextModel.complete(any(), any(), any(), any())).thenReturn(reply("继续完成"));
        AssistantService restarted = new AssistantService(config, repo, nextModel, tools, new AssistantRateLimiter(config), json);
        try {
            JsonNode pending = json.readTree(repo.find(id).pendingJson());
            restarted.resume(token, "ip", id, new AssistantRequest.Decision(pending.path("id").asText(), "choice-1")); status(id, "COMPLETED");
        } finally { restarted.shutdown(); }
    }
    @Test void rejectsNavigationOutsideThePublicSite() {
        for (String path : List.of("https://other.test", "//other.test", "/admin", "/articles/../admin", "/resume?redirect=x", "/articles/%2fadmin"))
            assertThatThrownBy(() -> AssistantNavigation.requirePublicPath(path)).isInstanceOf(BusinessException.class);
    }

    static class MemoryRepository implements AssistantRunRepository {
        final Map<String, Run> runs = new ConcurrentHashMap<>();
        final Map<String, CopyOnWriteArrayList<Event>> events = new ConcurrentHashMap<>();
        @Override public void create(Run r) { runs.put(r.id(), r); events.put(r.id(), new CopyOnWriteArrayList<>()); }
        @Override public Run find(String id) { return runs.get(id); }
        void replace(String id, String status, String context, String pending) {
            runs.compute(id, (key, r) -> new Run(r.id(), r.ownerHash(), status, r.lang(), r.pagePath(), r.message(), context, pending, r.expiresAt()));
        }
        @Override public void context(String id, String value) { Run r = find(id); replace(id, r.status(), value, r.pendingJson()); }
        @Override public void status(String id, String value) { replace(id, value, find(id).contextJson(), null); }
        @Override public void waitForDecision(String id, String context, String pending) { replace(id, "WAITING", context, pending); }
        @Override public synchronized boolean claimDecision(String id) {
            if (!find(id).status().equals("WAITING")) return false;
            status(id, "RUNNING"); return true;
        }
        @Override public void append(String id, Event event) { events.get(id).add(event); }
        @Override public List<Event> events(String id, long after) { return events.get(id).stream().filter(e -> e.sequence() > after).toList(); }
        @Override public void deleteExpired() {}
    }
}
