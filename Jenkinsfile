// 博客后端流水线：凭据校验 → 测试 → 构建镜像 → 部署 → 健康检查。
//
// 前置条件（Jenkins 节点需满足）：
//   1. 节点上有 JDK 17+ 和 Docker（能执行 docker / docker compose），并且就是要部署的那台 VPS；
//      如果 Jenkins 自己跑在容器里，需要挂载宿主机的 /var/run/docker.sock。
//   2. Jenkins 里已建好 Secret file 凭据，ID 固定为 blog-env（内容格式见 .env.example）。
//      密钥存在 Jenkins 服务器上，构建时自动注入，不必每次手填。
//   3. 管理员口令哈希的生成方式见 README。
//
// 关于密钥存放：
//   * 不要用「构建参数」传口令——Jenkins 不会记住上次填的值，漏填就会部署失败。
//     MySQL 口令只在数据卷首次初始化时生效，填错或漏填之后改参数也改不回数据库里的口令。
//   * Secret file 在 withCredentials 块内被写到临时路径，块结束即删除，
//     不进工作区、不进 git、不写入镜像层。解析规则见 deploy/export-blog-env.sh。
//   * MySQL 的两个口令只在数据卷【首次初始化】时生效。之后改凭据文件不会改变数据库里的口令，
//     只会让 API 连不上（容器 unhealthy）。要换口令必须先处理数据卷。
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
    }

    environment {
        IMAGE_TAG = "${env.BUILD_NUMBER}"
        // 与其它项目（agent-studio-env / flowpilot-env / lumax-env）同一套约定：一个 Secret file 凭据。
        BLOG_ENV_CREDENTIAL_ID = 'blog-env'
    }

    stages {
        stage('Validate credentials') {
            steps {
                // Secret file 只在本 stage 和 Deploy 里打开，测试 stage 拿不到生产口令，
                // 避免 Spring 环境变量盖住 application-test.yml 导致登录用例失败。
                withCredentials([file(credentialsId: env.BLOG_ENV_CREDENTIAL_ID, variable: 'ENV_FILE')]) {
                    // 开头的 #! 不能省：Jenkins 遇到没有 shebang 的脚本，会用 "sh -xe" 执行，
                    // -x 会把每条命令展开后的内容打印到日志，口令就全泄露了。
                    // 这里只报告"缺哪一项 / 哪一项格式不对"，绝不输出任何密钥的值。
                    sh '''#!/bin/bash
                        set -u
                        bad=0
                        eval "$(bash deploy/export-blog-env.sh "$ENV_FILE")"

                        for name in BLOG_DB_ROOT_PASSWORD BLOG_DB_PASSWORD BLOG_JWT_SECRET BLOG_ADMIN_USERNAME BLOG_ADMIN_PASSWORD_HASH; do
                            if [ -z "${!name:-}" ]; then
                                echo "凭据文件 $BLOG_ENV_CREDENTIAL_ID 缺少: $name"
                                bad=1
                            fi
                        done
                        if [ "$bad" = 1 ]; then
                            echo "请在 Jenkins → Credentials 里编辑 Secret file「$BLOG_ENV_CREDENTIAL_ID」，格式见仓库 .env.example。"
                            exit 1
                        fi

                        bcrypt_re='^[$]2[aby][$][0-9]{2}[$].{53}$'
                        if [ "${#BLOG_JWT_SECRET}" -lt 32 ]; then
                            echo "BLOG_JWT_SECRET 太短：至少需要 32 个字符"; bad=1
                        fi
                        if ! [[ "$BLOG_ADMIN_PASSWORD_HASH" =~ $bcrypt_re ]]; then
                            echo "BLOG_ADMIN_PASSWORD_HASH 不是 BCrypt 哈希：应以 \\$2a\\$ / \\$2b\\$ / \\$2y\\$ 开头、共 60 个字符。"
                            echo "常见原因：粘贴时丢了开头的 \\$ 符号，或填成了明文口令。"
                            bad=1
                        fi
                        if ! [[ "${BLOG_API_PORT}" =~ ^[0-9]{2,5}$ ]]; then
                            echo "BLOG_API_PORT 必须是数字端口"; bad=1
                        fi
                        [ "$bad" = 0 ] && echo "凭据校验通过（未输出任何密钥的值）"
                        exit "$bad"
                    '''
                }
            }
        }

        stage('Test') {
            when { expression { params.RUN_TESTS } }
            steps {
                // 本 stage 故意不加载 blog-env：生产口令一旦进进程，
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
                withCredentials([file(credentialsId: env.BLOG_ENV_CREDENTIAL_ID, variable: 'ENV_FILE')]) {
                    sh '''#!/bin/bash
                        set -eu
                        eval "$(bash deploy/export-blog-env.sh "$ENV_FILE")"
                        docker compose build blog-api
                    '''
                }
            }
        }

        stage('Deploy') {
            steps {
                withCredentials([file(credentialsId: env.BLOG_ENV_CREDENTIAL_ID, variable: 'ENV_FILE')]) {
                    // 解析后的值进进程环境，compose 从环境做插值，不依赖 --env-file 对 $ 的二次展开。
                    // BLOG_API_PORT / IMAGE_TAG 来自 Jenkins，shell 变量优先于凭据文件。
                    // MySQL 容器只要配置没变就不会重建，数据卷保持不动；仅 blog-api 会换新镜像。
                    sh '''#!/bin/bash
                        set -eu
                        eval "$(bash deploy/export-blog-env.sh "$ENV_FILE")"
                        docker compose up -d --remove-orphans
                    '''
                }
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
        failure { echo '构建失败。若已经部署过，上一个版本的镜像仍保留在本机，可用 IMAGE_TAG=<旧构建号> docker compose up -d blog-api 回滚（需已导出 blog-env 里的变量）。' }
    }
}
