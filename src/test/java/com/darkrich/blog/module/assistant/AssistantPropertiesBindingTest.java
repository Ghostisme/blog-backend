package com.darkrich.blog.module.assistant;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import java.io.IOException;
import java.io.UncheckedIOException;
import static org.assertj.core.api.Assertions.assertThat;

class AssistantPropertiesBindingTest {
    ApplicationContextRunner context() {
        return new ApplicationContextRunner().withUserConfiguration(Config.class).withInitializer(ctx -> {
            // Test the actual YAML fallbacks independently of Jenkins's optional blank parameters.
            var sources = ctx.getEnvironment().getPropertySources();
            sources.remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME);
            sources.remove(StandardEnvironment.SYSTEM_PROPERTIES_PROPERTY_SOURCE_NAME);
            try {
                new YamlPropertySourceLoader().load("application", new ClassPathResource("application.yml"))
                        .forEach(sources::addLast);
            } catch (IOException e) { throw new UncheckedIOException(e); }
        });
    }
    @Test void reusesTheExistingTranslationProviderByDefault() {
        context().withPropertyValues("BLOG_TRANSLATION_ENABLED=true", "BLOG_TRANSLATION_BASE_URL=https://provider.test/v1",
                "BLOG_TRANSLATION_API_KEY=test-key", "BLOG_TRANSLATION_MODEL=test-model").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            var properties = ctx.getBean(AssistantProperties.class);
            assertThat(properties.available()).isTrue();
            assertThat(properties.baseUrl()).isEqualTo("https://provider.test/v1");
            assertThat(properties.apiKey()).isEqualTo("test-key");
            assertThat(properties.model()).isEqualTo("test-model");
        });
    }
    @Test void supportsPartialIndependentOverrides() {
        context().withPropertyValues("BLOG_TRANSLATION_ENABLED=true", "BLOG_TRANSLATION_BASE_URL=https://provider.test/v1",
                "BLOG_TRANSLATION_API_KEY=translation-key", "BLOG_TRANSLATION_MODEL=translation-model",
                "BLOG_ASSISTANT_API_KEY=assistant-key", "BLOG_ASSISTANT_MODEL=assistant-model").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            var properties = ctx.getBean(AssistantProperties.class);
            assertThat(properties.available()).isTrue();
            assertThat(properties.baseUrl()).isEqualTo("https://provider.test/v1");
            assertThat(properties.apiKey()).isEqualTo("assistant-key");
            assertThat(properties.model()).isEqualTo("assistant-model");
        });
    }
    @Test void assistantCanBeDisabledWhileTranslationRemainsEnabled() {
        context().withPropertyValues("BLOG_TRANSLATION_ENABLED=true", "BLOG_TRANSLATION_API_KEY=test-key",
                "BLOG_ASSISTANT_ENABLED=false").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(AssistantProperties.class).available()).isFalse();
        });
    }
    @EnableConfigurationProperties(AssistantProperties.class)
    static class Config {}
}
