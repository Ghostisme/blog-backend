package com.darkrich.blog.module.importer;

import com.darkrich.blog.common.BusinessException;
import com.darkrich.blog.module.importer.MarkdownParser.ParsedArticle;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkdownParserTest {

    @Test
    void parsesFrontMatterFields() {
        String md = """
                ---
                title: 深入理解 Vue 响应式
                description: 从源码角度拆解依赖收集。
                tags: [Vue, 前端, "源码"]
                category: 前端
                author: 张三
                source_url: https://juejin.cn/post/123
                cover: https://img.example.com/a.png
                ---
                正文第一段，内容足够长可以作为摘要使用，不会被跳过。
                """;
        ParsedArticle a = MarkdownParser.parse("x.md", md);

        assertEquals("深入理解 Vue 响应式", a.title());
        assertEquals("从源码角度拆解依赖收集。", a.summary());
        assertEquals(java.util.List.of("Vue", "前端", "源码"), a.tags());
        assertEquals("前端", a.categoryHint());
        assertEquals("张三", a.sourceAuthor());
        assertEquals("https://juejin.cn/post/123", a.sourceUrl());
        assertEquals("https://img.example.com/a.png", a.coverUrl());
        assertFalse(a.content().contains("title:"), "front-matter 不应留在正文里");
    }

    @Test
    void fallsBackToFirstH1AndRemovesItFromBody() {
        ParsedArticle a = MarkdownParser.parse("x.md", "# 我的标题\n\n这是一段足够长的正文内容，用来生成摘要，长度超过二十个字符。\n");

        assertEquals("我的标题", a.title());
        assertFalse(a.content().startsWith("# "), "与标题重复的一级标题应从正文去掉，否则页面会显示两遍");
    }

    @Test
    void keepsH1WhenItDiffersFromFrontMatterTitle() {
        ParsedArticle a = MarkdownParser.parse("x.md", "---\ntitle: 文章标题\n---\n# 另一个标题\n\n正文内容足够长，足够长，足够长，足够长。\n");

        assertEquals("文章标题", a.title());
        assertTrue(a.content().startsWith("# 另一个标题"));
    }

    @Test
    void usesFileNameWhenNoTitleAnywhere() {
        ParsedArticle a = MarkdownParser.parse("手写一个 Promise.md", "只有正文，没有任何标题。这段话也足够长可以作摘要。\n");

        assertEquals("手写一个 Promise", a.title());
    }

    @Test
    void titleWithColonFallsBackToLenientParsing() {
        // 严格 YAML 下 "a: b: c" 是非法的，掘金标题里带冒号非常常见
        ParsedArticle a = MarkdownParser.parse("x.md", "---\ntitle: Spring Boot: 入门指南\ntags: Java, Spring\n---\n正文正文正文正文正文正文正文正文正文正文。\n");

        assertEquals("Spring Boot: 入门指南", a.title());
        assertEquals(java.util.List.of("Java", "Spring"), a.tags());
    }

    @Test
    void handlesBomAndCrlf() {
        ParsedArticle a = MarkdownParser.parse("x.md", "\uFEFF---\r\ntitle: BOM 测试\r\n---\r\n正文正文正文正文正文正文正文正文正文正文。\r\n");

        assertEquals("BOM 测试", a.title());
    }

    @Test
    void summaryComesFromFirstRealParagraphSkippingCodeAndStructure() {
        String md = """
                # 标题

                > 引用不算摘要

                ```java
                // 代码块里的文字不能当摘要
                ```

                这是**真正**的第一段正文，包含 `行内代码` 和 [链接](https://a.com)，长度足够。

                第二段。
                """;
        ParsedArticle a = MarkdownParser.parse("x.md", md);

        assertEquals("这是真正的第一段正文，包含 行内代码 和 链接，长度足够。", a.summary());
    }

    @Test
    void h1InsideCodeFenceIsNotATitle() {
        ParsedArticle a = MarkdownParser.parse("fallback.md", "```bash\n# 这是注释不是标题\n```\n\n正文正文正文正文正文正文正文正文正文正文。\n");

        assertEquals("fallback", a.title());
    }

    @Test
    void rejectsNonHttpUrls() {
        // javascript: 之类的协议最终会被渲染成链接/图片地址，必须在解析阶段丢弃
        ParsedArticle a = MarkdownParser.parse("x.md",
                "---\ntitle: T\nsource_url: javascript:alert(1)\ncover: data:text/html;base64,AAAA\n---\n正文正文正文正文正文正文正文正文正文正文。\n");

        assertNull(a.sourceUrl());
        assertNull(a.coverUrl());
    }

    @Test
    void leadingHorizontalRuleIsNotFrontMatter() {
        ParsedArticle a = MarkdownParser.parse("x.md", "---\n\n正文正文正文正文正文正文正文正文正文正文。\n");

        assertTrue(a.content().contains("正文"));
    }

    @Test
    void emptyBodyIsRejected() {
        assertThrows(BusinessException.class, () -> MarkdownParser.parse("x.md", "---\ntitle: 只有头\n---\n   \n"));
    }
}
