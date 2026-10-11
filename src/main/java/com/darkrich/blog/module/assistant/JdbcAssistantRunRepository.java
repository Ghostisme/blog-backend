package com.darkrich.blog.module.assistant;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
@RequiredArgsConstructor
public class JdbcAssistantRunRepository implements AssistantRunRepository {
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    @Override public void create(Run r) {
        jdbc.update("""
                INSERT INTO assistant_run
                (id, owner_hash, status, lang, page_path, message, context_json, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, r.id(), r.ownerHash(), r.status(), r.lang(), r.pagePath(), r.message(), r.contextJson(), r.expiresAt());
    }
    @Override public Run find(String id) {
        return jdbc.query("SELECT * FROM assistant_run WHERE id = ?", (rs, i) -> new Run(
                rs.getString("id"), rs.getString("owner_hash"), rs.getString("status"),
                rs.getString("lang"), rs.getString("page_path"), rs.getString("message"),
                rs.getString("context_json"), rs.getString("pending_json"),
                rs.getTimestamp("expires_at").toLocalDateTime()), id).stream().findFirst().orElse(null);
    }
    @Override public void context(String id, String value) {
        jdbc.update("UPDATE assistant_run SET context_json = ? WHERE id = ?", value, id);
    }
    @Override public void status(String id, String value) {
        jdbc.update("UPDATE assistant_run SET status = ?, pending_json = NULL WHERE id = ?", value, id);
    }
    @Override public void waitForDecision(String id, String context, String pending) {
        jdbc.update("UPDATE assistant_run SET status = 'WAITING', context_json = ?, pending_json = ? WHERE id = ?",
                context, pending, id);
    }
    @Override public boolean claimDecision(String id) {
        return jdbc.update("UPDATE assistant_run SET status = 'RUNNING', pending_json = NULL WHERE id = ? AND status = 'WAITING'",
                id) == 1;
    }
    @Override public void append(String id, Event e) {
        jdbc.update("INSERT INTO assistant_event (run_id, sequence_no, event_type, data_json) VALUES (?, ?, ?, ?)",
                id, e.sequence(), e.type(), e.data().toString());
    }
    @Override public List<Event> events(String id, long after) {
        return jdbc.query("SELECT sequence_no, event_type, data_json FROM assistant_event WHERE run_id = ? AND sequence_no > ? ORDER BY sequence_no",
                (rs, i) -> {
                    try { return new Event(rs.getLong(1), rs.getString(2), json.readTree(rs.getString(3))); }
                    catch (Exception e) { throw new IllegalStateException("助手事件无法解析", e); }
                }, id, after);
    }
    @Override public void deleteExpired() {
        jdbc.update("DELETE FROM assistant_run WHERE expires_at < CURRENT_TIMESTAMP");
    }
}
