package com.darkrich.blog.module.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import java.time.LocalDateTime;
import java.util.UUID;
import static org.assertj.core.api.Assertions.*;

/** Test only the new assistant schema; never touch the developer's databases. */
class JdbcAssistantRunRepositoryTest {
    JdbcAssistantRunRepository repo;
    JdbcTemplate jdbc;
    ObjectMapper json = new ObjectMapper();
    @BeforeEach void createIsolatedDatabase() throws Exception {
        var source = new DriverManagerDataSource("jdbc:h2:mem:assistant-" + UUID.randomUUID() + ";MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", "");
        try (var connection = source.getConnection()) {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource("db/migration/V4__assistant_runs.sql"));
        }
        jdbc = new JdbcTemplate(source); repo = new JdbcAssistantRunRepository(jdbc, json);
    }
    AssistantRunRepository.Run run(LocalDateTime expiry) {
        return new AssistantRunRepository.Run(UUID.randomUUID().toString(), "a".repeat(64), "RUNNING", "zh", "/articles", "你好", "[]", null, expiry);
    }
    @Test void persistsAWaitingCheckpointAndClaimsItOnlyOnce() {
        var r = run(LocalDateTime.now().plusHours(1)); repo.create(r);
        repo.waitForDecision(r.id(), "[{\"role\":\"assistant\"}]", "{\"id\":\"approval\"}");
        var stored = new JdbcAssistantRunRepository(jdbc, json).find(r.id());
        assertThat(stored.status()).isEqualTo("WAITING");
        assertThat(stored.pendingJson()).contains("approval");
        assertThat(repo.claimDecision(r.id())).isTrue();
        assertThat(repo.claimDecision(r.id())).isFalse();
        assertThat(repo.find(r.id()).status()).isEqualTo("RUNNING");
    }
    @Test void replaysOnlyEventsAfterTheCursorInOriginalOrder() {
        var r = run(LocalDateTime.now().plusHours(1)); repo.create(r);
        repo.append(r.id(), new AssistantRunRepository.Event(1, "meta", json.createObjectNode().put("runId", r.id())));
        repo.append(r.id(), new AssistantRunRepository.Event(2, "text_delta", json.createObjectNode().put("text", "你好")));
        repo.append(r.id(), new AssistantRunRepository.Event(3, "done", json.createObjectNode().put("status", "COMPLETED")));
        assertThat(repo.events(r.id(), 1)).extracting(AssistantRunRepository.Event::sequence).containsExactly(2L, 3L);
        assertThat(repo.events(r.id(), 1).get(0).data().path("text").asText()).isEqualTo("你好");
    }
    @Test void expirationDeletesOnlyExpiredRunsAndTheirEvents() {
        var old = run(LocalDateTime.now().minusHours(1)); var fresh = run(LocalDateTime.now().plusHours(1));
        repo.create(old); repo.create(fresh);
        repo.append(old.id(), new AssistantRunRepository.Event(1, "meta", json.createObjectNode()));
        repo.deleteExpired();
        assertThat(repo.find(old.id())).isNull(); assertThat(repo.find(fresh.id())).isNotNull();
        assertThat(repo.events(old.id(), 0)).isEmpty();
    }
}
