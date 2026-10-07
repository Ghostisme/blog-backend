package com.darkrich.blog.module.article;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KeywordParserTest {

    @Test
    void blankInputIsEmpty() {
        assertTrue(KeywordParser.parse(null).isEmpty());
        assertTrue(KeywordParser.parse("   ").isEmpty());
    }

    @Test
    void longTermsGoToFulltextAsRequiredPhrases() {
        KeywordParser.Parsed p = KeywordParser.parse("微服务 spring");

        assertEquals("+\"微服务\" +\"spring\"", p.ftsExpr());
        assertTrue(p.likePatterns().isEmpty());
    }

    @Test
    void shortTermsFallBackToLike() {
        // 单字符、纯符号切不出 ngram 词元，走全文索引会永远查不到
        KeywordParser.Parsed p = KeywordParser.parse("c++ spring");

        assertEquals("+\"spring\"", p.ftsExpr());
        assertEquals(List.of("%c++%"), p.likePatterns());
    }

    @Test
    void onlyShortTermMeansNoFulltext() {
        KeywordParser.Parsed p = KeywordParser.parse("a");

        assertNull(p.ftsExpr());
        assertEquals(List.of("%a%"), p.likePatterns());
    }

    @Test
    void likeWildcardsAreEscaped() {
        KeywordParser.Parsed p = KeywordParser.parse("a_b");

        assertEquals(List.of("%a\\_b%"), p.likePatterns());
    }

    @Test
    void quotesAreStrippedSoTheyCannotBreakBooleanSyntax() {
        KeywordParser.Parsed p = KeywordParser.parse("he\"llo");

        assertEquals("+\"hello\"", p.ftsExpr());
    }

    @Test
    void termCountIsCapped() {
        KeywordParser.Parsed p = KeywordParser.parse("aa bb cc dd ee ff gg");

        assertEquals(5, p.ftsExpr().split(" ").length);
    }
}
