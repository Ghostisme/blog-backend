package com.darkrich.blog;

import com.darkrich.blog.module.article.ArticleEditView;
import com.darkrich.blog.module.article.ArticleLevel;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 前台公开接口 + 访问控制边界。 */
class PublicApiIntegrationTest extends IntegrationTestBase {

    private static final LocalDateTime NOW = LocalDateTime.now().withNano(0);

    @Test
    void listShowsOnlyPublishedArticles() throws Exception {
        publish("公开文章", "这是一篇已经发布的文章正文内容", ArticleLevel.INTERMEDIATE, "frontend", NOW);
        draft("草稿文章", "这是一篇还没发布的草稿正文内容");

        mvc.perform(get("/api/articles"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].title").value("公开文章"));
    }

    @Test
    void listDoesNotLeakContent() throws Exception {
        publish("文章", "正文不应出现在列表里", ArticleLevel.INTERMEDIATE, "frontend", NOW);

        mvc.perform(get("/api/articles"))
                .andExpect(jsonPath("$.data.records[0].content").doesNotExist());
    }

    @Test
    void filtersByLevelCategoryAndTag() throws Exception {
        publish("前端入门", "正文正文正文", ArticleLevel.BEGINNER, "frontend", NOW.minusDays(1), "React");
        publish("后端高级", "正文正文正文", ArticleLevel.ADVANCED, "backend", NOW, "Spring");

        mvc.perform(get("/api/articles").param("level", "ADVANCED"))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].title").value("后端高级"));

        mvc.perform(get("/api/articles").param("categoryId", String.valueOf(categoryId("frontend"))))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].title").value("前端入门"));

        Long springTag = jdbc.queryForObject("SELECT id FROM tag WHERE name_zh = 'Spring'", Long.class);
        mvc.perform(get("/api/articles").param("tagId", String.valueOf(springTag)))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].title").value("后端高级"));
    }

    @Test
    void invalidEnumParameterIs400() throws Exception {
        mvc.perform(get("/api/articles").param("level", "NOPE"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void chineseKeywordSearchUsesNgramFulltext() throws Exception {
        publish("架构笔记", "本文介绍微服务架构中的服务拆分原则", ArticleLevel.ADVANCED, "backend", NOW);

        mvc.perform(get("/api/articles").param("keyword", "微服务"))
                .andExpect(jsonPath("$.data.total").value(1));
        mvc.perform(get("/api/articles").param("keyword", "服务拆分"))
                .andExpect(jsonPath("$.data.total").value(1));
        mvc.perform(get("/api/articles").param("keyword", "量子力学"))
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void shortKeywordFallsBackToLikeOnTitle() throws Exception {
        publish("C++ 内存模型", "正文正文正文", ArticleLevel.ADVANCED, "backend", NOW);

        mvc.perform(get("/api/articles").param("keyword", "c++"))
                .andExpect(jsonPath("$.data.total").value(1));
    }

    @Test
    void searchDoesNotReturnDrafts() throws Exception {
        draft("秘密草稿", "包含独特词汇量子纠缠的草稿");

        mvc.perform(get("/api/articles").param("keyword", "量子纠缠"))
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void detailCountsViewsAndLinksNeighbours() throws Exception {
        publish("最早", "正文正文正文", ArticleLevel.INTERMEDIATE, "frontend", NOW.minusDays(2));
        ArticleEditView mid = publish("中间", "正文正文正文", ArticleLevel.INTERMEDIATE, "frontend", NOW.minusDays(1));
        publish("最新", "正文正文正文", ArticleLevel.INTERMEDIATE, "frontend", NOW);

        mvc.perform(get("/api/articles/" + mid.slug()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content").value("正文正文正文"))
                .andExpect(jsonPath("$.data.viewCount").value(1))
                .andExpect(jsonPath("$.data.older.title").value("最早"))
                .andExpect(jsonPath("$.data.newer.title").value("最新"));
        mvc.perform(get("/api/articles/" + mid.slug()))
                .andExpect(jsonPath("$.data.viewCount").value(2));
    }

    @Test
    void draftAndUnknownSlugLookTheSame() throws Exception {
        ArticleEditView d = draft("草稿", "正文正文正文");

        mvc.perform(get("/api/articles/" + d.slug())).andExpect(status().isNotFound());
        mvc.perform(get("/api/articles/no-such-article")).andExpect(status().isNotFound());
    }

    @Test
    void hotSortOrdersByViews() throws Exception {
        ArticleEditView a = publish("冷门", "正文正文正文", ArticleLevel.INTERMEDIATE, "frontend", NOW);
        ArticleEditView b = publish("热门", "正文正文正文", ArticleLevel.INTERMEDIATE, "frontend", NOW.minusDays(1));
        mvc.perform(get("/api/articles/" + b.slug()));
        mvc.perform(get("/api/articles/" + b.slug()));
        mvc.perform(get("/api/articles/" + a.slug()));

        mvc.perform(get("/api/articles").param("sort", "HOT"))
                .andExpect(jsonPath("$.data.records[0].title").value("热门"));
        mvc.perform(get("/api/articles"))
                .andExpect(jsonPath("$.data.records[0].title").value("冷门"));
    }

    @Test
    void filtersEndpointAlwaysListsAllFourLevelsInOrder() throws Exception {
        publish("文章", "正文正文正文", ArticleLevel.EXPERT, "database", NOW, "Redis");

        mvc.perform(get("/api/filters"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.levels.length()").value(4))
                .andExpect(jsonPath("$.data.levels[0].level").value("BEGINNER"))
                .andExpect(jsonPath("$.data.levels[0].count").value(0))
                .andExpect(jsonPath("$.data.levels[3].level").value("EXPERT"))
                .andExpect(jsonPath("$.data.levels[3].count").value(1))
                .andExpect(jsonPath("$.data.tags[0].nameZh").value("Redis"));
    }

    @Test
    void resumeIsPublicAndRejectsUnknownLanguage() throws Exception {
        mvc.perform(get("/api/resume").param("lang", "en"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.basics.name").value("Your Name"));
        mvc.perform(get("/api/resume").param("lang", "xx"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void adminAndUnknownRoutesAreNotPublic() throws Exception {
        mvc.perform(get("/api/admin/articles")).andExpect(status().isUnauthorized());
        // 默认拒绝：没有显式放行的写接口，匿名访问同样是 401
        mvc.perform(post("/api/articles")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/does-not-exist")).andExpect(status().isUnauthorized());
    }
}
