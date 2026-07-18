#!/usr/bin/env bash
# 对 versionProperties 中每个 MC 版本执行 build + publishCurseForge。
# 用法：
#   export CURSEFORGE_TOKEN=你的token
#   ./scripts/publish-curseforge.sh
#   ./scripts/publish-curseforge.sh --dry-run
#   ./scripts/publish-curseforge.sh --anchors-only
#   ./scripts/publish-curseforge.sh --versions 1.20.1,1.20.6
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

DRY_RUN=0
ANCHORS_ONLY=0
VERSIONS=""
RELEASE_TYPE=""
CHANGELOG=""

while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run) DRY_RUN=1; shift ;;
    --anchors-only) ANCHORS_ONLY=1; shift ;;
    --versions) VERSIONS="$2"; shift 2 ;;
    --release-type) RELEASE_TYPE="$2"; shift 2 ;;
    --changelog) CHANGELOG="$2"; shift 2 ;;
    *) echo "Unknown arg: $1" >&2; exit 1 ;;
  esac
done

if [[ -z "${CURSEFORGE_TOKEN:-}" ]] && ! grep -qE '^\s*curseforge_token\s*=' "${HOME}/.gradle/gradle.properties" 2>/dev/null; then
  echo "缺少 CURSEFORGE_TOKEN（或 ~/.gradle/gradle.properties 中的 curseforge_token）。" >&2
  echo "获取：https://www.curseforge.com/account/api-tokens" >&2
  exit 1
fi

if ! grep -qE '^\s*curseforge_project_id\s*=\s*\S+' gradle.properties; then
  echo "gradle.properties 中 curseforge_project_id 为空。" >&2
  exit 1
fi

extra=()
if [[ "$DRY_RUN" -eq 1 ]]; then
  extra+=(-Pcurseforge_debug=true)
fi
if [[ -n "$RELEASE_TYPE" ]]; then
  extra+=("-Pcurseforge_release_type=${RELEASE_TYPE}")
fi
if [[ -n "$CHANGELOG" ]]; then
  extra+=("-Pcurseforge_changelog=${CHANGELOG}")
fi

mapfile -t all_versions < <(find versionProperties -name '*.properties' -printf '%f\n' | sed 's/\.properties$//' | sort -V)

if [[ -n "$VERSIONS" ]]; then
  IFS=',' read -r -a version_list <<< "$VERSIONS"
elif [[ "$ANCHORS_ONLY" -eq 1 ]]; then
  version_list=(1.20.1 1.20.2 1.20.5 1.20.6 1.21.1 1.21.2 1.21.5 1.21.6 1.21.9 1.21.11)
else
  version_list=("${all_versions[@]}")
fi

failed=()
for ver in "${version_list[@]}"; do
  ver="$(echo "$ver" | xargs)"
  [[ -z "$ver" ]] && continue
  props="versionProperties/${ver}.properties"
  if [[ ! -f "$props" ]]; then
    echo "SKIP $ver (no versionProperties)"
    continue
  fi
  builds_for="$(grep -E '^builds_for=' "$props" | cut -d= -f2- || echo fabric)"
  echo ""
  echo "=== Publish $ver ($builds_for) ==="
  if ! ./gradlew --no-daemon build publishCurseForge "-Pmc_ver=${ver}" "${extra[@]+"${extra[@]}"}"; then
    failed+=("$ver")
    echo "FAILED: $ver"
  else
    echo "OK: $ver"
  fi
done

if [[ ${#failed[@]} -gt 0 ]]; then
  echo "publish-curseforge failed: ${failed[*]}" >&2
  exit 1
fi

echo "publish-curseforge: all versions OK"
