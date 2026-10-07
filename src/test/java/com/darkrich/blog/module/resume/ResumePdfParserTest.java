package com.darkrich.blog.module.resume;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResumePdfParserTest {

    private final ResumePdfParser parser = new ResumePdfParser();

    @Test
    void fromTextFillsCommonChineseSections() {
        String text = """
                李智
                全栈工程师
                上海
                lizhi@example.com
                13800138000
                https://github.com/ghostisme

                个人简介
                做过支付和内容平台。

                专业技能
                前端：React、TypeScript
                后端：Java、MySQL

                工作经历
                某某科技 | 后端工程师
                2021.03 - 至今 上海
                - 负责订单服务
                - 把接口延迟打下来

                项目经历
                个人博客 | 全栈
                2024.01 - 2024.06
                https://blog.darkrich.com
                用 Spring Boot 和 React 搭的站点
                技术栈：Java、React

                教育经历
                某大学
                本科 | 计算机
                2013.09 - 2017.06
                """;
        ResumeContent c = parser.fromText(text);
        assertEquals("李智", c.basics().name());
        assertEquals("lizhi@example.com", c.basics().email());
        assertTrue(c.basics().phone().contains("138"));
        assertTrue(c.basics().website().contains("github.com"));
        assertTrue(c.summary().contains("支付"));
        assertEquals(2, c.skills().size());
        assertEquals("React", c.skills().get(0).items().get(0));
        assertEquals("某某科技", c.experience().get(0).company());
        assertEquals("后端工程师", c.experience().get(0).position());
        assertTrue(c.experience().get(0).highlights().get(0).contains("订单"));
        assertEquals("个人博客", c.projects().get(0).name());
        assertTrue(c.projects().get(0).url().contains("blog.darkrich.com"));
        assertEquals("某大学", c.education().get(0).school());
        assertEquals("计算机", c.education().get(0).major());
    }

    @Test
    void emptyOrWrongTypeIsRejected() {
        assertThrows(com.darkrich.blog.common.BusinessException.class,
                () -> parser.parse(new MockMultipartFile("file", "a.txt", "text/plain", "x".getBytes(StandardCharsets.UTF_8))));
        assertThrows(com.darkrich.blog.common.BusinessException.class,
                () -> parser.parse(new MockMultipartFile("file", "a.pdf", "application/pdf", new byte[0])));
    }

    @Test
    void parseBytesReadsTextLayerPdf() throws Exception {
        byte[] pdf = simplePdf("Alice\nSoftware Engineer\nalice@example.com\nSummary\nBuilt APIs.\nSkills\nJava, Spring");
        ResumeContent c = parser.parseBytes(pdf);
        assertEquals("Alice", c.basics().name());
        assertEquals("alice@example.com", c.basics().email());
        assertTrue(c.summary().contains("Built APIs"));
        assertTrue(c.skills().get(0).items().contains("Java"));
    }

    private static byte[] simplePdf(String text) throws Exception {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.beginText();
                cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                cs.setLeading(14);
                cs.newLineAtOffset(50, 750);
                for (String line : text.split("\n")) {
                    cs.showText(line);
                    cs.newLine();
                }
                cs.endText();
            }
            doc.save(out);
            return out.toByteArray();
        }
    }
}
