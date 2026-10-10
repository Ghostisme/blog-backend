#!/usr/bin/env bash
# 从 dotenv 文件导出博客部署所需的 BLOG_* 变量。
#
# 不能 source / 不能 set -a：BCrypt 哈希和部分口令含 `$`，source 会被 bash 当成变量展开。
# 本脚本只认「整行 KEY=VALUE」，把值按字面量取出，再用 printf %q 打成对 eval 安全的 export 语句。
#
# 用法（必须 eval，否则 export 只作用于子进程）：
#   eval "$(bash deploy/export-blog-env.sh /path/to/env-file)"
set -eu

file="${1:?用法: deploy/export-blog-env.sh <env-file>}"
if [ ! -f "$file" ]; then
    echo "找不到凭据文件: $file" >&2
    exit 1
fi

# 取出 KEY 对应的原始值。不打印密钥本身到 stderr。
get_val() {
    local key="$1"
    local line raw first last
    # 只匹配行首 KEY=，忽略注释和其它键；同一键出现多次时用最后一次（与 compose 一致）。
    line=$(grep -E "^${key}=" "$file" | tail -n1 || true)
    [ -n "$line" ] || { printf ''; return; }
    raw="${line#*=}"
    # Windows 记事本保存会带 \\r，不剥掉的话哈希长度对不上 BCrypt 规则
    raw="${raw%$'\r'}"
    # 剥掉包住整个值的一层引号。.env.example 要求哈希用单引号，
    # 是为了给「直接 docker compose --env-file」用；这里剥掉后由 %q 再导出，
    # 所以 Jenkins 路径下即使用户忘了加引号，哈希里的 $ 也不会被二次展开。
    if [ "${#raw}" -ge 2 ]; then
        first="${raw%"${raw#?}"}"
        last="${raw#"${raw%?}"}"
        if { [ "$first" = "'" ] && [ "$last" = "'" ]; } || { [ "$first" = '"' ] && [ "$last" = '"' ]; }; then
            raw="${raw#?}"
            raw="${raw%?}"
        fi
    fi
    printf '%s' "$raw"
}

emit() {
    local key="$1"
    local val
    val="$(get_val "$key")"
    printf 'export %s=%q\n' "$key" "$val"
}

emit BLOG_DB_ROOT_PASSWORD
emit BLOG_DB_PASSWORD
emit BLOG_JWT_SECRET
emit BLOG_ADMIN_USERNAME
emit BLOG_ADMIN_PASSWORD_HASH
emit BLOG_TRANSLATION_ENABLED
emit BLOG_TRANSLATION_BASE_URL
emit BLOG_TRANSLATION_API_KEY
emit BLOG_TRANSLATION_MODEL
emit BLOG_TRANSLATION_BATCH_SIZE
