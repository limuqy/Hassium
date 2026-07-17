#!/usr/bin/env bash
# 扫描 Java 源码中的 MC_1_*_* 常量，拒绝白名单外的碎片分界。
# 白名单与 docs/version-segments.md 同步。
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
ALLOWED='MC_1_20_1|MC_1_20_2|MC_1_20_5|MC_1_21_1|MC_1_21_2|MC_1_21_5|MC_1_21_6|MC_1_21_9|MC_1_21_11'

illegal=0
while IFS= read -r -d '' file; do
  while IFS= read -r match; do
    token=$(echo "$match" | grep -oE 'MC_1_[0-9]+_[0-9]+' | head -1)
    line=$(echo "$match" | cut -d: -f2)
    rel=${file#"$ROOT/"}
    if ! echo "$token" | grep -qE "^($ALLOWED)$"; then
      echo "  ${rel}:${line}: ${token}"
      illegal=1
    fi
  done < <(grep -nE 'MC_1_[0-9]+_[0-9]+' "$file" || true)
done < <(find "$ROOT/common" "$ROOT/fabric" "$ROOT/forge" "$ROOT/neoforge" -name '*.java' -print0 2>/dev/null)

if [[ "$illegal" -ne 0 ]]; then
  echo "Illegal version boundaries found (not in docs/version-segments.md whitelist)" >&2
  exit 1
fi

echo "scanVersionBoundaries: OK"
exit 0
