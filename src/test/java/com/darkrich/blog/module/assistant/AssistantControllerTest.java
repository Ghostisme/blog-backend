package com.darkrich.blog.module.assistant;

import com.darkrich.blog.common.GlobalExceptionHandler;
import com.darkrich.blog.module.article.ArticleQueryService;
import com.darkrich.blog.module.resume.ResumeService;
import com.darkrich.blog.security.JsonSecurityHandlers;
import com.darkrich.blog.security.SameOriginFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Consumer;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** Real controller, async SSE serialization and origin checks without a production database or provider. */
class AssistantControllerTest {
    final ObjectMapper json = new ObjectMapper();
    final AssistantProperties config = new AssistantProperties(true, "http://test/v1", "server-secret", "model", 12);
    final AssistantServiceTest.MemoryRepository repo = new AssistantServiceTest.MemoryRepository();
    final AssistantModelClient model = mock(AssistantModelClient.class);
    final AssistantTools tools = new AssistantTools(mock(ArticleQueryService.class), mock(ResumeService.class), json, repo);
    final AssistantService service = new AssistantService(config, repo, model, tools, new AssistantRateLimiter(config), json);
    MockMvc mvc;
    @BeforeEach void setup() {
        mvc = MockMvcBuilders.standaloneSetup(new AssistantController(config, service))
                .setControllerAdvice(new GlobalExceptionHandler())
                .addFilters(new SameOriginFilter(new JsonSecurityHandlers(json))).build();
    }
    @AfterEach void close() { service.shutdown(); }
    @Test void publicConfigurationNeverExposesProviderCredentials() throws Exception {
        var response = mvc.perform(get("/api/assistant/config"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.available").value(true)).andReturn();
        assertThat(response.getResponse().getContentAsString()).doesNotContain("server-secret", "baseUrl", "apiKey");
    }
    @Test void successfulReplyHasOrderedJsonSseAndNoProxyBuffering() throws Exception {
        when(model.complete(any(), any(), any(), any())).thenAnswer(invocation -> {
            invocation.<Consumer<String>>getArgument(1).accept("你好，小R在线。");
            return new AssistantModelClient.Reply(json.createObjectNode().put("role", "assistant").put("content", "你好，小R在线。"), List.of());
        });
        var result = mvc.perform(post("/api/assistant/chat").header("X-R-Session", "a".repeat(64))
                        .accept(MediaType.TEXT_EVENT_STREAM).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"你好\",\"lang\":\"zh\"}"))
                .andExpect(request().asyncStarted()).andExpect(header().string("X-Accel-Buffering", "no"))
                .andExpect(header().string("Cache-Control", "no-store")).andReturn();
        result.getAsyncResult(3000);
        var response = mvc.perform(asyncDispatch(result)).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)).andReturn();
        String stream = response.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertThat(stream).contains("event:meta", "event:text_delta", "你好，小R在线。", "event:done", "COMPLETED");
        assertThat(stream.indexOf("event:meta")).isLessThan(stream.indexOf("event:text_delta"));
        assertThat(stream.indexOf("event:text_delta")).isLessThan(stream.indexOf("event:done"));
    }
    @Test void invalidChatReturnsJsonEvenWhenTheClientAcceptsSse() throws Exception {
        mvc.perform(post("/api/assistant/chat").header("X-R-Session", "a".repeat(64))
                        .accept(MediaType.TEXT_EVENT_STREAM).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"message\":\"\"}"))
                .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value(400));
        verifyNoInteractions(model);
    }
    @Test void missingSessionIsAReadableBadRequest() throws Exception {
        mvc.perform(post("/api/assistant/chat").accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"你好\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("助手会话标识不合法"));
        verifyNoInteractions(model);
    }
    @Test void crossOriginCannotStartAPaidModelRequest() throws Exception {
        mvc.perform(post("/api/assistant/chat").header("Host", "blog.test").header("Origin", "https://other.test")
                        .header("X-R-Session", "a".repeat(64)).accept(MediaType.TEXT_EVENT_STREAM)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"message\":\"你好\"}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value(403));
        verifyNoInteractions(model);
        assertThat(repo.runs).isEmpty();
    }
}
