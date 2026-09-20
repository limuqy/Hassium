#!/usr/bin/env bash
# 一个脚本构建所有版本，并同时推送到 CurseForge + Modrinth（每个版本只构建一次，两个平台各自上传）。
#
# 幂等：先跑 config-only 的 `gradlew publishState` 查两个平台是否已有对应产物；
#       某平台该版本的全部 loader 都已存在 → 本次不构建、不上传。
#       上传阶段 Gradle 侧还有第二道守卫（buildSrc/publish-curseforge.gradle /
#       publish-modrinth.gradle 的 doFirst），处理"部分已存在"的情况。
#
# 用法：
#   export CURSEFORGE_TOKEN=... MODRINTH_TOKEN=...
#   ./scripts/publish-mods.sh
#   ./scripts/publish-mods.sh --dry-run
#   ./scripts/publish-mods.sh --versions 1.20.1,1.21.1
#   ./scripts/publish-mods.sh --anchors-only
#   ./scripts/publish-mods.sh --targets curseforge     # 只推一个平台
#   ./scripts/publish-mods.sh --force                  # 忽略"已存在"检查
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

DRY_RUN=0
ANCHORS_ONLY=0
FORCE=0
VERSIONS=""
RELEASE_TYPE=""
CHANGELOG=""
TARGETS="curseforge,modrinth"
RETRIES=3

while [[ $# -gt 0 ]]; do
  case "$1" in
    --dry-run) DRY_RUN=1; shift ;;
    --anchors-only) ANCHORS_ONLY=1; shift ;;
    --force) FORCE=1; shift ;;
    --versions) VERSIONS="$2"; shift 2 ;;
    --release-type) RELEASE_TYPE="$2"; shift 2 ;;
    --changelog) CHANGELOG="$2"; shift 2 ;;
    --targets) TARGETS="$2"; shift 2 ;;
    --retries) RETRIES="$2"; shift 2 ;;
    *) echo "Unknown arg: $1" >&2; exit 1 ;;
  esac
done

IFS=',' read -r -a target_list <<< "$TARGETS"
for i in "${!target_list[@]}"; do target_list[$i]="$(echo "${target_list[$i]}" | xargs)"; done

has_target() {
  local t
  for t in "${target_list[@]}"; do [[ "$t" == "$1" ]] && return 0; done
  return 1
}

LOCAL_PROPS="${HOME}/.gradle/gradle.properties"
has_local_token() {
  [[ -f "$LOCAL_PROPS" ]] && grep -qE "^\s*$1\s*=" "$LOCAL_PROPS" 2>/dev/null
}

# ---- 凭据 / 项目 ID 校验（只校验启用的平台）----
if has_target curseforge; then
  if [[ -z "${CURSEFORGE_TOKEN:-}" ]] && ! has_local_token curseforge_token; then
    echo "缺少 CURSEFORGE_TOKEN（或 ~/.gradle/gradle.properties 中的 curseforge_token）。" >&2
    echo "获取：https://www.curseforge.com/account/api-tokens" >&2
    exit 1
  fi
  if ! grep -qE '^\s*curseforge_project_id\s*=\s*\S+' gradle.properties; then
    echo "gradle.properties 中 curseforge_project_id 为空。" >&2
    exit 1
  fi
fi
if has_target modrinth; then
  if [[ -z "${MODRINTH_TOKEN:-}" ]] && ! has_local_token modrinth_token; then
    echo "缺少 MODRINTH_TOKEN（或 ~/.gradle/gradle.properties 中的 modrinth_token）。" >&2
    echo "获取：https://modrinth.com/settings/personal-access-tokens（scope 需含创建版本）" >&2
    exit 1
  fi
  if ! grep -qE '^\s*modrinth_project_id\s*=\s*\S+' gradle.properties; then
    echo "gradle.properties 中 modrinth_project_id 为空。" >&2
    exit 1
  fi
fi

extra=()
if [[ "$DRY_RUN" -eq 1 ]]; then
  if has_target curseforge; then extra+=(-Pcurseforge_debug=true); fi
  if has_target modrinth; then extra+=(-Pmodrinth_debug=true); fi
fi
if [[ -n "$RELEASE_TYPE" ]]; then
  if has_target curseforge; then extra+=("-Pcurseforge_release_type=${RELEASE_TYPE}"); fi
  if has_target modrinth; then extra+=("-Pmodrinth_release_type=${RELEASE_TYPE}"); fi
fi
if [[ -n "$CHANGELOG" ]]; then
  if has_target curseforge; then extra+=("-Pcurseforge_changelog=${CHANGELOG}"); fi
  if has_target modrinth; then extra+=("-Pmodrinth_changelog=${CHANGELOG}"); fi
fi

mapfile -t all_versions < <(find versionProperties -name '*.properties' -printf '%f\n' | sed 's/\.properties$//' | sort -V)

if [[ -n "$VERSIONS" ]]; then
  IFS=',' read -r -a version_list <<< "$VERSIONS"
elif [[ "$ANCHORS_ONLY" -eq 1 ]]; then
  version_list=(1.20.1 1.21.1 1.21.2 1.21.5 1.21.6 1.21.9 1.21.11)
else
  version_list=("${all_versions[@]}")
fi

failed=()
skipped=()

for ver in "${version_list[@]}"; do
  ver="$(echo "$ver" | xargs)"
  [[ -z "$ver" ]] && continue
  props="versionProperties/${ver}.properties"
  if [[ ! -f "$props" ]]; then
    echo "SKIP $ver (no versionProperties)"
    continue
  fi
  builds_for="$(grep -E '^builds_for=' "$props" | cut -d= -f2- || echo fabric)"

  # ---- 幂等预检：config-only（不触发构建），只发 GET ----
  state_out=""
  if ! state_out="$(./gradlew publishState "-Pmc_ver=${ver}" -q 2>&1)"; then
    echo ""
    echo "=== $ver ($builds_for) ==="
    echo "$state_out" >&2
    echo "FAILED: $ver (publishState 查询失败)"
    failed+=("$ver")
    continue
  fi
  mapfile -t state_lines < <(printf '%s\n' "$state_out" | grep -E '^PUBLISH_STATE ' || true)
  if [[ ${#state_lines[@]} -eq 0 ]]; then
    echo "FAILED: $ver (publishState 无输出)"
    failed+=("$ver")
    continue
  fi

  need_cf=0
  need_mr=0
  inconclusive=()
  for line in "${state_lines[@]}"; do
    cf="$(sed -n 's/.* cf=\([^ ]*\).*/\1/p' <<< "$line")"
    mr="$(sed -n 's/.* mr=\([^ ]*\).*/\1/p' <<< "$line")"
    if [[ "$cf" != "yes" ]]; then need_cf=1; fi
    if [[ "$mr" != "yes" ]]; then need_mr=1; fi
    # unknown(...) / no-token：查不出结论，只能照常上传（Gradle 侧同样只在 yes 时跳过）
    if [[ "$cf" != "yes" && "$cf" != "no" ]]; then inconclusive+=("cf=$cf"); fi
    if [[ "$mr" != "yes" && "$mr" != "no" ]]; then inconclusive+=("mr=$mr"); fi
  done

  tasks=(build)
  pending=()
  want_cf=0
  want_mr=0
  if has_target curseforge; then
    if [[ "$need_cf" -eq 1 || "$FORCE" -eq 1 ]]; then want_cf=1; fi
  fi
  if has_target modrinth; then
    if [[ "$need_mr" -eq 1 || "$FORCE" -eq 1 ]]; then want_mr=1; fi
  fi
  if [[ "$want_cf" -eq 1 ]]; then tasks+=(publishCurseForge); pending+=(curseforge); fi
  if [[ "$want_mr" -eq 1 ]]; then tasks+=(publishModrinth); pending+=(modrinth); fi

  if [[ ${#pending[@]} -eq 0 ]]; then
    echo "SKIP $ver（$TARGETS 均已发布）"
    skipped+=("$ver")
    continue
  fi

  echo ""
  echo "=== Publish $ver ($builds_for) → ${pending[*]} ==="
  for line in "${state_lines[@]}"; do echo "  $line"; done
  if [[ ${#inconclusive[@]} -gt 0 ]]; then
    echo "  警告：幂等检查未得出结论（${inconclusive[*]}），这些平台会照常上传"
  fi

  ok=0
  for ((attempt = 1; attempt <= RETRIES; attempt++)); do
    # 走 daemon（AGENTS.md：编译/打包用 daemon，不要 --no-daemon）。
    # 一条命令里同时跑 build + 两个平台的 publish task：构建只发生一次。
    # -x test：发布验证由运行时冒烟覆盖；common 的 plain JUnit 依赖 MC runtime/原版 API。
    if ./gradlew "${tasks[@]}" "-Pmc_ver=${ver}" -x test "${extra[@]+"${extra[@]}"}"; then
      ok=1
      break
    fi
    if [[ "$attempt" -lt "$RETRIES" ]]; then
      # 重试退避：平台侧偶发 5xx / CurseForge 的 Cloudflare 质询窗口。
      backoff=$((attempt * 20))
      echo "attempt $attempt failed, backing off ${backoff}s ..."
      sleep "$backoff"
    fi
  done

  if [[ "$ok" -eq 1 ]]; then
    echo "OK: $ver"
  else
    failed+=("$ver")
    echo "FAILED: $ver"
  fi
done

echo ""
if [[ ${#skipped[@]} -gt 0 ]]; then
  echo "skipped (already published): ${skipped[*]}"
fi
if [[ ${#failed[@]} -gt 0 ]]; then
  echo "publish-mods failed: ${failed[*]}" >&2
  exit 1
fi

echo "publish-mods: all versions OK"
