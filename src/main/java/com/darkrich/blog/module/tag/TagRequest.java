package com.darkrich.blog.module.tag;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 修改标签显示名的请求。slug 不允许改：它是去重依据，改了会让后续导入出现重复标签。 */
public record TagRequest(@NotBlank @Size(max = 64) String nameZh,
                         @NotBlank @Size(max = 64) String nameEn) {
}
