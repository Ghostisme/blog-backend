package com.darkrich.blog.common;

/** 文本处理小工具。 */
public final class TextUtil {

    private TextUtil() {
    }

    /**
     * 按「字符数」而非 UTF-16 单元截断，并保证不会把 emoji 等代理对从中间劈开。
     *
     * @param text      原文，可为 null
     * @param maxChars  最大字符数（码点数）
     * @param ellipsis  发生截断时追加的后缀，如 "…"；追加后总长度不超过 maxChars
     */
    public static String truncate(String text, int maxChars, String ellipsis) {
        if (text == null) {
            return null;
        }
        if (text.codePointCount(0, text.length()) <= maxChars) {
            return text;
        }
        int keep = Math.max(0, maxChars - ellipsis.codePointCount(0, ellipsis.length()));
        int end = text.offsetByCodePoints(0, keep);
        return text.substring(0, end) + ellipsis;
    }

    /** 去掉首尾空白；为空则返回 null，便于把“空字符串”统一存成 NULL。 */
    public static String blankToNull(String text) {
        if (text == null) {
            return null;
        }
        String t = text.trim();
        return t.isEmpty() ? null : t;
    }

    /**
     * 估算字数：中日韩字符每个算 1，连续的英文字母/数字算 1 个词。
     * 用于估算阅读时长，不要求精确。
     */
    public static int countWords(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int count = 0;
        boolean inWord = false;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (isCjk(cp)) {
                count++;
                inWord = false;
            } else if (Character.isLetterOrDigit(cp)) {
                if (!inWord) {
                    count++;
                    inWord = true;
                }
            } else {
                inWord = false;
            }
        }
        return count;
    }

    private static boolean isCjk(int cp) {
        Character.UnicodeScript script = Character.UnicodeScript.of(cp);
        return script == Character.UnicodeScript.HAN
                || script == Character.UnicodeScript.HIRAGANA
                || script == Character.UnicodeScript.KATAKANA
                || script == Character.UnicodeScript.HANGUL;
    }
}
