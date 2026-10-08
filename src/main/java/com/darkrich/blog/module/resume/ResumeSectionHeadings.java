package com.darkrich.blog.module.resume;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 简历小节标题识别。
 *
 * <p>标题有两种形态：独占一行（「工作经历」），或与第一条内容挤在同一行（「工作经历 2022.2-2025.2 某公司」）。
 * 后者来自「左侧标题栏 + 右侧正文」的版式：按位置排序抽文本时，标题和正文第一行处在同一水平线上，会被拼成一行。
 * 漏掉这种形态，整段内容就会落进上一个小节——典型表现是项目经历被塞进个人简介。
 */
final class ResumeSectionHeadings {

    enum Section {
        HEADER, SUMMARY, SKILLS, EXPERIENCE, PROJECTS, EDUCATION, OTHER
    }

    /**
     * @param rest    标题同一行里紧跟的正文，没有则为空串
     * @param sidebar 标题是全大写英文（左侧标题栏版式）。只有这种版式会出现标题与条目错行的问题，
     *                据此才去把错位的首行挪回来，避免误伤普通简历
     */
    record Match(Section section, String rest, boolean sidebar) {
    }

    private static final Pattern SIDEBAR_CAPS = Pattern.compile("[A-Z][A-Z &]*");

    /** 独占一行时认的标题（已去掉冒号、数字、空白并转小写）。 */
    private static final Map<String, Section> WHOLE_LINE = new HashMap<>();

    /**
     * 允许与正文同行的标题。只放不会出现在正文里的长词：
     * 「项目」「教育」这类短词后面跟空格太常见（「项目 管理平台」），会把正文误判成标题，所以只在独占一行时才认。
     */
    private static final Map<String, Section> INLINE = Map.ofEntries(
            Map.entry("工作经历", Section.EXPERIENCE),
            Map.entry("工作经验", Section.EXPERIENCE),
            Map.entry("实习经历", Section.EXPERIENCE),
            Map.entry("项目经历", Section.PROJECTS),
            Map.entry("项目经验", Section.PROJECTS),
            Map.entry("精选项目", Section.PROJECTS),
            Map.entry("代表项目", Section.PROJECTS),
            Map.entry("重点项目", Section.PROJECTS),
            Map.entry("核心项目", Section.PROJECTS),
            Map.entry("项目作品", Section.PROJECTS),
            Map.entry("教育经历", Section.EDUCATION),
            Map.entry("教育背景", Section.EDUCATION),
            Map.entry("个人简介", Section.SUMMARY),
            Map.entry("自我评价", Section.SUMMARY),
            Map.entry("个人评价", Section.SUMMARY),
            Map.entry("专业技能", Section.SKILLS),
            Map.entry("技能清单", Section.SKILLS),
            Map.entry("核心技能", Section.SKILLS));

    /** 全大写的英文标题，允许与正文同行；区分大小写，所以正文里的 "Experience in ..." 不会被误认。 */
    private static final Map<String, Section> INLINE_CAPS = Map.ofEntries(
            Map.entry("PROFESSIONAL SUMMARY", Section.SUMMARY),
            Map.entry("SUMMARY", Section.SUMMARY),
            Map.entry("PROFILE", Section.SUMMARY),
            Map.entry("TECHNICAL SKILLS", Section.SKILLS),
            Map.entry("CORE SKILLS", Section.SKILLS),
            Map.entry("SKILLS", Section.SKILLS),
            Map.entry("PROFESSIONAL EXPERIENCE", Section.EXPERIENCE),
            Map.entry("WORK EXPERIENCE", Section.EXPERIENCE),
            Map.entry("EXPERIENCE", Section.EXPERIENCE),
            Map.entry("SELECTED PROJECTS", Section.PROJECTS),
            Map.entry("FEATURED PROJECTS", Section.PROJECTS),
            Map.entry("KEY PROJECTS", Section.PROJECTS),
            Map.entry("PROJECTS", Section.PROJECTS),
            Map.entry("EDUCATION", Section.EDUCATION));

    static {
        register(Section.SUMMARY, "个人简介", "简介", "自我评价", "个人评价", "自我介绍", "个人总结", "个人优势",
                "summary", "profile", "about", "professionalsummary");
        register(Section.SKILLS, "技能", "专业技能", "技能清单", "核心技能", "技能特长", "skills", "technicalskills",
                "coreskills", "keyskills");
        register(Section.EXPERIENCE, "工作经历", "工作经验", "职业经历", "实习经历",
                "experience", "workexperience", "employment", "professionalexperience", "workhistory");
        register(Section.PROJECTS, "项目经历", "项目经验", "项目", "个人项目", "主要项目", "项目实践",
                "精选项目", "代表项目", "重点项目", "核心项目", "项目作品",
                "projects", "projectexperience", "selectedprojects", "featuredprojects", "keyprojects",
                "personalprojects");
        register(Section.EDUCATION, "教育经历", "教育背景", "教育", "education", "academic");
        register(Section.OTHER, "获奖", "证书", "荣誉", "awards", "certificates", "languages");
    }

    private ResumeSectionHeadings() {
    }

    /** 不是标题返回 null。current 用来区分「技术栈」是简历开头的技能标题，还是项目里的技术栈标签。 */
    static Match detect(String line, Section current) {
        String normalized = line.replaceAll("[：:\\d.、\\s]", "").toLowerCase(Locale.ROOT);
        if (!normalized.isEmpty() && normalized.length() <= 20) {
            Section whole = WHOLE_LINE.get(normalized);
            if (whole != null) {
                return new Match(whole, "", SIDEBAR_CAPS.matcher(line.strip()).matches());
            }
            // 带冒号的「技术栈：」是项目/工作条目里的标签，不是标题
            if (normalized.equals("技术栈") && isLead(current) && !line.contains("：") && !line.contains(":")) {
                return new Match(Section.SKILLS, "", false);
            }
        }
        for (Map.Entry<String, Section> e : INLINE.entrySet()) {
            Match m = splitAfter(line, e.getKey(), e.getValue(), false);
            if (m != null) {
                return m;
            }
        }
        for (Map.Entry<String, Section> e : INLINE_CAPS.entrySet()) {
            Match m = splitAfter(line, e.getKey(), e.getValue(), true);
            if (m != null) {
                return m;
            }
        }
        return isLead(current) ? splitAfter(line, "技术栈", Section.SKILLS, false) : null;
    }

    /** 还没进入经历类小节（页眉或简介）。 */
    private static boolean isLead(Section current) {
        return current == Section.HEADER || current == Section.SUMMARY;
    }

    /** 要求标题后紧跟空白：「技术栈：」「项目经历丰富」都不能算标题。 */
    private static Match splitAfter(String line, String keyword, Section section, boolean sidebar) {
        if (line.length() <= keyword.length() || !line.startsWith(keyword)
                || !Character.isWhitespace(line.charAt(keyword.length()))) {
            return null;
        }
        return new Match(section, line.substring(keyword.length()).strip(), sidebar);
    }

    private static void register(Section section, String... aliases) {
        for (String alias : aliases) {
            WHOLE_LINE.put(alias.toLowerCase(Locale.ROOT), section);
        }
    }
}
