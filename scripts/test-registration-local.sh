#!/usr/bin/env bash
set -euo pipefail
task_root="$(cd "$(dirname "$0")/.." && pwd)"
task_java_home="${JAVA_HOME:-}"
if [[ -x /usr/libexec/java_home ]]; then task_java_home="$(/usr/libexec/java_home -v 1.8)"; fi
if [[ -z "$task_java_home" ]] || ! "$task_java_home/bin/java" -version 2>&1 | head -n 1 | rg -q '"1\.8\.'; then
  echo 'Set JAVA_HOME to a Java 8 JDK.' >&2; exit 1
fi
task_redis_bin="${REDIS_SERVER:-$(command -v redis-server || true)}"
task_build_dir=""
cleanup() { if [[ -n "$task_build_dir" ]]; then rm -rf "$task_build_dir"; fi; }
trap cleanup EXIT
if [[ -z "$task_redis_bin" ]]; then
  if [[ "${BUILD_TEST_REDIS:-false}" != true ]]; then
    echo 'Set REDIS_SERVER to an existing native redis-server, or BUILD_TEST_REDIS=true to build Redis 6.2.24 in a temporary directory.' >&2; exit 1
  fi
  task_build_dir="$(mktemp -d "${TMPDIR:-/tmp}/codeoj-test-redis.XXXXXX")"
  curl --fail --location --max-time 90 https://download.redis.io/releases/redis-6.2.24.tar.gz -o "$task_build_dir/redis.tar.gz"
  task_digest="$(shasum -a 256 "$task_build_dir/redis.tar.gz" | cut -d ' ' -f 1)"
  if [[ "$task_digest" != c3f112749c7cb108597713469cc9beed305e91f036fba313ce8dcbad829f5821 ]]; then
    echo 'Redis source archive hash mismatch.' >&2; exit 1
  fi
  tar -xzf "$task_build_dir/redis.tar.gz" -C "$task_build_dir"
  make -C "$task_build_dir/redis-6.2.24" -j4 BUILD_TLS=no
  task_redis_bin="$task_build_dir/redis-6.2.24/src/redis-server"
fi
if [[ ! -x "$task_redis_bin" ]]; then echo 'REDIS_SERVER must name an executable.' >&2; exit 1; fi
task_redis_bin="$(cd "$(dirname "$task_redis_bin")" && pwd)/$(basename "$task_redis_bin")"
"$task_redis_bin" --version
env JAVA_HOME="$task_java_home" PATH="$task_java_home/bin:$PATH" mvn -B -f "$task_root/codeoj-backend/pom.xml" \
  -Dcodeoj.real-redis=true -Dcodeoj.redis-server="$task_redis_bin" -Dcodeoj.native-observation=true package
