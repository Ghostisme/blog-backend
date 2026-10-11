package com.darkrich.blog.module.assistant;

import com.darkrich.blog.common.BusinessException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Set;

/** The browser and server both validate tool destinations before executing them. */
public final class AssistantNavigation {
    private AssistantNavigation() {}
    public static String requirePublicPath(String value) {
        try {
            URI uri = URI.create(value);
            if (uri.isAbsolute() || uri.getRawAuthority() != null || uri.getRawFragment() != null
                    || !value.startsWith("/") || value.contains("\\") || value.length() > 512) throw new IllegalArgumentException();
            String path = uri.getPath();
            if (!Set.of("/", "/articles", "/resume").contains(path)
                    && !path.matches("/articles/[\\p{L}\\p{N}_-]+")) throw new IllegalArgumentException();
            if (uri.getRawQuery() != null) {
                if (path.startsWith("/articles/")) throw new IllegalArgumentException();
                Set<String> allowed = path.equals("/articles")
                        ? Set.of("page", "level", "category", "tag", "keyword", "sort") : Set.of("lang");
                for (String pair : uri.getRawQuery().split("&")) {
                    String key = URLDecoder.decode(pair.split("=", 2)[0], StandardCharsets.UTF_8);
                    if (!allowed.contains(key)) throw new IllegalArgumentException();
                }
            }
            return uri.toASCIIString();
        } catch (RuntimeException e) {
            throw BusinessException.badRequest("只能跳转到博客的公开页面");
        }
    }
    public static boolean explicitlyRequested(String message) {
        return message.matches("(?is).*(打开|跳转|带我|前往|转到|去看|查看简历|看.{0,8}简历|open|navigate|take me|go to|show.{0,12}resume).*");
    }
}
