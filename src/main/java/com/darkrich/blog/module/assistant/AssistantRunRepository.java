package com.darkrich.blog.module.assistant;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDateTime;
import java.util.List;

public interface AssistantRunRepository {
    record Run(String id, String ownerHash, String status, String lang, String pagePath,
               String message, String contextJson, String pendingJson, LocalDateTime expiresAt) {}
    record Event(long sequence, String type, JsonNode data) {}
    void create(Run run);
    Run find(String id);
    void context(String id, String json);
    void status(String id, String status);
    void waitForDecision(String id, String contextJson, String pendingJson);
    boolean claimDecision(String id);
    void append(String id, Event event);
    List<Event> events(String id, long after);
    void deleteExpired();
}
