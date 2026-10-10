package com.darkrich.blog.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

// 回归：BlogProperties 多一个兼容构造器后，Boot 会退回 JavaBean 绑定导致整个上下文起不来。
// 集成测试依赖 Docker，这里用不依赖容器的 ApplicationContextRunner 单独守住绑定。
class BlogPropertiesBindingTest {
    @Test
    void bindsDespiteExtraCompatibilityConstructor() {
        new ApplicationContextRunner()
                .withUserConfiguration(Cfg.class)
                .withPropertyValues(
                        "blog.admin.username=admin",
                        "blog.admin.password-hash=$2a$10$" + "a".repeat(53),
                        "blog.jwt.secret=" + "s".repeat(40),
                        "blog.cookie.name=blog_token")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    BlogProperties p = ctx.getBean(BlogProperties.class);
                    assertThat(p.translation().enabled()).isFalse();
                    assertThat(p.login().maxFailures()).isEqualTo(5);
                });
    }

    @EnableConfigurationProperties(BlogProperties.class)
    static class Cfg {}
}
