package com.darkrich.blog.module.article;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.darkrich.blog.common.BusinessException;
import com.darkrich.blog.common.PageResult;
import com.darkrich.blog.common.SlugUtil;
import com.darkrich.blog.common.TextUtil;
import com.darkrich.blog.module.category.CategoryService;
import com.darkrich.blog.module.tag.TagService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/** 后台文章管理：查询（含草稿）、增删改、批量操作。 */
@Service
@RequiredArgsConstructor
public class AdminArticleService {

    /** article.slug 列宽 160，减去 "-" 和 8 位哈希后留给可读前缀的长度。 */
    private static final int SLUG_BASE_LENGTH = 151;

    private final ArticleMapper mapper;
    private final ArticleAssembler assembler;
    private final CategoryService categoryService;
    private final TagService tagService;

    public PageResult<ArticleListItem> list(AdminArticleQuery q) {
        KeywordParser.Parsed kw = KeywordParser.parse(q.keyword());
        ArticleFilter filter = new ArticleFilter(q.status(), q.level(), q.categoryId(), null,
                kw.ftsExpr(), kw.likePatterns(), q.sort());
        IPage<Article> page = mapper.searchPage(new Page<>(q.page(), q.size()), filter);
        return PageResult.of(page, assembler.toListItems(page.getRecords()));
    }

    public ArticleEditView get(Long id) {
        return assembler.toEditView(require(id));
    }

    /** slug 是否已被占用。导入时据此识别“重复导入同一篇文章”。 */
    public boolean existsBySlug(String slug) {
        return mapper.exists(new LambdaQueryWrapper<Article>().eq(Article::getSlug, slug));
    }

    @Transactional
    public ArticleEditView create(ArticleSaveRequest req) {
        Article article = new Article();
        apply(article, req, true);
        mapper.insert(article); // slug 冲突抛 DuplicateKeyException → 409
        tagService.replaceArticleTags(article.getId(), tagsOf(req));
        // 重新读取：created_at / updated_at / view_count 由数据库默认值生成，内存里的对象还没有
        return assembler.toEditView(mapper.selectById(article.getId()));
    }

    @Transactional
    public ArticleEditView update(Long id, ArticleSaveRequest req) {
        Article article = require(id);
        apply(article, req, false);
        mapper.updateById(article);
        tagService.replaceArticleTags(id, tagsOf(req));
        return assembler.toEditView(mapper.selectById(id));
    }

    @Transactional
    public void delete(Long id) {
        // article_tag 外键为 ON DELETE CASCADE，关联行随之删除
        if (mapper.deleteById(id) == 0) {
            throw BusinessException.notFound("文章不存在");
        }
    }

    @Transactional
    public int deleteBatch(List<Long> ids) {
        return ids.isEmpty() ? 0 : mapper.deleteBatchIds(ids);
    }

    /**
     * 批量修改状态 / 等级 / 领域，返回实际更新的篇数。
     *
     * <p>批量发布时整批校验：只要有一篇最终没有领域，整批都不执行，
     * 而不是“发布一部分、报错一部分”，避免管理员搞不清哪些已经生效。
     */
    @Transactional
    public int patchBatch(ArticleBatchRequest req) {
        if (req.status() == null && req.level() == null && req.categoryId() == null) {
            throw BusinessException.badRequest("至少指定 status、level、categoryId 之一");
        }
        if (req.categoryId() != null) {
            categoryService.requireById(req.categoryId());
        }
        if (req.status() == ArticleStatus.PUBLISHED && req.categoryId() == null) {
            // 本次没有指定领域，就要求每一篇原本就有领域
            Long uncategorized = mapper.selectCount(new LambdaQueryWrapper<Article>()
                    .in(Article::getId, req.ids()).isNull(Article::getCategoryId));
            if (uncategorized > 0) {
                throw BusinessException.badRequest("有 " + uncategorized + " 篇文章尚未设置领域，无法发布");
            }
        }

        LambdaUpdateWrapper<Article> update = new LambdaUpdateWrapper<Article>().in(Article::getId, req.ids());
        if (req.level() != null) {
            update.set(Article::getLevel, req.level());
        }
        if (req.categoryId() != null) {
            update.set(Article::getCategoryId, req.categoryId());
        }
        if (req.status() != null) {
            update.set(Article::getStatus, req.status());
            if (req.status() == ArticleStatus.PUBLISHED) {
                // 只给“从未发布过”的文章补发布时间；重新发布已发布过的文章保留原来的时间，排序不会乱
                update.setSql("published_at = COALESCE(published_at, NOW())");
            }
        }
        return mapper.update(null, update);
    }

    private Article require(Long id) {
        Article article = mapper.selectById(id);
        if (article == null) {
            throw BusinessException.notFound("文章不存在");
        }
        return article;
    }

    private List<String> tagsOf(ArticleSaveRequest req) {
        return req.tags() == null ? List.of() : req.tags();
    }

    /** 把请求内容写入实体，并集中处理各种派生字段与业务规则。 */
    private void apply(Article a, ArticleSaveRequest req, boolean creating) {
        a.setTitle(req.title().trim());
        a.setContent(req.content());
        a.setSummary(TextUtil.truncate(TextUtil.blankToNull(req.summary()), 500, "…"));
        a.setLevel(req.level() == null ? ArticleLevel.INTERMEDIATE : req.level());
        a.setSourceUrl(TextUtil.blankToNull(req.sourceUrl()));
        a.setSourceAuthor(TextUtil.blankToNull(req.sourceAuthor()));
        a.setCoverUrl(TextUtil.blankToNull(req.coverUrl()));
        a.setWordCount(TextUtil.countWords(req.content()));

        // slug：显式传了就用；没传时新增按标题生成，修改保持不变（改标题不应让已被收藏/收录的链接失效）
        String slug = TextUtil.blankToNull(req.slug());
        if (slug != null) {
            a.setSlug(slug);
        } else if (creating) {
            a.setSlug(SlugUtil.generate(a.getTitle(), "post", SLUG_BASE_LENGTH));
        }

        if (req.categoryId() != null) {
            categoryService.requireById(req.categoryId());
        }
        a.setCategoryId(req.categoryId());

        ArticleStatus status = req.status() == null ? ArticleStatus.DRAFT : req.status();
        a.setStatus(status);
        if (status == ArticleStatus.PUBLISHED) {
            if (a.getCategoryId() == null) {
                throw BusinessException.badRequest("发布文章前必须选择领域");
            }
            // 优先用请求里手动指定的时间；否则沿用已有的；都没有才取当前时间。
            // 取消发布再发布时沿用原时间，文章在列表里的位置不会因为反复上下线而跳动
            if (req.publishedAt() != null) {
                a.setPublishedAt(req.publishedAt());
            } else if (a.getPublishedAt() == null) {
                a.setPublishedAt(LocalDateTime.now());
            }
        } else if (req.publishedAt() != null) {
            a.setPublishedAt(req.publishedAt());
        }
    }
}
