package com.darkrich.blog.module.importer;

import com.darkrich.blog.common.BusinessException;
import com.darkrich.blog.common.SlugUtil;
import com.darkrich.blog.module.article.AdminArticleService;
import com.darkrich.blog.module.article.ArticleEditView;
import com.darkrich.blog.module.article.ArticleSaveRequest;
import com.darkrich.blog.module.article.ArticleStatus;
import com.darkrich.blog.module.category.CategoryService;
import com.darkrich.blog.module.category.CategoryView;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 批量导入 Markdown → 文章草稿。
 *
 * <p>本类<b>刻意不加事务</b>：每个文件通过 {@link AdminArticleService#create} 各自开启独立事务，
 * 这样第 37 个文件失败只会回滚它自己，前 36 个已导入的不受影响。
 * 若在这里加一个大事务，一个坏文件就会让整批全部作废。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImportService {

    /** 单次请求最多文件数，配合 multipart 的总大小限制，防止一次请求占用过多内存。 */
    static final int MAX_FILES = 200;
    /** article.slug 列宽 160，减去 "-" 和 8 位哈希后留给可读前缀的长度（与 AdminArticleService 保持一致）。 */
    private static final int SLUG_BASE_LENGTH = 151;

    /**
     * 掘金自带的一级分类里，和本站领域不同名的别名 → 本站领域编码。
     * 掘金的 Android / iOS 对应本站的「移动端」。
     */
    private static final Map<String, String> CATEGORY_ALIASES = Map.of(
            "android", ArticleClassifier.MOBILE,
            "ios", ArticleClassifier.MOBILE,
            "git", ArticleClassifier.DEVOPS,
            "github", ArticleClassifier.DEVOPS);

    /** 单次最多链接数。每条都要出网，压低以免拖垮构建节点和目标站。 */
    static final int MAX_URLS = 10;

    private final AdminArticleService articleService;
    private final CategoryService categoryService;
    private final ArticleUrlFetcher urlFetcher;

    public ImportResult importFiles(List<MultipartFile> files) {
        if (files == null || files.isEmpty()) {
            throw BusinessException.badRequest("请选择要导入的 Markdown 文件");
        }
        if (files.size() > MAX_FILES) {
            throw BusinessException.badRequest("单次最多导入 " + MAX_FILES + " 个文件");
        }
        // 领域表很小，整批只查一次，避免每个文件都查库
        Map<String, Long> categoryLookup = buildCategoryLookup();

        List<ImportResult.Item> items = new ArrayList<>(files.size());
        for (MultipartFile file : files) {
            items.add(importOne(file, categoryLookup));
        }
        return ImportResult.of(items);
    }

    /**
     * 从公开链接导入。单条失败只记结果，不中断整批。
     * 去重按「本次请求内的原始字符串」；同一篇文章的不同 URL 仍可能各导入一次，靠标题 slug 跳过。
     */
    public ImportResult importUrls(List<String> urls) {
        if (urls == null || urls.isEmpty()) {
            throw BusinessException.badRequest("请填写文章链接");
        }
        List<String> cleaned = new ArrayList<>();
        for (String raw : urls) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String url = raw.trim();
            if (!cleaned.contains(url)) {
                cleaned.add(url);
            }
        }
        if (cleaned.isEmpty()) {
            throw BusinessException.badRequest("请填写文章链接");
        }
        if (cleaned.size() > MAX_URLS) {
            throw BusinessException.badRequest("单次最多导入 " + MAX_URLS + " 条链接");
        }
        Map<String, Long> categoryLookup = buildCategoryLookup();
        List<ImportResult.Item> items = new ArrayList<>(cleaned.size());
        for (String url : cleaned) {
            items.add(importOneUrl(url, categoryLookup));
        }
        return ImportResult.of(items);
    }

    private ImportResult.Item importOne(MultipartFile file, Map<String, Long> categoryLookup) {
        String fileName = displayName(file.getOriginalFilename());
        try {
            String lowerName = fileName.toLowerCase(Locale.ROOT);
            if (!lowerName.endsWith(".md") && !lowerName.endsWith(".markdown")) {
                return ImportResult.Item.failed(fileName, "仅支持 .md / .markdown 文件");
            }
            if (file.isEmpty()) {
                return ImportResult.Item.failed(fileName, "文件为空");
            }

            MarkdownParser.ParsedArticle parsed =
                    MarkdownParser.parse(fileName, new String(file.getBytes(), StandardCharsets.UTF_8));
            return saveParsed(fileName, parsed, categoryLookup);

        } catch (BusinessException e) {
            return ImportResult.Item.failed(fileName, e.getMessage());
        } catch (DuplicateKeyException e) {
            return ImportResult.Item.skipped(fileName, null, "已存在同标题文章，已跳过");
        } catch (IOException e) {
            log.warn("读取上传文件失败: {}", fileName, e);
            return ImportResult.Item.failed(fileName, "读取文件失败");
        } catch (RuntimeException e) {
            // 单个文件的意外错误不能中断整批；详细原因写日志，返回给前端的只是概括
            log.warn("导入文件失败: {}", fileName, e);
            return ImportResult.Item.failed(fileName, "解析或保存失败");
        }
    }

    private ImportResult.Item importOneUrl(String url, Map<String, Long> categoryLookup) {
        try {
            MarkdownParser.ParsedArticle parsed = urlFetcher.fetch(url);
            return saveParsed(url, parsed, categoryLookup);
        } catch (BusinessException e) {
            return ImportResult.Item.failed(url, e.getMessage());
        } catch (DuplicateKeyException e) {
            return ImportResult.Item.skipped(url, null, "已存在同标题文章，已跳过");
        } catch (RuntimeException e) {
            log.warn("导入链接失败: {}", url, e);
            return ImportResult.Item.failed(url, "抓取或保存失败");
        }
    }

    private ImportResult.Item saveParsed(String source, MarkdownParser.ParsedArticle parsed,
                                         Map<String, Long> categoryLookup) {
        // slug 由标题派生且稳定：重复导入同一篇文章会得到同一个 slug，据此识别并跳过
        String slug = SlugUtil.generate(parsed.title(), "post", SLUG_BASE_LENGTH);
        if (articleService.existsBySlug(slug)) {
            return ImportResult.Item.skipped(source, parsed.title(), "已存在同标题文章，已跳过");
        }
        ArticleClassifier.Suggestion suggestion =
                ArticleClassifier.classify(parsed.title(), parsed.tags(), parsed.content());
        Long categoryId = resolveCategory(categoryLookup, parsed.categoryHint(), suggestion.categoryCode());
        ArticleEditView created = articleService.create(new ArticleSaveRequest(
                parsed.title(), slug, parsed.summary(), parsed.content(),
                suggestion.level(), categoryId,
                // 一律草稿：领域、等级都是机器猜的，必须由人确认后再发布
                ArticleStatus.DRAFT,
                parsed.tags(), parsed.sourceUrl(), parsed.sourceAuthor(), parsed.coverUrl(), null));
        return ImportResult.Item.imported(source, created.title(), created.id());
    }

    /**
     * 确定文章领域：文件自带的分类优先（那是作者/收藏时的真实信息），其次用关键词建议，都没有则不分类。
     */
    private Long resolveCategory(Map<String, Long> lookup, String hint, String suggestedCode) {
        if (hint != null) {
            String key = hint.trim().toLowerCase(Locale.ROOT);
            Long id = lookup.get(CATEGORY_ALIASES.getOrDefault(key, key));
            if (id != null) {
                return id;
            }
        }
        return suggestedCode == null ? null : lookup.get(suggestedCode);
    }

    /** 建立「编码 / 中文名 / 英文名（小写） → 领域 id」的查找表。 */
    private Map<String, Long> buildCategoryLookup() {
        Map<String, Long> lookup = new HashMap<>();
        for (CategoryView c : categoryService.listAdmin()) {
            lookup.put(c.code().toLowerCase(Locale.ROOT), c.id());
            lookup.put(c.nameZh().toLowerCase(Locale.ROOT), c.id());
            lookup.put(c.nameEn().toLowerCase(Locale.ROOT), c.id());
        }
        return lookup;
    }

    /**
     * 取纯文件名。用字符串截取而不是 Path 解析：上传的文件名由客户端任意指定，
     * 含非法字符时 Path.of 会直接抛异常；这里只用于展示，不涉及任何文件系统访问。
     */
    private static String displayName(String original) {
        if (original == null || original.isBlank()) {
            return "unnamed";
        }
        int cut = Math.max(original.lastIndexOf('/'), original.lastIndexOf('\\'));
        return cut >= 0 ? original.substring(cut + 1) : original;
    }
}
