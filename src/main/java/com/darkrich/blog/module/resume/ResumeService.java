package com.darkrich.blog.module.resume;

import com.darkrich.blog.common.BusinessException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Set;

/** 简历读取与保存。 */
@Service
@RequiredArgsConstructor
public class ResumeService {

    /** 支持的语言。新增语言只需扩充这里，并在前端补对应界面文案。 */
    private static final Set<String> SUPPORTED_LANGS = Set.of("zh", "en");

    private final ResumeMapper mapper;
    private final ObjectMapper objectMapper;

    /**
     * 读取指定语言的简历。
     *
     * <p>数据库里没有该语言（如手工删过数据）时返回空结构而不是 404：
     * 简历页是站点入口之一，缺一份数据不应该导致整页报错。
     */
    public ResumeContent get(String lang) {
        requireSupported(lang);
        Resume row = mapper.selectById(lang);
        if (row == null) {
            return new ResumeContent(new ResumeContent.Basics(null, null, null, null, null, null, null, null),
                    null, null, null, null, null);
        }
        try {
            return objectMapper.readValue(row.getContent(), ResumeContent.class);
        } catch (JsonProcessingException e) {
            // 存量数据损坏属于服务端问题，不是调用方的错，让全局处理器记日志并返回 500
            throw new IllegalStateException("简历数据无法解析: " + lang, e);
        }
    }

    @Transactional
    public ResumeContent save(String lang, ResumeContent content) {
        requireSupported(lang);
        try {
            mapper.upsert(lang, objectMapper.writeValueAsString(content));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("简历数据无法序列化", e);
        }
        return content;
    }

    private void requireSupported(String lang) {
        if (!SUPPORTED_LANGS.contains(lang)) {
            throw BusinessException.badRequest("不支持的语言: " + lang);
        }
    }
}
