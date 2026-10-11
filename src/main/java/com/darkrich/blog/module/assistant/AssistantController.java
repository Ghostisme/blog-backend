package com.darkrich.blog.module.assistant;

import com.darkrich.blog.common.ApiResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.util.Map;

@RestController
@RequestMapping("/api/assistant")
@RequiredArgsConstructor
public class AssistantController {
    private final AssistantProperties properties;
    private final AssistantService service;
    @GetMapping("/config") public ApiResponse<Map<String, Object>> config() {
        return ApiResponse.ok(Map.of("name", "小R助手", "available", properties.available()));
    }
    @PostMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chat(@RequestHeader(value = "X-R-Session", defaultValue = "") String token, @Valid @RequestBody AssistantRequest request,
                           HttpServletRequest http, HttpServletResponse response) {
        streamHeaders(response); return service.chat(token, http.getRemoteAddr(), request);
    }
    @GetMapping(value = "/runs/{id}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(@RequestHeader(value = "X-R-Session", defaultValue = "") String token, @PathVariable String id,
                             @RequestParam(defaultValue = "0") long after, HttpServletResponse response) {
        streamHeaders(response); return service.replay(token, id, Math.max(0, after));
    }
    @PostMapping(value = "/runs/{id}/resume", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter resume(@RequestHeader(value = "X-R-Session", defaultValue = "") String token, @PathVariable String id,
                             @Valid @RequestBody AssistantRequest.Decision decision,
                             HttpServletRequest http, HttpServletResponse response) {
        streamHeaders(response); return service.resume(token, http.getRemoteAddr(), id, decision);
    }
    @PostMapping("/runs/{id}/cancel") public ApiResponse<Void> cancel(@RequestHeader(value = "X-R-Session", defaultValue = "") String token, @PathVariable String id) {
        service.cancel(token, id); return ApiResponse.ok(null);
    }
    private void streamHeaders(HttpServletResponse response) {
        response.setHeader("Cache-Control", "no-store");
        response.setHeader("X-Accel-Buffering", "no");
    }
}
