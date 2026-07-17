#!/usr/bin/env bash
# 按 docs/version-segments.md 对 9 个锚点 × builds_for 执行 compileJava。
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
  if ! "$GRADLEW" --no-daemon "${tasks[@]}" "-Pmc_ver=$ver"; then
    failed+=("$ver")
    echo "FAILED: $ver"
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
