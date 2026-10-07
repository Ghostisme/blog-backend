-- 种子数据：五个默认领域 + 中英文简历占位内容。
-- code 要与 ArticleClassifier 里的领域编码保持一致，改动时两边一起改。
INSERT INTO category (code, name_zh, name_en, icon, sort_order)
VALUES ('frontend', '前端', 'Frontend', 'laptop', 10),
       ('backend', '后端', 'Backend', 'server', 20),
       ('database', '数据库', 'Database', 'database', 30),
       ('devops', '运维', 'DevOps', 'cloud', 40),
       ('mobile', '移动端', 'Mobile', 'mobile', 50);

-- 占位简历：上线后在后台「简历管理」里替换为真实内容。
-- 注意：简历页是公开的，这里填写的邮箱/电话会被所有访客看到。
INSERT INTO resume (lang, content)
VALUES ('zh', JSON_OBJECT(
        'basics', JSON_OBJECT('name', '你的名字', 'title', '全栈工程师', 'email', '', 'phone', '',
                              'location', '', 'website', 'https://blog.darkrich.com', 'avatarUrl', '',
                              'links', JSON_ARRAY()),
        'summary', '在后台「简历管理」中填写个人简介。',
        'skills', JSON_ARRAY(),
        'experience', JSON_ARRAY(),
        'projects', JSON_ARRAY(),
        'education', JSON_ARRAY())),
       ('en', JSON_OBJECT(
        'basics', JSON_OBJECT('name', 'Your Name', 'title', 'Full-Stack Engineer', 'email', '', 'phone', '',
                              'location', '', 'website', 'https://blog.darkrich.com', 'avatarUrl', '',
                              'links', JSON_ARRAY()),
        'summary', 'Fill in your profile in the admin console.',
        'skills', JSON_ARRAY(),
        'experience', JSON_ARRAY(),
        'projects', JSON_ARRAY(),
        'education', JSON_ARRAY()));
