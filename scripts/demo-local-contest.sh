#!/usr/bin/env bash
set -euo pipefail
task_root="$(cd "$(dirname "$0")/.." && pwd)"
if [[ -x /usr/libexec/java_home ]]; then
  task_java_home="$(/usr/libexec/java_home -v 1.8)"
else
  task_java_home="${JAVA_HOME:?Set JAVA_HOME to a Java 8 JDK}"
fi
if ! "$task_java_home/bin/java" -version 2>&1 | head -n 1 | rg -q '"1\.8\.'; then
  echo 'This demo requires Java 8.' >&2; exit 1
fi
env JAVA_HOME="$task_java_home" PATH="$task_java_home/bin:$PATH" mvn -B -q -f "$task_root/codeoj-backend/pom.xml" -DskipTests package
task_h2="${MAVEN_REPO:-$HOME/.m2/repository}/com/h2database/h2/2.2.224/h2-2.2.224.jar"
if [[ ! -f "$task_h2" ]]; then echo 'Run the question-service functional tests first to resolve H2 2.2.224.' >&2; exit 1; fi
task_tmp="$(mktemp -d "${TMPDIR:-/tmp}/codeoj-contest-demo.XXXXXX")"
trap 'rm -rf "$task_tmp"' EXIT
for task_module in question-service codesandbox; do
  unzip -qo "$task_root/codeoj-backend/codeoj-backend-$task_module/target/codeoj-backend-$task_module-0.0.1-SNAPSHOT.jar" 'BOOT-INF/lib/*' -d "$task_tmp"
done
task_cp="$task_root/codeoj-backend/codeoj-backend-question-service/target/classes:$task_root/codeoj-backend/codeoj-backend-codesandbox/target/classes:$task_root/codeoj-backend/codeoj-backend-judge-service/target/classes:$task_h2:$task_tmp/BOOT-INF/lib/*"
mkdir "$task_tmp/classes"
cat > "$task_tmp/classes/logback.xml" <<'XML'
<configuration><appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender"><encoder><pattern>%level %msg%n</pattern></encoder></appender><root level="WARN"><appender-ref ref="CONSOLE"/></root></configuration>
XML
"$task_java_home/bin/javac" -encoding UTF-8 -cp "$task_cp" -d "$task_tmp/classes" "$task_root/scripts/demo/LocalContestDemo.java"
"$task_java_home/bin/java" -cp "$task_tmp/classes:$task_cp" LocalContestDemo "$task_root"
