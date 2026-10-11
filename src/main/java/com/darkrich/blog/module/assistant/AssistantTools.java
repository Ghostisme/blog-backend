package com.darkrich.blog.module.assistant;

import com.darkrich.blog.common.BusinessException;
import com.darkrich.blog.module.article.*;
import com.darkrich.blog.module.resume.ResumeService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.function.BiConsumer;

@Component
@RequiredArgsConstructor
public class AssistantTools {
    private final ArticleQueryService articles;
    private final ResumeService resumes;
    private final ObjectMapper json;
    private final AssistantRunRepository repository;
    public record Outcome(JsonNode result, ObjectNode approval) {}

    public ArrayNode definitions() {
        try { return (ArrayNode) json.readTree(DEFINITIONS); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    public String label(String name, boolean en) {
        return switch (name) {
            case "search_articles" -> en ? "Finding articles" : "正在查找文章";
            case "read_article" -> en ? "Reading the article" : "正在阅读文章";
            case "get_resume" -> en ? "Reading the profile" : "正在查看作者简历";
            case "get_blog_overview" -> en ? "Building the overview" : "正在整理博客统计";
            case "show_table" -> en ? "Preparing a table" : "正在整理表格";
            case "show_chart" -> en ? "Preparing a chart" : "正在绘制图表";
            case "create_document" -> en ? "Writing a document" : "正在编写文档";
            case "navigate" -> en ? "Opening the page" : "正在打开页面";
            default -> en ? "Preparing the next step" : "正在准备下一步";
        };
    }

    public Outcome execute(String name, JsonNode args, AssistantRunRepository.Run run,
                           BiConsumer<String, JsonNode> emit) {
        boolean en = run.lang().equals("en");
        JsonNode result;
        switch (name) {
            case "search_articles" -> {
                ArticleLevel level = args.hasNonNull("level") && !args.path("level").asText().isBlank()
                        ? ArticleLevel.valueOf(args.path("level").asText()) : null;
                var page = articles.list(new ArticleQuery(1, 8, level,
                        args.hasNonNull("categoryId") ? args.path("categoryId").longValue() : null,
                        null, args.path("keyword").asText(""),
                        "HOT".equals(args.path("sort").asText()) ? ArticleSort.HOT : ArticleSort.LATEST, run.lang()));
                ArrayNode items = json.createArrayNode();
                for (ArticleListItem a : page.records()) {
                    items.add(json.createObjectNode().put("title", a.title()).put("summary", a.summary())
                            .put("path", articlePath(a.slug())).put("level", a.level().name())
                            .put("readingMinutes", a.readingMinutes()).put("views", a.viewCount()));
                }
                emit.accept("block", block("articles", en ? "Articles to explore" : "找到这些文章").set("items", items));
                ObjectNode data = json.createObjectNode().put("total", page.total());
                data.set("items", items);
                result = data;
            }
            case "read_article" -> {
                String slug = args.path("slug").asText("");
                if (slug.isBlank() && run.pagePath().startsWith("/articles/")) {
                    slug = URI.create(run.pagePath()).getPath().substring("/articles/".length());
                }
                if (slug.isBlank()) throw BusinessException.badRequest("请先选择一篇文章");
                ArticleDetail a = articles.readForAssistant(slug, ArticleLanguage.from(run.lang()));
                ObjectNode data = json.createObjectNode().put("title", a.title()).put("summary", a.summary())
                        .put("path", articlePath(a.slug())).put("content", truncate(a.content(), 18000));
                emit.accept("block", block("articles", en ? "Source article" : "参考文章")
                        .set("items", json.createArrayNode().add(json.createObjectNode()
                                .put("title", a.title()).put("summary", a.summary())
                                .put("path", articlePath(a.slug())).put("readingMinutes", a.readingMinutes()))));
                result = data;
            }
            case "get_resume" -> result = json.valueToTree(resumes.get(run.lang()));
            case "get_blog_overview" -> {
                FilterOptions f = articles.filters();
                ArrayNode values = json.createArrayNode();
                ArrayNode rows = json.createArrayNode();
                f.categories().forEach(c -> {
                    String label = en ? c.nameEn() : c.nameZh();
                    values.add(json.createObjectNode().put("label", label).put("value", c.articleCount())
                            .put("path", "/articles?category=" + c.id()));
                    rows.add(json.createArrayNode().add(label).add(c.articleCount()));
                });
                ObjectNode chart = block("chart", en ? "Articles by topic" : "各领域文章分布")
                        .put("kind", "bar").put("unit", en ? "articles" : "篇")
                        .put("source", en ? "Published articles · live data" : "已发布文章 · 实时数据");
                chart.set("values", values);
                emit.accept("block", chart);
                if (args.path("includeTable").asBoolean(false)) {
                    ObjectNode table = block("table", en ? "Topic overview" : "领域统计表");
                    table.set("columns", json.createArrayNode().add(en ? "Topic" : "领域").add(en ? "Articles" : "文章数"));
                    table.set("rows", rows);
                    emit.accept("block", table);
                }
                result = json.valueToTree(f);
            }
            case "show_table" -> {
                ArrayNode columns = array(args, "columns", 8);
                ArrayNode rows = array(args, "rows", 40);
                for (JsonNode column : columns) if (!column.isTextual() || column.asText().isBlank()
                        || column.asText().length() > 120) throw BusinessException.badRequest("表头不合法");
                for (JsonNode row : rows) {
                    if (!row.isArray() || row.size() != columns.size()) throw BusinessException.badRequest("表格列数不一致");
                    for (JsonNode cell : row) if (!cell.isValueNode() || cell.asText().length() > 800) throw BusinessException.badRequest("表格内容过长");
                }
                ObjectNode table = block("table", text(args, "title", 120));
                table.set("columns", columns); table.set("rows", rows);
                emit.accept("block", table);
                result = json.createObjectNode().put("displayed", true);
            }
            case "show_chart" -> {
                ArrayNode values = array(args, "values", 20);
                for (JsonNode v : values) {
                    text(v, "label", 80);
                    if (!v.path("value").isNumber() || !Double.isFinite(v.path("value").asDouble())
                            || v.path("value").asDouble() < 0) throw BusinessException.badRequest("图表数据不合法");
                }
                ObjectNode chart = block("chart", text(args, "title", 120))
                        .put("kind", args.path("kind").asText().equals("line") ? "line" : "bar")
                        .put("unit", args.path("unit").asText("")).put("source", text(args, "source", 200));
                chart.set("values", values);
                emit.accept("block", chart);
                result = json.createObjectNode().put("displayed", true);
            }
            case "create_document" -> {
                ObjectNode doc = block("document", text(args, "title", 120))
                        .put("content", text(args, "content", 24000));
                emit.accept("block", doc);
                result = json.createObjectNode().put("documentId", doc.path("id").asText()).put("previewReady", true);
            }
            case "navigate" -> {
                String path = AssistantNavigation.requirePublicPath(text(args, "path", 512));
                if (URI.create(path).getPath().startsWith("/articles/")) {
                    articles.readForAssistant(URI.create(path).getPath().substring(10), ArticleLanguage.from(run.lang()));
                }
                String title = text(args, "label", 120);
                if (AssistantNavigation.explicitlyRequested(run.message())) {
                    emit.accept("navigation", json.createObjectNode().put("path", path).put("label", title));
                    result = json.createObjectNode().put("opened", path);
                } else {
                    emit.accept("block", block("articles", en ? "Explore this page" : "可以浏览这个页面")
                            .set("items", json.createArrayNode().add(json.createObjectNode().put("title", title).put("path", path))));
                    result = json.createObjectNode().put("link", path).put("note", "用户没有明确要求跳转，仅展示链接，不要声称已打开");
                }
            }
            case "ask_user" -> {
                ArrayNode options = array(args, "options", 4);
                if (options.size() < 2) throw BusinessException.badRequest("至少需要两个选项");
                ArrayNode choices = json.createArrayNode();
                for (int i = 0; i < options.size(); i++) choices.add(json.createObjectNode()
                        .put("id", "choice-" + i).put("label", text(options.get(i), "label", 120)));
                ObjectNode approval = json.createObjectNode().put("id", UUID.randomUUID().toString())
                        .put("kind", "question").put("title", text(args, "question", 300));
                approval.set("options", choices);
                return new Outcome(null, approval);
            }
            case "request_download" -> {
                ObjectNode doc = document(run.id(), text(args, "documentId", 64));
                ObjectNode approval = json.createObjectNode().put("id", UUID.randomUUID().toString())
                        .put("kind", "download").put("documentId", doc.path("id").asText())
                        .put("title", en ? "Download this document?" : "下载这份文档？")
                        .put("description", doc.path("title").asText() + ".md");
                approval.set("options", json.createArrayNode()
                        .add(json.createObjectNode().put("id", "approve").put("label", en ? "Download" : "确认下载"))
                        .add(json.createObjectNode().put("id", "cancel").put("label", en ? "Keep preview only" : "只看预览")));
                return new Outcome(null, approval);
            }
            default -> throw BusinessException.badRequest("不支持的助手操作");
        }
        return new Outcome(result, null);
    }

    public ObjectNode document(String runId, String id) {
        return repository.events(runId, 0).stream().filter(e -> e.type().equals("block")
                        && e.data().path("type").asText().equals("document") && e.data().path("id").asText().equals(id))
                .map(e -> (ObjectNode) e.data()).findFirst().orElseThrow(() -> BusinessException.notFound("文档已过期"));
    }
    private ObjectNode block(String type, String title) {
        return json.createObjectNode().put("id", UUID.randomUUID().toString()).put("type", type).put("title", title);
    }
    private static String articlePath(String slug) {
        return "/articles/" + URLEncoder.encode(slug, StandardCharsets.UTF_8).replace("+", "%20");
    }
    private static String truncate(String value, int max) {
        return value == null ? "" : value.length() <= max ? value : value.substring(0, max) + "\n[正文过长，此处截断]";
    }
    private static String text(JsonNode args, String key, int max) {
        JsonNode value = args.path(key);
        if (!value.isTextual() || value.asText().isBlank() || value.asText().length() > max) {
            throw BusinessException.badRequest("助手操作参数不合法: " + key);
        }
        return value.asText();
    }
    private static ArrayNode array(JsonNode args, String key, int max) {
        JsonNode value = args.path(key);
        if (!value.isArray() || value.size() < 1 || value.size() > max) throw BusinessException.badRequest("助手数据大小不合法");
        return (ArrayNode) value;
    }

    private static final String DEFINITIONS = """
        [
          {"type":"function","function":{"name":"search_articles","description":"检索真实已发布文章；推荐、查找内容前先调用。keyword为空时返回最新/热门。","parameters":{"type":"object","properties":{"keyword":{"type":"string"},"categoryId":{"type":"integer"},"level":{"type":"string","enum":["BEGINNER","INTERMEDIATE","ADVANCED","EXPERT"]},"sort":{"type":"string","enum":["LATEST","HOT"]}}}}},
          {"type":"function","function":{"name":"read_article","description":"阅读已发布文章正文。当前页是文章时slug可留空。总结必须先阅读，不能编造。","parameters":{"type":"object","properties":{"slug":{"type":"string"}}}}},
          {"type":"function","function":{"name":"get_resume","description":"读取作者公开简历，回答关于作者的问题。","parameters":{"type":"object","properties":{}}}},
          {"type":"function","function":{"name":"get_blog_overview","description":"读取博客实际领域/等级/标签统计，自动展示可点击柱状图。","parameters":{"type":"object","properties":{"includeTable":{"type":"boolean"}}}}},
          {"type":"function","function":{"name":"navigate","description":"用户明确要求打开某页时直接站内跳转，无需确认。path仅支持 /、/articles、/articles/{真实slug}、/resume?lang=zh或en；文章筛选用 /articles?category=ID&level=BEGINNER&keyword=关键词。目标不明确时先ask_user。","parameters":{"type":"object","properties":{"path":{"type":"string"},"label":{"type":"string"}},"required":["path","label"]}}},
          {"type":"function","function":{"name":"show_table","description":"以结构化表格展示比较或整理；最多8列40行。无需确认。","parameters":{"type":"object","properties":{"title":{"type":"string"},"columns":{"type":"array","items":{"type":"string"}},"rows":{"type":"array","items":{"type":"array","items":{"type":"string"}}}},"required":["title","columns","rows"]}}},
          {"type":"function","function":{"name":"show_chart","description":"展示有来源的数值图表；不能编造博客统计，博客统计优先get_blog_overview。","parameters":{"type":"object","properties":{"title":{"type":"string"},"kind":{"type":"string","enum":["bar","line"]},"unit":{"type":"string"},"source":{"type":"string"},"values":{"type":"array","items":{"type":"object","properties":{"label":{"type":"string"},"value":{"type":"number"}},"required":["label","value"]}}},"required":["title","kind","source","values"]}}},
          {"type":"function","function":{"name":"create_document","description":"生成Markdown文档预览，例如阅读路线、学习笔记、文章摘要。无需确认，不会发布或下载。content是完整Markdown。","parameters":{"type":"object","properties":{"title":{"type":"string"},"content":{"type":"string"}},"required":["title","content"]}}},
          {"type":"function","function":{"name":"ask_user","description":"仅在目标不明确且缺少信息时请求用户选择。普通对话、查询和明确站内跳转不能滥用确认。暂停后由用户选项恢复。","parameters":{"type":"object","properties":{"question":{"type":"string"},"options":{"type":"array","items":{"type":"object","properties":{"label":{"type":"string"}},"required":["label"]}}},"required":["question","options"]}}},
          {"type":"function","function":{"name":"request_download","description":"请求下载本轮create_document生成的文档。涉及浏览器下载，必须等待用户确认；documentId来自create_document结果。","parameters":{"type":"object","properties":{"documentId":{"type":"string"}},"required":["documentId"]}}}
        ]
        """;
}
