package com.darkrich.blog.module.article;

import com.darkrich.blog.config.BlogProperties;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/** OpenAI-compatible /chat/completions adapter. */
@Slf4j
@Component
public class OpenAiCompatibleTranslationProvider implements ArticleTranslationProvider {

    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(3);
    private final BlogProperties.Translation properties;
    private final ObjectMapper objectMapper;
    private final HttpClient client;

    public OpenAiCompatibleTranslationProvider(BlogProperties properties, ObjectMapper objectMapper) {
        this.properties = properties.translation();
        this.objectMapper = objectMapper;
        this.client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public ArticleTranslationResult translate(Article article, ArticleLanguage source, ArticleLanguage target) {
        if (!properties.enabled() || properties.apiKey() == null || properties.apiKey().isBlank()) {
            throw new IllegalStateException("翻译服务未启用或缺少 API Key");
        }
        try {
            String endpoint = properties.baseUrl().replaceAll("/+$", "") + "/chat/completions";
            String payload = objectMapper.writeValueAsString(Map.of(
                    "model", properties.model(),
                    "temperature", 0.2,
                    "messages", new Object[]{
                            Map.of("role", "system", "content", systemPrompt(source, target)),
                            Map.of("role", "user", "content", userPrompt(article))
                    }));
            HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                    .timeout(REQUEST_TIMEOUT)
                    .header("Authorization", "Bearer " + properties.apiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IllegalStateException("翻译服务返回 HTTP " + response.statusCode());
            }
            JsonNode root = objectMapper.readTree(response.body());
            String content = root.path("choices").path(0).path("message").path("content").asText(null);
            if (content == null || content.isBlank()) {
                throw new IllegalStateException("翻译服务返回了空结果");
            }
            JsonNode result = objectMapper.readTree(stripCodeFence(content));
            ArticleTranslationResult translated = new ArticleTranslationResult(
                    text(result, "title"), nullableText(result, "summary"), text(result, "content"));
            validate(translated);
            return translated;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("翻译请求被中断", e);
        } catch (IOException | RuntimeException e) {
            log.warn("文章翻译调用失败，articleId={}", article.getId(), e);
            throw new IllegalStateException("翻译服务调用失败", e);
        }
    }

    private static String systemPrompt(ArticleLanguage source, ArticleLanguage target) {
        return "你是专业技术文章翻译器。将" + languageName(source) + "翻译为" + languageName(target) + "。"
                + "只返回 JSON，不要返回 Markdown 代码围栏或解释文字。"
                + "JSON 必须包含 title、summary、content 三个字段。"
                + "保留 Markdown 标题层级、列表、表格、链接、图片地址、HTML 和代码围栏；"
                + "代码、命令、变量名、URL、文件路径和已有代码注释不要翻译。"
                + "不新增原文不存在的事实，不省略段落，术语保持一致。";
    }

    private static String userPrompt(Article article) {
        return "请翻译下面这篇文章。原文标题：\n" + value(article.getTitle())
                + "\n\n原文摘要：\n" + value(article.getSummary())
                + "\n\n原文正文：\n" + value(article.getContent());
    }

    private static String languageName(ArticleLanguage language) {
        return language == ArticleLanguage.EN ? "英文" : "中文";
    }

    private static String text(JsonNode node, String field) {
        String value = node.path(field).asText(null);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("翻译结果缺少 " + field);
        }
        return value;
    }

    private static String nullableText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static void validate(ArticleTranslationResult result) {
        if (result.title().length() > 255
                || result.summary() != null && result.summary().length() > 500
                || result.content().isBlank()) {
            throw new IllegalStateException("翻译结果字段长度或正文不合法");
        }
    }

    private static String stripCodeFence(String content) {
        String trimmed = content.trim();
        if (trimmed.startsWith("```") && trimmed.endsWith("```")) {
            int firstLine = trimmed.indexOf('\n');
            return firstLine > 0 ? trimmed.substring(firstLine + 1, trimmed.length() - 3).trim() : trimmed;
        }
        return trimmed;
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }
}
