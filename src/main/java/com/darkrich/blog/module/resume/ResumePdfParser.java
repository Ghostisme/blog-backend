package com.darkrich.blog.module.resume;

import com.darkrich.blog.common.BusinessException;
import com.darkrich.blog.common.TextUtil;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 把一份文字版简历 PDF 抽成 {@link ResumeContent}，填进后台表单，<b>不自动保存</b>。
 *
 * <p>扫描件、图片型 PDF 没有文字层，抽出来是空的，会直接报错。
 * 版式因人而异，只能按常见中英文标题分段启发式提取，结果必须人工核对。
 */
@Component
public class ResumePdfParser {

    static final int MAX_BYTES = 5 * 1024 * 1024;

    private static final Pattern EMAIL =
            Pattern.compile("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}");
    private static final Pattern PHONE =
            Pattern.compile("(?:\\+?\\d{1,3}[\\s\\-]?)?(?:1[3-9]\\d{9}|\\(?\\d{2,4}\\)?[\\s\\-]?\\d{7,8})");
    private static final Pattern URL = Pattern.compile("https?://\\S+", Pattern.CASE_INSENSITIVE);
    private static final Pattern PERIOD = Pattern.compile(
            "\\d{4}\\s*[./年\\-]\\s*\\d{0,2}(?:\\s*[./月]\\s*\\d{0,2})?\\s*[-~—–至到]+\\s*"
                    + "(?:\\d{4}|至今|现在|今|Present|Now|Current)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BULLET = Pattern.compile("^\\s*(?:[•●○◆■▪-]|\\d+[.)、])\\s+");

    public ResumeContent parse(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw BusinessException.badRequest("请选择 PDF 文件");
        }
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename();
        if (!name.isBlank() && !name.toLowerCase(Locale.ROOT).endsWith(".pdf")) {
            throw BusinessException.badRequest("仅支持 PDF 文件");
        }
        if (file.getSize() > MAX_BYTES) {
            throw BusinessException.badRequest("PDF 不能超过 5MB");
        }
        byte[] bytes;
        try {
            bytes = file.getBytes();
        } catch (IOException e) {
            throw BusinessException.badRequest("读取 PDF 失败");
        }
        return parseBytes(bytes);
    }

    ResumeContent parseBytes(byte[] bytes) {
        String raw;
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            if (doc.isEncrypted()) {
                throw BusinessException.badRequest("该 PDF 已加密，无法解析");
            }
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            raw = stripper.getText(doc);
        } catch (BusinessException e) {
            throw e;
        } catch (IOException e) {
            throw BusinessException.badRequest("无法读取该 PDF，请确认是未加密的文字版");
        }
        if (raw == null || raw.strip().length() < 20) {
            throw BusinessException.badRequest("PDF 里抽不到文字。扫描件或图片型简历请改用可复制文本的 PDF，或手动填写");
        }
        return fromText(raw.replace("\r\n", "\n").replace('\r', '\n'));
    }

    ResumeContent fromText(String raw) {
        List<String> lines = new ArrayList<>();
        for (String line : raw.split("\n")) {
            String t = line.replace(' ', ' ').strip();
            if (!t.isEmpty()) {
                lines.add(t);
            }
        }
        Section current = Section.HEADER;
        List<String> header = new ArrayList<>();
        List<String> summary = new ArrayList<>();
        List<String> skills = new ArrayList<>();
        List<String> experience = new ArrayList<>();
        List<String> projects = new ArrayList<>();
        List<String> education = new ArrayList<>();

        for (String line : lines) {
            Section next = detectSection(line);
            if (next != null) {
                current = next;
                continue;
            }
            switch (current) {
                case HEADER -> header.add(line);
                case SUMMARY -> summary.add(line);
                case SKILLS -> skills.add(line);
                case EXPERIENCE -> experience.add(line);
                case PROJECTS -> projects.add(line);
                case EDUCATION -> education.add(line);
                case OTHER -> {
                    // 证书/获奖等本站简历结构没有对应区块，丢掉以免误塞进经历
                }
            }
        }
        if (summary.isEmpty() && skills.isEmpty() && experience.isEmpty()
                && projects.isEmpty() && education.isEmpty()) {
            // 没有任何可识别的小节标题：除前几行当页眉外，其余进简介，避免整份简历被丢掉
            int cut = Math.min(6, header.size());
            List<String> rest = new ArrayList<>(header.subList(cut, header.size()));
            header = new ArrayList<>(header.subList(0, cut));
            summary = rest;
        }

        ResumeContent.Basics basics = parseBasics(header);
        return new ResumeContent(
                basics,
                join(summary, 5000),
                parseSkills(skills),
                parseExperience(experience),
                parseProjects(projects),
                parseEducation(education));
    }

    private ResumeContent.Basics parseBasics(List<String> lines) {
        String email = null;
        String phone = null;
        String website = null;
        List<ResumeContent.Link> links = new ArrayList<>();
        List<String> leftover = new ArrayList<>();
        for (String line : lines) {
            Matcher em = EMAIL.matcher(line);
            if (em.find() && email == null) {
                email = TextUtil.truncate(em.group(), 128, "");
            }
            Matcher ph = PHONE.matcher(line);
            if (ph.find() && phone == null && !line.contains("@")) {
                phone = TextUtil.truncate(ph.group().replaceAll("\\s+", " ").strip(), 64, "");
            }
            Matcher um = URL.matcher(line);
            while (um.find()) {
                String url = um.group().replaceAll("[),.;]+$", "");
                if (website == null) {
                    website = TextUtil.truncate(url, 256, "");
                } else if (links.size() < 10) {
                    links.add(new ResumeContent.Link(labelOf(url), TextUtil.truncate(url, 512, "")));
                }
            }
            String cleaned = um.reset().replaceAll("").replace(email == null ? "" : email, "")
                    .replace(phone == null ? "" : phone, "").strip();
            cleaned = cleaned.replaceAll("^[|｜/\\-—]+|[|｜/\\-—]+$", "").strip();
            if (!cleaned.isEmpty() && !cleaned.equalsIgnoreCase("简历") && !cleaned.equalsIgnoreCase("Resume")) {
                leftover.add(cleaned);
            }
        }
        String name = leftover.isEmpty() ? null : TextUtil.truncate(leftover.get(0), 64, "");
        String title = leftover.size() < 2 ? null : TextUtil.truncate(leftover.get(1), 128, "");
        String location = leftover.size() < 3 ? null : TextUtil.truncate(leftover.get(2), 128, "");
        return new ResumeContent.Basics(name, title, email, phone, location, website, null, links);
    }

    private List<ResumeContent.SkillGroup> parseSkills(List<String> lines) {
        List<ResumeContent.SkillGroup> groups = new ArrayList<>();
        for (String line : lines) {
            if (groups.size() >= 20) {
                break;
            }
            String[] parts = line.split("[：:]", 2);
            String name;
            String rest;
            if (parts.length == 2 && parts[0].length() <= 16) {
                name = parts[0].strip();
                rest = parts[1];
            } else {
                name = "技能";
                rest = line;
            }
            List<String> items = new ArrayList<>();
            for (String token : rest.split("[,，、;/|｜]+")) {
                String item = token.strip();
                if (!item.isEmpty() && items.size() < 30) {
                    items.add(TextUtil.truncate(item, 64, ""));
                }
            }
            if (!items.isEmpty()) {
                groups.add(new ResumeContent.SkillGroup(TextUtil.truncate(name, 64, ""), items));
            }
        }
        return groups;
    }

    private List<ResumeContent.Experience> parseExperience(List<String> lines) {
        List<ResumeContent.Experience> out = new ArrayList<>();
        for (List<String> block : splitBlocks(lines)) {
            if (out.size() >= 30) {
                break;
            }
            String company = null;
            String position = null;
            String period = null;
            String location = null;
            List<String> highlights = new ArrayList<>();
            for (String line : block) {
                Matcher pm = PERIOD.matcher(line);
                if (pm.find() && period == null) {
                    period = TextUtil.truncate(pm.group().replaceAll("\\s+", " ").strip(), 64, "");
                    String rest = (line.substring(0, pm.start()) + " " + line.substring(pm.end())).strip();
                    rest = rest.replaceAll("^[|｜/\\-—]+|[|｜/\\-—]+$", "").strip();
                    if (!rest.isEmpty()) {
                        location = TextUtil.truncate(rest, 128, "");
                    }
                    continue;
                }
                if (BULLET.matcher(line).find()) {
                    if (highlights.size() < 20) {
                        highlights.add(TextUtil.truncate(BULLET.matcher(line).replaceFirst("").strip(), 500, ""));
                    }
                    continue;
                }
                if (company == null) {
                    String[] parts = line.split("[|｜]", 2);
                    if (parts.length == 2) {
                        company = parts[0].strip();
                        position = parts[1].strip();
                    } else {
                        company = line;
                    }
                    continue;
                }
                if (position == null) {
                    position = line;
                    continue;
                }
                if (highlights.size() < 20) {
                    highlights.add(TextUtil.truncate(line, 500, ""));
                }
            }
            if (company != null || position != null || !highlights.isEmpty()) {
                out.add(new ResumeContent.Experience(
                        TextUtil.truncate(company, 128, ""),
                        TextUtil.truncate(position, 128, ""),
                        period, location, highlights));
            }
        }
        return out;
    }

    private List<ResumeContent.Project> parseProjects(List<String> lines) {
        List<ResumeContent.Project> out = new ArrayList<>();
        for (List<String> block : splitBlocks(lines)) {
            if (out.size() >= 30) {
                break;
            }
            String name = null;
            String role = null;
            String period = null;
            String url = null;
            String description = null;
            List<String> highlights = new ArrayList<>();
            List<String> tech = new ArrayList<>();
            for (String line : block) {
                Matcher um = URL.matcher(line);
                if (um.find() && url == null) {
                    url = TextUtil.truncate(um.group().replaceAll("[),.;]+$", ""), 512, "");
                    line = um.reset().replaceAll("").strip();
                    if (line.isEmpty()) {
                        continue;
                    }
                }
                Matcher pm = PERIOD.matcher(line);
                if (pm.find() && period == null) {
                    period = TextUtil.truncate(pm.group().replaceAll("\\s+", " ").strip(), 64, "");
                    continue;
                }
                if (looksLikeTech(line)) {
                    for (String token : line.replaceAll("^(技术栈|Tech(nology)?\\s*Stack)\\s*[：:]?", "")
                            .split("[,，、;/|｜]+")) {
                        String item = token.strip();
                        if (!item.isEmpty() && tech.size() < 20) {
                            tech.add(TextUtil.truncate(item, 64, ""));
                        }
                    }
                    continue;
                }
                if (BULLET.matcher(line).find()) {
                    if (highlights.size() < 20) {
                        highlights.add(TextUtil.truncate(BULLET.matcher(line).replaceFirst("").strip(), 500, ""));
                    }
                    continue;
                }
                if (name == null) {
                    String[] parts = line.split("[|｜]", 2);
                    name = parts[0].strip();
                    if (parts.length == 2) {
                        role = parts[1].strip();
                    }
                    continue;
                }
                if (description == null && line.length() > 20) {
                    description = TextUtil.truncate(line, 1000, "");
                    continue;
                }
                if (highlights.size() < 20) {
                    highlights.add(TextUtil.truncate(line, 500, ""));
                }
            }
            if (name != null || !highlights.isEmpty()) {
                out.add(new ResumeContent.Project(
                        TextUtil.truncate(name, 128, ""),
                        TextUtil.truncate(role, 128, ""),
                        period, description, tech, highlights, url));
            }
        }
        return out;
    }

    private List<ResumeContent.Education> parseEducation(List<String> lines) {
        List<ResumeContent.Education> out = new ArrayList<>();
        for (List<String> block : splitBlocks(lines)) {
            if (out.size() >= 10) {
                break;
            }
            String school = null;
            String degree = null;
            String major = null;
            String period = null;
            for (String line : block) {
                Matcher pm = PERIOD.matcher(line);
                if (pm.find() && period == null) {
                    period = TextUtil.truncate(pm.group().replaceAll("\\s+", " ").strip(), 64, "");
                    continue;
                }
                if (school == null) {
                    school = line;
                    continue;
                }
                if (degree == null) {
                    String[] parts = line.split("[|｜/·]", 2);
                    degree = parts[0].strip();
                    if (parts.length == 2) {
                        major = parts[1].strip();
                    }
                    continue;
                }
                if (major == null) {
                    major = line;
                }
            }
            if (school != null) {
                out.add(new ResumeContent.Education(
                        TextUtil.truncate(school, 128, ""),
                        TextUtil.truncate(degree, 128, ""),
                        TextUtil.truncate(major, 128, ""),
                        period));
            }
        }
        return out;
    }

    /**
     * 空行在抽文本时已被丢掉。切块规则：
     * 当前块已经有日期时，再遇到日期才是下一条（避免「公司 + 日期」被拆开）；
     * 当前块已经有要点时，再遇到短标题才是下一条。
     */
    private static List<List<String>> splitBlocks(List<String> lines) {
        List<List<String>> blocks = new ArrayList<>();
        List<String> current = new ArrayList<>();
        for (String line : lines) {
            boolean hasPeriod = current.stream().anyMatch(l -> PERIOD.matcher(l).find());
            boolean hasBody = current.stream().anyMatch(l -> BULLET.matcher(l).find() || l.length() > 40);
            boolean dateStartsNext = PERIOD.matcher(line).find() && hasPeriod;
            boolean titleStartsNext = line.length() <= 40 && !BULLET.matcher(line).find()
                    && !PERIOD.matcher(line).find() && hasBody;
            if (!current.isEmpty() && (dateStartsNext || titleStartsNext)) {
                blocks.add(current);
                current = new ArrayList<>();
            }
            current.add(line);
        }
        if (!current.isEmpty()) {
            blocks.add(current);
        }
        return blocks;
    }

    private static Section detectSection(String line) {
        String n = line.replaceAll("[：:：\\d.、\\s]", "").toLowerCase(Locale.ROOT);
        if (n.isEmpty() || n.length() > 20) {
            return null;
        }
        if (eq(n, "个人简介", "简介", "自我评价", "summary", "profile", "about")) {
            return Section.SUMMARY;
        }
        if (eq(n, "技能", "专业技能", "技能清单", "skills", "technicalskills")) {
            return Section.SKILLS;
        }
        if (eq(n, "工作经历", "工作经验", "职业经历", "experience", "workexperience", "employment")) {
            return Section.EXPERIENCE;
        }
        if (eq(n, "项目经历", "项目经验", "项目", "projects", "projectexperience")) {
            return Section.PROJECTS;
        }
        if (eq(n, "教育经历", "教育背景", "教育", "education", "academic")) {
            return Section.EDUCATION;
        }
        if (eq(n, "获奖", "证书", "荣誉", "awards", "certificates", "languages")) {
            return Section.OTHER;
        }
        return null;
    }

    private static boolean eq(String normalized, String... aliases) {
        for (String a : aliases) {
            if (normalized.equals(a.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static boolean looksLikeTech(String line) {
        String lower = line.toLowerCase(Locale.ROOT);
        return lower.startsWith("技术栈") || lower.startsWith("tech") || lower.startsWith("stack");
    }

    private static String join(List<String> lines, int max) {
        if (lines.isEmpty()) {
            return null;
        }
        return TextUtil.truncate(String.join("\n", lines), max, "");
    }

    private static String labelOf(String url) {
        String host = url.replaceFirst("^https?://", "").split("[/?#]", 2)[0];
        if (host.contains("github")) {
            return "GitHub";
        }
        if (host.contains("juejin")) {
            return "掘金";
        }
        if (host.contains("linkedin")) {
            return "LinkedIn";
        }
        return TextUtil.truncate(host, 64, "");
    }

    private enum Section {
        HEADER, SUMMARY, SKILLS, EXPERIENCE, PROJECTS, EDUCATION, OTHER
    }
}
