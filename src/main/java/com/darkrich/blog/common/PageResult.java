package com.darkrich.blog.common;

import com.baomidou.mybatisplus.core.metadata.IPage;

import java.util.List;
import java.util.function.Function;

/**
 * 分页结果。独立于 MyBatis-Plus 的 {@link IPage}，避免持久层类型泄漏到接口契约里。
 *
 * @param records 当前页数据
 * @param total   满足条件的总条数
 * @param page    当前页码（从 1 开始）
 * @param size    每页条数
 * @param <T>     元素类型
 */
public record PageResult<T>(List<T> records, long total, long page, long size) {

    /** 把持久层分页结果转换为接口分页结果，并顺带做元素类型转换。 */
    public static <S, T> PageResult<T> of(IPage<S> page, Function<S, T> mapper) {
        return new PageResult<>(
                page.getRecords().stream().map(mapper).toList(),
                page.getTotal(), page.getCurrent(), page.getSize());
    }

    /** 元素已经是最终类型（如已批量组装好）时使用。 */
    public static <T> PageResult<T> of(IPage<?> page, List<T> records) {
        return new PageResult<>(records, page.getTotal(), page.getCurrent(), page.getSize());
    }
}
