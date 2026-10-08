package com.darkrich.blog.module.article;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.darkrich.blog.common.BusinessException;
import com.darkrich.blog.common.PageResult;
import com.darkrich.blog.module.category.CategoryService;
import com.darkrich.blog.module.tag.TagService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 前台（公开）文章查询。所有方法都只会返回已发布的文章。 */
@Service
@RequiredArgsConstructor
public class ArticleQueryService {

    private final ArticleMapper mapper;
    private final ArticleAssembler assembler;
    private final CategoryService categoryService;
    private final TagService tagService;

    public PageResult<ArticleListItem> list(ArticleQuery q) {
        KeywordParser.Parsed kw = KeywordParser.parse(q.keyword());
        // 状态在这里写死为 PUBLISHED，而不是信任任何外部参数：前台绝不能看到草稿
        ArticleLanguage language = ArticleLanguage.from(q.lang());
        ArticleFilter filter = new ArticleFilter(ArticleStatus.PUBLISHED, q.level(), q.categoryId(), q.tagId(),
                kw.ftsExpr(), kw.likePatterns(), q.sort(), language.isEnglish());
        IPage<Article> page = mapper.searchPage(new Page<>(q.page(), q.size()), filter);
        return PageResult.of(page, assembler.toListItems(page.getRecords(), language));
    }

    /**
     * 按 slug 取文章详情，并把浏览量 +1。
     *
     * <p>草稿与不存在的文章一律返回同样的 404，不泄露“这个 slug 存在但未发布”。
     * 返回值里的浏览量直接 +1 而不是重新查询，省一次往返，读者看到的也是包含自己这一次的数字。
     */
    @Transactional
    public ArticleDetail detail(String slug, ArticleLanguage language) {
        Article article = mapper.selectOne(new LambdaQueryWrapper<Article>()
                .eq(Article::getSlug, slug)
                .eq(Article::getStatus, ArticleStatus.PUBLISHED));
        if (article == null) {
            throw BusinessException.notFound("文章不存在");
        }
        mapper.incrementViewCount(article.getId());
        article.setViewCount((article.getViewCount() == null ? 0 : article.getViewCount()) + 1);

        Article older = null;
        Article newer = null;
        // 已发布文章必有发布时间（AdminArticleService 保证），这里的判空只是防御历史脏数据
        if (article.getPublishedAt() != null) {
            older = mapper.selectOlder(article.getId(), article.getPublishedAt(), language.isEnglish());
            newer = mapper.selectNewer(article.getId(), article.getPublishedAt(), language.isEnglish());
        }
        return assembler.toDetail(article, older, newer, language);
    }

    public FilterOptions filters() {
        Map<ArticleLevel, Long> counts = mapper.countPublishedByLevel().stream()
                .collect(Collectors.toMap(LevelCount::getLevel, LevelCount::getCount));
        // 按枚举声明顺序（入门→资深）输出，缺失的等级补 0，保证前端入口齐全且顺序固定
        List<FilterOptions.LevelOption> levels = Arrays.stream(ArticleLevel.values())
                .map(l -> new FilterOptions.LevelOption(l, counts.getOrDefault(l, 0L)))
                .toList();
        return new FilterOptions(levels, categoryService.listPublic(), tagService.listPublic());
    }
}
