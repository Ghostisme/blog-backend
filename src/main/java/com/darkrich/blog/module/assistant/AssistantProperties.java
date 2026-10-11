package com.darkrich.blog.module.assistant;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** Independent overrides; application.yml falls back to the existing translation provider. */
@Validated
@ConfigurationProperties("blog.assistant")
public record AssistantProperties(
        @DefaultValue("false") boolean enabled,
        @NotBlank @DefaultValue("https://api.openai.com/v1") String baseUrl,
        @DefaultValue("") String apiKey,
        @NotBlank @DefaultValue("gpt-4o-mini") String model,
        @Min(1) @Max(50) @DefaultValue("12") int requestsPerWindow) {
    public boolean available() {
        return enabled && apiKey != null && !apiKey.isBlank();
    }
}
