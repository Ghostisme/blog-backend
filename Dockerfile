# ---------- 构建阶段 ----------
# 测试已在 Jenkins 的 Test 阶段跑过（见 Jenkinsfile），镜像构建不再重复：
# 测试依赖 Testcontainers，需要访问宿主机 Docker，放进镜像构建里既做不到也不该做。
FROM maven:3.9-eclipse-temurin-17 AS build
WORKDIR /workspace

# 先只拷 pom 再解析依赖：依赖不变时这一层命中缓存，改业务代码不会重新下载全部依赖
COPY pom.xml .
# retryHandler：从 Maven Central 下载大文件时偶发连接中断（Premature end of Content-Length），
# 让 Wagon 自动重试几次，而不是一次抖动就让整个构建失败
ENV MAVEN_OPTS="-Dmaven.wagon.http.retryHandler.count=5 -Dmaven.wagon.httpconnectionManager.ttlSeconds=25"
RUN --mount=type=cache,target=/root/.m2 mvn -B -q dependency:resolve

COPY src ./src
RUN --mount=type=cache,target=/root/.m2 mvn -B -q package -DskipTests

# ---------- 运行阶段 ----------
FROM eclipse-temurin:17-jre-alpine

# 用于 Jenkins 清理旧镜像时只匹配本项目，避免误伤 VPS 上其它容器的镜像
LABEL com.darkrich.blog="backend"

# 非 root 运行：即使应用被攻破，也无法直接改动容器内的系统文件
RUN addgroup -S blog && adduser -S blog -G blog
WORKDIR /app
COPY --from=build /workspace/target/blog-backend.jar app.jar
USER blog

# MaxRAMPercentage：让堆大小跟随容器内存上限（compose 里设置了 mem_limit），而不是按宿主机内存估算，
# 否则在共用 VPS 上 JVM 会以为自己有整机内存，被 OOM-kill。
# user.timezone：与数据库会话时区一致，保证 LocalDateTime.now() 与 MySQL 的 CURRENT_TIMESTAMP 同一口径。
ENV JAVA_OPTS="-XX:MaxRAMPercentage=70 -Duser.timezone=Asia/Shanghai" \
    SPRING_PROFILES_ACTIVE=prod

EXPOSE 8080

# alpine 自带 busybox wget，不必为健康检查额外安装 curl
HEALTHCHECK --interval=30s --timeout=5s --start-period=90s --retries=3 \
    CMD wget -qO- http://127.0.0.1:8080/actuator/health | grep -q '"status":"UP"' || exit 1

# 经 sh 启动是为了展开 JAVA_OPTS；exec 让 java 成为 PID 1，才能正确接收 docker stop 的 SIGTERM 做优雅停机
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar app.jar"]
