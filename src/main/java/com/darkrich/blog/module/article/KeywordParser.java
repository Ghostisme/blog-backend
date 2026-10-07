package com.darkrich.blog.module.article;

import java.util.ArrayList;
import java.util.List;

/**
 * 把用户输入的搜索词拆成「全文检索表达式 + LIKE 模式」两部分。
 *
 * <p>为什么要拆：文章表上的 FULLTEXT 索引使用 ngram 分词（最小词长 2），
 * <ul>
 *   <li>长度 ≥ 2 的词走全文索引，中文子串匹配快；</li>
 *   <li>单字符（如 "C"、"R"）或不含任何字母数字/汉字的词（如 "++"）没有对应的 ngram 词元，
 *       全文检索会直接查不到，必须退化成 LIKE。</li>
 * </ul>
 * 多个词之间是 AND 关系。
 */
public final class KeywordParser {

    /** 最多处理的词数，防止超长输入拼出巨大的查询。 */
    private static final int MAX_TERMS = 5;

    private KeywordParser() {
    }

    /**
     * @param ftsExpr      布尔模式表达式，如 {@code +"微服务" +"spring"}；没有可用词时为 null
     * @param likePatterns LIKE 模式列表（已转义通配符），没有时为空列表
     */
    public record Parsed(String ftsExpr, List<String> likePatterns) {
        public boolean isEmpty() {
            return ftsExpr == null && likePatterns.isEmpty();
        }
    }

    public static Parsed parse(String keyword) {
        if (keyword == null || keyword.isBlank()) {
            return new Parsed(null, List.of());
        }
        List<String> fts = new ArrayList<>();
        List<String> likes = new ArrayList<>();
        String[] terms = keyword.trim().split("\\s+");
        for (int i = 0; i < Math.min(terms.length, MAX_TERMS); i++) {
            // 双引号和反斜杠在布尔模式的短语语法里有特殊含义，直接剔除；其余操作符放进引号后已失效
            String term = terms[i].replace("\"", "").replace("\\", "");
            if (term.isEmpty()) {
                continue;
            }
            if (usableForFulltext(term)) {
                fts.add("+\"" + term + "\"");
            } else {
                likes.add("%" + escapeLike(term) + "%");
            }
        }
        return new Parsed(fts.isEmpty() ? null : String.join(" ", fts), likes);
    }

    /** 至少包含两个连续的「字母/数字/汉字」字符，才能切出 ngram 词元。 */
    private static boolean usableForFulltext(String term) {
        int run = 0;
        for (int i = 0; i < term.length(); ) {
            int cp = term.codePointAt(i);
            i += Character.charCount(cp);
            if (Character.isLetterOrDigit(cp)) {
                if (++run >= 2) {
                    return true;
                }
            } else {
                run = 0;
            }
        }
        return false;
    }

    /** 转义 LIKE 通配符，防止用户输入的 % 或 _ 被当成通配符。 */
    private static String escapeLike(String s) {
        return s.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
