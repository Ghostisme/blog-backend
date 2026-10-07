package com.darkrich.blog.module.category;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.darkrich.blog.common.BusinessException;
import com.darkrich.blog.common.TextUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/** 领域的查询与维护。其它模块只能通过本类访问领域数据，不直接碰 CategoryMapper。 */
@Service
@RequiredArgsConstructor
public class CategoryService {

    private final CategoryMapper mapper;

    /** 前台筛选栏：文章数仅统计已发布。 */
    public List<CategoryView> listPublic() {
        return mapper.selectWithPublishedCount().stream().map(CategoryView::from).toList();
    }

    /** 后台管理：文章数含草稿。 */
    public List<CategoryView> listAdmin() {
        return mapper.selectWithTotalCount().stream().map(CategoryView::from).toList();
    }

    /** 批量取简要信息，供文章列表组装时避免逐条查询。 */
    public Map<Long, CategoryBrief> briefsByIds(Collection<Long> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return mapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(Category::getId, CategoryBrief::from, (a, b) -> a));
    }

    public Optional<Category> findByCode(String code) {
        return Optional.ofNullable(
                mapper.selectOne(new LambdaQueryWrapper<Category>().eq(Category::getCode, code)));
    }

    /** 按 id 取领域，不存在则抛 404；文章保存时用它校验 categoryId 的合法性。 */
    public Category requireById(Long id) {
        Category c = mapper.selectById(id);
        if (c == null) {
            throw BusinessException.badRequest("领域不存在: " + id);
        }
        return c;
    }

    @Transactional
    public CategoryView create(CategoryRequest req) {
        Category c = new Category();
        apply(c, req);
        mapper.insert(c); // code 重复时抛 DuplicateKeyException，由全局处理器转成 409
        return CategoryView.from(c);
    }

    @Transactional
    public CategoryView update(Long id, CategoryRequest req) {
        Category c = mapper.selectById(id);
        if (c == null) {
            throw BusinessException.notFound("领域不存在");
        }
        apply(c, req);
        mapper.updateById(c);
        return CategoryView.from(c);
    }

    /** 删除领域。仍有文章引用时数据库外键会拒绝，这里把它翻译成更明确的提示。 */
    @Transactional
    public void delete(Long id) {
        try {
            if (mapper.deleteById(id) == 0) {
                throw BusinessException.notFound("领域不存在");
            }
        } catch (DataIntegrityViolationException e) {
            throw BusinessException.conflict("该领域下仍有文章，请先迁移或删除这些文章");
        }
    }

    private void apply(Category c, CategoryRequest req) {
        c.setCode(req.code());
        c.setNameZh(req.nameZh().trim());
        c.setNameEn(req.nameEn().trim());
        c.setIcon(TextUtil.blankToNull(req.icon()));
        c.setSortOrder(req.sortOrder() == null ? 0 : req.sortOrder());
    }
}
