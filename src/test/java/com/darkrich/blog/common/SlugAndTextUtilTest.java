package com.darkrich.blog.common;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SlugAndTextUtilTest {

    @Test
    void slugIsDeterministic() {
        assertEquals(SlugUtil.generate("Spring Boot 入门", "post", 50), SlugUtil.generate("Spring Boot 入门", "post", 50));
    }

    @Test
    void slugUsesReadableAsciiPrefix() {
        assertTrue(SlugUtil.generate("Spring Boot 入门", "post", 50).startsWith("spring-boot-"));
    }

    @Test
    void pureChineseUsesFallbackPrefix() {
        assertTrue(SlugUtil.generate("微服务架构", "post", 50).startsWith("post-"));
    }

    @Test
    void symbolOnlyDifferencesStillYieldDifferentSlugs() {
        // C / C# / C++ 去掉符号后前缀都是 "c"，靠基于原文的哈希区分
        String c = SlugUtil.generate("C", "tag", 50);
        String cSharp = SlugUtil.generate("C#", "tag", 50);
        String cpp = SlugUtil.generate("C++", "tag", 50);

        assertNotEquals(c, cSharp);
        assertNotEquals(c, cpp);
        assertNotEquals(cSharp, cpp);
    }

    @Test
    void slugIsCaseInsensitive() {
        assertEquals(SlugUtil.generate("Vue", "tag", 50), SlugUtil.generate("vue", "tag", 50));
    }

    @Test
    void slugRespectsLengthBudget() {
        String slug = SlugUtil.generate("a".repeat(500), "post", 20);

        // 20 位前缀 + "-" + 8 位哈希
        assertEquals(29, slug.length());
    }

    @Test
    void truncateDoesNotSplitSurrogatePairs() {
        assertEquals("a😀", TextUtil.truncate("a😀b", 2, ""));
        assertEquals("a😀…", TextUtil.truncate("a😀bc", 3, "…"));
    }

    @Test
    void truncateLeavesShortTextAlone() {
        assertEquals("abc", TextUtil.truncate("abc", 3, "…"));
        assertNull(TextUtil.truncate(null, 3, "…"));
    }

    @Test
    void blankToNull() {
        assertNull(TextUtil.blankToNull("   "));
        assertNull(TextUtil.blankToNull(null));
        assertEquals("x", TextUtil.blankToNull(" x "));
    }

    @Test
    void countWordsCountsCjkPerCharAndLatinPerWord() {
        assertEquals(4, TextUtil.countWords("Hello 世界 abc123"));
        assertEquals(0, TextUtil.countWords(""));
    }
}
