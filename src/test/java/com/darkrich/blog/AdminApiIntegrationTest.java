package com.darkrich.blog;

import com.darkrich.blog.module.article.ArticleEditView;
import com.darkrich.blog.module.article.ArticleLevel;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 后台：认证、CSRF、文章管理、批量操作、导入、简历保存。 */
class AdminApiIntegrationTest extends IntegrationTestBase {

    private static final String PASSWORD = "dev-password-123";
    /** 每个用例用不同的来源 IP：登录限流器是跨用例共享的单例，共用 IP 会互相锁死。 */
    private static final AtomicInteger IP_SEQ = new AtomicInteger(10);

    private static RequestPostProcessor fromIp(String ip) {
        return req -> {
            req.setRemoteAddr(ip);
            return req;
        };
    }

    private static String freshIp() {
        return "10.0.0." + IP_SEQ.incrementAndGet();
    }

    private String loginBody(String user, String pass) throws Exception {
        return json.writeValueAsString(Map.of("username", user, "password", pass));
    }

    /** 登录并返回会话 Cookie，供后续请求携带。 */
    private Cookie login() throws Exception {
        MvcResult r = mvc.perform(post("/api/admin/login").with(fromIp(freshIp()))
                        .contentType(MediaType.APPLICATION_JSON).content(loginBody("admin", PASSWORD)))
                .andExpect(status().isOk()).andReturn();
        Cookie cookie = r.getResponse().getCookie("blog_token");
        assertNotNull(cookie);
        return cookie;
    }

    private String articleBody(String title, String status, Long categoryId) throws Exception {
        Map<String, Object> body = new java.util.HashMap<>();
        body.put("title", title);
        body.put("content", "正文正文正文正文");
        body.put("status", status);
        body.put("level", "ADVANCED");
        body.put("categoryId", categoryId);
        body.put("tags", List.of("Java", "Spring"));
        return json.writeValueAsString(body);
    }

    // ------------------------------------------------------------ 认证

    @Test
    void wrongPasswordIs401() throws Exception {
        mvc.perform(post("/api/admin/login").with(fromIp(freshIp()))
                        .contentType(MediaType.APPLICATION_JSON).content(loginBody("admin", "wrong")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("用户名或密码错误"));
    }

    @Test
    void wrongUsernameGivesSameMessageAsWrongPassword() throws Exception {
        mvc.perform(post("/api/admin/login").with(fromIp(freshIp()))
                        .contentType(MediaType.APPLICATION_JSON).content(loginBody("nobody", PASSWORD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("用户名或密码错误"));
    }

    @Test
    void loginSetsHardenedCookieAndDoesNotReturnToken() throws Exception {
        MvcResult r = mvc.perform(post("/api/admin/login").with(fromIp(freshIp()))
                        .contentType(MediaType.APPLICATION_JSON).content(loginBody("admin", PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").doesNotExist())
                .andReturn();

        String setCookie = r.getResponse().getHeader("Set-Cookie");
        assertNotNull(setCookie);
        assertTrue(setCookie.contains("HttpOnly"), setCookie);
        assertTrue(setCookie.contains("SameSite=Strict"), setCookie);
        assertTrue(setCookie.contains("Path=/api/admin"), setCookie);
    }

    @Test
    void meRequiresValidCookie() throws Exception {
        mvc.perform(get("/api/admin/me")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/me").cookie(new Cookie("blog_token", "forged.token.value")))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/me").cookie(login()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("admin"));
    }

    @Test
    void logoutClearsCookie() throws Exception {
        MvcResult r = mvc.perform(post("/api/admin/logout")).andExpect(status().isOk()).andReturn();

        assertTrue(r.getResponse().getHeader("Set-Cookie").contains("Max-Age=0"));
    }

    @Test
    void repeatedFailuresLockTheIp() throws Exception {
        String ip = freshIp();
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/admin/login").with(fromIp(ip))
                            .contentType(MediaType.APPLICATION_JSON).content(loginBody("admin", "bad" + i)))
                    .andExpect(status().isUnauthorized());
        }

        // 锁定后即使口令正确也要拒绝，否则爆破者只需把正确口令放在最后一个
        mvc.perform(post("/api/admin/login").with(fromIp(ip))
                        .contentType(MediaType.APPLICATION_JSON).content(loginBody("admin", PASSWORD)))
                .andExpect(status().isTooManyRequests());
    }

    // ------------------------------------------------------------ CSRF

    @Test
    void crossOriginWriteIsRejected() throws Exception {
        Cookie session = login();

        mvc.perform(post("/api/admin/articles").cookie(session)
                        .header("Host", "blog.darkrich.com").header("Origin", "https://evil.example.com")
                        .contentType(MediaType.APPLICATION_JSON).content(articleBody("T", "DRAFT", null)))
                .andExpect(status().isForbidden());
    }

    @Test
    void sameOriginWriteIsAllowed() throws Exception {
        Cookie session = login();

        mvc.perform(post("/api/admin/articles").cookie(session)
                        .header("Host", "blog.darkrich.com").header("Origin", "https://blog.darkrich.com")
                        .contentType(MediaType.APPLICATION_JSON).content(articleBody("T", "DRAFT", null)))
                .andExpect(status().isOk());
    }

    // ------------------------------------------------------------ 文章管理

    @Test
    void createPublishedArticleAndSeeItPublicly() throws Exception {
        Cookie session = login();

        mvc.perform(post("/api/admin/articles").cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(articleBody("我的第一篇", "PUBLISHED", categoryId("backend"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.publishedAt").isNotEmpty())
                .andExpect(jsonPath("$.data.tags.length()").value(2));

        mvc.perform(get("/api/articles"))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.records[0].tags.length()").value(2));
    }

    @Test
    void publishingWithoutCategoryIsRejected() throws Exception {
        mvc.perform(post("/api/admin/articles").cookie(login())
                        .contentType(MediaType.APPLICATION_JSON).content(articleBody("T", "PUBLISHED", null)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void javascriptUrlInSourceLinkIsRejected() throws Exception {
        String body = json.writeValueAsString(Map.of("title", "T", "content", "正文",
                "sourceUrl", "javascript:alert(1)"));

        mvc.perform(post("/api/admin/articles").cookie(login())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    void duplicateSlugIsConflict() throws Exception {
        Cookie session = login();
        String body = json.writeValueAsString(Map.of("title", "T", "content", "正文", "slug", "same-slug"));

        mvc.perform(post("/api/admin/articles").cookie(session)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isOk());
        mvc.perform(post("/api/admin/articles").cookie(session)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isConflict());
    }

    @Test
    void updateReplacesTagsAndKeepsSlugAndViewCount() throws Exception {
        Cookie session = login();
        ArticleEditView created = publish("原标题", "正文正文正文", ArticleLevel.INTERMEDIATE, "backend",
                LocalDateTime.now().withNano(0), "A", "B");
        mvc.perform(get("/api/articles/" + created.slug()));

        String body = json.writeValueAsString(Map.of("title", "新标题", "content", "新正文",
                "status", "PUBLISHED", "categoryId", categoryId("backend"), "tags", List.of("C")));
        mvc.perform(put("/api/admin/articles/" + created.id()).cookie(session)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("新标题"))
                .andExpect(jsonPath("$.data.slug").value(created.slug()))
                .andExpect(jsonPath("$.data.tags.length()").value(1))
                .andExpect(jsonPath("$.data.tags[0]").value("C"))
                // 编辑是“先读后写”，不能把读到的旧浏览量写回去
                .andExpect(jsonPath("$.data.viewCount").value(1));
    }

    @Test
    void clearingOptionalFieldsActuallyClearsThem() throws Exception {
        Cookie session = login();
        String create = json.writeValueAsString(Map.of("title", "T", "content", "正文",
                "summary", "旧摘要", "sourceAuthor", "某人"));
        MvcResult r = mvc.perform(post("/api/admin/articles").cookie(session)
                .contentType(MediaType.APPLICATION_JSON).content(create)).andReturn();
        int id = json.readTree(r.getResponse().getContentAsString()).at("/data/id").asInt();

        mvc.perform(put("/api/admin/articles/" + id).cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("title", "T", "content", "正文"))))
                .andExpect(jsonPath("$.data.summary").doesNotExist())
                .andExpect(jsonPath("$.data.sourceAuthor").doesNotExist());
    }

    @Test
    void deleteRemovesArticleAndItsTagLinks() throws Exception {
        ArticleEditView a = publish("要删除", "正文正文正文", ArticleLevel.INTERMEDIATE, "backend",
                LocalDateTime.now(), "X");

        mvc.perform(delete("/api/admin/articles/" + a.id()).cookie(login())).andExpect(status().isOk());

        Integer links = jdbc.queryForObject("SELECT COUNT(*) FROM article_tag", Integer.class);
        assertTrue(links == 0);
        mvc.perform(delete("/api/admin/articles/" + a.id()).cookie(login())).andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------ 批量

    @Test
    void batchPublishMakesDraftsVisible() throws Exception {
        ArticleEditView d1 = draft("草稿一", "正文正文正文");
        ArticleEditView d2 = draft("草稿二", "正文正文正文");
        String body = json.writeValueAsString(Map.of("ids", List.of(d1.id(), d2.id()),
                "status", "PUBLISHED", "categoryId", categoryId("frontend"), "level", "BEGINNER"));

        mvc.perform(patch("/api/admin/articles").cookie(login())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.updated").value(2));

        mvc.perform(get("/api/articles").param("level", "BEGINNER"))
                .andExpect(jsonPath("$.data.total").value(2));
    }

    @Test
    void batchPublishFailsWholeBatchIfAnyLacksCategory() throws Exception {
        ArticleEditView d = draft("无领域草稿", "正文正文正文");
        String body = json.writeValueAsString(Map.of("ids", List.of(d.id()), "status", "PUBLISHED"));

        mvc.perform(patch("/api/admin/articles").cookie(login())
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/articles")).andExpect(jsonPath("$.data.total").value(0));
    }

    // ------------------------------------------------------------ 导入

    @Test
    void importCreatesDraftsSkipsDuplicatesAndReportsBadFiles() throws Exception {
        Cookie session = login();
        String md = """
                ---
                title: 深入理解 Redis 持久化原理
                tags: [Redis, 数据库]
                author: 某作者
                source_url: https://juejin.cn/post/1
                ---
                本文深入分析 Redis 的 RDB 与 AOF 两种持久化机制及其取舍，内容足够长。
                """;
        MockMultipartFile ok = new MockMultipartFile("files", "redis.md", "text/markdown", md.getBytes(StandardCharsets.UTF_8));
        MockMultipartFile txt = new MockMultipartFile("files", "notes.txt", "text/plain", "x".getBytes(StandardCharsets.UTF_8));

        mvc.perform(multipart("/api/admin/articles/import").file(ok).file(txt).cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.imported").value(1))
                .andExpect(jsonPath("$.data.failed").value(1));

        // 再导一次同一个文件：按标题派生的 slug 相同，应被识别并跳过
        mvc.perform(multipart("/api/admin/articles/import").file(ok).cookie(session))
                .andExpect(jsonPath("$.data.imported").value(0))
                .andExpect(jsonPath("$.data.skipped").value(1));

        MvcResult list = mvc.perform(get("/api/admin/articles").cookie(session)).andExpect(status().isOk()).andReturn();
        var record = json.readTree(list.getResponse().getContentAsString()).at("/data/records/0");
        assertTrue("DRAFT".equals(record.get("status").asText()), "导入的文章必须是草稿");
        assertTrue("ADVANCED".equals(record.get("level").asText()), "标题含“深入/原理”应建议为高级");
        assertTrue("database".equals(record.at("/category/code").asText()));
        // 草稿不应对前台可见
        mvc.perform(get("/api/articles")).andExpect(jsonPath("$.data.total").value(0));
    }

    // ------------------------------------------------------------ 领域 / 简历

    @Test
    void deletingCategoryInUseIsConflict() throws Exception {
        publish("文章", "正文正文正文", ArticleLevel.INTERMEDIATE, "mobile", LocalDateTime.now());

        mvc.perform(delete("/api/admin/categories/" + categoryId("mobile")).cookie(login()))
                .andExpect(status().isConflict());
    }

    @Test
    void savingResumeRoundTripsAndRejectsUnsafeLinks() throws Exception {
        Cookie session = login();
        Map<String, Object> ok = Map.of(
                "basics", Map.of("name", "张三", "title", "工程师",
                        "links", List.of(Map.of("label", "GitHub", "url", "https://github.com/x"))),
                "summary", "简介",
                "skills", List.of(Map.of("name", "后端", "items", List.of("Java", "MySQL"))));

        mvc.perform(put("/api/admin/resume/zh").cookie(session)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(ok)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/resume").param("lang", "zh"))
                .andExpect(jsonPath("$.data.basics.name").value("张三"))
                .andExpect(jsonPath("$.data.skills[0].items[1]").value("MySQL"))
                .andExpect(jsonPath("$.data.experience.length()").value(0));

        Map<String, Object> bad = Map.of("basics", Map.of("name", "x",
                "links", List.of(Map.of("label", "evil", "url", "javascript:alert(1)"))));
        mvc.perform(put("/api/admin/resume/zh").cookie(session)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(bad)))
                .andExpect(status().isBadRequest());
        assertFalse(mvc.perform(get("/api/resume").param("lang", "zh")).andReturn()
                .getResponse().getContentAsString().contains("javascript:"));
    }

    @Test
    void importUrlsRejectsPrivateAddressesAndDoesNotPersist() throws Exception {
        Cookie session = login();
        String body = json.writeValueAsString(Map.of("urls", List.of("http://127.0.0.1/secret")));
        mvc.perform(post("/api/admin/articles/import-urls").cookie(session)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.failed").value(1))
                .andExpect(jsonPath("$.data.imported").value(0));
        mvc.perform(get("/api/admin/articles").cookie(session))
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void parsePdfRequiresLoginAndRejectsNonPdf() throws Exception {
        mvc.perform(multipart("/api/admin/resume/parse-pdf")
                        .file(new MockMultipartFile("file", "a.pdf", "application/pdf", "x".getBytes(StandardCharsets.UTF_8))))
                .andExpect(status().isUnauthorized());

        mvc.perform(multipart("/api/admin/resume/parse-pdf")
                        .file(new MockMultipartFile("file", "a.txt", "text/plain", "hello".getBytes(StandardCharsets.UTF_8)))
                        .cookie(login()))
                .andExpect(status().isBadRequest());
    }
}
