// 博客后端流水线：测试 → 构建镜像 → 部署 → 健康检查。
//
// 前置条件（Jenkins 节点需满足）：
//   1. 节点上有 JDK 17 和 Docker（能执行 docker / docker compose），并且就是要部署的那台 VPS；
//      如果 Jenkins 自己跑在容器里，需要挂载宿主机的 /var/run/docker.sock。
//   2. 在 Jenkins「凭据」里创建下面 5 个 Secret text（ID 可按需改，同步改 environment 段即可）：
//        blog-db-root-password / blog-db-password / blog-jwt-secret /
//        blog-admin-username / blog-admin-password-hash
//      管理员口令哈希的生成方式见 README。
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
    }

    environment {
        IMAGE_TAG                = "${env.BUILD_NUMBER}"
        BLOG_API_PORT            = "${params.BLOG_API_PORT}"
        // credentials() 注入的值在日志里会被自动打码
        BLOG_DB_ROOT_PASSWORD    = credentials('blog-db-root-password')
        BLOG_DB_PASSWORD         = credentials('blog-db-password')
        BLOG_JWT_SECRET          = credentials('blog-jwt-secret')
        BLOG_ADMIN_USERNAME      = credentials('blog-admin-username')
        BLOG_ADMIN_PASSWORD_HASH = credentials('blog-admin-password-hash')
    }

    stages {
        stage('Test') {
            when { expression { params.RUN_TESTS } }
            steps {
                sh './mvnw -B test'
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
                // 单引号 + 环境变量传值：BCrypt 哈希里含 $，交给 Groovy 插值会被破坏。
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
        failure { echo '部署失败。上一个版本的镜像仍保留在本机，可用 IMAGE_TAG=<旧构建号> docker compose up -d blog-api 回滚。' }
    }
}
