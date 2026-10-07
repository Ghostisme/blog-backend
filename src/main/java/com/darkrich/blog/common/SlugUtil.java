package com.darkrich.blog.common;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;

/**
 * 标识（slug）生成。
 *
 * <p>设计目标是「同样的输入永远得到同样的 slug，且不同的输入几乎不会撞」：
 * <ul>
 *   <li>导入去重：重复导入同一篇文章，slug 相同，靠唯一键即可识别并跳过；</li>
 *   <li>中文标题：转不出可读的 ASCII，统一用「前缀 + 哈希」兜底；</li>
 *   <li>{@code C} / {@code C#} / {@code C++} 这类去掉符号后会变成同一个词的名字，
 *       靠末尾的哈希（基于原始文本而非 ASCII 部分）区分。</li>
 * </ul>
 */
public final class SlugUtil {

    private static final int HASH_LENGTH = 8;

    private SlugUtil() {
    }

    /**
     * 生成「可读前缀-哈希」形式的 slug。
     *
     * @param text        原始文本（标题 / 标签名）
     * @param fallback    文本里没有任何 ASCII 字母数字时使用的前缀，如 "post" / "tag"
     * @param maxBaseLength 可读前缀的最大长度，调用方按列宽减去哈希部分后传入
     * @return 形如 {@code spring-boot-3a9f12cd}，只含小写字母、数字和连字符
     */
    public static String generate(String text, String fallback, int maxBaseLength) {
        String normalized = text.trim().toLowerCase(Locale.ROOT);
        String base = asciiBase(normalized, maxBaseLength);
        if (base.isEmpty()) {
            base = fallback;
        }
        return base + "-" + shortHash(normalized);
    }

    /** 只保留 ASCII 字母数字，其余连续字符折叠成单个连字符，并截断到指定长度。 */
    static String asciiBase(String normalized, int maxLength) {
        String base = normalized.replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        if (base.length() > maxLength) {
            base = base.substring(0, maxLength).replaceAll("-+$", "");
        }
        return base;
    }

    private static String shortHash(String normalized) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest).substring(0, HASH_LENGTH);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 是 JDK 必备算法，走到这里说明运行环境本身有问题
            throw new IllegalStateException("JDK 缺少 SHA-256", e);
        }
    }
}
