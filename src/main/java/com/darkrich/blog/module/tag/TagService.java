package com.darkrich.blog.module.tag;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.darkrich.blog.common.BusinessException;
import com.darkrich.blog.common.SlugUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 标签的查询、维护，以及文章与标签关联关系的维护。 */
@Service
@RequiredArgsConstructor
public class TagService {

    /** 单篇文章最多标签数。导入的文章偶尔带一长串标签，不设上限会污染标签云。 */
    static final int MAX_TAGS_PER_ARTICLE = 10;
    private static final int MAX_NAME_LENGTH = 64;
    /** tag.slug 列宽 96，减去 "-" 和 8 位哈希后留给可读前缀的长度。 */
    private static final int SLUG_BASE_LENGTH = 87;
    private static final int PUBLIC_CLOUD_SIZE = 50;

    private final TagMapper tagMapper;
    private final ArticleTagMapper articleTagMapper;

    public List<TagView> listPublic() {
        return tagMapper.selectPublishedWithCount(PUBLIC_CLOUD_SIZE).stream().map(TagView::from).toList();
    }

    public List<TagView> listAdmin() {
        return tagMapper.selectAllWithCount().stream().map(TagView::from).toList();
    }

    /** 一次查出多篇文章的标签并按文章分组，列表页组装用。 */
    public Map<Long, List<TagBrief>> briefsByArticleIds(Collection<Long> articleIds) {
        if (articleIds.isEmpty()) {
            return Map.of();
        }
        return tagMapper.selectByArticleIds(articleIds).stream()
                .collect(Collectors.groupingBy(Tag::getArticleId,
                        Collectors.mapping(TagBrief::from, Collectors.toList())));
    }

    /**
     * 整体替换一篇文章的标签：先清后插，保证最终状态只取决于入参，与原有标签无关。
     * 必须在调用方的事务内执行，否则删除成功而插入失败时文章会丢失全部标签。
     *
     * @param tagNames 标签名（通常来自编辑表单或导入文件）；空白、重复项会被忽略
     */
    @Transactional
    public void replaceArticleTags(Long articleId, Collection<String> tagNames) {
        articleTagMapper.deleteByArticleId(articleId);
        List<Long> ids = resolveIds(tagNames);
        if (!ids.isEmpty()) {
            articleTagMapper.insertBatch(articleId, ids);
        }
    }

    @Transactional
    public TagView update(Long id, TagRequest req) {
        Tag tag = tagMapper.selectById(id);
        if (tag == null) {
            throw BusinessException.notFound("标签不存在");
        }
        tag.setNameZh(req.nameZh().trim());
        tag.setNameEn(req.nameEn().trim());
        tagMapper.updateById(tag);
        return TagView.from(tag);
    }

    /** 删除标签；article_tag 上的外键是 ON DELETE CASCADE，关联会自动清掉，文章本身不受影响。 */
    @Transactional
    public void delete(Long id) {
        if (tagMapper.deleteById(id) == 0) {
            throw BusinessException.notFound("标签不存在");
        }
    }

    /**
     * 把标签名解析成 id，不存在的自动创建。
     * 用 slug 去重（而不是原名），所以 "Vue" 与 "vue" 视为同一个标签，先出现者的写法保留。
     */
    private List<Long> resolveIds(Collection<String> tagNames) {
        // LinkedHashMap 按 slug 去重同时保持输入顺序
        Map<String, String> bySlug = new LinkedHashMap<>();
        for (String raw : tagNames) {
            if (raw == null) {
                continue;
            }
            String name = raw.trim();
            if (name.isEmpty()) {
                continue;
            }
            if (name.length() > MAX_NAME_LENGTH) {
                name = name.substring(0, MAX_NAME_LENGTH);
            }
            bySlug.putIfAbsent(SlugUtil.generate(name, "tag", SLUG_BASE_LENGTH), name);
            if (bySlug.size() >= MAX_TAGS_PER_ARTICLE) {
                break;
            }
        }
        return bySlug.entrySet().stream().map(e -> findOrCreate(e.getKey(), e.getValue())).toList();
    }

    private Long findOrCreate(String slug, String name) {
        Tag existing = tagMapper.selectOne(new LambdaQueryWrapper<Tag>().eq(Tag::getSlug, slug));
        if (existing != null) {
            return existing.getId();
        }
        Tag tag = new Tag();
        tag.setSlug(slug);
        // 文章来源是中文站点，标签名多为中文或英文技术名词；英文名缺省与中文名相同，后台可再改
        tag.setNameZh(name);
        tag.setNameEn(name);
        try {
            tagMapper.insert(tag);
        } catch (DuplicateKeyException e) {
            // 单管理员场景下几乎不会并发；真遇到时按冲突报错，让调用方重试即可
            throw BusinessException.conflict("标签创建冲突，请重试: " + name);
        }
        return tag.getId();
    }
}
