# blog-backend

blog.darkrich.com 个人博客后端。Spring Boot 3.5 · JDK 17 · MyBatis-Plus · MySQL 8。

## 功能

- 文章：按 **等级**（入门 / 进阶 / 高级 / 资深）、**领域**（前端 / 后端 / 数据库 / 运维 / 移动端，可在后台增删）、**标签** 分类；中文全文检索（MySQL ngram）。
- 简历：中 / 英文各一份，整体存为 JSON。
- 后台：单管理员登录（JWT 放在 HttpOnly Cookie）、文章 / 领域 / 标签 / 简历管理、批量修改、Markdown 批量导入。

## 接口

公开（无需登录）：

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/articles` | 列表。参数：`page size level categoryId tagId keyword sort(LATEST\|HOT)` |
| GET | `/api/articles/{slug}` | 详情（浏览量 +1，含上下篇） |
| GET | `/api/filters` | 筛选栏数据：等级 / 领域 / 标签及文章数 |
| GET | `/api/resume?lang=zh\|en` | 简历 |

后台（需登录，路径前缀 `/api/admin`）：`POST login / logout`、`GET me`、`/articles` 增删改查、`PATCH /articles` 批量修改、`DELETE /articles?ids=` 批量删除、`POST /articles/import` 导入、`/categories`、`/tags`、`PUT /resume/{lang}`。

统一响应 `{ "code": 200, "message": "ok", "data": ... }`，`code` 与 HTTP 状态码一致。

## 掘金文章导入

后台上传 `.md` 文件（可多选，单次最多 200 个），每个文件：

1. 解析 front-matter（`title / description / tags / category / author / source_url / cover` 等常见键名都识别，YAML 不合法时退到逐行宽容解析）；没有 front-matter 时取首个 `# 标题`，再不行用文件名。
2. 按关键词建议领域和等级，**一律进入草稿**，在后台校正后再批量发布。
3. 按标题生成稳定的 slug，重复导入同一篇会被识别并跳过。
4. 保留 `source_url` / `author`，前台详情页展示原文链接和作者。

图片保持原链接，不下载到本地。

## 本地开发

需要 JDK 17 和 Docker。

```bash
# 1. 起一个本地 MySQL（映射到 127.0.0.1:23306，仅本地开发用；端口冲突就换一个，并设置环境变量 BLOG_DB_URL）
docker run -d --name blog-mysql-dev -p 127.0.0.1:23306:3306 \
  -e MYSQL_ROOT_PASSWORD=root -e MYSQL_DATABASE=blog \
  -e MYSQL_USER=blog -e MYSQL_PASSWORD=blog-dev-password \
  mysql:8.0 --character-set-server=utf8mb4 --default-time-zone=+08:00

# 2. 以 dev profile 启动（管理员 admin / dev-password-123，仅限本地）
./mvnw spring-boot:run -Dspring-boot.run.profiles=dev
```

测试（集成测试使用 Testcontainers，需要 Docker 在运行；没有 Docker 时自动跳过）：

```bash
./mvnw test
```

## 部署

前后端分开部署，后端用 Docker。设计上不影响 VPS 上已有的容器：

- MySQL **不映射任何宿主机端口**，只在独立的 `blog-net` 网络内可达；
- API 只绑定 `127.0.0.1:${BLOG_API_PORT}`（默认 18086），公网不可直连，由宿主机 Nginx 反代；
- 容器、网络、数据卷统一带 `blog` 前缀；各限制 512MB 内存和日志大小。

### 环境变量

| 变量 | 说明 |
|---|---|
| `BLOG_API_PORT` | 宿主机监听端口（仅 127.0.0.1）。**部署前请确认未被占用** |
| `BLOG_DB_ROOT_PASSWORD` / `BLOG_DB_PASSWORD` | MySQL 口令。仅在数据卷首次初始化时生效 |
| `BLOG_JWT_SECRET` | JWT 签名密钥，至少 32 字符 |
| `BLOG_ADMIN_USERNAME` | 管理员用户名 |
| `BLOG_ADMIN_PASSWORD_HASH` | 管理员口令的 BCrypt 哈希 |

以上均无默认值，缺任何一项应用都会启动失败。生成管理员哈希（口令从标准输入读取，不留在命令历史里）：

```bash
read -rsp '口令: ' P; echo; printf '%s' "$P" | docker run --rm -i httpd:2.4-alpine htpasswd -niBC 10 x | cut -d: -f2; unset P
```

### 用 Jenkins 部署

1. 在 Jenkins 凭据里创建 5 个 Secret text：`blog-db-root-password`、`blog-db-password`、`blog-jwt-secret`、`blog-admin-username`、`blog-admin-password-hash`。
2. 新建流水线任务指向本仓库，使用根目录的 `Jenkinsfile`。构建节点需要 JDK 17 与 Docker，且就是目标 VPS（或能访问其 Docker）。
3. 首次构建参数里把 `BLOG_API_PORT` 改成 VPS 上空闲的端口。

### 手动部署

```bash
cp .env.example .env   # 按说明填写，注意哈希要用单引号
docker compose up -d --build
```

### Nginx

前端仓库的 `deploy/nginx/blog.darkrich.com.conf` 里已包含 `/api` 反代，`proxy_pass` 的端口需与 `BLOG_API_PORT` 一致。

## 安全说明

- 管理员口令只存 BCrypt 哈希，不进数据库、不进仓库。
- 登录按来源 IP 限流（5 次失败锁定 15 分钟）；令牌只在 HttpOnly + SameSite=Strict 的 Cookie 里，前端 JS 拿不到。
- 写操作额外校验 `Origin` 与 `Host` 同源（依赖 Nginx 透传 `Host`）。
- 未显式放行的路径一律拒绝。
- 简历页公开，简历里填写的邮箱 / 电话会被所有访客看到。
