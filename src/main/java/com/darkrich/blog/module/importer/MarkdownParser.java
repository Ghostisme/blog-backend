package com.darkrich.blog.module.importer;

import com.darkrich.blog.common.BusinessException;
import com.darkrich.blog.common.TextUtil;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把一份 Markdown 文件解析成文章草稿所需的字段。
 *
 * <p>设计上「尽量宽容」：掘金收藏导出的文件格式并不统一（有的带 Hexo/Jekyll 风格的 front-matter，
 * 有的只有正文），所以每个字段都有多个候选键名和兜底来源，而不是对格式做严格要求。
 * 解析不出来的字段留空，由后台人工补全——导入的文章本来就一律进草稿。
 */
public final class MarkdownParser {

    /** 解析结果。所有字段都已做过清洗，可直接用于构造保存请求。 */
    public record ParsedArticle(String title, String summary, String content, List<String> tags,
                                String categoryHint, String sourceUrl, String sourceAuthor, String coverUrl) {
    }

    // 候选键名已统一成「小写 + 下划线」形式，见 normalizeKey
    private static final List<String> TITLE_KEYS = List.of("title", "标题");
    private static final List<String> SUMMARY_KEYS = List.of("summary", "description", "brief_content", "excerpt", "摘要", "简介");
    private static final List<String> TAG_KEYS = List.of("tags", "tag", "keywords", "标签");
    private static final List<String> CATEGORY_KEYS = List.of("category", "categories", "分类");
    private static final List<String> SOURCE_URL_KEYS = List.of("source_url", "sourceurl", "original_url", "source", "url", "link", "原文链接");
    private static final List<String> AUTHOR_KEYS = List.of("source_author", "author", "作者");
    private static final List<String> COVER_KEYS = List.of("cover", "cover_image", "cover_url", "image", "thumbnail", "banner");

    /** 必须位于文件最开头的 --- 包裹块；(?s) 让 . 能匹配换行，惰性匹配取到第一个单独成行的 --- 为止。 */
    private static final Pattern FRONT_MATTER = Pattern.compile("\\A---[ \\t]*\\n(.*?)\\n---[ \\t]*(?:\\n|\\z)", Pattern.DOTALL);
    /** 宽容模式下的「键: 值」行。只按第一个冒号切分，因为标题本身常含冒号（会让严格 YAML 解析失败）。 */
    private static final Pattern KEY_VALUE_LINE = Pattern.compile("^([A-Za-z_\\u4e00-\\u9fa5][\\w\\-\\u4e00-\\u9fa5 ]*?)\\s*:\\s*(.*)$");
    private static final Pattern H1 = Pattern.compile("^#\\s+(.+?)\\s*#*\\s*$");
    /** 只放行 http(s)：这些值最终会渲染成链接或图片地址，不能让 javascript: 之类的协议混进来。 */
    private static final Pattern HTTP_URL = Pattern.compile("^https?://\\S+$", Pattern.CASE_INSENSITIVE);
    private static final Pattern STRUCTURAL_LIST = Pattern.compile("^([-*+]|\\d+[.)])\\s.*");
    private static final Pattern HORIZONTAL_RULE = Pattern.compile("^(-{3,}|\\*{3,}|_{3,})$");

    private static final int MAX_TITLE = 255;
    private static final int MAX_SUMMARY = 160;
    /** 太短的段落（如一句“前言”）不适合当摘要。 */
    private static final int MIN_SUMMARY_CHARS = 20;

    private MarkdownParser() {
    }

    /**
     * @param fileName 原始文件名，标题缺失时用它兜底
     * @param raw      文件全文（UTF-8 解码后）
     * @throws BusinessException 正文为空时
     */
    public static ParsedArticle parse(String fileName, String raw) {
        // 去 BOM、统一换行：Windows 导出的文件常带 \r\n 和 BOM，不处理会让后面的行匹配全部失效
        String text = raw.replace("\uFEFF", "").replace("\r\n", "\n").replace('\r', '\n');

        Map<String, Object> meta = new LinkedHashMap<>();
        String body = text;
        Matcher m = FRONT_MATTER.matcher(text);
        if (m.find()) {
            Map<String, Object> parsed = parseFrontMatter(m.group(1));
            // 解析出来一个键都没有，说明开头的 --- 只是分隔线而不是 front-matter，原样保留为正文
            if (!parsed.isEmpty()) {
                meta = parsed;
                body = text.substring(m.end());
            }
        }

        String title = firstString(meta, TITLE_KEYS);
        String[] lines = body.split("\n", -1);
        int firstNonBlank = firstNonBlankLine(lines);
        Heading h1 = findFirstH1(lines);
        if (title == null && h1 != null) {
            title = h1.text();
        }
        // 正文开头的一级标题如果和标题重复，页面上会显示两遍，去掉；不同的话说明是正文里的真标题，保留
        if (h1 != null && h1.lineIndex() == firstNonBlank && h1.text().equalsIgnoreCase(title)) {
            body = removeLine(lines, h1.lineIndex());
        }
        if (title == null) {
            title = stripExtension(fileName);
        }
        title = TextUtil.truncate(title.trim(), MAX_TITLE, "");

        String content = body.strip();
        if (content.isEmpty()) {
            throw BusinessException.badRequest("文件内容为空");
        }

        String summary = firstString(meta, SUMMARY_KEYS);
        summary = summary != null ? TextUtil.truncate(clean(summary), MAX_SUMMARY, "…") : summarize(content);

        return new ParsedArticle(title, summary, content, tags(meta),
                firstString(meta, CATEGORY_KEYS),
                httpUrl(firstString(meta, SOURCE_URL_KEYS)),
                TextUtil.truncate(firstString(meta, AUTHOR_KEYS), 128, ""),
                httpUrl(firstString(meta, COVER_KEYS)));
    }

    // ------------------------------------------------------------------ front matter

    /** 先按严格 YAML 解析；失败（如标题含冒号）再退到逐行宽容解析。 */
    private static Map<String, Object> parseFrontMatter(String yamlText) {
        try {
            // SafeConstructor：front-matter 来自外部文件，不允许 YAML 实例化任意 Java 对象
            Object loaded = new Yaml(new SafeConstructor(new LoaderOptions())).load(yamlText);
            if (loaded instanceof Map<?, ?> map) {
                Map<String, Object> out = new LinkedHashMap<>();
                map.forEach((k, v) -> out.put(normalizeKey(String.valueOf(k)), v));
                return out;
            }
        } catch (RuntimeException ignored) {
            // 落到下面的宽容解析
        }
        return lenientParse(yamlText);
    }

    private static Map<String, Object> lenientParse(String yamlText) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (String line : yamlText.split("\n")) {
            Matcher kv = KEY_VALUE_LINE.matcher(line.strip());
            if (kv.matches()) {
                String value = kv.group(2).strip().replaceAll("^[\"']|[\"']$", "");
                if (!value.isEmpty()) {
                    out.putIfAbsent(normalizeKey(kv.group(1)), value);
                }
            }
        }
        return out;
    }

    private static String normalizeKey(String key) {
        return key.trim().toLowerCase(Locale.ROOT).replace('-', '_').replace(' ', '_');
    }

    /** 取第一个有值的候选键；值是列表时取第一个元素（如 categories: [前端, React]）。 */
    private static String firstString(Map<String, Object> meta, List<String> keys) {
        for (String key : keys) {
            String s = scalar(meta.get(key));
            if (s != null) {
                return s;
            }
        }
        return null;
    }

    private static String scalar(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Collection<?> c) {
            for (Object o : c) {
                String s = scalar(o);
                if (s != null) {
                    return s;
                }
            }
            return null;
        }
        if (value instanceof Map) {
            return null;
        }
        String s = value.toString().trim();
        return s.isEmpty() ? null : s;
    }

    private static List<String> tags(Map<String, Object> meta) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (String key : TAG_KEYS) {
            collectTags(meta.get(key), out);
        }
        return new ArrayList<>(out);
    }

    /** 标签可能是 YAML 列表，也可能是 "a, b，c" 这样的字符串，统一摊平。 */
    private static void collectTags(Object value, Collection<String> out) {
        if (value == null) {
            return;
        }
        if (value instanceof Collection<?> c) {
            c.forEach(o -> collectTags(o, out));
            return;
        }
        for (String part : value.toString().split("[,，、;；]")) {
            // 去掉首尾的方括号、引号、空白，以及 #标签 写法的井号
            String tag = part.replaceAll("^[\\[\\s#\"']+|[\\]\\s\"']+$", "");
            if (!tag.isEmpty() && tag.length() <= 64) {
                out.add(tag);
            }
        }
    }

    private static String httpUrl(String s) {
        return (s != null && s.length() <= 512 && HTTP_URL.matcher(s).matches()) ? s : null;
    }

    // ------------------------------------------------------------------ 标题

    private record Heading(int lineIndex, String text) {
    }

    /** 找第一个一级标题；围栏代码块里的 "# 注释" 不算。 */
    private static Heading findFirstH1(String[] lines) {
        boolean inFence = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].strip();
            if (isFence(line)) {
                inFence = !inFence;
            } else if (!inFence) {
                Matcher m = H1.matcher(line);
                if (m.matches()) {
                    return new Heading(i, m.group(1).strip());
                }
            }
        }
        return null;
    }

    private static int firstNonBlankLine(String[] lines) {
        for (int i = 0; i < lines.length; i++) {
            if (!lines[i].isBlank()) {
                return i;
            }
        }
        return -1;
    }

    private static String removeLine(String[] lines, int index) {
        List<String> kept = new ArrayList<>(lines.length);
        for (int i = 0; i < lines.length; i++) {
            if (i != index) {
                kept.add(lines[i]);
            }
        }
        return String.join("\n", kept);
    }

    private static String stripExtension(String fileName) {
        return fileName.replaceFirst("(?i)\\.(md|markdown)$", "");
    }

    // ------------------------------------------------------------------ 摘要

    /** 取正文里第一个「足够长的普通段落」作为摘要；跳过标题、引用、列表、表格、图片、HTML 和代码块。 */
    private static String summarize(String body) {
        boolean inFence = false;
        StringBuilder paragraph = new StringBuilder();
        for (String raw : body.split("\n")) {
            String line = raw.strip();
            if (isFence(line)) {
                inFence = !inFence;
                continue;
            }
            if (inFence) {
                continue;
            }
            if (line.isEmpty() || isStructural(line)) {
                // 空行或结构性行都会结束当前段落
                String candidate = usable(paragraph);
                if (candidate != null) {
                    return candidate;
                }
                paragraph.setLength(0);
            } else {
                paragraph.append(line).append(' ');
            }
        }
        return usable(paragraph);
    }

    private static String usable(StringBuilder paragraph) {
        String cleaned = clean(paragraph.toString());
        return cleaned.codePointCount(0, cleaned.length()) >= MIN_SUMMARY_CHARS
                ? TextUtil.truncate(cleaned, MAX_SUMMARY, "…") : null;
    }

    private static boolean isFence(String line) {
        return line.startsWith("```") || line.startsWith("~~~");
    }

    private static boolean isStructural(String line) {
        return line.startsWith("#") || line.startsWith(">") || line.startsWith("|")
                || line.startsWith("![") || line.startsWith("<")
                || STRUCTURAL_LIST.matcher(line).matches() || HORIZONTAL_RULE.matcher(line).matches();
    }

    /**
     * 去掉行内 Markdown/HTML 标记，得到纯文本。
     * 刻意不处理下划线：snake_case 之类的标识符里的 _ 是内容，去掉会改坏原文。
     */
    private static String clean(String text) {
        return text
                .replaceAll("!\\[[^\\]]*]\\([^)]*\\)", "")
                .replaceAll("\\[([^\\]]*)]\\([^)]*\\)", "$1")
                .replaceAll("<[^>]+>", "")
                .replace("`", "")
                .replace("*", "")
                .replace("~~", "")
                .replaceAll("\\s+", " ")
                .strip();
    }
}
