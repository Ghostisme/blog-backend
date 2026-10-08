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
import java.util.List;

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

    /**
     * 双栏版式下 PDFBox 会把左栏标题和右栏首行正文拼成一行（「项目经历 2025.3-2025.8 某平台」），
     * 以前整段会因此认不出标题，项目经历被吞进个人简介。
     */
    @Test
    void headingGluedToFirstLineStillSplitsSections() {
        String text = """
                张三
                热衷于技术开发，主导过多个中大型项目，连续三年绩效良好。
                本科  上海  5年经验  微信：zhangsan
                工作经历 2023.3-2025.10 示例科技 后端工程师
                负责订单服务，保证高峰期稳定
                和下游系统的对接
                - 把接口延迟打下来
                项目经历 2024.3-2024.8 示例管理平台重构
                技术栈：Java、Spring Boot
                项目职责：负责核心模块设计并落地
                教育背景 2015.09-2019.06 示例大学（本科） 计算机科学与技术
                个人评价 学习能力强，沟通顺畅
                """;
        ResumeContent c = parser.fromText(text);

        assertEquals("张三", c.basics().name());
        assertEquals("上海", c.basics().location());
        assertTrue(c.summary().contains("中大型项目"));
        assertTrue(c.summary().contains("学习能力强"));

        assertEquals(1, c.experience().size());
        assertEquals("示例科技", c.experience().get(0).company());
        assertEquals("后端工程师", c.experience().get(0).position());
        assertTrue(c.experience().get(0).highlights().stream().anyMatch(h -> h.contains("和下游系统")));

        assertEquals(1, c.projects().size());
        assertEquals("示例管理平台重构", c.projects().get(0).name());
        assertTrue(c.projects().get(0).techStack().contains("Spring Boot"));

        assertEquals(1, c.education().size());
        assertEquals("示例大学", c.education().get(0).school());
        assertEquals("计算机科学与技术", c.education().get(0).major());
    }

    /** 「精选项目」「代表项目」等非常规项目标题也要认，否则整段项目会落进上一小节（个人简介/工作经历）。 */
    @Test
    void featuredProjectHeadingStartsProjectsSection() {
        String text = """
                张三
                个人评价 学习能力强，沟通顺畅
                工作经历 2023.3-2025.10 示例科技
                后端工程师
                负责订单服务
                精选项目 2024.3-2024.8 示例管理平台重构
                后端负责人
                技术栈：Java、Spring Boot
                项目职责：负责核心模块设计并落地
                """;
        ResumeContent c = parser.fromText(text);

        assertEquals(1, c.experience().size());
        assertEquals(1, c.projects().size());
        assertEquals("示例管理平台重构", c.projects().get(0).name());
        assertTrue(c.projects().get(0).techStack().contains("Spring Boot"));
        assertTrue(!c.summary().contains("管理平台"));
    }

    /**
     * 英文版、左侧标题栏版式（PROFILE / CORE SKILLS / EXPERIENCE ...）的真实抽取结果缩影：
     * 标题居中于小节，被拼在小节第二行上，首行（技能行、日期行）留在了上一小节末尾；
     * 另有页码、每页重复的页眉、带括号的技能项、不带冒号折行的技术栈。
     */
    @Test
    void englishSidebarLayoutSplitsIntoSections() {
        String text = """
                Rich Li
                AI FULL-STACK ENGINEER
                Shanghai    |    8+ years' experience    |    Bachelor's degree jane@example.com
                AI full-stack engineer with 8+ years of experience building enterprise software and complex systems.
                PROFILE Since 2025, focused on end-to-end AI application development across React / Next.js, NestJS BFF,
                and Docker delivery.
                Frontend: React 19, Next.js App Router, Vue 2/3, TypeScript, Zustand, Redux Toolkit, React Query
                CORE SKILLS Backend & Data: Node.js (NestJS / Koa / Express), FastAPI, SQLAlchemy, Alembic, REST APIs, BFF,
                MySQL, PostgreSQL
                Engineering Delivery: Docker, Jenkins, Nginx, CI/CD, Vite
                2025.12 - 2026.6 Taodou Technology (Shanghai) Co., Ltd.
                EXPERIENCE AI Full-Stack Engineer
                Led delivery of AI advertising products. Owned frontend architecture, NestJS BFF collaboration, Agent workflow
                integration, and engineering enablement.
                - Partnered with Python Agent and BFF services on authorization, multi-tenant configuration,
                context management, and API encapsulation.
                1 / 2
                Rich Li AI FULL-STACK ENGINEER  |  jane@example.com
                2020.8 - 2021.12 Shanghai Tonglin / Shanghai Ruizhuo
                EXPERIENCE Senior Frontend / Frontend Engineer
                Reworked frontend architecture for ERP platforms.
                - Migrated Vue 2 to Vue 3 and legacy state management;
                developed long-form caching and cross-tab sync.
                2026.7 - 2026.8 Overseas Pharmaceutical E-commerce & Operations Platform
                SELECTED PROJECTS Full-Stack Engineer
                Tech stack: React, TypeScript, Redux Toolkit, React Query, FastAPI, SQLAlchemy, Alembic, MySQL,
                Docker, Jenkins, Nginx
                Contributed to Customer / Staff dual portals and two FastAPI backends, owning the policy-based IAM
                core.
                - Created policy registrations for 42 business domains.
                2025.12 - 2026.6 Xingdong Admin & Multi-platform Mini-programs
                Frontend Lead
                Tech stack: React, Vue 3, Taro, Uni-app, WeChat mini-programs, Douyin mini-programs, Feishu
                mini-programs, Monorepo, slash-admin
                Delivered business modules covering influencer, asset, campaign, task collaboration and review.
                2016 - 2018   Lanzhou University (Bachelor's) Computer Science and Technology
                EDUCATION
                2 / 2
                """;
        ResumeContent c = parser.fromText(text);

        assertEquals("Rich Li", c.basics().name());
        assertEquals("AI FULL-STACK ENGINEER", c.basics().title());
        assertEquals("Shanghai", c.basics().location());
        assertEquals("jane@example.com", c.basics().email());

        assertTrue(c.summary().startsWith("AI full-stack engineer"));
        assertTrue(c.summary().contains("Since 2025"));
        assertTrue(!c.summary().contains("Frontend:"), "技能首行不应留在简介里");

        assertEquals(3, c.skills().size());
        assertEquals("Frontend", c.skills().get(0).name());
        assertEquals("Backend & Data", c.skills().get(1).name());
        assertTrue(c.skills().get(1).items().contains("Node.js (NestJS / Koa / Express)"));
        assertTrue(c.skills().get(1).items().contains("PostgreSQL"));
        assertTrue(c.skills().get(2).items().contains("CI/CD"));

        assertEquals(2, c.experience().size());
        ResumeContent.Experience first = c.experience().get(0);
        assertEquals("Taodou Technology (Shanghai) Co., Ltd.", first.company());
        assertEquals("AI Full-Stack Engineer", first.position());
        assertEquals("2025.12 - 2026.6", first.period());
        assertEquals(2, first.highlights().size());
        assertTrue(first.highlights().get(1).contains("configuration, context"), "逗号后折行要补空格");
        assertEquals("Shanghai Tonglin / Shanghai Ruizhuo", c.experience().get(1).company());
        assertEquals(2, c.experience().get(1).highlights().size(), "分号后折行的要点不应被拆成两条");
        assertTrue(c.experience().get(1).highlights().get(1).contains("management; developed"));
        assertTrue(!c.experience().get(1).highlights().stream().anyMatch(h -> h.contains("Overseas")),
                "项目的日期行不应留在上一段经历里");

        assertEquals(2, c.projects().size());
        ResumeContent.Project pharma = c.projects().get(0);
        assertEquals("Overseas Pharmaceutical E-commerce & Operations Platform", pharma.name());
        assertEquals("Full-Stack Engineer", pharma.role());
        assertEquals(List.of("React", "TypeScript", "Redux Toolkit", "React Query", "FastAPI", "SQLAlchemy",
                "Alembic", "MySQL", "Docker", "Jenkins", "Nginx"), pharma.techStack());
        assertTrue(pharma.description().startsWith("Contributed to Customer"));
        assertEquals(1, pharma.highlights().size());
        ResumeContent.Project xingdong = c.projects().get(1);
        assertEquals("Frontend Lead", xingdong.role());
        assertTrue(xingdong.techStack().contains("Feishu mini-programs"));
        assertTrue(xingdong.description().startsWith("Delivered business modules"));

        assertEquals(1, c.education().size());
        assertEquals("Lanzhou University", c.education().get(0).school());
        assertEquals("Bachelor's", c.education().get(0).degree());
        assertEquals("2016 - 2018", c.education().get(0).period());
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
