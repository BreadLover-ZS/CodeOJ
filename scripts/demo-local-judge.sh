#!/usr/bin/env bash
set -euo pipefail

task_root="$(cd "$(dirname "$0")/.." && pwd)"
if [[ -x /usr/libexec/java_home ]]; then
  task_java_home="$(/usr/libexec/java_home -v 1.8)"
else
  task_java_home="${JAVA_HOME:?Set JAVA_HOME to a Java 8 JDK}"
fi
task_java="$task_java_home/bin/java"
task_javac="$task_java_home/bin/javac"
if ! "$task_java" -version 2>&1 | head -n 1 | rg -q '"1\.8\.'; then
  echo 'This demo requires a Java 8 JDK.' >&2
  exit 1
fi

env JAVA_HOME="$task_java_home" PATH="$task_java_home/bin:$PATH" \
  mvn -B -q -f "$task_root/codeoj-backend/pom.xml" -DskipTests package

task_tmp="$(mktemp -d "${TMPDIR:-/tmp}/codeoj-local-demo.XXXXXX")"
trap 'rm -rf "$task_tmp"' EXIT
task_sandbox="$task_root/codeoj-backend/codeoj-backend-codesandbox"
unzip -q "$task_sandbox/target/codeoj-backend-codesandbox-0.0.1-SNAPSHOT.jar" 'BOOT-INF/lib/*' -d "$task_tmp"
task_cp="$task_sandbox/target/classes:$task_root/codeoj-backend/codeoj-backend-judge-service/target/classes:$task_tmp/BOOT-INF/lib/*"
mkdir "$task_tmp/classes"
"$task_javac" -encoding UTF-8 -cp "$task_cp" -d "$task_tmp/classes" "$task_root/scripts/demo/LocalJudgeDemo.java"
"$task_java" -cp "$task_tmp/classes:$task_cp" LocalJudgeDemo
