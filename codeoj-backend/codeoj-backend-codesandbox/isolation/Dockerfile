ARG BASE_IMAGE=eclipse-temurin:8-jdk-jammy
FROM ${BASE_IMAGE}
RUN apt-get update && apt-get install -y --no-install-recommends python3 \
    && rm -rf /var/lib/apt/lists/* \
    && mkdir -p /opt/helpers /opt/source /source /work/classes /receipt \
    && chown -R 10000:10000 /work && chmod 0700 /receipt
COPY src/main/java/com/codeoj/codeojbackendcodesandbox/service/impl/nativejava/*.java /opt/source/
RUN javac -encoding UTF-8 -d /opt/helpers /opt/source/*.java && rm -rf /opt/source
COPY isolation/supervisor.py /opt/codeoj/supervisor.py
RUN chmod 0555 /opt/codeoj/supervisor.py
WORKDIR /tmp
ENTRYPOINT ["python3", "/opt/codeoj/supervisor.py"]
LABEL codeoj.isolation.protocol="1"
