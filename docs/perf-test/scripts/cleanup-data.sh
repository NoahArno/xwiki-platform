#!/usr/bin/env bash
# XWiki 性能测试数据清理脚本（删除造数/压测产生的用户、菜单空间、保存测试页面）
# 用法: bash cleanup-data.sh [选项]
#   参数同 prepare-data.sh，另有 --save-page（默认 PerfTest/SimplePage）
set -euo pipefail

HOST=localhost; PORT=8080; CONTEXT=xwiki
ADMIN_USER=admin; ADMIN_PASS=admin
USER_COUNT=50; SPACE_COUNT=500; SPACE_PREFIX=MenuSpace
SAVE_PAGE="PerfTest/SimplePage"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --host|-H) HOST=$2; shift 2;;
    --port|-P) PORT=$2; shift 2;;
    --context|-C) CONTEXT=$2; shift 2;;
    --admin-user) ADMIN_USER=$2; shift 2;;
    --admin-pass) ADMIN_PASS=$2; shift 2;;
    --user-count) USER_COUNT=$2; shift 2;;
    --space-count) SPACE_COUNT=$2; shift 2;;
    --space-prefix) SPACE_PREFIX=$2; shift 2;;
    --save-page) SAVE_PAGE=$2; shift 2;;
    *) echo "未知参数: $1"; exit 1;;
  esac
done

BASE="http://${HOST}:${PORT}/${CONTEXT}"
AUTH=(-u "${ADMIN_USER}:${ADMIN_PASS}")

# 删除页面（404 视为已删）；$1=Space/Page（斜杠分隔，含嵌套空间）
del_page() {
  local space="${1%/*}" page="${1##*/}"
  local code
  code=$(curl -s -o /dev/null -w '%{http_code}' -X DELETE "${AUTH[@]}" \
    "${BASE}/rest/wikis/xwiki/spaces/${space}/pages/${page}")
  echo "  删除 $1 -> ${code}"
}

echo "== 删除压测用户 =="
del_page "XWiki/PerfTestUser"
for i in $(seq 1 "$USER_COUNT"); do
  del_page "XWiki/PerfUser${i}"
done

echo "== 删除菜单空间 =="
for i in $(seq 1 "$SPACE_COUNT"); do
  del_page "$(printf '%s%04d' "$SPACE_PREFIX" "$i")/WebHome"
  [[ $((i % 100)) -eq 0 ]] && echo "  已清理 ${i}/${SPACE_COUNT}"
done

echo "== 删除保存测试页面（含 split 模式 _N 后缀页） =="
for i in $(seq 1 100); do
  del_page "${SAVE_PAGE}_${i}" || true
done
del_page "$SAVE_PAGE"

echo "== 清理完成 =="
