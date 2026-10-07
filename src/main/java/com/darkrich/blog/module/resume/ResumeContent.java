package com.darkrich.blog.module.resume;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * 简历内容结构（一种语言一份）。整体序列化成 JSON 存进 resume.content。
 *
 * <p>用强类型而不是任意 JSON：后台保存时能校验字段与长度，前端也有稳定的契约；
 * {@code ignoreUnknown} 让以后给结构加字段后，旧数据、旧前端依然能读。
 *
 * <p>每个 List 字段在紧凑构造器里把 null 规整成空列表，前端可以直接 {@code .map} 而不必判空。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ResumeContent(
        @Valid @NotNull Basics basics,
        @Size(max = 5000) String summary,
        @Valid @Size(max = 20) List<SkillGroup> skills,
        @Valid @Size(max = 30) List<Experience> experience,
        @Valid @Size(max = 30) List<Project> projects,
        @Valid @Size(max = 10) List<Education> education) {

    public ResumeContent {
        skills = skills == null ? List.of() : skills;
        experience = experience == null ? List.of() : experience;
        projects = projects == null ? List.of() : projects;
        education = education == null ? List.of() : education;
    }

    /**
     * 基本信息。
     * 注意：简历页是公开的，邮箱、电话会被所有访客看到，不想公开的留空即可。
     *
     * @param links 外部链接，如 GitHub、掘金主页
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Basics(@Size(max = 64) String name, @Size(max = 128) String title,
                         @Size(max = 128) String email, @Size(max = 64) String phone,
                         @Size(max = 128) String location, @Size(max = 256) String website,
                         @Size(max = 512) String avatarUrl, @Valid @Size(max = 10) List<Link> links) {
        public Basics {
            links = links == null ? List.of() : links;
        }
    }

    /** 外部链接。url 限定 http(s)，渲染为 href 时杜绝 javascript: 协议。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Link(@Size(max = 64) String label,
                       @Size(max = 512) @jakarta.validation.constraints.Pattern(regexp = "^(https?://\\S+)?$",
                               message = "必须是 http(s) 链接") String url) {
    }

    /** 技能分组，如「前端：React、TypeScript …」。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record SkillGroup(@Size(max = 64) String name, @Size(max = 30) List<@Size(max = 64) String> items) {
        public SkillGroup {
            items = items == null ? List.of() : items;
        }
    }

    /**
     * 工作经历。
     *
     * @param period     时间段的展示文本（如 "2021.03 - 至今"），不做日期解析，中英文版本可各写各的
     * @param highlights 要点列表，每条一行
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Experience(@Size(max = 128) String company, @Size(max = 128) String position,
                             @Size(max = 64) String period, @Size(max = 128) String location,
                             @Size(max = 20) List<@Size(max = 500) String> highlights) {
        public Experience {
            highlights = highlights == null ? List.of() : highlights;
        }
    }

    /** 项目经历。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Project(@Size(max = 128) String name, @Size(max = 128) String role,
                          @Size(max = 64) String period, @Size(max = 1000) String description,
                          @Size(max = 20) List<@Size(max = 64) String> techStack,
                          @Size(max = 20) List<@Size(max = 500) String> highlights,
                          @Size(max = 512) @jakarta.validation.constraints.Pattern(regexp = "^(https?://\\S+)?$",
                                  message = "必须是 http(s) 链接") String url) {
        public Project {
            techStack = techStack == null ? List.of() : techStack;
            highlights = highlights == null ? List.of() : highlights;
        }
    }

    /** 教育经历。 */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Education(@Size(max = 128) String school, @Size(max = 128) String degree,
                            @Size(max = 128) String major, @Size(max = 64) String period) {
    }
}
