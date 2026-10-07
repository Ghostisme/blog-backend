package com.darkrich.blog;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

/**
 * 博客后端入口。
 *
 * <p>按业务模块分包（module.article / category / tag / resume / auth / importer），
 * 每个模块自带 controller、service、mapper、dto、vo，模块之间只通过 service 交互。
 */
@SpringBootApplication
@ConfigurationPropertiesScan
// 只扫描带 @Mapper 的接口：各模块的 mapper 分散在不同包里，这样新增模块无需再改这里
@MapperScan(basePackages = "com.darkrich.blog.module", annotationClass = org.apache.ibatis.annotations.Mapper.class)
public class BlogApplication {

    public static void main(String[] args) {
        SpringApplication.run(BlogApplication.class, args);
    }
}
