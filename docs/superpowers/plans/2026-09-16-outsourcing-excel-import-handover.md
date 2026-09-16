# 科技外包用户 Excel 全量导入 — 交接与验证清单

> 2026-09-16 | 对应设计 `2026-09-15-outsourcing-excel-import-design.md`、计划 `2026-09-15-outsourcing-excel-import.md`

本文档给**需要在真实实例上完成部署与端到端验证**的人。
代码实现与单元测试已全部完成；**部署与端到端验证需要有人操作运行中的 XWiki 实例**。

---

## 1. 交付内容

在既有 `xwiki-platform-oa-sync` 模块内新增一条链路：

```
页面上传 .xlsx → REST 端点（管理员）→ POI 解析（B 列 staff_id）
  → 线程池并发建号（每任务独立执行上下文）→ 串行批量入组「开发中心科技外包组」
  → 汇总统计 → 写一条 OASyncResult 同步记录
```

**与 MQ 增量链路共用同一份建号/入组实现**（`DoapUserSynchronizer`），
两条链路在结构上不可能漂移。

| 变更 | 文件 |
|---|---|
| 新增 | `internal/excel/OutsourcingExcelParser.java`、`OutsourcingExcelParseResult.java`、`ExcelImportResult.java`、`ExcelImportExecutor.java`、`OutsourcingExcelImportService.java` |
| 改 | `internal/mq/DoapUserSynchronizer.java`（抽出可复用的建号/批量入组；**修 2 个线上 bug**） |
| 改 | `internal/rest/OASyncRESTResource.java`（新增上传端点；**给 `trigger` 补上缺失的鉴权**） |
| 改 | `internal/configuration/DefaultOASyncConfiguration.java`、api 的 `OASyncConfiguration.java`（`xwiki.oa-sync.excel.threads`） |
| 改 | `xwiki-platform-oa-sync-ui/.../OASync/WebHome.xml`（上传 UI + 管理员门禁） |
| 改 | `pom.xml`（`poi-ooxml`）、`components.txt`（3 个新组件）、`logback-test.xml`、`xwiki.cfg.vm` |

### 顺带修复的 3 个既有线上问题（**需要知悉**）

| # | 问题 | 影响 |
|---|---|---|
| 1 | `DoapUserSynchronizer.addMemberIfMissing` 用 `getXObject`（返回该类的**第一个**对象）取对象，新增成员时会**覆盖第一个已有成员** | 「开发中心科技外包组」**永远只能容纳一个外包用户**。线上那个组当前很可能只剩最后一个人。**本次全量导入会把所有人重新加回来，不需要额外的一次性数据修复** |
| 2 | 组写入是"读-改-写"且**没有任何互斥**，而 MQ 消费者是并发多线程的（`consume-thread-max` 默认 8） | 两个线程各写整篇 ⇒ 后者覆盖前者 ⇒ 某用户"建号成功 + 日志 success + MQ ACK"**却不在组里**（静默丢权限）。已加 JVM 级锁；**注意该锁不覆盖跨 JVM 并发**（多节点各起不同消费组时仍需数据库级并发控制） |
| 3 | `OASyncRESTResource.trigger()`（`POST /rest/oa-sync/trigger`）**完全没有权限校验**，且内部服务也没有 | 任何已登录用户都能触发全量行员/外包同步。已补管理员校验 |

---

## 2. 构建与测试状态（已完成，本地实测）

```
Tests run: 118, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS（api / default / ui 三模块）
```

产物：

| 产物 | 大小 |
|---|---|
| `xwiki-platform-oa-sync-api/target/xwiki-platform-oa-sync-api-18.1.0.jar` | 23,033 B |
| `xwiki-platform-oa-sync-default/target/xwiki-platform-oa-sync-default-18.1.0.jar` | 93,534 B |
| `xwiki-platform-oa-sync-ui/target/xwiki-platform-oa-sync-ui-18.1.0.xar` | 4,408 B |

**构建必须用 Java 21**：仓库 enforcer 要求最低 21，JDK 17 会直接 `You must build with Java 21 minimum!`

```bash
export JAVA_HOME=$(/usr/libexec/java_home -v 21)
```

---

## 3. 部署（需要你执行）

### 3.1 拷 jar

```bash
SRC=/Users/noaharno/programer/sourcecode/xwiki_parent/xwiki-platform/xwiki-platform-core/xwiki-platform-oa-sync
DST=/Users/noaharno/programer/software/xwiki/apache-tomcat-11.0.22/webapps/xwiki/WEB-INF/lib

cp "$SRC/xwiki-platform-oa-sync-api/target/xwiki-platform-oa-sync-api-18.1.0.jar" \
   "$SRC/xwiki-platform-oa-sync-default/target/xwiki-platform-oa-sync-default-18.1.0.jar" \
   "$DST/"
```

当前该目录里是 **8月14 的旧 jar**（22,990 / 71,223 B），会被覆盖成新的 23,033 / 93,534 B。

**POI 的运行时依赖已经全部就位**（`poi-5.5.1`、`poi-ooxml-5.5.1`、`poi-ooxml-lite-5.5.1`、
`poi-scratchpad-5.5.1`、`xmlbeans-5.3.0`、`curvesapi-1.08`、`SparseBitSet-1.3`），**不需要额外拷任何 jar**。

> 小提示：同目录存在成对的 `.xed` 校验文件（XWiki 用来检测已装扩展是否被改动）。
> 覆盖 jar 后它们的校验值会过期，扩展管理器可能提示"文件已被修改"。属预期现象，
> 要么忽略，要么删掉那两条 `xwiki-platform-oa-sync-*.xed`。

### 3.2 导入 XAR（页面 UI）

`xwiki-platform-oa-sync-ui-18.1.0.xar` 需要装进 wiki 才能看到新页面区块。
若你之前的部署方式是"随发行包 WAR 一起构建"，重新构建 WAR 即可；
若按 `docs/doap-mq-user-sync.md` 的手动补 jar 方式，则需要另外导入这个 XAR
（管理 → 扩展 → 安装，或把它放进 `WEB-INF/` 后由扩展管理器安装）。

### 3.3 重启

```bash
/Users/noaharno/programer/software/xwiki/apache-tomcat-11.0.22/bin/shutdown.sh
/Users/noaharno/programer/software/xwiki/apache-tomcat-11.0.22/bin/startup.sh
```

**启动后先看一眼日志**，确认没有组件注册或依赖缺失的报错：

```bash
tail -n 200 /Users/noaharno/programer/software/xwiki/apache-tomcat-11.0.22/logs/catalina.out | grep -iE "oa.sync|oa-sync|NoClassDefFound|ClassNotFound"
```

---

## 4. 端到端验证清单（需要你执行）

准备一个真实/样例 Excel（**单 sheet、第 1 行表头、`staff_id` 在 B 列**），记作 `/tmp/outsourcing.xlsx`。

### 4.1 正向路径（**最重要**）

```bash
curl -sS -w '\nHTTP %{http_code}\n' -u "Admin:admin" -X POST \
  -H "Content-Type: application/octet-stream" \
  --data-binary @/tmp/outsourcing.xlsx \
  http://localhost:8080/xwiki/rest/oa-sync/outsourcing-excel
```

Expected: **HTTP 200** + 合法 JSON，形如
`{"status":"SUCCESS","total":N,"created":X,"existed":Y,"joinedGroup":Z,"alreadyMember":W,"failed":0,"errors":[]}`

**记录总耗时**（加 `-w '%{time_total}\n'`），用于判断默认 4 线程是否合适。

> 为什么必须单独验这一步：单测里 `Content-Type` 是手工传进去的，
> **测不到 `@HeaderParam("Content-Type")` 的绑定在真实容器里是否成立**。
> 这是本次唯一无法用单测覆盖的运行时假设（失败模式是 fail-closed：所有上传都 415，
> 不是安全漏洞，但会直接让功能不可用）。

再用 **`.xlsx` 标准 MIME** 传一次（客户端直接 `fetch(body:file)` 会走这条），也必须成功：

```bash
curl -sS -w '\nHTTP %{http_code}\n' -u "Admin:admin" -X POST \
  -H "Content-Type: application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" \
  --data-binary @/tmp/outsourcing.xlsx \
  http://localhost:8080/xwiki/rest/oa-sync/outsourcing-excel
```

Expected: **HTTP 200**（幂等，`created` 应为 0）

### 4.2 幂等复跑

再执行一次 4.1 第一条命令。Expected: `"created":0` 且 `"joinedGroup":0`，`"alreadyMember"` 等于去重后的工号数。

### 4.3 校验组内成员数（**唯一的人数权威来源**）

打开 `XWiki.开发中心科技外包组`，确认 XWikiGroups 对象数量 = Excel 去重后的工号数
（若该组此前已有成员，则是「原有 + 新增」的总数）。

抽样 3 个工号，确认：
- `http://localhost:8080/xwiki/bin/view/XWiki/<staffId>` 页面存在且是用户资料页
- 用户资料页的「组」标签里能看到「开发中心科技外包组」

> ⚠️ 不要用 API 返回的 `joinedGroup` 当作人数校验——它是按 `member` **字符串**比较得出的**参考值**，
> 组里若已存在全限定形式（`xwiki:XWiki.10086`）会偏大 1~N（不影响权限）。

### 4.4 权限

```bash
# 非管理员 → 403
curl -sS -o /dev/null -w '%{http_code}\n' -u "someNonAdminUser:password" -X POST \
  -H "Content-Type: application/octet-stream" \
  --data-binary @/tmp/outsourcing.xlsx \
  http://localhost:8080/xwiki/rest/oa-sync/outsourcing-excel

# 既有的 trigger 端点现在也应拒绝非管理员（本次新补的鉴权）
curl -sS -o /dev/null -w '%{http_code}\n' -u "someNonAdminUser:password" -X POST \
  http://localhost:8080/xwiki/rest/oa-sync/trigger
```

Expected: 两次都是 **403**

### 4.5 坏输入

```bash
# 非法文件 → 400（不是 500）
curl -sS -o /dev/null -w '%{http_code}\n' -u "Admin:admin" -X POST \
  -H "Content-Type: application/octet-stream" \
  --data-binary "not-an-excel" \
  http://localhost:8080/xwiki/rest/oa-sync/outsourcing-excel

# 缺 Content-Type → 415
curl -sS -o /dev/null -w '%{http_code}\n' -u "Admin:admin" -X POST \
  --data-binary @/tmp/outsourcing.xlsx \
  http://localhost:8080/xwiki/rest/oa-sync/outsourcing-excel
```

Expected: 依次 `400`、`415`

### 4.6 超限上传（单测覆盖不到）

```bash
dd if=/dev/zero of=/tmp/big.xlsx bs=1m count=50
curl -sS -w '\nHTTP %{http_code}\n' -u "Admin:admin" -X POST \
  -H "Content-Type: application/octet-stream" \
  --data-binary @/tmp/big.xlsx \
  http://localhost:8080/xwiki/rest/oa-sync/outsourcing-excel
```

Expected: **400** 且 body 含"上传文件超过体积上限"。

> 端点读到 10 MiB + 1 字节即返回，**不排空剩余 body**；Tomcat 默认 `maxSwallowSize = 2 MiB`，
> 传 50 MB 时客户端**可能**在写完前被 reset，于是看到的是 `Failed to fetch` 而不是友好的 400。
> **请把实际观察到的行为记下来**。若确实被 reset，正确做法是调大 Tomcat 的 `maxSwallowSize`
> （或把闸门改成先看 `Content-Length`），**不要**在前端 JS 里复制 10 MiB 常量做预检。

### 4.7 页面

访问 `http://localhost:8080/xwiki/bin/view/OASync/WebHome`：

- 「Excel 全量导入：科技外包」区块存在，文件选择 + 按钮可用
- 上传后出现完成提示，**且摘要在页面上保留**（不再只靠会自动消失的通知）
- `最近同步记录` 表格出现一条 `outsourcing-excel` 记录
- **用非管理员账号**访问同一 URL：应当看不到控件（或明确提示需要管理员权限），
  而不是看到一个点了就报 `[undefined] FAILURE` 的按钮

---

## 5. 需要你决策的两件事

### 5.1 checkstyle：本模块从未通过，且与中文文档冲突

实测（`mvn verify` **不** skip checkstyle）：

| 范围 | 违规数 |
|---|---|
| `oa-sync-default` 在**本次改动之前**的基线 | **98 条**（BUILD FAILURE） |
| 本次净增 | **11 条** |
| 对照 `xwiki-platform-livedata-rest` | **0 条**（非误报） |

净增的 11 条里，有两类**会持续复发**且**不该"修"**：

- **`JavadocStyle` ×3**：根因是 checkstyle 的首句结束符正则**不接受中文句号 `。`**，
  而本 fork 的 javadoc 全是中文。真去"修"只能往中文文档里塞英文句号。
  正确修法是让配置接受 `。！？`，但配置文件在上游 `xwiki-commons-tool-verification-resources` JAR 里。
- **`VisibilityModifier` ×2 + `DeclarationOrder` ×2**：同一根因——**包级可见的 `@Inject` 字段**，
  而这是本模块"new 出组件再直接赋值字段"的测试约定要求的。

**建议**：现在一条都不修，单独立项处理三件事——
(a) 把 `。` 加进 `JavadocStyle.endOfSentenceFormat`；
(b) 决定 `oa-sync-default` 是否纳入 checkstyle；
(c) 然后一次性归零 98+11 条。

**决策的第一问应该是：CI 到底对 `oa-sync-default` 跑不跑 checkstyle？**
（本次所有构建命令都带 `-Dxwiki.checkstyle.skip=true`，沿用 `AGENTS.md` 记录的写法，
所以**本地全绿 ≠ CI 全绿**。这也是该模块能带着 98 条违规活到今天的原因。）

### 5.2 页面数据的可见性

`OASync.WebHome` 的 `<hidden>true</hidden>` **只管导航可见性，不设访问权**。
下方"最近同步记录"表格（`errorLog` 里含**工号**）对**任何已登录用户**直接开 URL 都可见。
`#if` 门禁只隐藏了操作控件，不是访问控制。

**若这些数据敏感，需要的是真正的页面访问权限**（顺带让 `#if` 退化为纵深防御）。
本次未做，因为这是产品/运维决策。

---

## 6. 已知限制（记录，非缺陷）

| # | 限制 | 说明 |
|---|---|---|
| 1 | **子 wiki 作用域** | `/rest/oa-sync/...` 路径不带 `/wikis/{id}`，整个能力今天就是**主 wiki** 作用域的。页面门禁用 `hasWikiAdminRights()` 与端点的 wiki 级 `checkAccess` 对齐；唯一残留是"子 wiki 管理员会看到按钮但 403 / 主 wiki 管理员在子 wiki 看不到按钮"。真要支持子 wiki 需先决定"每 wiki 一份 OASync"还是"全局一份" |
| 2 | **渲染缓存** | `DefaultRenderingCache` 的缓存 key **不含用户**，而页面里有用户相关的 `#if`。默认 `core.renderingcache.enabled=false` 且未配置 pattern，今天无影响；**启用渲染缓存时不要把 `OASync.WebHome` 匹配进 `core.renderingcache.documents`** |
| 3 | **并发入组的锁是 JVM 级** | 单 Tomcat 足够；多节点各起**不同消费组**的 consumer 并发写同一个组文档时，仍需数据库级并发控制 |
| 4 | **`joinedGroup` 是参考值** | 按 `member` 字符串比较得出，组内已有全限定形式时会偏大 1~N（不影响权限、二次调用幂等）。人数校验以组文档为准 |
| 5 | **`failCount` 与 `status` 是不同维度** | `status` 运行级（有 error 即 `PARTIAL_FAILURE`），`failCount` 用户级（沿用 api javadoc 与 FTP 链路口径）。所以"PARTIAL_FAILURE 且 failCount=0"（如入组整体失败）是正常的，原因看 `errorLog` 的 `[错误]` 条目 |

---

## 7. 回滚

```bash
# 恢复 8月14 的旧 jar（若你有备份），或从 git 重新构建上一个提交
cd /Users/noaharno/programer/sourcecode/xwiki_parent/xwiki-platform
git log --oneline -- xwiki-platform-core/xwiki-platform-oa-sync | head
```

若要临时停用上传端点，把页面上的 Excel 区块注释掉即可（REST 端点仍存在，但需要管理员权限才能调用）。
