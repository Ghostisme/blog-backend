package com.darkrich.blog.module.assistant;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public record AssistantRequest(
        @NotBlank @Size(max = 4000) String message,
        @Pattern(regexp = "zh|en") String lang,
        @Size(max = 512) String pagePath,
        @Valid @Size(max = 12) List<HistoryMessage> history) {
    public record HistoryMessage(@Pattern(regexp = "user|assistant") @NotBlank String role,
                                 @NotBlank @Size(max = 4000) String content) {}
    public record Decision(@NotBlank @Size(max = 64) String approvalId,
                           @NotBlank @Size(max = 64) String choiceId) {}
}
