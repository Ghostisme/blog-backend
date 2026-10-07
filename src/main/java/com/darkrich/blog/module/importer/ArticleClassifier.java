package com.darkrich.blog.module.importer;

import com.darkrich.blog.module.article.ArticleLevel;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 基于关键词的分类建议（领域 + 难度）。
 *
 * <p>定位是「给人工校正省力」，不是「替人做决定」：导入的文章一律进草稿，这里的结果只是预填值。
 * 所以规则刻意保持简单可读，宁可漏判（返回 null / 默认进阶），也不要追求复杂的打分模型。
 *
 * <p>领域编码（frontend / backend / database / devops / mobile）与
 * {@code V2__seed_categories_and_resume.sql} 的种子数据对应；
 * 后台删掉或改了这些编码，对应领域就不会再被自动归类（导入时会得到“未分类”）。
 */
public final class ArticleClassifier {

    public static final String FRONTEND = "frontend";
    public static final String BACKEND = "backend";
    public static final String DATABASE = "database";
    public static final String DEVOPS = "devops";
    public static final String MOBILE = "mobile";

    /**
     * @param categoryCode 建议的领域编码；证据不足时为 null
     * @param level        建议的难度，永不为 null（拿不准时为“进阶”）
     */
    public record Suggestion(String categoryCode, ArticleLevel level) {
    }

    /** 标题/标签命中一次的得分：标题和标签是作者刻意写的，比正文里顺带提到更能说明主题。 */
    private static final int TITLE_OR_TAG_WEIGHT = 3;
    private static final int BODY_WEIGHT = 1;
    /** 至少要达到这个分才给出建议：正文里零星提到一次不足以判定领域。 */
    private static final int MIN_SCORE = 3;
    /** 只看正文开头：主题通常在前面交代，全文扫描既慢又容易被后面的“顺带提到”带偏。 */
    private static final int BODY_SCAN_CHARS = 2000;

    /** 领域 → 关键词模式。LinkedHashMap 保证得分相同时，先声明的领域优先。 */
    private static final Map<String, List<Pattern>> CATEGORY_KEYWORDS = new LinkedHashMap<>();

    static {
        register(FRONTEND, "react", "vue", "angular", "svelte", "javascript", "typescript", "css", "html",
                "webpack", "vite", "tailwind", "sass", "next.js", "nuxt", "dom", "前端", "浏览器", "小程序");
        register(BACKEND, "java", "spring", "springboot", "spring boot", "mybatis", "golang", "go语言", "python",
                "django", "flask", "fastapi", "node.js", "nodejs", "nestjs", "express", "jvm", "netty", "dubbo",
                "grpc", "kafka", "rabbitmq", "微服务", "分布式", "消息队列", "多线程", "并发", "后端");
        register(DATABASE, "mysql", "postgresql", "postgres", "redis", "mongodb", "elasticsearch", "clickhouse",
                "oracle", "sqlite", "sql", "数据库", "索引", "事务", "分库分表", "慢查询");
        register(DEVOPS, "docker", "kubernetes", "k8s", "nginx", "jenkins", "ci/cd", "linux", "devops", "ansible",
                "terraform", "prometheus", "grafana", "helm", "gitlab", "github actions", "运维", "部署", "监控", "容器");
        register(MOBILE, "android", "ios", "flutter", "swift", "swiftui", "kotlin", "jetpack", "react native",
                "react-native", "objective-c", "uniapp", "uni-app", "harmonyos", "鸿蒙", "移动端");
    }

    // 难度关键词按「高 → 低」检查，命中即返回；都不命中就是默认的“进阶”
    private static final List<String> EXPERT_WORDS = List.of(
            "架构", "千万级", "亿级", "百万级", "高可用", "高并发", "海量", "技术选型", "复盘");
    private static final List<String> ADVANCED_WORDS = List.of(
            "源码", "原理", "深入", "底层", "内核", "剖析", "揭秘", "手写", "深度", "性能优化", "实现机制");
    private static final List<String> BEGINNER_WORDS = List.of(
            "入门", "基础", "初学", "新手", "零基础", "小白", "快速上手", "速成", "初识", "上手",
            "getting started", "introduction", "hello world");

    private ArticleClassifier() {
    }

    public static Suggestion classify(String title, List<String> tags, String content) {
        String titleText = lower(title);
        String tagText = lower(String.join(" ", tags == null ? List.of() : tags));
        String bodyHead = lower(content.length() > BODY_SCAN_CHARS ? content.substring(0, BODY_SCAN_CHARS) : content);

        String bestCode = null;
        int bestScore = 0;
        for (Map.Entry<String, List<Pattern>> entry : CATEGORY_KEYWORDS.entrySet()) {
            int score = 0;
            for (Pattern keyword : entry.getValue()) {
                if (keyword.matcher(titleText).find()) {
                    score += TITLE_OR_TAG_WEIGHT;
                }
                if (keyword.matcher(tagText).find()) {
                    score += TITLE_OR_TAG_WEIGHT;
                }
                if (keyword.matcher(bodyHead).find()) {
                    score += BODY_WEIGHT;
                }
            }
            // 严格大于：分数相同保留先声明的领域
            if (score > bestScore) {
                bestScore = score;
                bestCode = entry.getKey();
            }
        }
        return new Suggestion(bestScore >= MIN_SCORE ? bestCode : null, levelOf(titleText + " " + tagText));
    }

    /** 难度只看标题和标签：正文里出现“原理”“基础”太常见，会让几乎所有文章都被判成同一档。 */
    private static ArticleLevel levelOf(String text) {
        if (containsAny(text, EXPERT_WORDS)) {
            return ArticleLevel.EXPERT;
        }
        if (containsAny(text, ADVANCED_WORDS)) {
            return ArticleLevel.ADVANCED;
        }
        if (containsAny(text, BEGINNER_WORDS)) {
            return ArticleLevel.BEGINNER;
        }
        return ArticleLevel.INTERMEDIATE;
    }

    private static boolean containsAny(String text, List<String> words) {
        return words.stream().anyMatch(text::contains);
    }

    private static String lower(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT);
    }

    /**
     * 注册关键词。纯 ASCII 词要求前后不是字母数字（整词匹配）：
     * 否则 "java" 会命中 "javascript"，"go" 一类的短词会命中无数无关单词。
     * 含中文的词直接子串匹配——中文没有空格分词，整词匹配无从谈起。
     */
    private static void register(String code, String... words) {
        List<Pattern> patterns = new ArrayList<>();
        for (String word : words) {
            boolean ascii = word.chars().allMatch(c -> c < 128);
            String quoted = Pattern.quote(word);
            patterns.add(Pattern.compile(ascii ? "(?<![a-z0-9])" + quoted + "(?![a-z0-9])" : quoted));
        }
        CATEGORY_KEYWORDS.put(code, patterns);
    }
}
