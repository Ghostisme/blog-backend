package com.darkrich.blog;

import com.darkrich.blog.module.article.AdminArticleService;
import com.darkrich.blog.module.article.ArticleEditView;
import com.darkrich.blog.module.article.ArticleLevel;
import com.darkrich.blog.module.article.ArticleSaveRequest;
import com.darkrich.blog.module.article.ArticleStatus;
import com.darkrich.blog.module.category.CategoryService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 集成测试基类：真实 MySQL 8（Testcontainers） + 完整 Spring 上下文 + MockMvc。
 *
 * <p>为什么用真实 MySQL 而不是 H2：文章搜索依赖 {@code FULLTEXT ... WITH PARSER ngram} 与
 * {@code MATCH ... AGAINST}，这是 MySQL 专有语法，H2 既不支持也无法模拟。
 *
 * <p>为什么用「单例容器」而不是 {@code @Container}：Spring 会缓存测试上下文，
 * 若每个测试类各起一个容器，第二个类复用缓存上下文时连的还是上一个已销毁容器的端口。
 * 这里容器在类加载时启动一次，整个测试 JVM 共享，由 Testcontainers 的 Ryuk 在退出时回收。
 *
 * <p>没有 Docker 时整组集成测试被跳过（{@code disabledWithoutDocker}），
 * 不影响只依赖纯单元测试的构建；静态块里同样先判断，避免未跳过前就因连不上 Docker 而报错。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
public abstract class IntegrationTestBase {

    static final MySQLContainer<?> MYSQL;

    static {
        if (DockerClientFactory.instance().isDockerAvailable()) {
            MYSQL = new MySQLContainer<>("mysql:8.0").withDatabaseName("blog");
            MYSQL.start();
        } else {
            MYSQL = null;
        }
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    protected MockMvc mvc;
    @Autowired
    protected ObjectMapper json;
    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected AdminArticleService articles;
    @Autowired
    protected CategoryService categories;

    /** 每个用例前清空文章与标签（article_tag 随外键级联清掉）；领域和简历是种子数据，保留。 */
    @BeforeEach
    void cleanData() {
        jdbc.execute("DELETE FROM article");
        jdbc.execute("DELETE FROM tag");
    }

    protected Long categoryId(String code) {
        return categories.findByCode(code).orElseThrow().getId();
    }

    protected ArticleEditView publish(String title, String content, ArticleLevel level, String categoryCode,
                                      LocalDateTime publishedAt, String... tags) {
        return articles.create(new ArticleSaveRequest(title, null, null, content, level, categoryId(categoryCode),
                ArticleStatus.PUBLISHED, List.of(tags), null, null, null, publishedAt));
    }

    protected ArticleEditView draft(String title, String content) {
        return articles.create(new ArticleSaveRequest(title, null, null, content, ArticleLevel.INTERMEDIATE, null,
                ArticleStatus.DRAFT, List.of(), null, null, null, null));
    }
}
