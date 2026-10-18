#!/usr/bin/env bash
# Native functional integration, macOS arm64. All fixture runtimes/data are temporary.
set -euo pipefail
task_root="$(cd "$(dirname "$0")/.." && pwd)"
task_work="$(mktemp -d "${TMPDIR:-/tmp}/codeoj-native-runtimes.XXXXXX")"
trap 'rm -rf "$task_work"' EXIT
if [[ -x /usr/libexec/java_home ]]; then
  task_java_home="$(/usr/libexec/java_home -v 1.8)"
else
  task_java_home="${JAVA_HOME:?Set JAVA_HOME to a Java 8 JDK}"
fi
if ! "$task_java_home/bin/java" -version 2>&1 | head -n 1 | rg -q '"1\.8\.'; then
  echo 'This fixture requires Java 8.' >&2; exit 1
fi
task_evidence="${CODEOJ_NATIVE_EVIDENCE:-$task_root/outputs/validation/native-$(date +%Y%m%d-%H%M%S)}"
mkdir -p "$task_evidence"
python3 "$task_root/scripts/local/prepare-runtimes.py" --root "$task_work/runtimes" > "$task_evidence/runtime-preparation.log" 2>&1
env JAVA_HOME="$task_java_home" PATH="$task_java_home/bin:$PATH" mvn -B -f "$task_root/codeoj-backend/pom.xml" -DskipTests package > "$task_evidence/backend-package.log" 2>&1
env VUE_APP_API_BASE_URL= npm --prefix "$task_root/codeoj-frontend" run build > "$task_evidence/frontend-build.log" 2>&1
CODEOJ_JAVA8_HOME="$task_java_home" python3 "$task_root/scripts/local/run-native-stack.py" --runtimes "$task_work/runtimes" --evidence "$task_evidence"
