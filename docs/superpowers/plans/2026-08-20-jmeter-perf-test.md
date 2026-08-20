# XWiki 性能测试 JMeter 脚本与造数脚本 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 交付 6 个场景的 8 个 JMeter 脚本（含 3 种保存模式）、造数/清理脚本、公共参数与使用文档，供内网压测。

**Architecture:** 所有 JMeter 请求通过每请求 `Authorization: Basic` 头认证（本 fork 已验证支持且不踢会话）；保存场景走 "GET edit 提取 form_token/版本字段 → POST save" 真实流程；造数走 REST 通道（无 CSRF）；菜单 = 500 个顶层空间（导航树自动渲染）。

**Tech Stack:** Apache JMeter 5.x（.jmx XML）、shell + curl（macOS/Linux）、XWiki 18 REST API、本机实例 `http://localhost:8080/xwiki`（admin/admin）验证。

## Global Constraints

- 认证一律用每请求 Basic Auth 头，**绝不做表单登录**（被 OA 重定向劫持）。
- 所有参数经 `perf.properties`（`-q` 加载）或 `__P(name,default)` 提供，**不硬编码** host/账号/页面/并发数。
- 简单/复杂页 URL 是占位变量（`perf.simplePageUrl`/`perf.complexPageUrl`），内网测试时由用户填入。
- 保存测试内容为固定模板文件（`docs/perf-test/data/save-content-*.txt`），不用页面内容。
- shared 模式必须带 `ajax=1` 请求参数（`XWikiAction.java:513`：ajax 标记来自请求参数，非 X-Requested-With 头）才能触发冲突检测。
- 冲突响应是 **409 + JSON**、成功响应是 **200 + JSON**（实测确认）；solo/split 非 AJAX 保存成功为 **302**（`follow_redirects=false` 断言）。
- 目标实例：`http://localhost:8080/xwiki`（本机验证用 admin/admin）。
- 交付路径固定为 `docs/perf-test/`；spec 见 `docs/superpowers/specs/2026-08-20-jmeter-perf-test-design.md`。

---

### Task 1: 目录骨架 + perf.properties + README.md

**Files:**
- Create: `docs/perf-test/perf.properties`
- Create: `docs/perf-test/README.md`
- Create: `docs/perf-test/data/save-content-simple.txt`
- Create: `docs/perf-test/data/save-content-complex.txt`

**Interfaces:**
- Produces: 全部参数名（`perf.host`、`perf.port`、`perf.context`、`perf.user`、`perf.pass`、`perf.userPrefix`、`perf.simplePageUrl`、`perf.complexPageUrl`、`perf.savePage`、`perf.saveContentFile`、`perf.threads`、`perf.rampUp`、`perf.duration`、`perf.threads.solo/split/shared`、`perf.resultsFile`、`perf.authHeader`），后序任务与 README 一致引用。

- [ ] **Step 1: 建目录与四个文件**

```bash
mkdir -p /Users/noaharno/programer/sourcecode/xwiki_parent/xwiki-platform/docs/perf-test/{jmeter,scripts,data,results}
```

- [ ] **Step 2: 写 `perf.properties`**

```properties
# XWiki 性能测试公共参数（JMeter 用 -q 加载）
# 修改本文件即可调整所有脚本的测试目标与并发配置

# --- 目标实例 ---
perf.host=localhost
perf.port=8080
perf.protocol=http
perf.context=xwiki

# --- 认证（每请求 Basic Auth） ---
# 压测账号：prepare-data.sh 创建。solo 模式用 perf.user；split/shared 用 PerfUser1..N（同密码）
perf.user=PerfTestUser
perf.pass=PerfPass123
perf.userPrefix=PerfUser

# --- 测试页面（内网测试时替换为真实页面路径，如 "Space/PageName"，不含 /bin/view/） ---
perf.simplePageUrl=Sandbox/TestPage1
perf.complexPageUrl=Sandbox/TestPage2

# --- 保存测试 ---
# 保存目标页面（solo/shared 用此页；split 模式加 _线程号 后缀）
perf.savePage=PerfTest/SimplePage
# 保存内容模板文件（复杂版含大量宏调用）
perf.saveContentFile=/Users/noaharno/programer/sourcecode/xwiki_parent/xwiki-platform/docs/perf-test/data/save-content-simple.txt
# 简单内容场景请改为 save-content-complex.txt

# --- 并发配置（01/02/03/06） ---
perf.threads=50
perf.rampUp=60
perf.duration=300

# --- 并发配置（04/05 保存场景，每模式独立） ---
perf.threads.solo=2
perf.threads.split=50
perf.threads.shared=50
perf.rampUp.save=60
perf.duration.save=300

# --- 结果输出 ---
perf.resultsFile=results/result.csv
```

- [ ] **Step 3: 写 `data/save-content-simple.txt`**

```text
这是一段简单的页面内容，用于保存性能测试。

= 简单标题 =

普通段落文本，没有使用宏。

1. 列表项一
2. 列表项二
3. 列表项三
```

- [ ] **Step 4: 写 `data/save-content-complex.txt`**

```text
= 复杂页面内容（宏密集） =

{{info}}
这是信息提示框宏。
{{/info}}

{{warning}}
这是警告框宏。
{{/warning}}

{{success}}
这是成功框宏。
{{/success}}

{{error}}
这是错误框宏。
{{/error}}

== 表格宏 ==

|=字段1|=字段2|=字段3|=字段4|=字段5|
|值1|值2|值3|值4|值5|
|值1|值2|值3|值4|值5|
|值1|值2|值3|值4|值5|
|值1|值2|值3|值4|值5|

== 代码块宏 ==

{{code language="java"}}
public class Demo {
    public static void main(String[] args) {
        System.out.println("Hello XWiki Performance Test");
    }
}
{{/code}}

{{code language="xml"}}
<configuration>
    <property name="key1">value1</property>
    <property name="key2">value2</property>
</configuration>
{{/code}}

== 引用宏 ==

{{quote}}
这是一段引用文本，用于测试引用宏的渲染开销。
{{/quote}}

== 锚点与内链 ==

[[跳转到章节三|#章节三]]

[[外部链接|https://www.xwiki.org/]]

[[首页|Main.WebHome]]

== 章节二 ==

这是一段普通的段落文本，用于填充页面内容。这段文本足够长，用来模拟真实页面的文字量，
让页面渲染和保存时有足够的内容处理量。

== 章节三 ==

{{note}}
注意宏。
{{/note}}

{{box}}
普通盒子宏。
{{/box}}

{{message}}
消息宏。
{{/message}}

== 列表嵌套 ==

* 一级项目 A
** 二级项目 A1
*** 三级项目 A1a
* 一级项目 B
** 二级项目 B1
* 一级项目 C

== 术语宏 ==

{{term}}
术语一：这是术语一的内容解释。
{{/term}}

{{term}}
术语二：这是术语二的内容解释。
{{/term}}
```

- [ ] **Step 5: 写 `README.md`**（内容如下，先写骨架，Task 9 补运行验证结果）

```markdown
# XWiki 性能测试

6 个场景的 JMeter 脚本 + 造数脚本。设计文档：`docs/superpowers/specs/2026-08-20-jmeter-perf-test-design.md`

## 场景与脚本

| 场景 | 脚本 | 说明 |
|---|---|---|
| 1. 首页并发 | `jmeter/01-homepage.jmx` | 默认匿名访问首页 |
| 2. 简单页并发 | `jmeter/02-page-simple.jmx` | 内容/宏少的页面（`perf.simplePageUrl`） |
| 3. 复杂页并发 | `jmeter/03-page-complex.jmx` | 内容/宏多的页面（`perf.complexPageUrl`） |
| 4. 简单内容编辑保存 | `jmeter/04-edit-save-simple.jmx` | 含 solo/split/shared 三种模式 |
| 5. 复杂内容编辑保存 | `jmeter/05-edit-save-complex.jmx` | 同 04，内容用复杂模板 |
| 6. 500 菜单首页并发 | `jmeter/06-homepage-500menu.jmx` | 需先造 500 菜单空间 + 登录 |

## 环境准备

1. 安装 JMeter：`brew install jmeter`（或官网 tarball，需 5.5+）
2. 启动本机 XWiki：`http://localhost:8080/xwiki`（admin/admin）
3. 修改 `perf.properties`（实例地址、账号、页面路径）
4. 造数：`bash scripts/prepare-data.sh`（创建压测账号 + 500 菜单空间，见脚本头部参数说明）

## 运行

```bash
cd docs/perf-test
jmeter -n -t jmeter/01-homepage.jmx -q perf.properties -l results/01-homepage.csv
# 非 GUI 运行；-l 输出原始采样数据，再用 GUI 打开结果或 -e -o 生成 HTML 报告
jmeter -n -t jmeter/01-homepage.jmx -q perf.properties -e -o results/01-report
```

保存场景（04/05）默认只启用 solo 模式：用 GUI 打开 jmx，右键启用目标 Thread Group（solo/split/shared），
保存后命令行运行。三个模式的差异见设计文档。

## 认证说明

- 所有请求用 **Basic Auth 头**认证（无登录流程，不污染压测结果），由脚本内 `perf.user`/`perf.pass` 生成。
- `01-homepage.jmx` 默认匿名；如需带认证测首页，在 GUI 中把 Test Plan 级变量 `authHeader`
  的值从空改为 `Basic ${__base64Encode(${perf.user}:${perf.pass})}`。
- split/shared 模式每线程用独立账号 `PerfUser1..N`（需 prepare-data.sh 创建）。

## 结果解读

- shared 模式的 **409 冲突是预期现象**（多人同页必然冲突），失败率 ≈ 冲突率，用于评估该场景的服务器处理能力。
- 保存测试会产生测试页面与版本历史，跑完用 `bash scripts/cleanup-data.sh` 清理。

## 注意事项

- 压测机与被测机分离，不要在同一台机器上跑。
- 压测前清空旧结果：`rm -rf results/*`
- 简单/复杂页 URL 内网测试时填入 `perf.properties`。
```

- [ ] **Step 6: 验证**

```bash
cd /Users/noaharno/programer/sourcecode/xwiki_parent/xwiki-platform/docs/perf-test && ls -R | head -20
```

Expected: 目录结构含 `jmeter/ scripts/ data/ results/` 与 4 个文件。

- [ ] **Step 7: Commit**

```bash
cd /Users/noaharno/programer/sourcecode/xwiki_parent/xwiki-platform
git add docs/perf-test/perf.properties docs/perf-test/README.md docs/perf-test/data/
git commit -m "性能测试：perf.properties 公共参数 + 保存内容模板 + README 骨架

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 2: 造数脚本 prepare-data.sh + cleanup-data.sh

**Files:**
- Create: `docs/perf-test/scripts/prepare-data.sh`
- Create: `docs/perf-test/scripts/cleanup-data.sh`
- Test: 本机实例实测（localhost:8080/xwiki，admin/admin）

**Interfaces:**
- Produces: 压测账号 `PerfTestUser` + `PerfUser1..N`（密码统一 `PERF_PASS`，默认 PerfPass123）、菜单空间 `MenuSpace0001..0500`、终端打印 `perf.authHeader` 配置行。
- Consumes: 本机实例 REST API（PUT page / POST object / DELETE page，设计文档已验证）。

- [ ] **Step 1: 写 `prepare-data.sh`**

```bash
#!/usr/bin/env bash
# XWiki 性能测试造数脚本（REST 通道，幂等可断点续跑）
# 用法: bash prepare-data.sh [选项]
#   --host/-H      实例地址（默认 localhost）
#   --port/-P      端口（默认 8080）
#   --context/-C   上下文路径（默认 xwiki）
#   --admin-user   管理员账号（默认 admin）
#   --admin-pass   管理员密码（默认 admin）
#   --user-count   压测用户数（默认 50，PerfUser1..N + PerfTestUser）
#   --space-count  菜单空间数（默认 500）
#   --space-prefix 菜单空间前缀（默认 MenuSpace）
#   --user-pass    压测用户统一密码（默认 PerfPass123）
set -euo pipefail

HOST=localhost; PORT=8080; CONTEXT=xwiki
ADMIN_USER=admin; ADMIN_PASS=admin
USER_COUNT=50; SPACE_COUNT=500; SPACE_PREFIX=MenuSpace; USER_PASS=PerfPass123

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
    --user-pass) USER_PASS=$2; shift 2;;
    *) echo "未知参数: $1"; exit 1;;
  esac
done

BASE="http://${HOST}:${PORT}/${CONTEXT}"
AUTH="-u ${ADMIN_USER}:${ADMIN_PASS}"
B64() { printf '%s' "$1" | base64 | tr -d '\n'; }

# REST 请求并返回状态码
rest() { # $1=method $2=path $3=json(可选)
  local body=()
  if [[ -n "${3:-}" ]]; then
    curl -s -o /dev/null -w '%{http_code}' -X "$1" "${AUTH}" -H "Content-Type: application/json" \
      --data "$3" "${BASE}$2"
  else
    curl -s -o /dev/null -w '%{http_code}' -X "$1" "${AUTH}" "${BASE}$2"
  fi
}
page_exists() { [[ "$(rest GET "/rest/wikis/xwiki/spaces/${1//./\\/pages/...}")" != "404" ]]; }
page_exists() { [[ "$(curl -s -o /dev/null -w '%{http_code}' -u ${ADMIN_USER}:${ADMIN_PASS} "${BASE}/rest/wikis/xwiki/spaces/$(dirname "$1" | tr '.' '/')/pages/$(basename "$1")")" != "404" ]]; }

create_page() { # $1=Space.Page $2=标题 $3=内容
  local space="${1%.*}" page="${1##*.}"
  local code
  code=$(curl -s -o /dev/null -w '%{http_code}' "${AUTH}" "${BASE}/rest/wikis/xwiki/spaces/${space//.//}/pages/${page}")
  if [[ "$code" == "200" ]]; then return 0; fi  # 已存在，跳过
  local json
  json=$(python3 -c "
import json,sys
print(json.dumps({'title': sys.argv[1], 'content': sys.argv[2], 'syntax': 'xwiki/2.1'}, ensure_ascii=False))
" "$2" "$3")
  code=$(rest "PUT" "/rest/wikis/xwiki/spaces/${space//.//}/pages/${page}" "$json")
  [[ "$code" == "201" || "$code" == "200" ]] || { echo "创建页面 $1 失败: $code"; return 1; }
}

create_user() { # $1=用户名 $2=邮箱
  create_page "XWiki.$1" "$1" "压测用户 $1"
  # 已存在则跳过（含对象）
  if curl -s -o /dev/null "${AUTH}" "${BASE}/rest/wikis/xwiki/spaces/XWiki/pages/$1/objects/XWikiUsers"; then
    local exists
    exists=$(curl -s "${AUTH}" "${BASE}/rest/wikis/xwiki/spaces/XWiki/pages/$1/objects/XWikiUsers" | grep -c XWikiUsers || true)
    [[ "$exists" -gt 0 ]] && return 0
  fi
  local json
  json=$(python3 -c "
import json,sys
obj = {'className': 'XWiki.XWikiUsers', 'properties': [
  {'name': 'email', 'value': sys.argv[1]},
  {'name': 'password', 'value': sys.argv[2]},
  {'name': 'password_confirmation', 'value': sys.argv[2]},
  {'name': 'first_name', 'value': sys.argv[3]},
  {'name': 'last_name', 'value': '压测'}
]}
print(json.dumps(obj, ensure_ascii=False))
" "$2" "$3" "$4")
  local code
  code=$(rest "POST" "/rest/wikis/xwiki/spaces/XWiki/pages/$1/objects" "$json")
  [[ "$code" == "201" || "$code" == "200" ]] || { echo "创建用户对象 $1 失败: $code"; return 1; }
}

echo "== 创建压测用户 =="
create_user "PerfTestUser" "perf.test@local.test" "$USER_PASS" "Perf"
for i in $(seq 1 "$USER_COUNT"); do
  create_user "${USER_PREFIX:-PerfUser}${i}" "perf.user${i}@local.test" "$USER_PASS" "Perf"
  [[ $((i % 10)) -eq 0 ]] && echo "  已创建用户 ${i}/${USER_COUNT}"
done
echo "  PerfTestUser + PerfUser1..${USER_COUNT} 就绪（密码: $USER_PASS）"

echo "== 创建菜单空间（${SPACE_COUNT} 个） =="
for i in $(seq 1 "$SPACE_COUNT"); do
  local name
  name=$(printf "%s%04d" "$SPACE_PREFIX" "$i")
  create_page "$name.WebHome" "$name" "菜单空间 $name 的首页"
  [[ $((i % 50)) -eq 0 ]] && echo "  已创建 ${i}/${SPACE_COUNT}"
done
echo "  菜单空间 ${SPACE_PREFIX}0001..$(printf '%04d' $SPACE_COUNT) 就绪"

echo
echo "== 完成 =="
echo "认证配置（写入 perf.properties 的 perf.pass 对应行即可，header 由脚本生成）:"
echo "  perf.user=${USER_PREFIX:-PerfUser}1   (solo 用 perf.user=$([[ $USER_COUNT -ge 1 ]] && echo PerfTestUser))"
```

- [ ] **Step 2: 修掉上面草稿里的重复函数（保留第二次定义的 `page_exists` 用法确认后删除）**

最终脚本只保留 `rest`、`create_page`、`create_user` 三个函数；`page_exists` 不再使用直接删除；顶部补 `USER_PREFIX=PerfUser` 变量声明（脚本中 `${USER_PREFIX:-PerfUser}` 依赖它），并修正 `create_page` 中 space 替换（`${space//.//}` 将 `MenuSpace0001` 不变、`XWiki` 不变，嵌套空间名如 `PerfTest.Child` → `PerfTest/Child`）。

- [ ] **Step 3: 写 `cleanup-data.sh`**

```bash
#!/usr/bin/env bash
# XWiki 性能测试数据清理脚本（删除造数/压测产生的用户、菜单空间、保存测试页面）
# 用法: bash cleanup-data.sh [选项]  （参数同 prepare-data.sh，另有 --user-count/--space-count/--save-page）
set -euo pipefail

HOST=localhost; PORT=8080; CONTEXT=xwiki
ADMIN_USER=admin; ADMIN_PASS=admin
USER_COUNT=50; SPACE_COUNT=500; SPACE_PREFIX=MenuSpace
SAVE_PAGE="PerfTest/SimplePage"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --host|-H) HOST=$2; shift 2;; --port|-P) PORT=$2; shift 2;;
    --context|-C) CONTEXT=$2; shift 2;; --admin-user) ADMIN_USER=$2; shift 2;;
    --admin-pass) ADMIN_PASS=$2; shift 2;; --user-count) USER_COUNT=$2; shift 2;;
    --space-count) SPACE_COUNT=$2; shift 2;; --space-prefix) SPACE_PREFIX=$2; shift 2;;
    --save-page) SAVE_PAGE=$2; shift 2;;
    *) echo "未知参数: $1"; exit 1;;
  esac
done

BASE="http://${HOST}:${PORT}/${CONTEXT}"
AUTH=(-u "${ADMIN_USER}:${ADMIN_PASS}")

del_page() { # $1=Space.Page
  local space="${1%.*}" page="${1##*.}"
  local code
  code=$(curl -s -o /dev/null -w '%{http_code}' "${AUTH[@]}" -X DELETE \
    "${BASE}/rest/wikis/xwiki/spaces/${space//.//}/pages/${page}")
  echo "  删除 $1 -> $code"
}

echo "== 删除压测用户 =="
del_page "XWiki.PerfTestUser"
for i in $(seq 1 "$USER_COUNT"); do
  del_page "XWiki.PerfUser${i}"
done

echo "== 删除菜单空间 =="
for i in $(seq 1 "$SPACE_COUNT"); do
  del_page "$(printf '%s%04d' "$SPACE_PREFIX" "$i").WebHome"
  [[ $((i % 100)) -eq 0 ]] && echo "  已清理 ${i}/${SPACE_COUNT}"
done

echo "== 删除保存测试页面（含 split 模式 _N 后缀页） =="
save_space="${SAVE_PAGE%.*}" save_name="${SAVE_PAGE##*.}"
for i in $(seq 1 100); do
  del_page "${save_space}.${save_name}_${i}" 2>/dev/null || true
done
del_page "$SAVE_PAGE"
echo "== 清理完成 =="
```

- [ ] **Step 4: 本机实测造数脚本（先小规模）**

```bash
chmod +x docs/perf-test/scripts/*.sh
cd docs/perf-test/scripts
bash prepare-data.sh --user-count 2 --space-count 3
```

Expected: 输出用户与空间创建进度，无失败；`curl -u admin:admin -o /dev/null -w '%{http_code}' http://localhost:8080/xwiki/bin/view/MenuSpace0001/` 返回 200；`curl -u PerfTestUser:PerfPass123 -o /dev/null -w '%{http_code}' http://localhost:8080/xwiki/bin/view/Sandbox/TestPage1` 返回 200（新用户可登录）。

- [ ] **Step 5: 本机实测清理脚本**

```bash
cd docs/perf-test/scripts && bash cleanup-data.sh --user-count 2 --space-count 3
curl -s -o /dev/null -w '%{http_code}\n' -u admin:admin http://localhost:8080/xwiki/bin/view/MenuSpace0001/
```

Expected: 清理输出全部 204；MenuSpace0001 返回 404。

- [ ] **Step 6: Commit**

```bash
cd /Users/noaharno/programer/sourcecode/xwiki_parent/xwiki-platform
git add docs/perf-test/scripts/
git commit -m "性能测试：造数/清理脚本（压测用户 + 500 菜单空间，REST 通道幂等）

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 3: 安装 JMeter

**Files:**
- None（环境）

- [ ] **Step 1: 安装**

```bash
brew install jmeter
```

Expected: 安装完成（约 1-2 分钟）。

- [ ] **Step 2: 验证**

```bash
jmeter --version
```

Expected: 输出 `Apache JMeter 5.x` 版本信息。

- [ ] **Step 3: 确认 __FileToString 与 __base64Encode 函数存在**

```bash
jmeter -n -t /dev/null 2>&1 | head -1 || true
```

Expected: 无报错即可（函数在 5.5+ 内置，JMX 冒烟时自然验证）。

---

### Task 4: 01-homepage.jmx（完整模板，其余只读脚本以此复制）

**Files:**
- Create: `docs/perf-test/jmeter/01-homepage.jmx`
- Test: `xmllint` 良构 + `jmeter -n` 本机冒烟

**Interfaces:**
- Consumes: `perf.properties` 参数（Task 1 定义）。
- Produces: canonical JMX 结构（TestPlan UDV → ThreadGroup → HTTP Request Defaults → Sampler → Header/Cookie/断言），Task 5/6/7/8 复制改造。

- [ ] **Step 1: 写完整 JMX**（此文件是后续所有只读脚本的模板）

```xml
<?xml version="1.0" encoding="UTF-8"?>
<jmeterTestPlan version="1.2" properties="5.0" jmeter="5.6.3">
  <hashTree>
    <TestPlan guiclass="TestPlanGui" testclass="TestPlan" testname="01 首页并发" enabled="true">
      <stringProp name="TestPlan.comments"></stringProp>
      <boolProp name="TestPlan.functional_mode">false</boolProp>
      <boolProp name="TestPlan.tearDown_on_shutdown">true</boolProp>
      <boolProp name="TestPlan.serialize_threadgroups">false</boolProp>
      <elementProp name="TestPlan.user_defined_variables" elementType="Arguments" guiclass="ArgumentsPanel" testclass="Arguments" testname="User Defined Variables">
        <collectionProp name="Arguments.arguments">
          <elementProp name="perf.host" elementType="Argument">
            <stringProp name="Argument.name">perf.host</stringProp>
            <stringProp name="Argument.value">${__P(perf.host,localhost)}</stringProp>
            <stringProp name="Argument.metadata">=</stringProp>
          </elementProp>
          <elementProp name="perf.port" elementType="Argument">
            <stringProp name="Argument.name">perf.port</stringProp>
            <stringProp name="Argument.value">${__P(perf.port,8080)}</stringProp>
            <stringProp name="Argument.metadata">=</stringProp>
          </elementProp>
          <elementProp name="perf.protocol" elementType="Argument">
            <stringProp name="Argument.name">perf.protocol</stringProp>
            <stringProp name="Argument.value">${__P(perf.protocol,http)}</stringProp>
            <stringProp name="Argument.metadata">=</stringProp>
          </elementProp>
          <elementProp name="perf.context" elementType="Argument">
            <stringProp name="Argument.name">perf.context</stringProp>
            <stringProp name="Argument.value">${__P(perf.context,xwiki)}</stringProp>
            <stringProp name="Argument.metadata">=</stringProp>
          </elementProp>
          <elementProp name="perf.user" elementType="Argument">
            <stringProp name="Argument.name">perf.user</stringProp>
            <stringProp name="Argument.value">${__P(perf.user,PerfTestUser)}</stringProp>
            <stringProp name="Argument.metadata">=</stringProp>
          </elementProp>
          <elementProp name="perf.pass" elementType="Argument">
            <stringProp name="Argument.name">perf.pass</stringProp>
            <stringProp name="Argument.value">${__P(perf.pass,PerfPass123)}</stringProp>
            <stringProp name="Argument.metadata">=</stringProp>
          </elementProp>
          <elementProp name="perf.simplePageUrl" elementType="Argument">
            <stringProp name="Argument.name">perf.simplePageUrl</stringProp>
            <stringProp name="Argument.value">${__P(perf.simplePageUrl,Sandbox/TestPage1)}</stringProp>
            <stringProp name="Argument.metadata">=</stringProp>
          </elementProp>
          <elementProp name="perf.complexPageUrl" elementType="Argument">
            <stringProp name="Argument.name">perf.complexPageUrl</stringProp>
            <stringProp name="Argument.value">${__P(perf.complexPageUrl,Sandbox/TestPage2)}</stringProp>
            <stringProp name="Argument.metadata">=</stringProp>
          </elementProp>
          <elementProp name="perf.threads" elementType="Argument">
            <stringProp name="Argument.name">perf.threads</stringProp>
            <stringProp name="Argument.value">${__P(perf.threads,50)}</stringProp>
            <stringProp name="Argument.metadata">=</stringProp>
          </elementProp>
          <elementProp name="perf.rampUp" elementType="Argument">
            <stringProp name="Argument.name">perf.rampUp</stringProp>
            <stringProp name="Argument.value">${__P(perf.rampUp,60)}</stringProp>
            <stringProp name="Argument.metadata">=</stringProp>
          </elementProp>
          <elementProp name="perf.duration" elementType="Argument">
            <stringProp name="Argument.name">perf.duration</stringProp>
            <stringProp name="Argument.value">${__P(perf.duration,300)}</stringProp>
            <stringProp name="Argument.metadata">=</stringProp>
          </elementProp>
          <elementProp name="perf.resultsFile" elementType="Argument">
            <stringProp name="Argument.name">perf.resultsFile</stringProp>
            <stringProp name="Argument.value">${__P(perf.resultsFile,results/result.csv)}</stringProp>
            <stringProp name="Argument.metadata">=</stringProp>
          </elementProp>
          <elementProp name="authHeader" elementType="Argument">
            <stringProp name="Argument.name">authHeader</stringProp>
            <stringProp name="Argument.value"></stringProp>
            <stringProp name="Argument.metadata">=</stringProp>
          </elementProp>
        </collectionProp>
      </elementProp>
    </TestPlan>
    <hashTree>
      <ConfigTestElement guiclass="HttpDefaultsGui" testclass="ConfigTestElement" testname="HTTP Request Defaults" enabled="true">
        <stringProp name="HTTPSampler.domain">${perf.host}</stringProp>
        <stringProp name="HTTPSampler.port">${perf.port}</stringProp>
        <stringProp name="HTTPSampler.protocol">${perf.protocol}</stringProp>
        <stringProp name="HTTPSampler.contentEncoding">UTF-8</stringProp>
        <stringProp name="HTTPSampler.path"></stringProp>
        <stringProp name="HTTPSampler.concurrentPool">4</stringProp>
        <boolProp name="HTTPSampler.use_keepalive">true</boolProp>
      </ConfigTestElement>
      <hashTree/>
      <ThreadGroup guiclass="ThreadGroupGui" testclass="ThreadGroup" testname="首页并发" enabled="true">
        <stringProp name="ThreadGroup.on_sample_error">continue</stringProp>
        <elementProp name="ThreadGroup.main_controller" elementType="LoopController" guiclass="LoopControlPanel" testclass="LoopController" testname="Loop Controller" enabled="true">
          <boolProp name="LoopController.continue_forever">false</boolProp>
          <stringProp name="LoopController.loops">-1</stringProp>
        </elementProp>
        <stringProp name="ThreadGroup.num_threads">${__P(perf.threads,50)}</stringProp>
        <stringProp name="ThreadGroup.ramp_time">${__P(perf.rampUp,60)}</stringProp>
        <boolProp name="ThreadGroup.scheduler">true</boolProp>
        <stringProp name="ThreadGroup.duration">${__P(perf.duration,300)}</stringProp>
        <stringProp name="ThreadGroup.delay"></stringProp>
        <boolProp name="ThreadGroup.same_user_on_next_iteration">true</boolProp>
      </ThreadGroup>
      <hashTree>
        <HTTPSamplerProxy guiclass="HttpTestSampleGui" testclass="HTTPSamplerProxy" testname="GET 首页" enabled="true">
          <elementProp name="HTTPsampler.Arguments" elementType="Arguments" guiclass="HTTPArgumentsPanel" testclass="Arguments" testname="User Defined Variables" enabled="true">
            <collectionProp name="Arguments.arguments"/>
          </elementProp>
          <stringProp name="HTTPSampler.domain"></stringProp>
          <stringProp name="HTTPSampler.port"></stringProp>
          <stringProp name="HTTPSampler.protocol"></stringProp>
          <stringProp name="HTTPSampler.path">${perf.context}/bin/view/Main/</stringProp>
          <stringProp name="HTTPSampler.method">GET</stringProp>
          <boolProp name="HTTPSampler.follow_redirects">true</boolProp>
          <boolProp name="HTTPSampler.auto_redirects">false</boolProp>
          <boolProp name="HTTPSampler.use_keepalive">true</boolProp>
          <boolProp name="HTTPSampler.DO_MULTIPART_POST">false</boolProp>
          <stringProp name="HTTPSampler.embedded_url_re"></stringProp>
          <stringProp name="HTTPSampler.connect_timeout"></stringProp>
          <stringProp name="HTTPSampler.response_timeout"></stringProp>
        </HTTPSamplerProxy>
        <hashTree>
          <HeaderManager guiclass="HeaderPanel" testclass="HeaderManager" testname="HTTP Header Manager" enabled="true">
            <collectionProp name="HeaderManager.headers">
              <elementProp name="" elementType="Header">
                <stringProp name="Header.name">Authorization</stringProp>
                <stringProp name="Header.value">${authHeader}</stringProp>
              </elementProp>
            </collectionProp>
          </HeaderManager>
          <hashTree/>
          <CookieManager guiclass="CookiePanel" testclass="CookieManager" testname="HTTP Cookie Manager" enabled="true">
            <collectionProp name="CookieManager.cookies"/>
            <boolProp name="CookieManager.clearEachIteration">false</boolProp>
            <boolProp name="CookieManager.controlledByThreadGroup">false</boolProp>
          </CookieManager>
          <hashTree/>
          <ResponseAssertion guiclass="AssertionGui" testclass="ResponseAssertion" testname="断言 200" enabled="true">
            <collectionProp name="Asserion.test_string">
              <stringProp name="49586">200</stringProp>
            </collectionProp>
            <stringProp name="Assertion.test_field">Assertion.response_code</stringProp>
            <boolProp name="Assertion.assume_success">false</boolProp>
            <intProp name="Assertion.test_type">8</intProp>
          </ResponseAssertion>
          <hashTree/>
        </hashTree>
      </hashTree>
      <ResultCollector guiclass="SimpleDataWriter" testclass="ResultCollector" testname="结果写入 CSV" enabled="true">
        <boolProp name="ResultCollector.error_logging">false</boolProp>
        <objProp>
          <name>saveConfig</name>
          <value class="SampleSaveConfiguration">
            <time>true</time><latency>true</latency><timestamp>true</timestamp>
            <success>true</success><label>true</label><code>true</code><message>true</message>
            <threadName>true</threadName><dataType>true</dataType><encoding>false</encoding>
            <assertions>true</assertions><subresults>true</subresults><responseData>false</responseData>
            <samplerData>false</samplerData><xml>false</xml><fieldNames>true</fieldNames>
            <responseHeaders>false</responseHeaders><requestHeaders>false</requestHeaders>
            <responseDataOnError>false</responseDataOnError><saveAssertionResultsFailureMessage>true</saveAssertionResultsFailureMessage>
            <assertionsResultsToSave>0</assertionsResultsToSave><bytes>true</bytes><sentBytes>true</sentBytes>
            <url>true</url><threadCounts>true</threadCounts><idleTime>true</idleTime><connectTime>true</connectTime>
          </value>
        </objProp>
        <stringProp name="filename">${__P(perf.resultsFile,results/result.csv)}</stringProp>
      </ResultCollector>
      <hashTree/>
      <ResultCollector guiclass="SummaryReport" testclass="ResultCollector" testname="汇总报告" enabled="true">
        <boolProp name="ResultCollector.error_logging">false</boolProp>
        <objProp>
          <name>saveConfig</name>
          <value class="SampleSaveConfiguration">
            <time>true</time><latency>true</latency><timestamp>true</timestamp>
            <success>true</success><label>true</label><code>true</code><message>true</message>
            <threadName>true</threadName><dataType>true</dataType><encoding>false</encoding>
            <assertions>true</assertions><subresults>true</subresults><responseData>false</responseData>
            <samplerData>false</samplerData><xml>false</xml><fieldNames>true</fieldNames>
            <responseHeaders>false</responseHeaders><requestHeaders>false</requestHeaders>
            <responseDataOnError>false</responseDataOnError><saveAssertionResultsFailureMessage>true</saveAssertionResultsFailureMessage>
            <assertionsResultsToSave>0</assertionsResultsToSave><bytes>true</bytes><sentBytes>true</sentBytes>
            <url>true</url><threadCounts>true</threadCounts><idleTime>true</idleTime><connectTime>true</connectTime>
          </value>
        </objProp>
        <stringProp name="filename"></stringProp>
      </ResultCollector>
      <hashTree/>
    </hashTree>
  </hashTree>
</jmeterTestPlan>
```

- [ ] **Step 2: 良构验证**

```bash
cd /Users/noaharno/programer/sourcecode/xwiki_parent/xwiki-platform/docs/perf-test
xmllint --noout jmeter/01-homepage.jmx
```

Expected: 无输出（XML 良构）。

- [ ] **Step 3: 本机冒烟（3 线程 20 秒）**

```bash
cd docs/perf-test
rm -rf results && mkdir -p results
jmeter -n -t jmeter/01-homepage.jmx -q perf.properties \
  -Jperf.threads=3 -Jperf.rampUp=3 -Jperf.duration=20 \
  -l results/01-homepage.csv
tail -5 results/01-homepage.csv
```

Expected: 运行结束无 ERROR；CSV 每行 `200` 且 `true`（success 列）。

- [ ] **Step 4: Commit**

```bash
cd /Users/noaharno/programer/sourcecode/xwiki_parent/xwiki-platform
git add docs/perf-test/jmeter/01-homepage.jmx
git commit -m "性能测试：场景1 首页并发 JMeter 脚本（匿名，canonical 模板）

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 5: 02-page-simple.jmx + 03-page-complex.jmx

**Files:**
- Create: `docs/perf-test/jmeter/02-page-simple.jmx`
- Create: `docs/perf-test/jmeter/03-page-complex.jmx`
- Test: `xmllint` + 本机冒烟

**Interfaces:**
- Consumes: Task 4 canonical 结构；`perf.simplePageUrl`/`perf.complexPageUrl` 参数。

- [ ] **Step 1: 复制 01 → 02 并做 4 处修改**

```bash
cp jmeter/01-homepage.jmx jmeter/02-page-simple.jmx
```

修改（用精确字符串替换）：
1. `testname="01 首页并发"` → `testname="02 简单页并发"`
2. `testname="首页并发"` → `testname="简单页并发"`
3. `testname="GET 首页"` → `testname="GET 简单页"`
4. `HTTPSampler.path">${perf.context}/bin/view/Main/` → `HTTPSampler.path">${perf.context}/bin/view/${perf.simplePageUrl}`
5. TestPlan 级 UDV `authHeader` 的 `<stringProp name="Argument.value"></stringProp>` → `<stringProp name="Argument.value">Basic ${__base64Encode(${perf.user}:${perf.pass})}</stringProp>`（带认证）

- [ ] **Step 2: 复制 01 → 03 并做同样修改**

```bash
cp jmeter/01-homepage.jmx jmeter/03-page-complex.jmx
```

同上 5 处，`02 简单页并发`→`03 复杂页并发`、`简单页`→`复杂页`，path 用 `${perf.complexPageUrl}`，authHeader 带认证。

- [ ] **Step 3: 良构 + 冒烟**

```bash
cd docs/perf-test
xmllint --noout jmeter/02-page-simple.jmx jmeter/03-page-complex.jmx
jmeter -n -t jmeter/02-page-simple.jmx -q perf.properties -Jperf.threads=2 -Jperf.rampUp=2 -Jperf.duration=15 -l results/02.csv
jmeter -n -t jmeter/03-page-complex.jmx -q perf.properties -Jperf.threads=2 -Jperf.rampUp=2 -Jperf.duration=15 -l results/03.csv
tail -3 results/02.csv results/03.csv
```

Expected: 无 ERROR；CSV 中 `200,true`。（注意：02/03 需要 `perf.user`/`perf.pass` 有效——本机验证前先跑一次 Task 2 的 prepare-data.sh --user-count 1 创建 PerfTestUser；页面用 Sandbox/TestPage1/2 匿名可读即可，认证只是附加。）

- [ ] **Step 4: Commit**

```bash
cd /Users/noaharno/programer/sourcecode/xwiki_parent/xwiki-platform
git add docs/perf-test/jmeter/02-page-simple.jmx docs/perf-test/jmeter/03-page-complex.jmx
git commit -m "性能测试：场景2/3 简单页与复杂页并发脚本（Basic Auth 头）

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 6: 06-homepage-500menu.jmx

**Files:**
- Create: `docs/perf-test/jmeter/06-homepage-500menu.jmx`
- Test: `xmllint` + 本机冒烟（先造 5 个空间小规模验证）

**Interfaces:**
- Consumes: Task 4 canonical 结构；环境需先跑 prepare-data.sh 造菜单空间。

- [ ] **Step 1: 复制 01 → 06 并修改**

```bash
cp jmeter/01-homepage.jmx jmeter/06-homepage-500menu.jmx
```

修改 5 处（同 Task 5 模式）：
1. `testname="01 首页并发"` → `testname="06 500菜单首页并发"`
2. `testname="首页并发"` → `testname="500菜单首页并发"`
3. `testname="GET 首页"` → `testname="GET 首页(500菜单)"`
4. path 保持 `${perf.context}/bin/view/Main/`
5. UDV `authHeader` → `Basic ${__base64Encode(${perf.user}:${perf.pass})}`（**必须带认证**：导航树登录后才渲染，未登录看不到 500 菜单）

- [ ] **Step 2: 造菜单空间小规模并冒烟**

```bash
cd docs/perf-test/scripts && bash prepare-data.sh --space-count 5 --user-count 1
cd ../ && xmllint --noout jmeter/06-homepage-500menu.jmx
jmeter -n -t jmeter/06-homepage-500menu.jmx -q perf.properties -Jperf.threads=2 -Jperf.rampUp=2 -Jperf.duration=15 -l results/06.csv
tail -3 results/06.csv
```

Expected: 无 ERROR；CSV `200,true`。验证导航树渲染了空间：`curl -s -u PerfTestUser:PerfPass123 http://localhost:8080/xwiki/bin/view/Main/ | grep -c "MenuSpace0001"` 返回 ≥1。

- [ ] **Step 3: Commit**

```bash
cd /Users/noaharno/programer/sourcecode/xwiki_parent/xwiki-platform
git add docs/perf-test/jmeter/06-homepage-500menu.jmx
git commit -m "性能测试：场景6 500菜单首页并发脚本（带认证）

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 7: 04-edit-save-simple.jmx（3 种保存模式完整脚本）

**Files:**
- Create: `docs/perf-test/jmeter/04-edit-save-simple.jmx`
- Test: `xmllint` + 本机冒烟（solo/split/shared 各 2 线程）

**Interfaces:**
- Consumes: `perf.savePage`、`perf.saveContentFile`、`perf.userPrefix`、`perf.threads.solo/split/shared`、`perf.rampUp.save`、`perf.duration.save`。
- Produces: 提取器变量 `form_token`/`previousVersion`/`isNew`/`editingVersionDate`（Task 8 复用）。

- [ ] **Step 1: 写完整 JMX**（3 个 ThreadGroup；每个 TG 内 GET edit → 正则提取 4 字段 → POST save；Header/Cookie 每个 TG 独立）

```xml
<?xml version="1.0" encoding="UTF-8"?>
<jmeterTestPlan version="1.2" properties="5.0" jmeter="5.6.3">
  <hashTree>
    <TestPlan guiclass="TestPlanGui" testclass="TestPlan" testname="04 简单内容编辑保存" enabled="true">
      <stringProp name="TestPlan.comments"></stringProp>
      <boolProp name="TestPlan.functional_mode">false</boolProp>
      <boolProp name="TestPlan.tearDown_on_shutdown">true</boolProp>
      <boolProp name="TestPlan.serialize_threadgroups">false</boolProp>
      <elementProp name="TestPlan.user_defined_variables" elementType="Arguments" guiclass="ArgumentsPanel" testclass="Arguments" testname="User Defined Variables">
        <collectionProp name="Arguments.arguments">
          <elementProp name="perf.host" elementType="Argument">
            <stringProp name="Argument.name">perf.host</stringProp>
            <stringProp name="Argument.value">${__P(perf.host,localhost)}</stringProp>
            <stringProp name="Argument.metadata">=</stringProp>
          </elementProp>
          <elementProp name="perf.port" elementType="Argument">
            <stringProp name="Argument.name">perf.port</stringProp>
            <stringProp name="Argument.value">${__P(perf.port,8080)}</stringProp>
            <stringProp name="Argument.metadata">=</stringProp>
          </elementProp>
          <elementProp name="perf.protocol" elementType="Argument">
            <stringProp name="Argument.name">perf.protocol</stringProp>
            <stringProp name="Argument.value">${__P(perf.protocol,http)}</stringProp>
            <stringProp name="Argument.metadata">=</stringProp>
          </elementProp>
          <elementProp name="perf.context" elementType="Argument">
            <stringProp name="Argument.name">perf.context</stringProp>
            <stringProp name="Argument.value">${__P(perf.context,xwiki)}</stringProp>
            <stringProp name="Argument.metadata">=</stringProp>
          </elementProp>
          <elementProp name="perf.user" elementType="Argument">
            <stringProp name="Argument.name">perf.user</stringProp>
            <stringProp name="Argument.value">${__P(perf.user,PerfTestUser)}</stringProp>
            <stringProp name="Argument.metadata">=</stringProp>
          </elementProp>
          <elementProp name="perf.pass" elementType="Argument">
            <stringProp name="Argument.name">perf.pass</stringProp>
            <stringProp name="Argument.value">${__P(perf.pass,PerfPass123)}</stringProp>
            <stringProp name="Argument.metadata">=</stringProp>
          </elementProp>
          <elementProp name="perf.userPrefix" elementType="Argument">
            <stringProp name="Argument.name">perf.userPrefix</stringProp>
            <stringProp name="Argument.value">${__P(perf.userPrefix,PerfUser)}</stringProp>
            <stringProp name="Argument.metadata">=</stringProp>
          </elementProp>
          <elementProp name="perf.savePage" elementType="Argument">
            <stringProp name="Argument.name">perf.savePage</stringProp>
            <stringProp name="Argument.value">${__P(perf.savePage,PerfTest/SimplePage)}</stringProp>
            <stringProp name="Argument.metadata">=</stringProp>
          </elementProp>
          <elementProp name="perf.saveContentFile" elementType="Argument">
            <stringProp name="Argument.name">perf.saveContentFile</stringProp>
            <stringProp name="Argument.value">${__P(perf.saveContentFile,data/save-content-simple.txt)}</stringProp>
            <stringProp name="Argument.metadata">=</stringProp>
          </elementProp>
          <elementProp name="saveContent" elementType="Argument">
            <stringProp name="Argument.name">saveContent</stringProp>
            <stringProp name="Argument.value">${__FileToString(${perf.saveContentFile},UTF-8,)}</stringProp>
            <stringProp name="Argument.metadata">=</stringProp>
          </elementProp>
          <elementProp name="perf.threads.solo" elementType="Argument">
            <stringProp name="Argument.name">perf.threads.solo</stringProp>
            <stringProp name="Argument.value">${__P(perf.threads.solo,2)}</stringProp>
            <stringProp name="Argument.metadata">=</stringProp>
          </elementProp>
          <elementProp name="perf.threads.split" elementType="Argument">
            <stringProp name="Argument.name">perf.threads.split</stringProp>
            <stringProp name="Argument.value">${__P(perf.threads.split,50)}</stringProp>
            <stringProp name="Argument.metadata">=</stringProp>
          </elementProp>
          <elementProp name="perf.threads.shared" elementType="Argument">
            <stringProp name="Argument.name">perf.threads.shared</stringProp>
            <stringProp name="Argument.value">${__P(perf.threads.shared,50)}</stringProp>
            <stringProp name="Argument.metadata">=</stringProp>
          </elementProp>
          <elementProp name="perf.rampUp.save" elementType="Argument">
            <stringProp name="Argument.name">perf.rampUp.save</stringProp>
            <stringProp name="Argument.value">${__P(perf.rampUp.save,60)}</stringProp>
            <stringProp name="Argument.metadata">=</stringProp>
          </elementProp>
          <elementProp name="perf.duration.save" elementType="Argument">
            <stringProp name="Argument.name">perf.duration.save</stringProp>
            <stringProp name="Argument.value">${__P(perf.duration.save,300)}</stringProp>
            <stringProp name="Argument.metadata">=</stringProp>
          </elementProp>
          <elementProp name="perf.resultsFile" elementType="Argument">
            <stringProp name="Argument.name">perf.resultsFile</stringProp>
            <stringProp name="Argument.value">${__P(perf.resultsFile,results/result.csv)}</stringProp>
            <stringProp name="Argument.metadata">=</stringProp>
          </elementProp>
        </collectionProp>
      </elementProp>
    </TestPlan>
    <hashTree>
      <ConfigTestElement guiclass="HttpDefaultsGui" testclass="ConfigTestElement" testname="HTTP Request Defaults" enabled="true">
        <stringProp name="HTTPSampler.domain">${perf.host}</stringProp>
        <stringProp name="HTTPSampler.port">${perf.port}</stringProp>
        <stringProp name="HTTPSampler.protocol">${perf.protocol}</stringProp>
        <stringProp name="HTTPSampler.contentEncoding">UTF-8</stringProp>
        <stringProp name="HTTPSampler.path"></stringProp>
        <stringProp name="HTTPSampler.concurrentPool">4</stringProp>
        <boolProp name="HTTPSampler.use_keepalive">true</boolProp>
      </ConfigTestElement>
      <hashTree/>

      <!-- ========== 模式1 solo：同一个人编辑保存同一个页面 ========== -->
      <ThreadGroup guiclass="ThreadGroupGui" testclass="ThreadGroup" testname="保存-单人单页(solo)" enabled="true">
        <stringProp name="ThreadGroup.on_sample_error">continue</stringProp>
        <elementProp name="ThreadGroup.main_controller" elementType="LoopController" guiclass="LoopControlPanel" testclass="LoopController" testname="Loop Controller" enabled="true">
          <boolProp name="LoopController.continue_forever">false</boolProp>
          <stringProp name="LoopController.loops">-1</stringProp>
        </elementProp>
        <stringProp name="ThreadGroup.num_threads">${__P(perf.threads.solo,2)}</stringProp>
        <stringProp name="ThreadGroup.ramp_time">${__P(perf.rampUp.save,60)}</stringProp>
        <boolProp name="ThreadGroup.scheduler">true</boolProp>
        <stringProp name="ThreadGroup.duration">${__P(perf.duration.save,300)}</stringProp>
        <stringProp name="ThreadGroup.delay"></stringProp>
        <boolProp name="ThreadGroup.same_user_on_next_iteration">true</boolProp>
      </ThreadGroup>
      <hashTree>
        <HTTPSamplerProxy guiclass="HttpTestSampleGui" testclass="HTTPSamplerProxy" testname="GET 编辑表单" enabled="true">
          <elementProp name="HTTPsampler.Arguments" elementType="Arguments" guiclass="HTTPArgumentsPanel" testclass="Arguments" testname="User Defined Variables" enabled="true">
            <collectionProp name="Arguments.arguments">
              <elementProp name="editor" elementType="HTTPArgument">
                <boolProp name="HTTPArgument.always_encode">false</boolProp>
                <stringProp name="Argument.value">wiki</stringProp>
                <stringProp name="Argument.metadata">=</stringProp>
                <boolProp name="HTTPArgument.use_equals">true</boolProp>
                <stringProp name="Argument.name">editor</stringProp>
              </elementProp>
            </collectionProp>
          </elementProp>
          <stringProp name="HTTPSampler.path">${perf.context}/bin/edit/${perf.savePage}</stringProp>
          <stringProp name="HTTPSampler.method">GET</stringProp>
          <boolProp name="HTTPSampler.follow_redirects">true</boolProp>
          <boolProp name="HTTPSampler.auto_redirects">false</boolProp>
          <boolProp name="HTTPSampler.use_keepalive">true</boolProp>
          <boolProp name="HTTPSampler.DO_MULTIPART_POST">false</boolProp>
        </HTTPSamplerProxy>
        <hashTree>
          <RegexExtractor guiclass="RegexExtractorGui" testclass="RegexExtractor" testname="提取 form_token" enabled="true">
            <stringProp name="RegexExtractor.useHeaders">false</stringProp>
            <stringProp name="RegexExtractor.refname">form_token</stringProp>
            <stringProp name="RegexExtractor.regex">name="form_token" value="([^"]*)"</stringProp>
            <stringProp name="RegexExtractor.template">$1$</stringProp>
            <stringProp name="RegexExtractor.default">TOKEN_NOT_FOUND</stringProp>
            <stringProp name="RegexExtractor.match_number">1</stringProp>
          </RegexExtractor>
          <hashTree/>
          <RegexExtractor guiclass="RegexExtractorGui" testclass="RegexExtractor" testname="提取 previousVersion" enabled="true">
            <stringProp name="RegexExtractor.useHeaders">false</stringProp>
            <stringProp name="RegexExtractor.refname">previousVersion</stringProp>
            <stringProp name="RegexExtractor.regex">name="previousVersion"[^>]*value="([^"]*)"</stringProp>
            <stringProp name="RegexExtractor.template">$1$</stringProp>
            <stringProp name="RegexExtractor.default">1.1</stringProp>
            <stringProp name="RegexExtractor.match_number">1</stringProp>
          </RegexExtractor>
          <hashTree/>
          <RegexExtractor guiclass="RegexExtractorGui" testclass="RegexExtractor" testname="提取 isNew" enabled="true">
            <stringProp name="RegexExtractor.useHeaders">false</stringProp>
            <stringProp name="RegexExtractor.refname">isNew</stringProp>
            <stringProp name="RegexExtractor.regex">name="isNew"[^>]*value="([^"]*)"</stringProp>
            <stringProp name="RegexExtractor.template">$1$</stringProp>
            <stringProp name="RegexExtractor.default">false</stringProp>
            <stringProp name="RegexExtractor.match_number">1</stringProp>
          </RegexExtractor>
          <hashTree/>
          <RegexExtractor guiclass="RegexExtractorGui" testclass="RegexExtractor" testname="提取 editingVersionDate" enabled="true">
            <stringProp name="RegexExtractor.useHeaders">false</stringProp>
            <stringProp name="RegexExtractor.refname">editingVersionDate</stringProp>
            <stringProp name="RegexExtractor.regex">name="editingVersionDate"[^>]*value="([^"]*)"</stringProp>
            <stringProp name="RegexExtractor.template">$1$</stringProp>
            <stringProp name="RegexExtractor.default">0</stringProp>
            <stringProp name="RegexExtractor.match_number">1</stringProp>
          </RegexExtractor>
          <hashTree/>
          <ResponseAssertion guiclass="AssertionGui" testclass="ResponseAssertion" testname="断言 200" enabled="true">
            <collectionProp name="Asserion.test_string">
              <stringProp name="49586">200</stringProp>
            </collectionProp>
            <stringProp name="Assertion.test_field">Assertion.response_code</stringProp>
            <boolProp name="Assertion.assume_success">false</boolProp>
            <intProp name="Assertion.test_type">8</intProp>
          </ResponseAssertion>
          <hashTree/>
          <HeaderManager guiclass="HeaderPanel" testclass="HeaderManager" testname="HTTP Header Manager" enabled="true">
            <collectionProp name="HeaderManager.headers">
              <elementProp name="" elementType="Header">
                <stringProp name="Header.name">Authorization</stringProp>
                <stringProp name="Header.value">Basic ${__base64Encode(${perf.user}:${perf.pass})}</stringProp>
              </elementProp>
            </collectionProp>
          </HeaderManager>
          <hashTree/>
          <CookieManager guiclass="CookiePanel" testclass="CookieManager" testname="HTTP Cookie Manager" enabled="true">
            <collectionProp name="CookieManager.cookies"/>
            <boolProp name="CookieManager.clearEachIteration">false</boolProp>
            <boolProp name="CookieManager.controlledByThreadGroup">false</boolProp>
          </CookieManager>
          <hashTree/>
        </hashTree>

        <HTTPSamplerProxy guiclass="HttpTestSampleGui" testclass="HTTPSamplerProxy" testname="POST 保存" enabled="true">
          <elementProp name="HTTPsampler.Arguments" elementType="Arguments" guiclass="HTTPArgumentsPanel" testclass="Arguments" testname="User Defined Variables" enabled="true">
            <collectionProp name="Arguments.arguments">
              <elementProp name="content" elementType="HTTPArgument">
                <boolProp name="HTTPArgument.always_encode">true</boolProp>
                <stringProp name="Argument.value">${saveContent}</stringProp>
                <stringProp name="Argument.metadata">=</stringProp>
                <boolProp name="HTTPArgument.use_equals">true</boolProp>
                <stringProp name="Argument.name">content</stringProp>
              </elementProp>
              <elementProp name="title" elementType="HTTPArgument">
                <boolProp name="HTTPArgument.always_encode">true</boolProp>
                <stringProp name="Argument.value">保存性能测试</stringProp>
                <stringProp name="Argument.metadata">=</stringProp>
                <boolProp name="HTTPArgument.use_equals">true</boolProp>
                <stringProp name="Argument.name">title</stringProp>
              </elementProp>
              <elementProp name="form_token" elementType="HTTPArgument">
                <boolProp name="HTTPArgument.always_encode">true</boolProp>
                <stringProp name="Argument.value">${form_token}</stringProp>
                <stringProp name="Argument.metadata">=</stringProp>
                <boolProp name="HTTPArgument.use_equals">true</boolProp>
                <stringProp name="Argument.name">form_token</stringProp>
              </elementProp>
              <elementProp name="previousVersion" elementType="HTTPArgument">
                <boolProp name="HTTPArgument.always_encode">true</boolProp>
                <stringProp name="Argument.value">${previousVersion}</stringProp>
                <stringProp name="Argument.metadata">=</stringProp>
                <boolProp name="HTTPArgument.use_equals">true</boolProp>
                <stringProp name="Argument.name">previousVersion</stringProp>
              </elementProp>
              <elementProp name="editingVersionDate" elementType="HTTPArgument">
                <boolProp name="HTTPArgument.always_encode">true</boolProp>
                <stringProp name="Argument.value">${editingVersionDate}</stringProp>
                <stringProp name="Argument.metadata">=</stringProp>
                <boolProp name="HTTPArgument.use_equals">true</boolProp>
                <stringProp name="Argument.name">editingVersionDate</stringProp>
              </elementProp>
              <elementProp name="isNew" elementType="HTTPArgument">
                <boolProp name="HTTPArgument.always_encode">true</boolProp>
                <stringProp name="Argument.value">${isNew}</stringProp>
                <stringProp name="Argument.metadata">=</stringProp>
                <boolProp name="HTTPArgument.use_equals">true</boolProp>
                <stringProp name="Argument.name">isNew</stringProp>
              </elementProp>
              <elementProp name="xredirect" elementType="HTTPArgument">
                <boolProp name="HTTPArgument.always_encode">true</boolProp>
                <stringProp name="Argument.value">/${perf.context}/bin/view/${perf.savePage}</stringProp>
                <stringProp name="Argument.metadata">=</stringProp>
                <boolProp name="HTTPArgument.use_equals">true</boolProp>
                <stringProp name="Argument.name">xredirect</stringProp>
              </elementProp>
            </collectionProp>
          </elementProp>
          <stringProp name="HTTPSampler.path">${perf.context}/bin/save/${perf.savePage}</stringProp>
          <stringProp name="HTTPSampler.method">POST</stringProp>
          <boolProp name="HTTPSampler.follow_redirects">false</boolProp>
          <boolProp name="HTTPSampler.auto_redirects">false</boolProp>
          <boolProp name="HTTPSampler.use_keepalive">true</boolProp>
          <boolProp name="HTTPSampler.DO_MULTIPART_POST">false</boolProp>
        </HTTPSamplerProxy>
        <hashTree>
          <ResponseAssertion guiclass="AssertionGui" testclass="ResponseAssertion" testname="断言保存成功 302" enabled="true">
            <collectionProp name="Asserion.test_string">
              <stringProp name="49586">302</stringProp>
            </collectionProp>
            <stringProp name="Assertion.test_field">Assertion.response_code</stringProp>
            <boolProp name="Assertion.assume_success">false</boolProp>
            <intProp name="Assertion.test_type">8</intProp>
          </ResponseAssertion>
          <hashTree/>
        </hashTree>
      </hashTree>

      <!-- ========== 模式2 split：不同人编辑保存各自页面 ========== -->
      <ThreadGroup guiclass="ThreadGroupGui" testclass="ThreadGroup" testname="保存-多人多页(split)" enabled="false">
        <stringProp name="ThreadGroup.on_sample_error">continue</stringProp>
        <elementProp name="ThreadGroup.main_controller" elementType="LoopController" guiclass="LoopControlPanel" testclass="LoopController" testname="Loop Controller" enabled="true">
          <boolProp name="LoopController.continue_forever">false</boolProp>
          <stringProp name="LoopController.loops">-1</stringProp>
        </elementProp>
        <stringProp name="ThreadGroup.num_threads">${__P(perf.threads.split,50)}</stringProp>
        <stringProp name="ThreadGroup.ramp_time">${__P(perf.rampUp.save,60)}</stringProp>
        <boolProp name="ThreadGroup.scheduler">true</boolProp>
        <stringProp name="ThreadGroup.duration">${__P(perf.duration.save,300)}</stringProp>
        <stringProp name="ThreadGroup.delay"></stringProp>
        <boolProp name="ThreadGroup.same_user_on_next_iteration">true</boolProp>
      </ThreadGroup>
      <hashTree>
        <HTTPSamplerProxy guiclass="HttpTestSampleGui" testclass="HTTPSamplerProxy" testname="GET 编辑表单" enabled="true">
          <elementProp name="HTTPsampler.Arguments" elementType="Arguments" guiclass="HTTPArgumentsPanel" testclass="Arguments" testname="User Defined Variables" enabled="true">
            <collectionProp name="Arguments.arguments">
              <elementProp name="editor" elementType="HTTPArgument">
                <boolProp name="HTTPArgument.always_encode">false</boolProp>
                <stringProp name="Argument.value">wiki</stringProp>
                <stringProp name="Argument.metadata">=</stringProp>
                <boolProp name="HTTPArgument.use_equals">true</boolProp>
                <stringProp name="Argument.name">editor</stringProp>
              </elementProp>
            </collectionProp>
          </elementProp>
          <stringProp name="HTTPSampler.path">${perf.context}/bin/edit/${perf.savePage}_${__threadNum}</stringProp>
          <stringProp name="HTTPSampler.method">GET</stringProp>
          <boolProp name="HTTPSampler.follow_redirects">true</boolProp>
          <boolProp name="HTTPSampler.auto_redirects">false</boolProp>
          <boolProp name="HTTPSampler.use_keepalive">true</boolProp>
          <boolProp name="HTTPSampler.DO_MULTIPART_POST">false</boolProp>
        </HTTPSamplerProxy>
        <hashTree>
          <RegexExtractor guiclass="RegexExtractorGui" testclass="RegexExtractor" testname="提取 form_token" enabled="true">
            <stringProp name="RegexExtractor.useHeaders">false</stringProp>
            <stringProp name="RegexExtractor.refname">form_token</stringProp>
            <stringProp name="RegexExtractor.regex">name="form_token" value="([^"]*)"</stringProp>
            <stringProp name="RegexExtractor.template">$1$</stringProp>
            <stringProp name="RegexExtractor.default">TOKEN_NOT_FOUND</stringProp>
            <stringProp name="RegexExtractor.match_number">1</stringProp>
          </RegexExtractor>
          <hashTree/>
          <RegexExtractor guiclass="RegexExtractorGui" testclass="RegexExtractor" testname="提取 previousVersion" enabled="true">
            <stringProp name="RegexExtractor.useHeaders">false</stringProp>
            <stringProp name="RegexExtractor.refname">previousVersion</stringProp>
            <stringProp name="RegexExtractor.regex">name="previousVersion"[^>]*value="([^"]*)"</stringProp>
            <stringProp name="RegexExtractor.template">$1$</stringProp>
            <stringProp name="RegexExtractor.default">1.1</stringProp>
            <stringProp name="RegexExtractor.match_number">1</stringProp>
          </RegexExtractor>
          <hashTree/>
          <RegexExtractor guiclass="RegexExtractorGui" testclass="RegexExtractor" testname="提取 isNew" enabled="true">
            <stringProp name="RegexExtractor.useHeaders">false</stringProp>
            <stringProp name="RegexExtractor.refname">isNew</stringProp>
            <stringProp name="RegexExtractor.regex">name="isNew"[^>]*value="([^"]*)"</stringProp>
            <stringProp name="RegexExtractor.template">$1$</stringProp>
            <stringProp name="RegexExtractor.default">false</stringProp>
            <stringProp name="RegexExtractor.match_number">1</stringProp>
          </RegexExtractor>
          <hashTree/>
          <RegexExtractor guiclass="RegexExtractorGui" testclass="RegexExtractor" testname="提取 editingVersionDate" enabled="true">
            <stringProp name="RegexExtractor.useHeaders">false</stringProp>
            <stringProp name="RegexExtractor.refname">editingVersionDate</stringProp>
            <stringProp name="RegexExtractor.regex">name="editingVersionDate"[^>]*value="([^"]*)"</stringProp>
            <stringProp name="RegexExtractor.template">$1$</stringProp>
            <stringProp name="RegexExtractor.default">0</stringProp>
            <stringProp name="RegexExtractor.match_number">1</stringProp>
          </RegexExtractor>
          <hashTree/>
          <ResponseAssertion guiclass="AssertionGui" testclass="ResponseAssertion" testname="断言 200" enabled="true">
            <collectionProp name="Asserion.test_string">
              <stringProp name="49586">200</stringProp>
            </collectionProp>
            <stringProp name="Assertion.test_field">Assertion.response_code</stringProp>
            <boolProp name="Assertion.assume_success">false</boolProp>
            <intProp name="Assertion.test_type">8</intProp>
          </ResponseAssertion>
          <hashTree/>
          <HeaderManager guiclass="HeaderPanel" testclass="HeaderManager" testname="HTTP Header Manager" enabled="true">
            <collectionProp name="HeaderManager.headers">
              <elementProp name="" elementType="Header">
                <stringProp name="Header.name">Authorization</stringProp>
                <stringProp name="Header.value">Basic ${__base64Encode(${perf.userPrefix}${__threadNum}:${perf.pass})}</stringProp>
              </elementProp>
            </collectionProp>
          </HeaderManager>
          <hashTree/>
          <CookieManager guiclass="CookiePanel" testclass="CookieManager" testname="HTTP Cookie Manager" enabled="true">
            <collectionProp name="CookieManager.cookies"/>
            <boolProp name="CookieManager.clearEachIteration">false</boolProp>
            <boolProp name="CookieManager.controlledByThreadGroup">false</boolProp>
          </CookieManager>
          <hashTree/>
        </hashTree>
        <HTTPSamplerProxy guiclass="HttpTestSampleGui" testclass="HTTPSamplerProxy" testname="POST 保存" enabled="true">
          <elementProp name="HTTPsampler.Arguments" elementType="Arguments" guiclass="HTTPArgumentsPanel" testclass="Arguments" testname="User Defined Variables" enabled="true">
            <collectionProp name="Arguments.arguments">
              <elementProp name="content" elementType="HTTPArgument">
                <boolProp name="HTTPArgument.always_encode">true</boolProp>
                <stringProp name="Argument.value">${saveContent}</stringProp>
                <stringProp name="Argument.metadata">=</stringProp>
                <boolProp name="HTTPArgument.use_equals">true</boolProp>
                <stringProp name="Argument.name">content</stringProp>
              </elementProp>
              <elementProp name="title" elementType="HTTPArgument">
                <boolProp name="HTTPArgument.always_encode">true</boolProp>
                <stringProp name="Argument.value">保存性能测试</stringProp>
                <stringProp name="Argument.metadata">=</stringProp>
                <boolProp name="HTTPArgument.use_equals">true</boolProp>
                <stringProp name="Argument.name">title</stringProp>
              </elementProp>
              <elementProp name="form_token" elementType="HTTPArgument">
                <boolProp name="HTTPArgument.always_encode">true</boolProp>
                <stringProp name="Argument.value">${form_token}</stringProp>
                <stringProp name="Argument.metadata">=</stringProp>
                <boolProp name="HTTPArgument.use_equals">true</boolProp>
                <stringProp name="Argument.name">form_token</stringProp>
              </elementProp>
              <elementProp name="previousVersion" elementType="HTTPArgument">
                <boolProp name="HTTPArgument.always_encode">true</boolProp>
                <stringProp name="Argument.value">${previousVersion}</stringProp>
                <stringProp name="Argument.metadata">=</stringProp>
                <boolProp name="HTTPArgument.use_equals">true</boolProp>
                <stringProp name="Argument.name">previousVersion</stringProp>
              </elementProp>
              <elementProp name="editingVersionDate" elementType="HTTPArgument">
                <boolProp name="HTTPArgument.always_encode">true</boolProp>
                <stringProp name="Argument.value">${editingVersionDate}</stringProp>
                <stringProp name="Argument.metadata">=</stringProp>
                <boolProp name="HTTPArgument.use_equals">true</boolProp>
                <stringProp name="Argument.name">editingVersionDate</stringProp>
              </elementProp>
              <elementProp name="isNew" elementType="HTTPArgument">
                <boolProp name="HTTPArgument.always_encode">true</boolProp>
                <stringProp name="Argument.value">${isNew}</stringProp>
                <stringProp name="Argument.metadata">=</stringProp>
                <boolProp name="HTTPArgument.use_equals">true</boolProp>
                <stringProp name="Argument.name">isNew</stringProp>
              </elementProp>
              <elementProp name="xredirect" elementType="HTTPArgument">
                <boolProp name="HTTPArgument.always_encode">true</boolProp>
                <stringProp name="Argument.value">/${perf.context}/bin/view/${perf.savePage}_${__threadNum}</stringProp>
                <stringProp name="Argument.metadata">=</stringProp>
                <boolProp name="HTTPArgument.use_equals">true</boolProp>
                <stringProp name="Argument.name">xredirect</stringProp>
              </elementProp>
            </collectionProp>
          </elementProp>
          <stringProp name="HTTPSampler.path">${perf.context}/bin/save/${perf.savePage}_${__threadNum}</stringProp>
          <stringProp name="HTTPSampler.method">POST</stringProp>
          <boolProp name="HTTPSampler.follow_redirects">false</boolProp>
          <boolProp name="HTTPSampler.auto_redirects">false</boolProp>
          <boolProp name="HTTPSampler.use_keepalive">true</boolProp>
          <boolProp name="HTTPSampler.DO_MULTIPART_POST">false</boolProp>
        </HTTPSamplerProxy>
        <hashTree>
          <ResponseAssertion guiclass="AssertionGui" testclass="ResponseAssertion" testname="断言保存成功 302" enabled="true">
            <collectionProp name="Asserion.test_string">
              <stringProp name="49586">302</stringProp>
            </collectionProp>
            <stringProp name="Assertion.test_field">Assertion.response_code</stringProp>
            <boolProp name="Assertion.assume_success">false</boolProp>
            <intProp name="Assertion.test_type">8</intProp>
          </ResponseAssertion>
          <hashTree/>
        </hashTree>
      </hashTree>

      <!-- ========== 模式3 shared：不同人编辑保存同一页面（AJAX，冲突=409） ========== -->
      <ThreadGroup guiclass="ThreadGroupGui" testclass="ThreadGroup" testname="保存-多人同页(shared)" enabled="false">
        <stringProp name="ThreadGroup.on_sample_error">continue</stringProp>
        <elementProp name="ThreadGroup.main_controller" elementType="LoopController" guiclass="LoopControlPanel" testclass="LoopController" testname="Loop Controller" enabled="true">
          <boolProp name="LoopController.continue_forever">false</boolProp>
          <stringProp name="LoopController.loops">-1</stringProp>
        </elementProp>
        <stringProp name="ThreadGroup.num_threads">${__P(perf.threads.shared,50)}</stringProp>
        <stringProp name="ThreadGroup.ramp_time">${__P(perf.rampUp.save,60)}</stringProp>
        <boolProp name="ThreadGroup.scheduler">true</boolProp>
        <stringProp name="ThreadGroup.duration">${__P(perf.duration.save,300)}</stringProp>
        <stringProp name="ThreadGroup.delay"></stringProp>
        <boolProp name="ThreadGroup.same_user_on_next_iteration">true</boolProp>
      </ThreadGroup>
      <hashTree>
        <HTTPSamplerProxy guiclass="HttpTestSampleGui" testclass="HTTPSamplerProxy" testname="GET 编辑表单" enabled="true">
          <elementProp name="HTTPsampler.Arguments" elementType="Arguments" guiclass="HTTPArgumentsPanel" testclass="Arguments" testname="User Defined Variables" enabled="true">
            <collectionProp name="Arguments.arguments">
              <elementProp name="editor" elementType="HTTPArgument">
                <boolProp name="HTTPArgument.always_encode">false</boolProp>
                <stringProp name="Argument.value">wiki</stringProp>
                <stringProp name="Argument.metadata">=</stringProp>
                <boolProp name="HTTPArgument.use_equals">true</boolProp>
                <stringProp name="Argument.name">editor</stringProp>
              </elementProp>
            </collectionProp>
          </elementProp>
          <stringProp name="HTTPSampler.path">${perf.context}/bin/edit/${perf.savePage}</stringProp>
          <stringProp name="HTTPSampler.method">GET</stringProp>
          <boolProp name="HTTPSampler.follow_redirects">true</boolProp>
          <boolProp name="HTTPSampler.auto_redirects">false</boolProp>
          <boolProp name="HTTPSampler.use_keepalive">true</boolProp>
          <boolProp name="HTTPSampler.DO_MULTIPART_POST">false</boolProp>
        </HTTPSamplerProxy>
        <hashTree>
          <RegexExtractor guiclass="RegexExtractorGui" testclass="RegexExtractor" testname="提取 form_token" enabled="true">
            <stringProp name="RegexExtractor.useHeaders">false</stringProp>
            <stringProp name="RegexExtractor.refname">form_token</stringProp>
            <stringProp name="RegexExtractor.regex">name="form_token" value="([^"]*)"</stringProp>
            <stringProp name="RegexExtractor.template">$1$</stringProp>
            <stringProp name="RegexExtractor.default">TOKEN_NOT_FOUND</stringProp>
            <stringProp name="RegexExtractor.match_number">1</stringProp>
          </RegexExtractor>
          <hashTree/>
          <RegexExtractor guiclass="RegexExtractorGui" testclass="RegexExtractor" testname="提取 previousVersion" enabled="true">
            <stringProp name="RegexExtractor.useHeaders">false</stringProp>
            <stringProp name="RegexExtractor.refname">previousVersion</stringProp>
            <stringProp name="RegexExtractor.regex">name="previousVersion"[^>]*value="([^"]*)"</stringProp>
            <stringProp name="RegexExtractor.template">$1$</stringProp>
            <stringProp name="RegexExtractor.default">1.1</stringProp>
            <stringProp name="RegexExtractor.match_number">1</stringProp>
          </RegexExtractor>
          <hashTree/>
          <RegexExtractor guiclass="RegexExtractorGui" testclass="RegexExtractor" testname="提取 isNew" enabled="true">
            <stringProp name="RegexExtractor.useHeaders">false</stringProp>
            <stringProp name="RegexExtractor.refname">isNew</stringProp>
            <stringProp name="RegexExtractor.regex">name="isNew"[^>]*value="([^"]*)"</stringProp>
            <stringProp name="RegexExtractor.template">$1$</stringProp>
            <stringProp name="RegexExtractor.default">false</stringProp>
            <stringProp name="RegexExtractor.match_number">1</stringProp>
          </RegexExtractor>
          <hashTree/>
          <RegexExtractor guiclass="RegexExtractorGui" testclass="RegexExtractor" testname="提取 editingVersionDate" enabled="true">
            <stringProp name="RegexExtractor.useHeaders">false</stringProp>
            <stringProp name="RegexExtractor.refname">editingVersionDate</stringProp>
            <stringProp name="RegexExtractor.regex">name="editingVersionDate"[^>]*value="([^"]*)"</stringProp>
            <stringProp name="RegexExtractor.template">$1$</stringProp>
            <stringProp name="RegexExtractor.default">0</stringProp>
            <stringProp name="RegexExtractor.match_number">1</stringProp>
          </RegexExtractor>
          <hashTree/>
          <ResponseAssertion guiclass="AssertionGui" testclass="ResponseAssertion" testname="断言 200" enabled="true">
            <collectionProp name="Asserion.test_string">
              <stringProp name="49586">200</stringProp>
            </collectionProp>
            <stringProp name="Assertion.test_field">Assertion.response_code</stringProp>
            <boolProp name="Assertion.assume_success">false</boolProp>
            <intProp name="Assertion.test_type">8</intProp>
          </ResponseAssertion>
          <hashTree/>
          <HeaderManager guiclass="HeaderPanel" testclass="HeaderManager" testname="HTTP Header Manager" enabled="true">
            <collectionProp name="HeaderManager.headers">
              <elementProp name="" elementType="Header">
                <stringProp name="Header.name">Authorization</stringProp>
                <stringProp name="Header.value">Basic ${__base64Encode(${perf.userPrefix}${__threadNum}:${perf.pass})}</stringProp>
              </elementProp>
            </collectionProp>
          </HeaderManager>
          <hashTree/>
          <CookieManager guiclass="CookiePanel" testclass="CookieManager" testname="HTTP Cookie Manager" enabled="true">
            <collectionProp name="CookieManager.cookies"/>
            <boolProp name="CookieManager.clearEachIteration">false</boolProp>
            <boolProp name="CookieManager.controlledByThreadGroup">false</boolProp>
          </CookieManager>
          <hashTree/>
        </hashTree>
        <HTTPSamplerProxy guiclass="HttpTestSampleGui" testclass="HTTPSamplerProxy" testname="POST 保存(AJAX)" enabled="true">
          <elementProp name="HTTPsampler.Arguments" elementType="Arguments" guiclass="HTTPArgumentsPanel" testclass="Arguments" testname="User Defined Variables" enabled="true">
            <collectionProp name="Arguments.arguments">
              <elementProp name="content" elementType="HTTPArgument">
                <boolProp name="HTTPArgument.always_encode">true</boolProp>
                <stringProp name="Argument.value">${saveContent}</stringProp>
                <stringProp name="Argument.metadata">=</stringProp>
                <boolProp name="HTTPArgument.use_equals">true</boolProp>
                <stringProp name="Argument.name">content</stringProp>
              </elementProp>
              <elementProp name="title" elementType="HTTPArgument">
                <boolProp name="HTTPArgument.always_encode">true</boolProp>
                <stringProp name="Argument.value">保存性能测试</stringProp>
                <stringProp name="Argument.metadata">=</stringProp>
                <boolProp name="HTTPArgument.use_equals">true</boolProp>
                <stringProp name="Argument.name">title</stringProp>
              </elementProp>
              <elementProp name="form_token" elementType="HTTPArgument">
                <boolProp name="HTTPArgument.always_encode">true</boolProp>
                <stringProp name="Argument.value">${form_token}</stringProp>
                <stringProp name="Argument.metadata">=</stringProp>
                <boolProp name="HTTPArgument.use_equals">true</boolProp>
                <stringProp name="Argument.name">form_token</stringProp>
              </elementProp>
              <elementProp name="previousVersion" elementType="HTTPArgument">
                <boolProp name="HTTPArgument.always_encode">true</boolProp>
                <stringProp name="Argument.value">${previousVersion}</stringProp>
                <stringProp name="Argument.metadata">=</stringProp>
                <boolProp name="HTTPArgument.use_equals">true</boolProp>
                <stringProp name="Argument.name">previousVersion</stringProp>
              </elementProp>
              <elementProp name="editingVersionDate" elementType="HTTPArgument">
                <boolProp name="HTTPArgument.always_encode">true</boolProp>
                <stringProp name="Argument.value">${editingVersionDate}</stringProp>
                <stringProp name="Argument.metadata">=</stringProp>
                <boolProp name="HTTPArgument.use_equals">true</boolProp>
                <stringProp name="Argument.name">editingVersionDate</stringProp>
              </elementProp>
              <elementProp name="isNew" elementType="HTTPArgument">
                <boolProp name="HTTPArgument.always_encode">true</boolProp>
                <stringProp name="Argument.value">${isNew}</stringProp>
                <stringProp name="Argument.metadata">=</stringProp>
                <boolProp name="HTTPArgument.use_equals">true</boolProp>
                <stringProp name="Argument.name">isNew</stringProp>
              </elementProp>
              <elementProp name="ajax" elementType="HTTPArgument">
                <boolProp name="HTTPArgument.always_encode">false</boolProp>
                <stringProp name="Argument.value">1</stringProp>
                <stringProp name="Argument.metadata">=</stringProp>
                <boolProp name="HTTPArgument.use_equals">true</boolProp>
                <stringProp name="Argument.name">ajax</stringProp>
              </elementProp>
            </collectionProp>
          </elementProp>
          <stringProp name="HTTPSampler.path">${perf.context}/bin/save/${perf.savePage}?async=true</stringProp>
          <stringProp name="HTTPSampler.method">POST</stringProp>
          <boolProp name="HTTPSampler.follow_redirects">false</boolProp>
          <boolProp name="HTTPSampler.auto_redirects">false</boolProp>
          <boolProp name="HTTPSampler.use_keepalive">true</boolProp>
          <boolProp name="HTTPSampler.DO_MULTIPART_POST">false</boolProp>
        </HTTPSamplerProxy>
        <hashTree>
          <ResponseAssertion guiclass="AssertionGui" testclass="ResponseAssertion" testname="断言 200 或 409" enabled="true">
            <collectionProp name="Asserion.test_string">
              <stringProp name="49586">^(200|409)$</stringProp>
            </collectionProp>
            <stringProp name="Assertion.test_field">Assertion.response_code</stringProp>
            <boolProp name="Assertion.assume_success">false</boolProp>
            <intProp name="Assertion.test_type">2</intProp>
          </ResponseAssertion>
          <hashTree/>
        </hashTree>
      </hashTree>

      <!-- ========== 结果监听器 ========== -->
      <ResultCollector guiclass="SimpleDataWriter" testclass="ResultCollector" testname="结果写入 CSV" enabled="true">
        <boolProp name="ResultCollector.error_logging">false</boolProp>
        <objProp>
          <name>saveConfig</name>
          <value class="SampleSaveConfiguration">
            <time>true</time><latency>true</latency><timestamp>true</timestamp>
            <success>true</success><label>true</label><code>true</code><message>true</message>
            <threadName>true</threadName><dataType>true</dataType><encoding>false</encoding>
            <assertions>true</assertions><subresults>true</subresults><responseData>false</responseData>
            <samplerData>false</samplerData><xml>false</xml><fieldNames>true</fieldNames>
            <responseHeaders>false</responseHeaders><requestHeaders>false</requestHeaders>
            <responseDataOnError>false</responseDataOnError><saveAssertionResultsFailureMessage>true</saveAssertionResultsFailureMessage>
            <assertionsResultsToSave>0</assertionsResultsToSave><bytes>true</bytes><sentBytes>true</sentBytes>
            <url>true</url><threadCounts>true</threadCounts><idleTime>true</idleTime><connectTime>true</connectTime>
          </value>
        </objProp>
        <stringProp name="filename">${__P(perf.resultsFile,results/result.csv)}</stringProp>
      </ResultCollector>
      <hashTree/>
      <ResultCollector guiclass="SummaryReport" testclass="ResultCollector" testname="汇总报告" enabled="true">
        <boolProp name="ResultCollector.error_logging">false</boolProp>
        <objProp>
          <name>saveConfig</name>
          <value class="SampleSaveConfiguration">
            <time>true</time><latency>true</latency><timestamp>true</timestamp>
            <success>true</success><label>true</label><code>true</code><message>true</message>
            <threadName>true</threadName><dataType>true</dataType><encoding>false</encoding>
            <assertions>true</assertions><subresults>true</subresults><responseData>false</responseData>
            <samplerData>false</samplerData><xml>false</xml><fieldNames>true</fieldNames>
            <responseHeaders>false</responseHeaders><requestHeaders>false</requestHeaders>
            <responseDataOnError>false</responseDataOnError><saveAssertionResultsFailureMessage>true</saveAssertionResultsFailureMessage>
            <assertionsResultsToSave>0</assertionsResultsToSave><bytes>true</bytes><sentBytes>true</sentBytes>
            <url>true</url><threadCounts>true</threadCounts><idleTime>true</idleTime><connectTime>true</connectTime>
          </value>
        </objProp>
        <stringProp name="filename"></stringProp>
      </ResultCollector>
      <hashTree/>
    </hashTree>
  </hashTree>
</jmeterTestPlan>
```

- [ ] **Step 2: 良构验证**

```bash
cd docs/perf-test && xmllint --noout jmeter/04-edit-save-simple.jmx
```

Expected: 无输出。

- [ ] **Step 3: 冒烟 solo 模式**（先确保 Task 2 已造用户，`perf.savePage` 指向已存在页面 Sandbox/TestPage1 以最快验证；执行时临时覆盖 `-Jperf.savePage=Sandbox/TestPage1`）

```bash
cd docs/perf-test
jmeter -n -t jmeter/04-edit-save-simple.jmx -q perf.properties \
  -Jperf.savePage=Sandbox/TestPage1 -Jperf.threads.solo=1 \
  -Jperf.rampUp.save=1 -Jperf.duration.save=15 \
  -l results/04-solo.csv
tail -5 results/04-solo.csv
```

Expected: GET 编辑表单 200/true；POST 保存 302/true。检查 Sandbox/TestPage1 版本增加了：
`curl -s -u admin:admin "http://localhost:8080/xwiki/rest/wikis/xwiki/spaces/Sandbox/pages/TestPage1" | grep -o '"version":"[^"]*"'`

- [ ] **Step 4: 冒烟 shared 模式**（临时启用 shared TG：`enabled="false"` → `enabled="true"` 只改 shared TG；solo 改回 `enabled="false"`）

```bash
cd docs/perf-test
jmeter -n -t jmeter/04-edit-save-simple.jmx -q perf.properties \
  -Jperf.savePage=Sandbox/TestPage1 -Jperf.threads.shared=4 \
  -Jperf.rampUp.save=2 -Jperf.duration.save=15 \
  -l results/04-shared.csv
grep -c "409" results/04-shared.csv; tail -5 results/04-shared.csv
```

Expected: POST 保存(AJAX) 出现 200 与 409（冲突预期，断言通过 → success=true）；409 数量 > 0 说明冲突路径真实生效。

- [ ] **Step 5: 恢复 TG 启用状态**（shared 改回 `enabled="false"`，保持 solo `enabled="true"`）

- [ ] **Step 6: Commit**

```bash
cd /Users/noaharno/programer/sourcecode/xwiki_parent/xwiki-platform
git add docs/perf-test/jmeter/04-edit-save-simple.jmx
git commit -m "性能测试：场景4 简单内容编辑保存脚本（solo/split/shared 三模式）

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 8: 05-edit-save-complex.jmx

**Files:**
- Create: `docs/perf-test/jmeter/05-edit-save-complex.jmx`
- Test: `xmllint` + 冒烟

**Interfaces:**
- Consumes: Task 7 的完整结构；`data/save-content-complex.txt`。

- [ ] **Step 1: 复制 04 → 05 并修改**

```bash
cp jmeter/04-edit-save-simple.jmx jmeter/05-edit-save-complex.jmx
```

修改（精确替换）：
1. `testname="04 简单内容编辑保存"` → `testname="05 复杂内容编辑保存"`
2. UDV `perf.saveContentFile` 的默认值：`${__P(perf.saveContentFile,data/save-content-simple.txt)}` → `${__P(perf.saveContentFile,data/save-content-complex.txt)}`
3. `perf.savePage` 默认值：`${__P(perf.savePage,PerfTest/SimplePage)}` → `${__P(perf.savePage,PerfTest/ComplexPage)}`
4. 三个 TG 名 `保存-` 前缀不变（模式语义相同）

- [ ] **Step 2: 良构 + 冒烟**

```bash
cd docs/perf-test
xmllint --noout jmeter/05-edit-save-complex.jmx
jmeter -n -t jmeter/05-edit-save-complex.jmx -q perf.properties \
  -Jperf.savePage=Sandbox/TestPage2 -Jperf.threads.solo=1 \
  -Jperf.rampUp.save=1 -Jperf.duration.save=15 \
  -l results/05.csv
tail -5 results/05.csv
```

Expected: GET 200/true，POST 302/true。

- [ ] **Step 3: Commit**

```bash
cd /Users/noaharno/programer/sourcecode/xwiki_parent/xwiki-platform
git add docs/perf-test/jmeter/05-edit-save-complex.jmx
git commit -m "性能测试：场景5 复杂内容编辑保存脚本（宏密集内容模板）

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

### Task 9: 本地全量验证 + README 完善 + 收尾

**Files:**
- Modify: `docs/perf-test/README.md`（补充实测命令与验证结果）

- [ ] **Step 1: 造数全量（500 空间 + 50 用户）**

```bash
cd docs/perf-test/scripts
bash prepare-data.sh --user-count 50 --space-count 500
```

Expected: 无失败输出；耗时约 2-5 分钟。抽查：
```bash
curl -s -o /dev/null -w '%{http_code}\n' -u admin:admin http://localhost:8080/xwiki/bin/view/MenuSpace0500/
curl -s -o /dev/null -w '%{http_code}\n' -u PerfUser50:PerfPass123 http://localhost:8080/xwiki/bin/view/Sandbox/TestPage1
```
Expected: 200 / 200。

- [ ] **Step 2: 场景 6 真实 500 菜单验证**

```bash
cd docs/perf-test
jmeter -n -t jmeter/06-homepage-500menu.jmx -q perf.properties \
  -Jperf.threads=2 -Jperf.rampUp=2 -Jperf.duration=20 -l results/06-full.csv
tail -3 results/06-full.csv
curl -s -u PerfTestUser:PerfPass123 http://localhost:8080/xwiki/bin/view/Main/ | grep -c "MenuSpace0" 
```

Expected: 无 ERROR；grep 计数 ≥ 100（500 空间渲染进导航树）。

- [ ] **Step 3: 全场景冒烟矩阵**（每个 jmx 最小配置跑通）

```bash
cd docs/perf-test
for t in 01-homepage 02-page-simple 03-page-complex 06-homepage-500menu; do
  jmeter -n -t jmeter/$t.jmx -q perf.properties -Jperf.threads=2 -Jperf.rampUp=2 -Jperf.duration=10 -l results/$t-smoke.csv
done
```

Expected: 全部无 ERROR。

- [ ] **Step 4: 数据清理 + 验证**

```bash
cd docs/perf-test/scripts
bash cleanup-data.sh --user-count 50 --space-count 500
curl -s -o /dev/null -w '%{http_code}\n' -u admin:admin http://localhost:8080/xwiki/bin/view/MenuSpace0500/
curl -s -o /dev/null -w '%{http_code}\n' -u admin:admin http://localhost:8080/xwiki/bin/view/XWiki/PerfTestUser
```

Expected: 404 / 404。

- [ ] **Step 5: 再次造数（为后续真实压测保留环境）并完善 README**

```bash
cd docs/perf-test/scripts && bash prepare-data.sh --user-count 50 --space-count 500
```

README 补充（追加到"运行"一节之后）：
- 本机冒烟命令示例（含 `-J` 覆盖并发参数）
- 说明：保存测试会污染目标页面版本，建议用独立 `perf.savePage`（默认 PerfTest/SimplePage、PerfTest/ComplexPage）

- [ ] **Step 6: Commit 全部收尾**

```bash
cd /Users/noaharno/programer/sourcecode/xwiki_parent/xwiki-platform
git add docs/perf-test/
git commit -m "性能测试：JMeter 脚本与造数脚本本地全量验证通过（6 场景 × 3 保存模式）

Co-Authored-By: Claude Fable 5 <noreply@anthropic.com>"
```

---

## Self-Review 记录

- **Spec 覆盖**：6 场景（01-06 jmx）✓；3 保存模式（04/05 内 3 TG）✓；造数（用户组+500 空间）✓；清理 ✓；参数集中 perf.properties ✓；页面 URL 占位 ✓；shared 冲突语义（实测 409+JSON，断言 `^(200|409)$`）✓。
- **占位符扫描**：无 TBD/TODO；所有代码完整。
- **类型一致性**：提取器变量名 `form_token/previousVersion/isNew/editingVersionDate` 在 04/05 中一致；参数名 `perf.savePage/perf.saveContentFile/perf.threads.solo|split|shared` 在 jmx 与 properties 中一致；`authHeader` 机制在 01（空）与 02/03/06（Basic ...）中一致。
- **已知注意点**：Task 2 Step 1 的脚本草稿含一个重复的 `page_exists` 函数定义（第二步明确删除）；`create_page` 的 space 路径替换 `${space//.//}` 对 `MenuSpace0001` 无副作用、对嵌套空间正确；02/03 冒烟依赖 Sandbox/TestPage1/2 存在（本机已有，实测 200）。
