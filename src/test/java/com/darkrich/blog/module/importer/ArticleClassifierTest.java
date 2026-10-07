package com.darkrich.blog.module.importer;

import com.darkrich.blog.module.article.ArticleLevel;
import com.darkrich.blog.module.importer.ArticleClassifier.Suggestion;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ArticleClassifierTest {

    @Test
    void classifiesFrontendByTitleAndTags() {
        Suggestion s = ArticleClassifier.classify("深入理解 Vue 响应式", List.of("前端"), "正文");

        assertEquals(ArticleClassifier.FRONTEND, s.categoryCode());
        assertEquals(ArticleLevel.ADVANCED, s.level());
    }

    @Test
    void javaDoesNotMatchInsideJavascript() {
        // 整词匹配：否则所有 JavaScript 文章都会被同时算进后端
        Suggestion s = ArticleClassifier.classify("JavaScript 基础", List.of(), "正文");

        assertEquals(ArticleClassifier.FRONTEND, s.categoryCode());
        assertEquals(ArticleLevel.BEGINNER, s.level());
    }

    @Test
    void sqlDoesNotMatchInsideMysql() {
        Suggestion s = ArticleClassifier.classify("MySQL 索引优化", List.of(), "正文");

        assertEquals(ArticleClassifier.DATABASE, s.categoryCode());
    }

    @Test
    void classifiesGitAsDevops() {
        Suggestion s = ArticleClassifier.classify("掌握 Git 分支整合", List.of("Git"), "正文");

        assertEquals(ArticleClassifier.DEVOPS, s.categoryCode());
    }

    @Test
    void githubDoesNotMatchInsideJavascript() {
        Suggestion s = ArticleClassifier.classify("GitHub Actions 入门", List.of(), "正文");

        assertEquals(ArticleClassifier.DEVOPS, s.categoryCode());
        assertEquals(ArticleLevel.BEGINNER, s.level());
    }

    @Test
    void classifiesMobile() {
        Suggestion s = ArticleClassifier.classify("Flutter 状态管理", List.of("Android"), "正文");

        assertEquals(ArticleClassifier.MOBILE, s.categoryCode());
    }

    @Test
    void expertLevelOutranksOtherSignals() {
        Suggestion s = ArticleClassifier.classify("亿级流量架构演进与原理剖析", List.of(), "正文");

        assertEquals(ArticleLevel.EXPERT, s.level());
    }

    @Test
    void returnsNoCategoryWhenEvidenceIsWeak() {
        Suggestion s = ArticleClassifier.classify("随笔", List.of(), "今天天气不错");

        assertNull(s.categoryCode());
        assertEquals(ArticleLevel.INTERMEDIATE, s.level());
    }

    @Test
    void singleBodyMentionIsNotEnough() {
        // 正文零星提到一次只有 1 分，低于阈值
        Suggestion s = ArticleClassifier.classify("一些想法", List.of(), "最近在看 react 的文档");

        assertNull(s.categoryCode());
    }

    @Test
    void toleratesNullTags() {
        Suggestion s = ArticleClassifier.classify("Redis 缓存设计", null, "正文");

        assertEquals(ArticleClassifier.DATABASE, s.categoryCode());
    }
}
