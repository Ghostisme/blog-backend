package com.darkrich.blog.module.article;

import com.darkrich.blog.module.category.CategoryBrief;
import com.darkrich.blog.module.category.CategoryService;
import com.darkrich.blog.module.tag.TagBrief;
import com.darkrich.blog.module.tag.TagService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 把 {@link Article} 实体组装成各种对外视图。
 *
 * <p>核心职责是「批量补全关联数据」：列表页一次性查出所有文章的分类和标签，
 * 而不是每篇文章各查一次（N+1）。前台、后台共用，保证两边展示的口径一致。
 */
@Component
@RequiredArgsConstructor
public class ArticleAssembler {

    /** 阅读速度估算：每分钟约 400 字（中文技术文章的常见取值，仅用于粗略展示）。 */
    private static final int WORDS_PER_MINUTE = 400;

    private final CategoryService categoryService;
    private final TagService tagService;

    public List<ArticleListItem> toListItems(List<Article> articles) {
        if (articles.isEmpty()) {
            return List.of();
        }
        Map<Long, CategoryBrief> categories = categoryService.briefsByIds(
                articles.stream().map(Article::getCategoryId).filter(Objects::nonNull).distinct().toList());
        Map<Long, List<TagBrief>> tags = tagService.briefsByArticleIds(
                articles.stream().map(Article::getId).toList());

        return articles.stream().map(a -> new ArticleListItem(
                a.getId(), a.getSlug(), a.getTitle(), a.getSummary(), a.getLevel(),
                // categoryId 可能为 null（草稿未分类）；不能对 null 调用 Map.get，不可变 Map 会抛 NPE
                a.getCategoryId() == null ? null : categories.get(a.getCategoryId()),
                tags.getOrDefault(a.getId(), List.of()),
                a.getCoverUrl(), nz(a.getViewCount()), readingMinutes(a.getWordCount()),
                a.getStatus(), a.getPublishedAt(), a.getUpdatedAt())).toList();
    }

    public ArticleDetail toDetail(Article a, Article older, Article newer) {
        Map<Long, CategoryBrief> categories = a.getCategoryId() == null
                ? Map.of() : categoryService.briefsByIds(List.of(a.getCategoryId()));
        List<TagBrief> tags = tagService.briefsByArticleIds(List.of(a.getId())).getOrDefault(a.getId(), List.of());
        return new ArticleDetail(
                a.getId(), a.getSlug(), a.getTitle(), a.getSummary(), a.getContent(), a.getLevel(),
                a.getCategoryId() == null ? null : categories.get(a.getCategoryId()), tags,
                a.getCoverUrl(), a.getSourceUrl(), a.getSourceAuthor(),
                nz(a.getViewCount()), a.getWordCount() == null ? 0 : a.getWordCount(),
                readingMinutes(a.getWordCount()), a.getPublishedAt(), a.getUpdatedAt(),
                ArticleNav.from(older), ArticleNav.from(newer));
    }

    public ArticleEditView toEditView(Article a) {
        List<String> tagNames = tagService.briefsByArticleIds(List.of(a.getId()))
                .getOrDefault(a.getId(), List.of()).stream().map(TagBrief::nameZh).toList();
        return new ArticleEditView(
                a.getId(), a.getSlug(), a.getTitle(), a.getSummary(), a.getContent(), a.getLevel(),
                a.getCategoryId(), a.getStatus(), tagNames, a.getSourceUrl(), a.getSourceAuthor(),
                a.getCoverUrl(), nz(a.getViewCount()), a.getPublishedAt(), a.getCreatedAt(), a.getUpdatedAt());
    }

    private static int readingMinutes(Integer wordCount) {
        int words = wordCount == null ? 0 : wordCount;
        return Math.max(1, (int) Math.ceil(words / (double) WORDS_PER_MINUTE));
    }

    private static long nz(Long value) {
        return value == null ? 0 : value;
    }
}
