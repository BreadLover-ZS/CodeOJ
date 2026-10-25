#!/usr/bin/env bash
set -euo pipefail
script_dir="$(cd "$(dirname "$0")" && pwd)"
task_root="$(cd "$script_dir/../../../.." && pwd)"
: "${REDIS_SERVER:?Provide an absolute path to Redis 6.2.24. A private test instance is created and removed.}"
[[ "$REDIS_SERVER" = /* && -x "$REDIS_SERVER" ]] || exit 1
task_java_home="${JAVA_HOME:-}"
if [[ -x /usr/libexec/java_home ]]; then task_java_home="$(/usr/libexec/java_home -v 1.8)"; fi
[[ -n "$task_java_home" ]] || exit 1
task_source="$task_root/codeoj-backend/codeoj-backend-question-service/src/test/java/com/codeoj/codeojbackendquestionservice/contest/RegistrationChainAuditProbeTest.java"
task_classes="$task_root/codeoj-backend/codeoj-backend-question-service/target/test-classes/com/codeoj/codeojbackendquestionservice/contest"
[[ ! -e "$task_source" && ! -e "$task_classes/RegistrationChainAuditProbeTest.class" ]] || { echo 'Probe source/class already exists; refusing to overwrite.' >&2; exit 1; }
cleanup() {
  rm -f "$task_source" "$task_classes/RegistrationChainAuditProbeTest.class" "$task_classes/RegistrationChainAuditProbeTest\$1.class"
}
trap cleanup EXIT
cp "$script_dir/RegistrationChainAuditProbeTest.java" "$task_source"
echo 'The first two probes assert observed defects. Passing probes do not mean these defects are repaired.'
env JAVA_HOME="$task_java_home" PATH="$task_java_home/bin:$PATH" mvn -o -B -f "$task_root/codeoj-backend/pom.xml" -pl codeoj-backend-question-service -am \
  -Dtest='RegistrationChainAuditProbeTest#olderReconcileFailurePausesANewerSuccessfulReconciliation+pendingReservationIsStillAdvertisedAsRemainingCapacity+cancellationAfterPartialReservationRecoversIdentityAndRefundsOnlyOnce' \
  -Dsurefire.failIfNoSpecifiedTests=false -Dcodeoj.real-redis=true -Dcodeoj.redis-server="$REDIS_SERVER" test
