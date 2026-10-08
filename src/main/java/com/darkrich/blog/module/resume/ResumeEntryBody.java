package com.darkrich.blog.module.resume;

import com.darkrich.blog.common.TextUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 一条工作 / 项目经历的正文累加器：处理「工作职责：」「项目描述：」「技术栈：」这类标签行、
 * 编号要点，以及 PDF 排版造成的折行。
 *
 * <p>折行是这里最容易出错的地方：定宽文本框里一句话会在任意位置断成多行，
 * 若把每行都当成独立要点，结果就是一条要点被拆成好几条，甚至把半个单词当成一条。
 */
final class ResumeEntryBody {

    static final Pattern BULLET = Pattern.compile("^\\s*(?:[•●○◆■▪-]|\\d+[.)、])\\s+");

    private static final Pattern DESCRIPTION_LABEL =
            Pattern.compile("^(?:项目描述|项目简介|项目背景|Description)\\s*[：:]\\s*(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern TECH_LABEL = Pattern.compile(
            "^(?:(?:技术栈|技术架构)\\s*[：:]?|(?:Tech(?:nology)?\\s*Stack|Stack)\\s*[：:])\\s*(.*)$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern HIGHLIGHT_LABEL = Pattern.compile(
            "^(?:工作职责|工作内容|工作成绩|工作业绩|工作成果|项目职责|项目业绩|项目成果|项目亮点|主要职责|技术能力"
                    + "|Responsibilities|Achievements)\\s*[：:]\\s*(.*)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern TECH_SPLIT = Pattern.compile("[,，、;；|｜]+");

    /** 低于这个长度的行大概率是一条完整的短句，不是被折断的半句。 */
    private static final int WRAP_MIN_LENGTH = 30;
    private static final int MAX_HIGHLIGHTS = 20;
    private static final int MAX_TECH = 20;

    private enum Mode {
        NONE, DESCRIPTION, TECH, HIGHLIGHTS
    }

    /** 英文整句至少有这么多个词；技术栈续行（"mini-programs, Monorepo"）远没有这么长。 */
    private static final int PROSE_MIN_WORDS = 5;
    /** 技术栈折行续接时，下一行首个词不短于这个长度就视为新单词（补空格），更短的当作被折断的词尾。 */
    private static final int MIN_WHOLE_WORD = 4;
    private static final String TECH_SEPARATORS = ",，、;；|｜";

    private Mode mode = Mode.NONE;
    private String lastTechLine = "";
    private final List<String> highlights = new ArrayList<>();
    private final StringBuilder description = new StringBuilder();
    private final StringBuilder tech = new StringBuilder();

    /** 是标签行就吃掉并返回 true；标签后面同行的内容一并归入对应字段。 */
    boolean consumeLabel(String line) {
        Matcher m = DESCRIPTION_LABEL.matcher(line);
        if (m.matches()) {
            mode = Mode.DESCRIPTION;
            append(m.group(1));
            return true;
        }
        m = TECH_LABEL.matcher(line);
        if (m.matches()) {
            mode = Mode.TECH;
            append(m.group(1));
            return true;
        }
        m = HIGHLIGHT_LABEL.matcher(line);
        if (m.matches()) {
            mode = Mode.HIGHLIGHTS;
            append(m.group(1));
            return true;
        }
        return false;
    }

    /**
     * @param freeLineIsDescription 还没遇到任何标签/要点时，一条较长的自由文本算简介（项目）还是要点（工作经历）
     */
    void add(String line, boolean freeLineIsDescription) {
        if (techListEnded(line)) {
            // 技术栈标签后没有结构化的结束标记，英文简历里紧随其后的整句是项目描述，不能再并进技术栈
            mode = Mode.NONE;
        }
        if (BULLET.matcher(line).find()) {
            mode = Mode.HIGHLIGHTS;
            addHighlight(BULLET.matcher(line).replaceFirst("").strip());
            return;
        }
        if (mode == Mode.NONE) {
            if (freeLineIsDescription && description.isEmpty() && line.length() > 20) {
                mode = Mode.DESCRIPTION;
            } else {
                mode = Mode.HIGHLIGHTS;
            }
        }
        append(line);
    }

    boolean started() {
        return !highlights.isEmpty() || !description.isEmpty() || !tech.isEmpty();
    }

    List<String> highlights() {
        List<String> out = new ArrayList<>(highlights.size());
        highlights.forEach(h -> out.add(TextUtil.truncate(h, 500, "")));
        return out;
    }

    String description() {
        return description.isEmpty() ? null : TextUtil.truncate(description.toString().strip(), 1000, "");
    }

    List<String> techItems() {
        List<String> items = new ArrayList<>();
        for (String token : TECH_SPLIT.split(tech)) {
            String item = token.strip();
            if (!item.isEmpty() && items.size() < MAX_TECH) {
                items.add(TextUtil.truncate(item, 64, ""));
            }
        }
        return items;
    }

    private void append(String text) {
        String t = text.strip();
        if (t.isEmpty()) {
            return;
        }
        switch (mode) {
            case DESCRIPTION -> joinInto(description, t, true);
            case TECH -> {
                // 折行有两种：中文版式常断在单词中间（「zustan」「d」），不能补空格；
                // 英文版式断在空格处（「Feishu」「mini-programs」），不补就会粘成一个词
                joinInto(tech, t, startsNewWord(t));
                lastTechLine = t;
            }
            case HIGHLIGHTS, NONE -> addHighlightOrMerge(t);
        }
    }

    private void addHighlightOrMerge(String text) {
        if (!highlights.isEmpty() && continuesPrevious(highlights.get(highlights.size() - 1), text)) {
            int last = highlights.size() - 1;
            highlights.set(last, join(highlights.get(last), text, true));
        } else {
            addHighlight(text);
        }
    }

    /**
     * 非要点标记的行是否接在上一条要点后面。除了「上一行没写完」，还有一种：
     * 上一条以分号收尾、这一行以小写英文开头（"...state management;" / "developed long-form caching"），
     * 分号后另起一条要点必然带要点标记，所以没标记的小写开头只能是同一条的折行。
     */
    private static boolean continuesPrevious(String previous, String next) {
        if (isUnfinished(previous)) {
            return true;
        }
        char first = next.charAt(0);
        return ";".indexOf(previous.charAt(previous.length() - 1)) >= 0 && first < 128 && Character.isLowerCase(first);
    }

    private void addHighlight(String text) {
        if (!text.isEmpty() && highlights.size() < MAX_HIGHLIGHTS) {
            highlights.add(text);
        }
    }

    /** 技术栈列表是否已经写完：上一行不以分隔符收尾，且当前行是一句整话。 */
    private boolean techListEnded(String line) {
        if (mode != Mode.TECH || lastTechLine.isEmpty()) {
            return false;
        }
        boolean listContinues = TECH_SEPARATORS.indexOf(lastTechLine.charAt(lastTechLine.length() - 1)) >= 0;
        return !listContinues && line.strip().split("\\s+").length >= PROSE_MIN_WORDS;
    }

    private static boolean startsNewWord(String continuation) {
        int end = 0;
        while (end < continuation.length() && !Character.isWhitespace(continuation.charAt(end))
                && TECH_SEPARATORS.indexOf(continuation.charAt(end)) < 0) {
            end++;
        }
        return end >= MIN_WHOLE_WORD;
    }

    /** 上一行是否没写完：够长且不以句末标点结尾，多半是被排版折断的半句。 */
    static boolean isUnfinished(CharSequence previous) {
        if (previous.length() < WRAP_MIN_LENGTH) {
            return false;
        }
        return "。.!?！？；;".indexOf(previous.charAt(previous.length() - 1)) < 0;
    }

    private static void joinInto(StringBuilder target, String text, boolean spaceBetweenWords) {
        String joined = target.isEmpty() ? text : join(target.toString(), text, spaceBetweenWords);
        target.setLength(0);
        target.append(joined);
    }

    /** 中文折行直接拼接；仅当两侧都是英文/数字且允许时才补空格，避免「built asystem」。 */
    private static String join(String previous, String next, boolean spaceBetweenWords) {
        char a = previous.charAt(previous.length() - 1);
        char b = next.charAt(0);
        // 英文标点（逗号、分号、句号）后折行同样发生在空格处，不补就成了「decomposition,code」
        boolean wordBoundary = spaceBetweenWords && (isAsciiWord(a) || ",;.".indexOf(a) >= 0) && isAsciiWord(b);
        return previous + (wordBoundary ? " " : "") + next;
    }

    private static boolean isAsciiWord(char c) {
        return c < 128 && Character.isLetterOrDigit(c);
    }
}
