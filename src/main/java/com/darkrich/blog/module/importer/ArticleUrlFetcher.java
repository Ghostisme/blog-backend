package com.darkrich.blog.module.importer;

import com.darkrich.blog.common.BusinessException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把一篇公开文章 URL 抓成 {@link MarkdownParser.ParsedArticle}。
 *
 * <p>掘金优先走公开 API 拿 Markdown；失败或非掘金则抓 HTML。
 * 登录墙、付费墙、纯前端渲染且无正文的页面会失败，导入结果里写原因，不中断整批。
 */
@Component
@RequiredArgsConstructor
public class ArticleUrlFetcher {

    private static final Pattern JUEJIN_POST =
            Pattern.compile("https?://(?:www\\.)?juejin\\.(?:cn|im)/post/(\\d+)", Pattern.CASE_INSENSITIVE);
    private static final URI JUEJIN_DETAIL =
            URI.create("https://api.juejin.cn/content_api/v1/article/detail");

    private final SafeHttpClient http;
    private final ObjectMapper objectMapper;

    public MarkdownParser.ParsedArticle fetch(String rawUrl) {
        URI uri = SafeHttpClient.parsePublicHttpUrl(rawUrl);
        String juejinId = juejinPostId(uri);
        if (juejinId != null) {
            try {
                return fetchJuejin(uri, juejinId);
            } catch (BusinessException ignored) {
                // API 失败再走 HTML，给「接口改版 / 偶发 403」留退路
            }
        }
        return fetchHtml(uri);
    }

    static String juejinPostId(URI uri) {
        Matcher m = JUEJIN_POST.matcher(uri.toString());
        return m.find() ? m.group(1) : null;
    }

    private MarkdownParser.ParsedArticle fetchJuejin(URI pageUrl, String articleId) {
        SafeHttpClient.Fetched fetched = http.postJson(JUEJIN_DETAIL, "{\"article_id\":\"" + articleId + "\"}");
        JsonNode root;
        try {
            root = objectMapper.readTree(fetched.body());
        } catch (Exception e) {
            throw BusinessException.badRequest("掘金接口返回无法解析");
        }
        if (root.path("err_no").asInt(-1) != 0) {
            throw BusinessException.badRequest("掘金接口拒绝了该文章");
        }
        JsonNode data = root.path("data");
        JsonNode info = data.path("article_info");
        String title = text(info.path("title"));
        String summary = text(info.path("brief_content"));
        String cover = text(info.path("cover_image"));
        String author = text(data.path("author_user_info").path("user_name"));
        List<String> tags = new ArrayList<>();
        for (JsonNode tag : data.path("tags")) {
            String name = text(tag.path("tag_name"));
            if (name != null) {
                tags.add(name);
            }
        }
        String category = text(data.path("category").path("category_name"));
        String markdown = text(info.path("mark_content"));
        if (markdown == null || markdown.isBlank()) {
            String html = text(info.path("content"));
            markdown = html == null ? "" : HtmlToMarkdown.convert(html, pageUrl.toString());
        }
        if (markdown.isBlank()) {
            throw BusinessException.badRequest("掘金返回的正文为空");
        }
        return toParsed(title, summary, markdown, tags, category, pageUrl.toString(), author, cover);
    }

    private MarkdownParser.ParsedArticle fetchHtml(URI uri) {
        SafeHttpClient.Fetched fetched = http.get(uri);
        String body = fetched.body();
        String ctype = fetched.contentType() == null ? "" : fetched.contentType().toLowerCase(Locale.ROOT);
        if (ctype.contains("json")) {
            MarkdownParser.ParsedArticle fromJson = tryJsonArticle(body, fetched.url().toString());
            if (fromJson != null) {
                return fromJson;
            }
        }
        Document doc = Jsoup.parse(body, fetched.url().toString());
        Element next = doc.getElementById("__NEXT_DATA__");
        if (next != null) {
            MarkdownParser.ParsedArticle fromNext = tryJsonArticle(next.data(), fetched.url().toString());
            if (fromNext != null) {
                return fromNext;
            }
        }
        String title = og(doc, "og:title");
        if (title == null && doc.selectFirst("h1") != null) {
            title = doc.selectFirst("h1").text();
        }
        if (title == null && doc.title() != null && !doc.title().isBlank()) {
            title = doc.title();
        }
        String author = og(doc, "og:article:author");
        if (author == null) {
            author = og(doc, "author");
        }
        String cover = og(doc, "og:image");
        String description = og(doc, "og:description");
        String markdown = HtmlToMarkdown.convert(body, fetched.url().toString());
        if (markdown.isBlank()) {
            throw BusinessException.badRequest("未能从该页面提取正文（可能需要登录，或不是公开文章）");
        }
        return toParsed(title, description, markdown, List.of(), null, fetched.url().toString(), author, cover);
    }

    /** 在任意 JSON 里找 mark_content / title，适配掘金 __NEXT_DATA__ 这类深嵌套结构。 */
    private MarkdownParser.ParsedArticle tryJsonArticle(String json, String pageUrl) {
        JsonNode root;
        try {
            root = objectMapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
        JsonNode mark = findField(root, "mark_content");
        if (mark == null || !mark.isTextual() || mark.asText().isBlank()) {
            return null;
        }
        String title = text(findField(root, "title"));
        String summary = text(findField(root, "brief_content"));
        String author = text(findField(root, "user_name"));
        String cover = text(findField(root, "cover_image"));
        return toParsed(title, summary, mark.asText(), List.of(), null, pageUrl, author, cover);
    }

    private static JsonNode findField(JsonNode node, String name) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (node.isObject()) {
            if (node.has(name) && !node.get(name).isNull() && !node.get(name).asText("").isBlank()) {
                return node.get(name);
            }
            var it = node.elements();
            while (it.hasNext()) {
                JsonNode found = findField(it.next(), name);
                if (found != null) {
                    return found;
                }
            }
        } else if (node.isArray()) {
            for (JsonNode child : node) {
                JsonNode found = findField(child, name);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private MarkdownParser.ParsedArticle toParsed(String title, String summary, String markdown,
                                                  List<String> tags, String category,
                                                  String sourceUrl, String author, String cover) {
        StringBuilder raw = new StringBuilder();
        raw.append("---\n");
        if (title != null && !title.isBlank()) {
            raw.append("title: ").append(yamlQuote(title.strip())).append('\n');
        }
        if (summary != null && !summary.isBlank()) {
            raw.append("description: ").append(yamlQuote(summary.strip())).append('\n');
        }
        if (author != null && !author.isBlank()) {
            raw.append("author: ").append(yamlQuote(author.strip())).append('\n');
        }
        if (sourceUrl != null) {
            raw.append("source_url: ").append(sourceUrl).append('\n');
        }
        if (cover != null && cover.startsWith("http")) {
            raw.append("cover: ").append(cover).append('\n');
        }
        if (category != null && !category.isBlank()) {
            raw.append("category: ").append(yamlQuote(category.strip())).append('\n');
        }
        if (!tags.isEmpty()) {
            raw.append("tags: [");
            for (int i = 0; i < tags.size(); i++) {
                if (i > 0) {
                    raw.append(", ");
                }
                raw.append(yamlQuote(tags.get(i)));
            }
            raw.append("]\n");
        }
        raw.append("---\n\n").append(markdown);
        String fileName = title != null && !title.isBlank() ? title.strip() : "imported";
        return MarkdownParser.parse(fileName, raw.toString());
    }

    private static String yamlQuote(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static String text(JsonNode node) {
        if (node == null || node.isMissingNode() || node.isNull()) {
            return null;
        }
        String v = node.asText();
        return v == null || v.isBlank() ? null : v;
    }

    private static String og(Document doc, String property) {
        Element el = doc.selectFirst("meta[property=" + property + "], meta[name=" + property + "]");
        if (el == null) {
            return null;
        }
        String content = el.attr("content");
        return content.isBlank() ? null : content;
    }
}
