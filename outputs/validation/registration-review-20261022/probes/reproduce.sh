#!/usr/bin/env bash
set -euo pipefail
script_dir="$(cd "$(dirname "$0")" && pwd)"
task_root="$(cd "$script_dir/../../../.." && pwd)"
: "${REDIS_SERVER:?Set REDIS_SERVER to an absolute path to a Redis 6.2.24 executable. Tests start and remove their own private instances.}"
[[ "$REDIS_SERVER" = /* && -x "$REDIS_SERVER" ]] || { echo 'REDIS_SERVER must be an absolute executable path.' >&2; exit 1; }
task_java_home="${JAVA_HOME:-}"
if [[ -x /usr/libexec/java_home ]]; then task_java_home="$(/usr/libexec/java_home -v 1.8)"; fi
[[ -n "$task_java_home" ]] || { echo 'Set JAVA_HOME to a Java 8 JDK.' >&2; exit 1; }
"$task_java_home/bin/java" -version 2>&1 | head -n1 | rg -q '"1\.8\.'
task_source="$task_root/codeoj-backend/codeoj-backend-question-service/src/test/java/com/codeoj/codeojbackendquestionservice/contest/RegistrationAuditProbeTest.java"
task_class="$task_root/codeoj-backend/codeoj-backend-question-service/target/test-classes/com/codeoj/codeojbackendquestionservice/contest/RegistrationAuditProbeTest.class"
[[ ! -e "$task_source" && ! -e "$task_class" ]] || { echo 'Refusing to overwrite an existing probe source/class.' >&2; exit 1; }
cleanup() { rm -f "$task_source" "$task_class"; }
trap cleanup EXIT
cp "$script_dir/RegistrationAuditProbeTest.java" "$task_source"
echo 'Probe assertions confirm observed defects; passing probes do not mean the defects are fixed.'
env JAVA_HOME="$task_java_home" PATH="$task_java_home/bin:$PATH" mvn -B -o -f "$task_root/codeoj-backend/pom.xml" -pl codeoj-backend-question-service -am \
  -Dcodeoj.real-redis=true -Dcodeoj.redis-server="$REDIS_SERVER" \
  -Dtest='RegistrationRedisIntegrationTest,RegistrationMessagingTest,RegistrationAuditProbeTest#rollbackWithOrphanMasksMissingConfirmedIdentity+admittedLedgerCanExceedReconciliationBound+slowRequestIsClassifiedAsClockSkewWithoutClockDrift' \
  -Dsurefire.failIfNoSpecifiedTests=false test
