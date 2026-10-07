// 博客后端流水线：参数校验 → 测试 → 构建镜像 → 部署 → 健康检查。
//
// 前置条件（Jenkins 节点需满足）：
//   1. 节点上有 JDK 17+ 和 Docker（能执行 docker / docker compose），并且就是要部署的那台 VPS；
//      如果 Jenkins 自己跑在容器里，需要挂载宿主机的 /var/run/docker.sock。
//   2. 密钥不放在 Jenkins「凭据」里，而是作为【构建参数】在点 "Build with Parameters" 时填写（见 parameters 段）。
//      管理员口令哈希的生成方式见 README。
//
// 关于用参数传密钥，需要知道的取舍：
//   * 每次构建都要重新填写全部密钥——Jenkins 不会记住上一次填的值。
//     也因此无法用 webhook / 定时任务自动触发：自动触发时参数是空的，会在"参数校验"阶段直接失败。
//   * password 类型的参数在界面上是掩码显示，并以加密形式保存在构建记录里；但它不等于凭据存储，
//     有"构建"权限的人仍然能打开参数页面。
//   * MySQL 的两个口令只在数据卷【首次初始化】时生效。之后每次构建必须填回【同一个值】：
//     API 用它连库，填错会连不上(容器会 unhealthy)；而数据库里的口令不会被改变。
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

        // 以下参数会被 Jenkins 自动导出为同名环境变量，docker-compose.yml 直接读取，不需要再写 environment 段。
        // 刻意不设默认值：默认值会明文写进本仓库，等于把密钥提交了。
        password(name: 'BLOG_DB_ROOT_PASSWORD', defaultValue: '',
                 description: 'MySQL root 口令。仅在数据卷首次初始化时生效，之后每次都要填同一个值')
        password(name: 'BLOG_DB_PASSWORD', defaultValue: '',
                 description: '应用连接 MySQL 用的口令。仅在数据卷首次初始化时生效，之后每次都要填同一个值')
        password(name: 'BLOG_JWT_SECRET', defaultValue: '',
                 description: 'JWT 签名密钥，至少 32 个字符。更换它会让所有已登录的管理员会话失效')
        string(name: 'BLOG_ADMIN_USERNAME', defaultValue: '',
               description: '管理员用户名（不是机密，所以用普通文本参数）')
        password(name: 'BLOG_ADMIN_PASSWORD_HASH', defaultValue: '',
                 description: '管理员口令的 BCrypt 哈希，以 $2a$ / $2b$ / $2y$ 开头，共 60 个字符。生成方式见 README')
    }

    environment {
        IMAGE_TAG = "${env.BUILD_NUMBER}"
    }

    stages {
        stage('Validate parameters') {
            steps {
                // 开头的 #! 不能省：Jenkins 遇到没有 shebang 的脚本，会用 "sh -xe" 执行，
                // -x 会把每条命令展开后的内容打印到日志，参数值就全泄露了。
                // 有了 shebang，脚本按原样执行，不加 -x。
                // 这里只报告"缺哪一项 / 哪一项格式不对"，绝不输出任何参数的值或长度。
                sh '''#!/bin/bash
                    set -u
                    bad=0
                    for name in BLOG_API_PORT BLOG_DB_ROOT_PASSWORD BLOG_DB_PASSWORD BLOG_JWT_SECRET BLOG_ADMIN_USERNAME BLOG_ADMIN_PASSWORD_HASH; do
                        if [ -z "${!name:-}" ]; then
                            echo "缺少参数: $name"; bad=1
                        fi
                    done
                    if [ "$bad" = 1 ]; then
                        echo "请用 'Build with Parameters' 并填全上面列出的参数。"
                        echo "（第一次点 Build Now 时 Jenkins 还没有登记这些参数，这次失败是预期的。）"
                        exit 1
                    fi

                    bcrypt_re='^[$]2[aby][$][0-9]{2}[$].{53}$'
                    if [ "${#BLOG_JWT_SECRET}" -lt 32 ]; then
                        echo "BLOG_JWT_SECRET 太短：至少需要 32 个字符"; bad=1
                    fi
                    if ! [[ "$BLOG_ADMIN_PASSWORD_HASH" =~ $bcrypt_re ]]; then
                        echo "BLOG_ADMIN_PASSWORD_HASH 不是 BCrypt 哈希：应以 \\$2a\\$ / \\$2b\\$ / \\$2y\\$ 开头、共 60 个字符。"
                        echo "常见原因：粘贴时丢了开头的 \\$ 符号，或者填成了明文口令。"
                        bad=1
                    fi
                    if ! [[ "$BLOG_API_PORT" =~ ^[0-9]{2,5}$ ]]; then
                        echo "BLOG_API_PORT 必须是数字端口"; bad=1
                    fi
                    [ "$bad" = 0 ] && echo "参数校验通过（未输出任何参数的值）"
                    exit "$bad"
                '''
            }
        }

        stage('Test') {
            when { expression { params.RUN_TESTS } }
            steps {
                // env -u：把这 5 个变量从测试进程里拿掉。
                // 它们是给【部署】用的真实配置，但 Spring 的环境变量优先级高于 application-test.yml：
                // 不清掉的话，集成测试会拿真实的管理员哈希去验证测试口令，登录相关的用例必然失败。
                // 不能改成设为空字符串：空值同样会覆盖 yml，并触发配置校验失败。
                sh '''
                    env -u BLOG_ADMIN_USERNAME -u BLOG_ADMIN_PASSWORD_HASH -u BLOG_JWT_SECRET \
                        -u BLOG_DB_PASSWORD -u BLOG_DB_ROOT_PASSWORD \
                        ./mvnw -B test
                '''
            }
            post {
                always { junit testResults: 'target/surefire-reports/*.xml', allowEmptyResults: true }
            }
        }

        stage('Build image') {
            steps {
                // 只构建 blog-api；镜像带构建号标签，失败时可以手动回滚到上一个版本
                sh 'docker compose build blog-api'
            }
        }

        stage('Deploy') {
            steps {
                // 密钥经环境变量传给 compose，不出现在命令行里，日志中看不到它们。
                // MySQL 容器只要配置没变就不会重建，数据卷保持不动；仅 blog-api 会换新镜像。
                sh 'docker compose up -d --remove-orphans'
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
        failure { echo '构建失败。若已经部署过，上一个版本的镜像仍保留在本机，可用 IMAGE_TAG=<旧构建号> docker compose up -d blog-api 回滚。' }
    }
}
