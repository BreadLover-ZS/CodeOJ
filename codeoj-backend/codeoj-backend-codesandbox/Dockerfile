# 基础镜像：沙箱需要在容器内编译运行用户代码，必须使用 JDK
# openjdk:8-jdk-alpine 已停止维护且国内镜像源不可拉取，改用同版本 Temurin（沙箱需要完整 JDK 编译用户代码）
FROM eclipse-temurin:8-jdk

# 指定工作目录
WORKDIR /app

# 将 jar 包添加到工作目录
ADD target/codeoj-backend-codesandbox-0.0.1-SNAPSHOT.jar .

# 暴露端口
EXPOSE 8090

# 启动命令
ENTRYPOINT ["java","-jar","/app/codeoj-backend-codesandbox-0.0.1-SNAPSHOT.jar","--spring.profiles.active=prod"]
