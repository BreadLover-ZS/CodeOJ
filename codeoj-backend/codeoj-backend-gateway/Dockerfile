# 基础镜像
# openjdk:8-jdk-alpine 已停止维护且国内镜像源不可拉取，改用同版本 Temurin（含完整 JDK）
FROM eclipse-temurin:8-jdk
  
# 指定工作目录
WORKDIR /app
  
# 将 jar 包添加到工作目录
ADD target/codeoj-backend-gateway-0.0.1-SNAPSHOT.jar .
  
# 暴露端口
EXPOSE 8101
  
# 启动命令
ENTRYPOINT ["java","-jar","/app/codeoj-backend-gateway-0.0.1-SNAPSHOT.jar","--spring.profiles.active=prod"]