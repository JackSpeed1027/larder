# 运行镜像只带 JRE 与产物，不把 Maven/源码带进最终层。
# 基础镜像按 digest 固定（见 docs/versions.lock.json 的 eclipse-temurin 条目）。
FROM eclipse-temurin:21-jre@sha256:0b99b3376fbb50dbf9c0af8535fdd5b8eb0d3fdfc8b1e4967f383b6919f6901a

WORKDIR /app
# 构建由本机 Maven Wrapper 完成（mvnw package），镜像只负责运行
COPY target/larder-backend-0.1.0-SNAPSHOT.jar /app/app.jar

ENV TZ=UTC \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=70 -Duser.timezone=UTC"

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
