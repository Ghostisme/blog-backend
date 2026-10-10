// 博客后端流水线：密钥落盘 → 测试 → 构建镜像 → 部署 → 健康检查。
//
// 前置条件（Jenkins 节点需满足）：
//   1. 节点上有 JDK 17+ 和 Docker（能执行 docker / docker compose），并且就是要部署的那台 VPS；
//      如果 Jenkins 自己跑在容器里，需要挂载宿主机的 /var/run/docker.sock。
//   2. 管理员口令哈希的生成方式见 README。
//
// 密钥怎么存（和「每次手填构建参数」的差别）：
//   Jenkins 的 password 参数【不会记住】上次填的值，点 Build Now 时是空的。
//   所以第一次用 "Build with Parameters" 填全密钥后，流水线写到 Jenkins 家目录下的
//   /var/lib/jenkins/blog.env（权限 600，不进 git、不进工作区、不进镜像）。
//   之后再点 Build Now，参数留空就会读这份文件。想换口令时再填一次，会覆盖保存。
//   这和那三个 Upwork 项目「存一次、以后自动注入」是同一个效果，只是入口在任务参数框，
//   不用去 Credentials 页上传 Secret file。
//
//   MySQL 的两个口令只在数据卷【首次初始化】时生效。之后改保存的文件不会改变数据库里的口令，
//   只会让 API 连不上（容器 unhealthy）。要换口令必须先处理数据卷。
//
//   翻译配置也会保存到同一个文件。第一次启用时把 BLOG_TRANSLATION_ENABLED=true、
//   API 地址、模型和 API Key 填入；以后留空沿用已保存的值。翻译开关为 false 时，
//   文章仍会入队，但后台不会调用外部翻译服务。
//
// 对 VPS 上其它容器的影响：
//   只操作 compose 项目 "blog" 下的 blog-api / blog-mysql，以及带 blog-backend 名字的镜像；
//   不执行任何全局 prune。
pipeline {
    agent any

    options {
        timestamps()
        disableConcurrentBuilds()          // 两次构建同时 compose up 会互相抢容器
        buildDiscarder(logRotator(numToKeepStr: '20'))
        timeout(time: 30, unit: 'MINUTES')
    }

    parameters {
        string(name: 'BLOG_API_PORT', defaultValue: '18086',
               description: 'API 在宿主机上监听的端口（仅 127.0.0.1）。请选一个 VPS 上未被占用的端口，并与 Nginx 的 proxy_pass 保持一致')
        booleanParam(name: 'RUN_TESTS', defaultValue: true,
                     description: '是否执行测试。集成测试会用 Testcontainers 临时起一个 MySQL 容器，内存紧张时可取消')
        string(name: 'JDK_HOME', defaultValue: '/usr/lib/jvm/java-17-openjdk-amd64',
               description: '编译测试用的 JDK 目录，必须含 javac 且支持 --release 17。Jenkins 服务进程的 PATH 往往和登录 shell 不同，不能依赖默认 java')

        // 以下密钥第一次必填，保存后留空即可。刻意不设默认值：默认值会明文写进本仓库。
        password(name: 'BLOG_DB_ROOT_PASSWORD', defaultValue: '',
                 description: 'MySQL root 口令。第一次必填（会保存到服务器）；之后留空沿用已保存的值')
        password(name: 'BLOG_DB_PASSWORD', defaultValue: '',
                 description: '应用连接 MySQL 用的口令。第一次必填；之后留空沿用已保存的值。只在数据卷首次初始化时生效')
        password(name: 'BLOG_JWT_SECRET', defaultValue: '',
                 description: 'JWT 签名密钥，至少 32 个字符。第一次必填；之后留空沿用。更换它会让所有已登录的管理员会话失效')
        string(name: 'BLOG_ADMIN_USERNAME', defaultValue: '',
               description: '管理员用户名。第一次必填；之后留空沿用已保存的值')
        password(name: 'BLOG_ADMIN_PASSWORD_HASH', defaultValue: '',
                 description: '管理员口令的 BCrypt 哈希，以 $2a$ / $2b$ / $2y$ 开头，共 60 个字符。第一次必填；之后留空沿用')

        // 翻译配置第一次按需填写；之后留空沿用服务器上已保存的值。
        // enabled 使用 string 而不是 booleanParam，这样 Build Now 的默认空值不会把已启用配置覆盖回 false。
        string(name: 'BLOG_TRANSLATION_ENABLED', defaultValue: '',
               description: '是否启用中文转英文后台翻译：true / false。第一次启用请填 true；之后留空沿用')
        string(name: 'BLOG_TRANSLATION_BASE_URL', defaultValue: '',
               description: 'OpenAI 兼容接口地址，例如 https://api.openai.com/v1；留空沿用')
        password(name: 'BLOG_TRANSLATION_API_KEY', defaultValue: '',
                 description: '翻译服务 API Key。启用翻译时必填；之后留空沿用已保存的值')
        string(name: 'BLOG_TRANSLATION_MODEL', defaultValue: '',
               description: '翻译模型，例如 gpt-4o-mini；留空沿用')
        string(name: 'BLOG_TRANSLATION_BATCH_SIZE', defaultValue: '',
               description: '每轮最多处理任务数，1-10；留空沿用')
    }

    environment {
        IMAGE_TAG = "${env.BUILD_NUMBER}"
        // 落在 Jenkins 家目录，不在工作区里，checkout 清不掉。Jenkins 容器要持久化这个路径。
        BLOG_ENV_STORE = '/var/lib/jenkins/blog.env'
    }

    stages {
        stage('Save or load secrets') {
            steps {
                // 开头的 #! 不能省：Jenkins 遇到没有 shebang 的脚本，会用 "sh -xe" 执行，
                // -x 会把每条命令展开后的内容打印到日志，口令就全泄露了。
                // 这里只报告缺哪一项 / 格式对不对，绝不输出任何密钥的值。
                sh '''#!/bin/bash
                    set -u
                    bad=0
                    store="${BLOG_ENV_STORE}"
                    translation_enabled_input="${BLOG_TRANSLATION_ENABLED:-}"
                    translation_base_url_input="${BLOG_TRANSLATION_BASE_URL:-}"
                    translation_api_key_input="${BLOG_TRANSLATION_API_KEY:-}"
                    translation_model_input="${BLOG_TRANSLATION_MODEL:-}"
                    translation_batch_size_input="${BLOG_TRANSLATION_BATCH_SIZE:-}"

                    filled=0
                    empty=0
                    for name in BLOG_DB_ROOT_PASSWORD BLOG_DB_PASSWORD BLOG_JWT_SECRET BLOG_ADMIN_USERNAME BLOG_ADMIN_PASSWORD_HASH; do
                        if [ -n "${!name:-}" ]; then
                            filled=$((filled + 1))
                        else
                            empty=$((empty + 1))
                        fi
                    done

                    if [ "$filled" -gt 0 ] && [ "$empty" -gt 0 ]; then
                        echo "密钥参数必须一次填全或全部留空（留空 = 用服务器上已保存的值）。本次填了 ${filled} 项、空了 ${empty} 项。"
                        exit 1
                    fi

                    if [ "$filled" -eq 5 ]; then
                        bcrypt_re='^[$]2[aby][$][0-9]{2}[$].{53}$'
                        if [ "${#BLOG_JWT_SECRET}" -lt 32 ]; then
                            echo "BLOG_JWT_SECRET 太短：至少需要 32 个字符"; bad=1
                        fi
                        if ! [[ "$BLOG_ADMIN_PASSWORD_HASH" =~ $bcrypt_re ]]; then
                            echo "BLOG_ADMIN_PASSWORD_HASH 不是 BCrypt 哈希：应以 \\$2a\\$ / \\$2b\\$ / \\$2y\\$ 开头、共 60 个字符。"
                            echo "常见原因：粘贴时丢了开头的 \\$ 符号，或填成了明文口令。"
                            bad=1
                        fi
                        if [ "$bad" != 0 ]; then
                            exit 1
                        fi
                        # umask 077：文件 600，只有 jenkins 用户能读。先写临时文件再 mv，避免写到一半留下半份。
                        umask 077
                        tmp="${store}.tmp.$$"
                        {
                            # 哈希含 $，必须单引号包住，手动 docker compose --env-file 时才不会被展开。
                            # Jenkins 路径走 export-blog-env.sh，有无引号都能解析。
                            printf 'BLOG_DB_ROOT_PASSWORD=%s\n' "$BLOG_DB_ROOT_PASSWORD"
                            printf 'BLOG_DB_PASSWORD=%s\n' "$BLOG_DB_PASSWORD"
                            printf 'BLOG_JWT_SECRET=%s\n' "$BLOG_JWT_SECRET"
                            printf 'BLOG_ADMIN_USERNAME=%s\n' "$BLOG_ADMIN_USERNAME"
                            printf "BLOG_ADMIN_PASSWORD_HASH='%s'\n" "$BLOG_ADMIN_PASSWORD_HASH"
                        } > "$tmp"
                        # 重填密钥（例如更换 JWT）时不能把已保存的翻译配置一起冲掉：
                        # 这里先原样带上旧的 BLOG_TRANSLATION_* 行，后面的合并步骤再按本次参数覆盖。
                        if [ -f "$store" ]; then
                            grep -E '^BLOG_TRANSLATION_' "$store" >> "$tmp" || true
                        fi
                        mv -f "$tmp" "$store"
                        echo "密钥已保存到服务器（之后 Build Now 不用再填）"
                    elif [ ! -f "$store" ]; then
                        echo "服务器上还没有保存过密钥，且本次参数是空的。"
                        echo "请用 Build with Parameters，把数据库口令 / JWT / 管理员用户名和哈希填全。"
                        echo "填一次就会保存；以后点 Build Now 即可。"
                        exit 1
                    else
                        echo "使用服务器上已保存的密钥（本次参数留空）"
                    fi

                    eval "$(bash deploy/export-blog-env.sh "$store")"

                    # 翻译配置可选，但一旦启用必须有完整的可运行配置。
                    # 先取本次参数，留空的项再沿用 blog.env，最后才落盘，避免 Build Now 的默认空值覆盖旧配置。
                    translation_enabled="${translation_enabled_input:-${BLOG_TRANSLATION_ENABLED:-false}}"
                    translation_base_url="${translation_base_url_input:-${BLOG_TRANSLATION_BASE_URL:-https://api.openai.com/v1}}"
                    translation_api_key="${translation_api_key_input:-${BLOG_TRANSLATION_API_KEY:-}}"
                    translation_model="${translation_model_input:-${BLOG_TRANSLATION_MODEL:-gpt-4o-mini}}"
                    translation_batch_size="${translation_batch_size_input:-${BLOG_TRANSLATION_BATCH_SIZE:-2}}"

                    case "$translation_enabled" in
                        true|false) ;;
                        *)
                            echo "BLOG_TRANSLATION_ENABLED 必须是 true 或 false"
                            bad=1
                            ;;
                    esac
                    if [ "$translation_enabled" = "true" ] && [ -z "$translation_api_key" ]; then
                        echo "翻译已启用，但 BLOG_TRANSLATION_API_KEY 为空"
                        bad=1
                    fi
                    if [ -z "$translation_base_url" ] || ! [[ "$translation_base_url" =~ ^https?:// ]]; then
                        echo "BLOG_TRANSLATION_BASE_URL 必须是 http:// 或 https:// 开头的地址"
                        bad=1
                    fi
                    if [ -z "$translation_model" ]; then
                        echo "BLOG_TRANSLATION_MODEL 不能为空"
                        bad=1
                    fi
                    if ! [[ "$translation_batch_size" =~ ^([1-9]|10)$ ]]; then
                        echo "BLOG_TRANSLATION_BATCH_SIZE 必须是 1-10"
                        bad=1
                    fi
                    if [ "$bad" != 0 ]; then
                        exit 1
                    fi

                    # 始终用合并后的值重写，保证旧版 blog.env 也补齐翻译配置；不打印任何密钥内容。
                    umask 077
                    tmp="${store}.tmp.$$"
                    {
                        printf 'BLOG_DB_ROOT_PASSWORD=%s\n' "$BLOG_DB_ROOT_PASSWORD"
                        printf 'BLOG_DB_PASSWORD=%s\n' "$BLOG_DB_PASSWORD"
                        printf 'BLOG_JWT_SECRET=%s\n' "$BLOG_JWT_SECRET"
                        printf 'BLOG_ADMIN_USERNAME=%s\n' "$BLOG_ADMIN_USERNAME"
                        printf "BLOG_ADMIN_PASSWORD_HASH='%s'\n" "$BLOG_ADMIN_PASSWORD_HASH"
                        printf 'BLOG_TRANSLATION_ENABLED=%s\n' "$translation_enabled"
                        printf 'BLOG_TRANSLATION_BASE_URL=%s\n' "$translation_base_url"
                        printf 'BLOG_TRANSLATION_API_KEY=%s\n' "$translation_api_key"
                        printf 'BLOG_TRANSLATION_MODEL=%s\n' "$translation_model"
                        printf 'BLOG_TRANSLATION_BATCH_SIZE=%s\n' "$translation_batch_size"
                    } > "$tmp"
                    mv -f "$tmp" "$store"

                    # 后续 stage 会重新从 blog.env 导出，这里只校验合并结果。
                    for name in BLOG_DB_ROOT_PASSWORD BLOG_DB_PASSWORD BLOG_JWT_SECRET BLOG_ADMIN_USERNAME BLOG_ADMIN_PASSWORD_HASH; do
                        if [ -z "${!name:-}" ]; then
                            echo "已保存的文件缺少: $name"; bad=1
                        fi
                    done
                    if [ "${#BLOG_JWT_SECRET}" -lt 32 ]; then
                        echo "已保存的 BLOG_JWT_SECRET 太短"; bad=1
                    fi
                    if ! [[ "${BLOG_API_PORT}" =~ ^[0-9]{2,5}$ ]]; then
                        echo "BLOG_API_PORT 必须是数字端口"; bad=1
                    fi
                    [ "$bad" = 0 ] && echo "密钥和翻译配置校验通过（未输出任何密钥的值）"
                    exit "$bad"
                '''
            }
        }

        stage('Test') {
            when { expression { params.RUN_TESTS } }
            steps {
                // 本 stage 故意不加载 blog.env：生产口令一旦进进程，
                // Spring 环境变量优先级高于 application-test.yml，登录相关用例会失败。
                sh '''
                    # Jenkins 服务进程的 PATH / JAVA_HOME 和登录 shell 不是同一套。
                    # 本机曾出现：登录用户 javac 是 17，服务进程却用了不支持 --release 17 的 javac。
                    # 这里钉死到参数 JDK_HOME，让 Maven 和 javac 都来自同一个完整 JDK。
                    export JAVA_HOME="${JDK_HOME:-/usr/lib/jvm/java-17-openjdk-amd64}"
                    export PATH="$JAVA_HOME/bin:$PATH"
                    echo "JAVA_HOME=$JAVA_HOME"
                    command -v java; java -version
                    command -v javac; javac -version
                    ./mvnw -B test
                '''
            }
            post {
                always { junit testResults: 'target/surefire-reports/*.xml', allowEmptyResults: true }
            }
        }

        stage('Build image') {
            steps {
                // compose 在解析整份 yml 时就会展开 ${VAR:?}，缺变量会在「构建」阶段就失败，
                // 即便镜像构建本身用不到口令。这里只注入环境、不启动容器，也不会把密钥写进镜像层
                // （Dockerfile 没有 build-arg 引用这些变量）。
                sh '''#!/bin/bash
                    set -eu
                    eval "$(bash deploy/export-blog-env.sh "$BLOG_ENV_STORE")"
                    docker compose build blog-api
                '''
            }
        }

        stage('Deploy') {
            steps {
                // 解析后的值进进程环境，compose 从环境做插值，不依赖 --env-file 对 $ 的二次展开。
                // BLOG_API_PORT / IMAGE_TAG 来自 Jenkins，shell 变量优先于保存的文件。
                // MySQL 容器只要配置没变就不会重建，数据卷保持不动；仅 blog-api 会换新镜像。
                sh '''#!/bin/bash
                    set -eu
                    eval "$(bash deploy/export-blog-env.sh "$BLOG_ENV_STORE")"
                    docker compose up -d --remove-orphans
                '''
            }
        }

        stage('Health check') {
            steps {
                // 看容器自身的 HEALTHCHECK 状态，而不是 curl 127.0.0.1：
                // Jenkins 若跑在容器里，它的 127.0.0.1 不是宿主机
                sh '''
                    for i in $(seq 1 30); do
                        status=$(docker inspect -f '{{.State.Health.Status}}' blog-api 2>/dev/null || echo missing)
                        echo "blog-api health: $status"
                        [ "$status" = "healthy" ] && exit 0
                        sleep 5
                    done
                    echo "健康检查超时，最近日志："
                    docker logs --tail 100 blog-api
                    exit 1
                '''
            }
        }

        stage('Cleanup') {
            steps {
                // 只保留本项目最近 5 个镜像版本；仅匹配 blog-backend 仓库，不碰其它项目的镜像
                sh '''
                    docker images blog-backend --format '{{.Tag}}' \
                        | grep -E '^[0-9]+$' | sort -rn | tail -n +6 \
                        | xargs -r -I{} docker rmi blog-backend:{} || true
                '''
            }
        }
    }

    post {
        failure { echo '构建失败。若已经部署过，上一个版本的镜像仍保留在本机，可用 IMAGE_TAG=<旧构建号> docker compose up -d blog-api 回滚（需已导出 blog.env 里的变量）。' }
    }
}
