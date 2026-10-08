package com.darkrich.blog.module.article;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/** Stable source-version hash used to make translation jobs idempotent. */
public final class ArticleTranslationHash {

    private ArticleTranslationHash() {
    }

    public static String of(String title, String summary, String content) {
        String source = value(title) + "\n" + value(summary) + "\n" + value(content);
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(source.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                out.append(Character.forDigit((b >>> 4) & 0x0f, 16));
                out.append(Character.forDigit(b & 0x0f, 16));
            }
            return out.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 不可用", e);
        }
    }

    public static String of(Article article) {
        return of(article.getTitle(), article.getSummary(), article.getContent());
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }
}
