package com.darkrich.blog.module.article;

import java.util.Locale;

/** Public article language. Unknown values deliberately fall back to Chinese. */
public enum ArticleLanguage {
    ZH("zh"),
    EN("en");

    private final String code;

    ArticleLanguage(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    public boolean isEnglish() {
        return this == EN;
    }

    public static ArticleLanguage from(String raw) {
        if (raw == null || raw.isBlank()) {
            return ZH;
        }
        return raw.trim().toLowerCase(Locale.ROOT).startsWith("en") ? EN : ZH;
    }
}
