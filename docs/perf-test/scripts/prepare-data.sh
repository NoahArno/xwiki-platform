#!/usr/bin/env bash
# XWiki 性能测试造数脚本（REST 通道，幂等可断点续跑）
# 创建：压测用户 PerfTestUser + PerfUser1..N（split/shared 模式用）+ N 个菜单空间（导航树菜单项）
# 用法: bash prepare-data.sh [选项]
#   --host/-H       实例地址（默认 localhost）
#   --port/-P       端口（默认 8080）
#   --context/-C    上下文路径（默认 xwiki）
#   --admin-user    管理员账号（默认 admin）
#   --admin-pass    管理员密码（默认 admin）
#   --user-count    压测用户数（默认 50，PerfUser1..N + PerfTestUser）
#   --user-pass     压测用户统一密码（默认 PerfPass123，需与 perf.properties 的 perf.pass 一致）
#   --space-count   菜单空间数（默认 500）
#   --space-prefix  菜单空间前缀（默认 MenuSpace）
set -euo pipefail

HOST=localhost; PORT=8080; CONTEXT=xwiki
ADMIN_USER=admin; ADMIN_PASS=admin
USER_COUNT=50; USER_PREFIX=PerfUser; USER_PASS=PerfPass123
SPACE_COUNT=500; SPACE_PREFIX=MenuSpace

while [[ $# -gt 0 ]]; do
  case "$1" in
    --host|-H) HOST=$2; shift 2;;
    --port|-P) PORT=$2; shift 2;;
    --context|-C) CONTEXT=$2; shift 2;;
    --admin-user) ADMIN_USER=$2; shift 2;;
    --admin-pass) ADMIN_PASS=$2; shift 2;;
    --user-count) USER_COUNT=$2; shift 2;;
    --user-pass) USER_PASS=$2; shift 2;;
    --space-count) SPACE_COUNT=$2; shift 2;;
    --space-prefix) SPACE_PREFIX=$2; shift 2;;
    *) echo "未知参数: $1"; exit 1;;
  esac
done

BASE="http://${HOST}:${PORT}/${CONTEXT}"
AUTH=(-u "${ADMIN_USER}:${ADMIN_PASS}")

rest() { # $1=method $2=path $3=json(可选)
  local args=(-s -o /dev/null -w '%{http_code}' -X "$1" "${AUTH[@]}")
  if [[ -n "${3:-}" ]]; then
    curl "${args[@]}" -H "Content-Type: application/json" --data "$3" "${BASE}$2"
  else
    curl "${args[@]}" "${BASE}$2"
  fi
}

json_page() { # $1=标题 $2=内容 → JSON
  python3 -c "
import json, sys
print(json.dumps({'title': sys.argv[1], 'content': sys.argv[2], 'syntax': 'xwiki/2.1'}, ensure_ascii=False))
" "$1" "$2"
}

json_user() { # $1=邮箱 $2=密码 $3=名 → JSON
  python3 -c "
import json, sys
obj = {'className': 'XWiki.XWikiUsers', 'properties': [
    {'name': 'email', 'value': sys.argv[1]},
    {'name': 'password', 'value': sys.argv[2]},
    {'name': 'password_confirmation', 'value': sys.argv[2]},
    {'name': 'first_name', 'value': sys.argv[3]},
    {'name': 'last_name', 'value': '压测'},
]}
print(json.dumps(obj, ensure_ascii=False))
" "$1" "$2" "$3"
}

# 创建或跳过页面；$1=Space/Page（斜杠分隔，含嵌套空间）$2=标题 $3=内容
create_page() {
  local space="${1%/*}" page="${1##*/}"
  local code
  code=$(rest "GET" "/rest/wikis/xwiki/spaces/${space}/pages/${page}")
  if [[ "$code" == "200" ]]; then
    return 0  # 已存在，跳过
  fi
  local json
  json=$(json_page "$2" "$3")
  code=$(rest "PUT" "/rest/wikis/xwiki/spaces/${space}/pages/${page}" "$json")
  if [[ "$code" != "201" && "$code" != "200" ]]; then
    echo "创建页面 $1 失败: HTTP $code" >&2
    return 1
  fi
}

# 创建用户（页面 + XWikiUsers 对象）；$1=用户名
create_user() {
  local code
  create_page "XWiki/$1" "$1" "压测用户 $1"
  # 对象已存在则跳过
  code=$(rest "GET" "/rest/wikis/xwiki/spaces/XWiki/pages/$1/objects/XWikiUsers")
  if [[ "$code" == "200" ]]; then
    return 0
  fi
  local json
  json=$(json_user "$1@local.test" "$USER_PASS" "$1")
  code=$(rest "POST" "/rest/wikis/xwiki/spaces/XWiki/pages/$1/objects" "$json")
  if [[ "$code" != "201" && "$code" != "200" ]]; then
    echo "创建用户 $1 失败: HTTP $code" >&2
    return 1
  fi
}

echo "== 创建压测用户（${USER_COUNT} 个 + PerfTestUser） =="
create_user "PerfTestUser"
for i in $(seq 1 "$USER_COUNT"); do
  create_user "${USER_PREFIX}${i}"
  [[ $((i % 10)) -eq 0 ]] && echo "  已创建用户 ${i}/${USER_COUNT}"
done
echo "  PerfTestUser + ${USER_PREFIX}1..${USER_COUNT} 就绪（密码: ${USER_PASS}）"

echo "== 创建菜单空间（${SPACE_COUNT} 个，前缀 ${SPACE_PREFIX}） =="
for i in $(seq 1 "$SPACE_COUNT"); do
  local_name=$(printf "%s%04d" "$SPACE_PREFIX" "$i")
  create_page "${local_name}/WebHome" "$local_name" "菜单空间 $local_name 的首页"
  [[ $((i % 50)) -eq 0 ]] && echo "  已创建 ${i}/${SPACE_COUNT}"
done
echo "  ${SPACE_PREFIX}0001..$(printf '%04d' "$SPACE_COUNT") 就绪"

echo
echo "== 完成 =="
echo "确认 perf.properties 与以下配置一致："
echo "  perf.user=${USER_PREFIX}1   (solo 模式用 perf.user=PerfTestUser)"
echo "  perf.pass=${USER_PASS}"
