package com.darkrich.blog.module.importer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HtmlToMarkdownTest {

    @Test
    void convertsArticleAndDropsChrome() {
        String html = """
                <html><body>
                <nav>导航不要</nav>
                <article>
                  <h1>标题</h1>
                  <p>第一段 <strong>粗</strong> 和 <a href="https://example.com/x">链接</a></p>
                  <pre><code>code();</code></pre>
                  <ul><li>一项</li></ul>
                  <img alt="图" src="https://cdn.example.com/a.png">
                </article>
                <footer>页脚不要</footer>
                </body></html>
                """;
        String md = HtmlToMarkdown.convert(html, "https://example.com/post");
        assertTrue(md.contains("# 标题"));
        assertTrue(md.contains("**粗**"));
        assertTrue(md.contains("[链接](https://example.com/x)"));
        assertTrue(md.contains("```"));
        assertTrue(md.contains("code();"));
        assertTrue(md.contains("- 一项"));
        assertTrue(md.contains("![图](https://cdn.example.com/a.png)"));
        assertFalse(md.contains("导航不要"));
        assertFalse(md.contains("页脚不要"));
    }
}
