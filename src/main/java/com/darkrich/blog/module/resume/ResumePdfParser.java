package com.darkrich.blog.module.resume;

import com.darkrich.blog.common.BusinessException;
import com.darkrich.blog.common.TextUtil;
import com.darkrich.blog.module.resume.ResumeSectionHeadings.Section;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
            Pattern.compile("(?:\\+?\\d{1,3}[\\s\\-]?)?"
                    // 手机号常写成 3-4-4 分段（152 0227 0460），连写的 11 位也在这一支里
                    + "(?:1[3-9]\\d[\\s\\-]?\\d{4}[\\s\\-]?\\d{4}|\\(?\\d{2,4}\\)?[\\s\\-]?\\d{7,8})");
    /** 页眉里不属于姓名/职位/地点的零碎信息，丢掉免得顶替掉真正的职位或地点。 */
    private static final Pattern NOISE_SEGMENT = Pattern.compile(
            "(?:本科|硕士|博士|大专|专科|\\d+\\s*年(?:工作)?经验|微信\\s*[:：].*|[|｜]"
                    + "|\\d+\\+?\\s*years?['’]?\\s*(?:of\\s+)?experience|(?:Bachelor|Master|Doctor)['’]?s?(?:\\s+degree)?)",
            Pattern.CASE_INSENSITIVE);
    /** 「1 / 2」这类页码。 */
    private static final Pattern PAGE_FOOTER = Pattern.compile("\\d{1,3}\\s*/\\s*\\d{1,3}");
    /** 多页简历每页顶部重复的页眉不会超过这个长度；更长的行是正文。 */
    private static final int MAX_RUNNING_HEADER = 80;
    /** 「Frontend: React、…」这种以分类名开头的技能行，用来识别被挤到上一小节末尾的技能首行。 */
    private static final Pattern SKILL_LABEL_LINE = Pattern.compile("^[A-Z][\\w &/]{1,39}:\\s+\\S.*$");
    private static final Pattern TITLE_HINT = Pattern.compile(
            "工程师|开发|架构|经理|总监|专员|设计师|全栈|前端|后端|算法|运维|测试|leader|engineer|developer|manager|designer|architect",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern URL = Pattern.compile("https?://\\S+", Pattern.CASE_INSENSITIVE);
    private static final Pattern PERIOD = Pattern.compile(
            // 起始月份可省略，「2016 - 2018」这种纯年份区间也算时间段
            "\\d{4}(?:\\s*[./年\\-]\\s*\\d{0,2}(?:\\s*[./月]\\s*\\d{0,2})?)?\\s*[-~—–至到]+\\s*"
                    // 结束年份后的月份也要吞掉，否则「2025.3-2025.10 公司」会残留「.10」混进公司名
                    + "(?:\\d{4}(?:\\s*[./年]\\s*\\d{1,2}月?)?|至今|现在|今|Present|Now|Current)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern BULLET = ResumeEntryBody.BULLET;
    /** 句末紧跟「技术栈」说明排版把标签拼到了上一句后面，要拆开，否则标签识别不到。 */
    private static final Pattern GLUED_TECH_LABEL =
            Pattern.compile("(?<=[。.!?！？])(?=(?:技术栈|Tech(?:nology)?\\s*Stack)\\s*[：:])", Pattern.CASE_INSENSITIVE);
    /** 带学位的学校行，如「兰州大学（本科）」。 */
    private static final Pattern SCHOOL_WITH_DEGREE = Pattern.compile("^(.+?)\\s*[（(]([^）)]{1,16})[）)]\\s*(.*)$");
    private static final int MAX_BLOCK_TITLE = 40;
    /** 中文分类名很短；英文分类名（"Rich Interaction & Multi-platform"）长得多，这里按英文放宽。 */
    private static final int MAX_SKILL_LABEL = 40;

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
        String headerEmail = null;
        for (String line : raw.split("\n")) {
            String t = line.replace(' ', ' ').strip();
            if (t.isEmpty() || PAGE_FOOTER.matcher(t).matches()) {
                continue;
            }
            // 多页简历每页顶部重复一遍「姓名 + 邮箱」页眉，只保留第一次，否则会被当成正文
            Matcher em = EMAIL.matcher(t);
            if (em.find()) {
                if (headerEmail == null) {
                    headerEmail = em.group();
                } else if (headerEmail.equalsIgnoreCase(em.group()) && t.length() <= MAX_RUNNING_HEADER) {
                    continue;
                }
            }
            lines.addAll(List.of(GLUED_TECH_LABEL.split(t)));
        }
        // OTHER（证书/获奖等）本站简历结构没有对应区块，照常收集但最后不用，以免误塞进经历
        Map<Section, List<String>> buckets = new EnumMap<>(Section.class);
        for (Section s : Section.values()) {
            buckets.put(s, new ArrayList<>());
        }
        Section current = Section.HEADER;

        for (String whole : lines) {
            String line = whole;
            ResumeSectionHeadings.Match heading = ResumeSectionHeadings.detect(whole, current);
            if (heading != null) {
                if (heading.sidebar() && heading.section() != current) {
                    reclaimLeadingLine(buckets.get(current), buckets.get(heading.section()), heading);
                }
                current = heading.section();
                line = heading.rest();
                if (line.isEmpty()) {
                    continue;
                }
            }
            buckets.get(current).add(line);
        }
        List<String> header = buckets.get(Section.HEADER);
        List<String> summary = buckets.get(Section.SUMMARY);
        List<String> skills = buckets.get(Section.SKILLS);
        List<String> experience = buckets.get(Section.EXPERIENCE);
        List<String> projects = buckets.get(Section.PROJECTS);
        List<String> education = buckets.get(Section.EDUCATION);
        if (summary.isEmpty() && skills.isEmpty() && experience.isEmpty()
                && projects.isEmpty() && education.isEmpty()) {
            // 没有任何可识别的小节标题：除前几行当页眉外，其余进简介，避免整份简历被丢掉
            int cut = Math.min(6, header.size());
            List<String> rest = new ArrayList<>(header.subList(cut, header.size()));
            header = new ArrayList<>(header.subList(0, cut));
            summary = rest;
        }

        // 页眉里整句的自我介绍（带标点的长句）不是职位，挪到简介开头；链接和邮箱行不动
        List<String> intro = new ArrayList<>();
        List<String> contact = new ArrayList<>();
        for (String line : header) {
            boolean sentence = line.contains("。") || line.contains("，") || line.length() > 40;
            if (sentence && !URL.matcher(line).find() && !line.contains("@")) {
                intro.add(line);
            } else {
                contact.add(line);
            }
        }
        header = contact;
        summary.addAll(0, intro);

        ResumeContent.Basics basics = parseBasics(header);
        return new ResumeContent(
                basics,
                join(summary, 5000),
                parseSkills(skills),
                parseExperience(experience),
                parseProjects(projects),
                parseEducation(education));
    }

    /**
     * 左侧标题栏版式下，标题垂直居中于小节，抽文本时会和小节的第二行拼在一起，
     * 于是小节的第一行被留在了上一小节末尾（技能首行落进简介、日期行落进上一段经历）。
     * 只回收末尾这一行，并且只认两种明确特征：条目以时间段开头，或技能行以「分类名:」开头。
     */
    private static void reclaimLeadingLine(List<String> from, List<String> to, ResumeSectionHeadings.Match heading) {
        if (from.isEmpty()) {
            return;
        }
        String last = from.get(from.size() - 1);
        boolean entry = heading.section() == Section.EXPERIENCE || heading.section() == Section.PROJECTS
                || heading.section() == Section.EDUCATION;
        // 标题同行的正文已带时间段时，说明条目首行就在标题这一行，不用回收
        boolean restHasPeriod = PERIOD.matcher(heading.rest()).find();
        boolean belongs = entry
                ? !restHasPeriod && startsWithPeriod(last)
                : heading.section() == Section.SKILLS && SKILL_LABEL_LINE.matcher(last).matches();
        if (belongs) {
            from.remove(from.size() - 1);
            to.add(last);
        }
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
            // 「本科  上海  7年经验」这类信息挤在一行、靠多个空格分栏，要拆开逐段看
            for (String segment : cleaned.split("\\s{2,}")) {
                String s = segment.strip();
                if (!s.isEmpty() && !s.equalsIgnoreCase("简历") && !s.equalsIgnoreCase("Resume")
                        && !NOISE_SEGMENT.matcher(s).matches()) {
                    leftover.add(s);
                }
            }
        }
        String name = leftover.isEmpty() ? null : TextUtil.truncate(leftover.get(0), 64, "");
        // 不再按行号硬分配：职位靠关键词认，其余第一段短文本当地点
        String title = null;
        String location = null;
        for (String candidate : leftover.stream().skip(1).toList()) {
            if (title == null && TITLE_HINT.matcher(candidate).find()) {
                title = TextUtil.truncate(candidate, 128, "");
            } else if (location == null) {
                location = TextUtil.truncate(candidate, 128, "");
            }
        }
        return new ResumeContent.Basics(name, title, email, phone, location, website, null, links);
    }

    private List<ResumeContent.SkillGroup> parseSkills(List<String> lines) {
        // 先按「分类：内容」归组，再切条目。没有分类名的行：上一组还没写完（折行）就并进去，否则另起「技能」组
        List<String> names = new ArrayList<>();
        List<StringBuilder> bodies = new ArrayList<>();
        for (String line : lines) {
            String[] parts = line.split("[：:]", 2);
            if (parts.length == 2 && parts[0].length() <= MAX_SKILL_LABEL) {
                names.add(parts[0].strip());
                bodies.add(new StringBuilder(parts[1].strip()));
            } else if (!bodies.isEmpty() && endsMidSentence(bodies.get(bodies.size() - 1))) {
                StringBuilder prev = bodies.get(bodies.size() - 1);
                // 英文折行发生在空格处，不补空格会把两个词粘成一个；中文折行不需要
                char tail = prev.charAt(prev.length() - 1);
                boolean words = tail < 128 && Character.isLetterOrDigit(tail)
                        && line.charAt(0) < 128 && Character.isLetter(line.charAt(0));
                prev.append(words ? " " : "").append(line);
            } else {
                names.add("技能");
                bodies.add(new StringBuilder(line));
            }
        }
        List<ResumeContent.SkillGroup> groups = new ArrayList<>();
        for (int i = 0; i < names.size() && groups.size() < 20; i++) {
            List<String> items = new ArrayList<>();
            for (String item : splitSkillItems(bodies.get(i).toString())) {
                if (items.size() < 30) {
                    items.add(TextUtil.truncate(item, 64, ""));
                }
            }
            if (!items.isEmpty()) {
                groups.add(new ResumeContent.SkillGroup(TextUtil.truncate(names.get(i), 64, ""), items));
            }
        }
        return groups;
    }

    /**
     * 按分隔符切技能条目，但括号里的分隔符不切（「Node.js (NestJS / Koa / Express)」是一项），
     * 两侧都是大写缩写的斜杠也不切（「CI/CD」「UI/UX」），「React/Vue」仍然切开。
     */
    private static List<String> splitSkillItems(String text) {
        List<String> items = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int depth = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '(' || c == '（') {
                depth++;
            } else if ((c == ')' || c == '）') && depth > 0) {
                depth--;
            }
            boolean separator = depth == 0 && (",，、;；|｜。".indexOf(c) >= 0 || (c == '/' && slashSplits(text, i)));
            if (separator) {
                flushItem(items, cur);
            } else {
                cur.append(c);
            }
        }
        flushItem(items, cur);
        return items;
    }

    private static boolean slashSplits(String text, int at) {
        boolean digitBefore = at > 0 && Character.isDigit(text.charAt(at - 1));
        boolean digitAfter = at + 1 < text.length() && Character.isDigit(text.charAt(at + 1));
        if (digitBefore && digitAfter) {
            return false;
        }
        boolean upperBefore = at > 0 && Character.isUpperCase(text.charAt(at - 1));
        boolean upperAfter = at + 1 < text.length() && Character.isUpperCase(text.charAt(at + 1));
        return !(upperBefore && upperAfter);
    }

    private static void flushItem(List<String> items, StringBuilder cur) {
        String item = cur.toString().strip();
        if (!item.isEmpty()) {
            items.add(item);
        }
        cur.setLength(0);
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
            ResumeEntryBody body = new ResumeEntryBody();
            for (String line : block) {
                Matcher pm = PERIOD.matcher(line);
                if (!body.started() && period == null && pm.find()) {
                    period = TextUtil.truncate(pm.group().replaceAll("\\s+", " ").strip(), 64, "");
                    String rest = (line.substring(0, pm.start()) + " " + line.substring(pm.end())).strip();
                    rest = rest.replaceAll("^[|｜/\\-—]+|[|｜/\\-—]+$", "").strip();
                    if (!rest.isEmpty()) {
                        // 「日期 公司」同行时余下的是公司；公司已知（标题行在前）时才是地点
                        if (company == null) {
                            // 「日期 公司 职位」同行：仅当末尾那段带职位关键词才拆，避免把「Opsmate AI」这类公司名劈开
                            int gap = rest.lastIndexOf(' ');
                            String tail = gap < 0 ? "" : rest.substring(gap + 1);
                            if (gap > 0 && position == null && tail.length() <= 20 && TITLE_HINT.matcher(tail).find()) {
                                company = rest.substring(0, gap).strip();
                                position = tail;
                            } else {
                                company = rest;
                            }
                        } else {
                            location = TextUtil.truncate(rest, 128, "");
                        }
                    }
                    continue;
                }
                if (body.consumeLabel(line)) {
                    continue;
                }
                if (!body.started() && !BULLET.matcher(line).find() && line.length() <= MAX_BLOCK_TITLE) {
                    if (company == null) {
                        String[] parts = line.split("[|｜]", 2);
                        company = parts[0].strip();
                        if (parts.length == 2) {
                            position = parts[1].strip();
                        }
                        continue;
                    }
                    if (position == null) {
                        position = positionOf(line);
                        continue;
                    }
                }
                body.add(line, false);
            }
            if (company != null || position != null || body.started()) {
                out.add(new ResumeContent.Experience(
                        TextUtil.truncate(company, 128, ""),
                        TextUtil.truncate(position, 128, ""),
                        period, location, body.highlights()));
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
            ResumeEntryBody body = new ResumeEntryBody();
            for (String line : block) {
                Matcher um = URL.matcher(line);
                if (url == null && um.find()) {
                    url = TextUtil.truncate(um.group().replaceAll("[),.;]+$", ""), 512, "");
                    line = um.reset().replaceAll("").strip();
                    if (line.isEmpty()) {
                        continue;
                    }
                }
                Matcher pm = PERIOD.matcher(line);
                if (!body.started() && period == null && pm.find()) {
                    period = TextUtil.truncate(pm.group().replaceAll("\\s+", " ").strip(), 64, "");
                    // 「日期 项目名」同行时余下的是项目名
                    String rest = (line.substring(0, pm.start()) + " " + line.substring(pm.end())).strip();
                    rest = rest.replaceAll("^[|｜/\\-—]+|[|｜/\\-—]+$", "").strip();
                    if (!rest.isEmpty() && name == null) {
                        name = rest;
                    }
                    continue;
                }
                if (body.consumeLabel(line)) {
                    continue;
                }
                if (!body.started() && !BULLET.matcher(line).find() && line.length() <= MAX_BLOCK_TITLE) {
                    if (name == null) {
                        String[] parts = line.split("[|｜]", 2);
                        name = parts[0].strip();
                        if (parts.length == 2) {
                            role = parts[1].strip();
                        }
                        continue;
                    }
                    if (role == null) {
                        role = positionOf(line);
                        continue;
                    }
                }
                body.add(line, true);
            }
            if (name != null || body.started()) {
                out.add(new ResumeContent.Project(
                        TextUtil.truncate(name, 128, ""),
                        TextUtil.truncate(role, 128, ""),
                        period, body.description(), body.techItems(), body.highlights(), url));
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
                    // 「日期 学校」同行时，余下的是学校
                    String rest = (line.substring(0, pm.start()) + " " + line.substring(pm.end())).strip();
                    if (school != null || rest.isEmpty()) {
                        continue;
                    }
                    // 「日期 学校……」：余下部分按学校行处理，往下走同一套拆分
                    line = rest;
                }
                if (school == null) {
                    // 学校行右侧常有对齐到另一栏的专业，抽文本后中间隔着一长串空格
                    String[] columns = line.split("\\s{3,}", 2);
                    // 「兰州大学（本科）」：括号里是学位，不是学校名的一部分；括号后若还有字就是专业
                    Matcher sm = SCHOOL_WITH_DEGREE.matcher(columns[0].strip());
                    if (sm.matches()) {
                        school = sm.group(1).strip();
                        degree = sm.group(2).strip();
                        if (!sm.group(3).isBlank()) {
                            major = sm.group(3).strip();
                        }
                    } else {
                        school = columns[0].strip();
                    }
                    if (columns.length == 2 && !columns[1].isBlank()) {
                        major = columns[1].strip();
                    }
                    continue;
                }
                // 学校之后的长句多半是「主修课程……」被折成了几行，不是学位/专业，别拿来填字段
                if (line.length() > MAX_BLOCK_TITLE) {
                    break;
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
     * 空行在抽文本时已被丢掉，只能靠版式特征切条目。常见两种：
     * <ul>
     * <li>日期在前：每条以「2022.2-2025.2 公司」开头，遇到行首日期就是下一条；</li>
     * <li>标题在前：每条以「公司 | 职位」开头、日期随后。当前块已有日期时再遇到日期，
     * 或已有正文时再遇到短标题，才是下一条（避免「公司 + 日期」被拆开）。</li>
     * </ul>
     * 判断短标题时要排除折行残片：上一行没写完（长且无句末标点）时，当前行是它的延续而不是新标题。
     */
    private static List<List<String>> splitBlocks(List<String> lines) {
        boolean periodFirst = !lines.isEmpty() && startsWithPeriod(lines.get(0));
        List<List<String>> blocks = new ArrayList<>();
        List<String> current = new ArrayList<>();
        for (String line : lines) {
            if (!current.isEmpty() && startsNext(current, line, periodFirst)) {
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

    private static boolean startsNext(List<String> current, String line, boolean periodFirst) {
        if (BULLET.matcher(line).find()) {
            return false;
        }
        if (periodFirst) {
            return startsWithPeriod(line);
        }
        if (PERIOD.matcher(line).find()) {
            return current.stream().anyMatch(l -> PERIOD.matcher(l).find());
        }
        boolean hasBody = current.stream().anyMatch(l -> BULLET.matcher(l).find() || l.length() > MAX_BLOCK_TITLE);
        return hasBody && line.length() <= MAX_BLOCK_TITLE
                && !ResumeEntryBody.isUnfinished(current.get(current.size() - 1));
    }

    private static boolean startsWithPeriod(String line) {
        Matcher m = PERIOD.matcher(line);
        return m.find() && m.start() == 0;
    }

    /** 「公司 | 职位」取竖线后面的部分；没有竖线时整行就是职位。 */
    private static String positionOf(String line) {
        int bar = Math.max(line.lastIndexOf('|'), line.lastIndexOf('｜'));
        return (bar < 0 ? line : line.substring(bar + 1)).strip();
    }

    private static boolean endsMidSentence(CharSequence text) {
        return ResumeEntryBody.isUnfinished(text);
    }

    private static String join(List<String> lines, int max) {
        if (lines.isEmpty()) {
            return null;
        }
        // 上一行没写完（折行）就接上，否则一段话在页面上会被硬换行切碎
        StringBuilder out = new StringBuilder(lines.get(0));
        for (String line : lines.subList(1, lines.size())) {
            boolean wrapped = ResumeEntryBody.isUnfinished(out.substring(out.lastIndexOf("\n") + 1));
            out.append(wrapped ? " " : "\n").append(line);
        }
        return TextUtil.truncate(out.toString(), max, "");
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
}
