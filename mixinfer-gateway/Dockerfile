# ---- 构建阶段 ----
FROM maven:3.9.9-eclipse-temurin-17 AS build
WORKDIR /build

# 先复制 pom.xml，充分利用 Docker 层缓存加速依赖下载
COPY pom.xml .
RUN mvn -B dependency:go-offline

# 复制源码并构建（跳过测试，测试在 CI 中执行）
COPY src ./src
RUN mvn -B clean package -DskipTests

# ---- 运行阶段 ----
FROM eclipse-temurin:17-jre-alpine
WORKDIR /app

# 创建非 root 用户，降低容器被攻破后的影响面
RUN addgroup -S mixinfer && adduser -S mixinfer -G mixinfer

# 创建数据目录并赋予 mixinfer 用户权限（配合命名卷使用）
RUN mkdir -p /app/data && chown -R mixinfer:mixinfer /app/data

USER mixinfer

COPY --from=build /build/target/mixinfer-gateway-*.jar app.jar

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]