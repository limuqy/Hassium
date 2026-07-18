#!/usr/bin/env bash
# 按 docs/version-segments.md 对 9 个锚点 × builds_for 执行 compileJava。
# 每个锚点结束后 --stop，避免 loom 全局锁 / Daemon 残留导致下一版本无限等待。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

ANCHORS=(
  1.20.1
  1.20.2
  1.20.5
  1.21.1
  1.21.2
  1.21.5
  1.21.6
  1.21.9
  1.21.11
)

GRADLEW=./gradlew

stop_daemons() {
  "$GRADLEW" --stop >/dev/null 2>&1 || true
}

assert_no_foreign_loom_lock() {
  local loom="${HOME}/.gradle/caches/fabric-loom"
  [[ -d "$loom" ]] || return 0
  while IFS= read -r lock; do
    [[ -f "$lock" ]] || continue
    local pid
    pid=$(grep -oE 'pid["=: ]+[0-9]+' "$lock" 2>/dev/null | grep -oE '[0-9]+' | head -1 || true)
    [[ -n "${pid:-}" ]] || continue
    if kill -0 "$pid" 2>/dev/null; then
      cat <<EOF >&2

错误: fabric-loom 缓存锁仍被存活进程占用 (pid=$pid)。
命令行 Gradle 会无限等待该锁。请先停止 IDE Gradle Sync 或结束该进程后重试。

EOF
      exit 2
    fi
  done < <(find "$loom" -name '*.lock' 2>/dev/null || true)
}

echo "Stopping existing Gradle daemons..."
stop_daemons
assert_no_foreign_loom_lock

failed=()

for ver in "${ANCHORS[@]}"; do
  props="versionProperties/${ver}.properties"
  if [[ ! -f "$props" ]]; then
    echo "SKIP $ver (no versionProperties)"
    continue
  fi
  builds_for=$(grep -E '^builds_for=' "$props" | cut -d= -f2 | tr -d '\r')
  IFS=',' read -ra loaders <<< "$builds_for"
  tasks=()
  for loader in "${loaders[@]}"; do
    loader=$(echo "$loader" | xargs)
    [[ -n "$loader" ]] || continue
    tasks+=(":${loader}:compileJava")
  done

  echo ""
  echo "=== Anchor $ver (${builds_for}) ==="
  assert_no_foreign_loom_lock
  set +e
  "$GRADLEW" "${tasks[@]}" "-Pmc_ver=$ver" --console=plain
  code=$?
  set -e
  stop_daemons

  if [[ $code -ne 0 ]]; then
    failed+=("$ver")
    echo "FAILED: $ver (exit $code)"
  else
    echo "OK: $ver"
  fi
done

if [[ ${#failed[@]} -gt 0 ]]; then
  echo "compileAnchors failed: ${failed[*]}" >&2
  exit 1
fi

echo "compileAnchors: all anchors OK"
exit 0
