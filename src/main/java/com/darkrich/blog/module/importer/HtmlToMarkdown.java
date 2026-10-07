package com.darkrich.blog.module.importer;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

/**
 * 把一篇公开 HTML 转成大致可读的 Markdown。
 *
 * <p>不是通用转换器：只服务于「贴链接导入」。导航、页脚、脚本丢掉，优先取 article / main。
 * 代码块、标题、列表、链接、图片保留；复杂表格会退化成纯文本。
 */
final class HtmlToMarkdown {

    private HtmlToMarkdown() {
    }

    static String convert(String html, String baseUri) {
        Document doc = Jsoup.parse(html, baseUri == null ? "" : baseUri);
        doc.select("script,style,nav,footer,header,aside,form,iframe,noscript,svg,button").remove();
        Element root = first(doc, "article", "[role=main]", "main", ".markdown-body",
                ".article-content", ".article-body", ".post-content", "#content");
        if (root == null) {
            root = doc.body();
        }
        if (root == null) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        walk(root, out);
        return out.toString().replaceAll("[ \\t]+\\n", "\n").replaceAll("\\n{3,}", "\n\n").strip();
    }

    private static Element first(Document doc, String... selectors) {
        for (String sel : selectors) {
            Element el = doc.selectFirst(sel);
            if (el != null) {
                return el;
            }
        }
        return null;
    }

    private static void walk(Node node, StringBuilder out) {
        if (node instanceof TextNode text) {
            out.append(text.text());
            return;
        }
        if (!(node instanceof Element el)) {
            return;
        }
        String tag = el.tagName();
        switch (tag) {
            case "br" -> out.append('\n');
            case "hr" -> out.append("\n\n---\n\n");
            case "p", "div", "section", "figure" -> {
                walkChildren(el, out);
                out.append("\n\n");
            }
            case "h1", "h2", "h3", "h4", "h5", "h6" -> {
                int level = tag.charAt(1) - '0';
                out.append("\n\n").append("#".repeat(level)).append(' ');
                walkChildren(el, out);
                out.append("\n\n");
            }
            case "pre" -> {
                out.append("\n\n```\n").append(el.wholeText().strip()).append("\n```\n\n");
            }
            case "code" -> {
                if (el.parent() != null && "pre".equals(el.parent().tagName())) {
                    return;
                }
                out.append('`').append(el.text()).append('`');
            }
            case "a" -> {
                String href = el.absUrl("href");
                String label = el.text().strip();
                if (href.startsWith("http") && !label.isEmpty()) {
                    out.append('[').append(label).append("](").append(href).append(')');
                } else {
                    walkChildren(el, out);
                }
            }
            case "img" -> {
                String src = el.absUrl("src");
                if (src.startsWith("http")) {
                    out.append("![").append(el.attr("alt")).append("](").append(src).append(')');
                }
            }
            case "ul", "ol" -> {
                int i = 1;
                for (Element li : el.children()) {
                    if (!"li".equals(li.tagName())) {
                        continue;
                    }
                    out.append('\n');
                    if ("ol".equals(tag)) {
                        out.append(i++).append(". ");
                    } else {
                        out.append("- ");
                    }
                    walkChildren(li, out);
                }
                out.append("\n\n");
            }
            case "blockquote" -> {
                StringBuilder inner = new StringBuilder();
                walkChildren(el, inner);
                for (String line : inner.toString().strip().split("\n")) {
                    out.append("> ").append(line).append('\n');
                }
                out.append('\n');
            }
            case "strong", "b" -> {
                out.append("**");
                walkChildren(el, out);
                out.append("**");
            }
            case "em", "i" -> {
                out.append('*');
                walkChildren(el, out);
                out.append('*');
            }
            case "li" -> walkChildren(el, out);
            default -> walkChildren(el, out);
        }
    }

    private static void walkChildren(Element el, StringBuilder out) {
        for (Node child : el.childNodes()) {
            walk(child, out);
        }
    }
}
