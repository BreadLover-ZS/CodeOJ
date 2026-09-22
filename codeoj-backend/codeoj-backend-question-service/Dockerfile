# 基础镜像
# openjdk:8-jdk-alpine 已停止维护且国内镜像源不可拉取，改用同版本 Temurin（含完整 JDK）
FROM eclipse-temurin:8-jdk
  
# 指定工作目录
WORKDIR /app
  
# 将 jar 包添加到工作目录，比如 target/codeoj-backend-user-service-0.0.1-SNAPSHOT.jar
ADD target/codeoj-backend-question-service-0.0.1-SNAPSHOT.jar .
  
# 暴露端口
EXPOSE 8103
  
# 启动命令
ENTRYPOINT ["java","-jar","/app/codeoj-backend-question-service-0.0.1-SNAPSHOT.jar","--spring.profiles.active=prod"]