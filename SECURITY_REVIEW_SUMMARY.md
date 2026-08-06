# XWiki 二开系统安全测试报告复核汇总

> 生成日期：2026-08-05
> 适用范围：公司基于开源 XWiki 的二开系统（替代 Confluence）
> 复核方式：对照部署代码/依赖源码逐项分析 IAST/SAST 报告，并对真实问题完成修复与测试

---

## 1. 概述

安全测试平台（IAST/SAST）共报告 **6 项** 漏洞/风险，涉及路径穿越、HQL 注入、XXE、SSTI。

**总体结论：1 项真实（已完成修复），5 项为误报。**

误报的共性根因：

1. **使用管理员账号测试**：管理员持有编程权限（PR）/脚本权限（SCRIPT），使所有"需要 PR 才执行"的路径（HQL 校验跳过、Velocity 脚本执行、模板作者检查）全部走通，制造出"污点确认执行"的假象；而真实攻击者（无 PR）会在对应权限门处被拦截。
2. **粗粒度污点传播**：工具把"请求参数 → 方法参数 → 敏感 sink（`createQuery` / `SAXParser.parse` / `evaluate`）"标为漏洞，但不识别中间存在的**参数化绑定、白名单/正则校验、`isSafe` 校验、容器/Tika 防护配置、权限门**。
3. **版本不匹配**：部分报告引用旧版本源码的类名/行号（如 `XMLReaderUtils`），与实际部署版本（XWiki 18.1.0 / Tika 3.2.3）不符。

---

## 2. 环境信息

| 组件 | 版本 | 说明 |
|---|---|---|
| XWiki Platform | 18.1.0 | 二开基线 |
| Tomcat | 11.0.22 | 本地部署，`allowLinking` 未开启（默认安全） |
| xwiki-commons-xml | 18.1.0 | XML 解析，已含 XXE 防护 |
| Apache Tika | 3.2.3 | 附件 MIME 检测，已含 XXE 防护 |
| xwiki-commons-velocity | 18.1.0 | Velocity 引擎，已含权限门 |

---

## 3. 逐项分析

### 3.1 目录穿越：`AbstractResourceSkin.java:146` —— 真实，已修复

| 项目 | 内容 |
|---|---|
| 报告 | `/xwiki/bin/get/安全测试空间-限定权限/WebHome?xpage=../../WEB-INF/xwiki.cfg`，`xpage` 参数 → `Paths.get()` |
| 链路 | `xpage` → `Utils.getPage()` → `parseTemplate()` → `Skin.getResource()` → `AbstractResourceSkin.getSkinResourcePath()` → `Paths.get(resourcePath)` |
| 原代码问题 | ① 校验在 `Paths.get` **之后**（用户输入已进入敏感函数）；② 返回的是**未归一化原始路径**，与下游（ClassLoader / Servlet 容器 / URL 解析）二次归一化结果可能不一致，存在绕过面 |
| 修复 | **源头**：`com/xpn/xwiki/web/Utils.java` 的 `getPage()` 拒绝含 `..` 路径段、绝对路径（`/`、`\` 开头）、含 `\` 的 `xpage`，回退默认模板并记 WARN；**汇点**：`AbstractResourceSkin.getSkinResourcePath()` 在 `Paths.get` 前增加 `isPathTraversalAttempt` 校验，保留原有 `normalize() + startsWith(skinFolder)` 兜底 |
| 测试 | 新增/更新 `ClassLoaderSkinTest`、`EnvironmentSkinTest`、`UtilsTest`，16 个用例全部通过（覆盖逃逸 `..`、不逃逸 `..`、绝对路径、反斜杠） |
| 状态 | ✅ 已修复 |

### 3.2 目录穿越：`AbstractFileResourceSet.java:61` —— 误报

| 项目 | 内容 |
|---|---|
| 报告 | `file()` 方法中 `new File(fileBase, name)` 的路径穿越 |
| 实际 | **Tomcat 容器内置类**（`org.apache.catalina.webresources`，位于 `tomcat-embed-core`），**不是二开项目代码** |
| 防护 | `file.getCanonicalPath()`（解析 `..` 与符号链接）与 `canonicalBase` 前缀比对 + 绝对路径归一化 + 剩余路径必须以 `/` 开头 + 大小写检查 |
| 配置核实 | `apache-tomcat-11.0.22/conf/` 与 `webapps/xwiki/META-INF/context.xml` 均未开启 `allowLinking`（默认 false），防护完整生效 |
| 结论 | 不改代码；SAST 将 `org.apache.catalina.*` 加入白名单，保持 Tomcat 版本更新 |

### 3.3 HQL 注入：`XWikiHibernateStore.java:2445 / searchDocuments:2835`、`HqlQueryExecutor.java:254` —— 误报

| 项目 | 内容 |
|---|---|
| 报告 | liveData REST 的 `query` / `dir` / `sort` 参数 → `session.createQuery(statement)` |
| 链路 | `/rest/liveData/sources/liveTable/entries` → LiveDataQuery → liveTable source → 模板渲染 → `$services.query.hql($sql)` → `HqlQueryExecutor:254`；或 `$xwiki.searchDocuments` → `XWikiHibernateStore:2835 → 2445` |
| 模板层防护 | `dir` 全模板 asc/desc 白名单；`sort` 正则 `^(doc\.)?\w+$` 或 `checkOrderBySafe`（`\A(\w+\.)?\w+(\s+\w+)?\z`）或 `validSortFields` 白名单；过滤值全部 `?N` / `:name` 占位 + `bindValues` 参数化；列名 `replaceAll('\W','')` 消毒 |
| 脚本 API 层 | `api/XWiki` 所有 `searchDocuments` 重载均有 `checkSearchQueryAllowed`：**无 PR 用户**的 `wheresql` 拼成完整 HQL 后过 `HQLStatementValidator.isSafe`（仅允许合法 select），否则抛 `requires programming right` |
| 执行层 | `HqlQueryExecutor.checkAllowed`：无 PR 用户每条 HQL 过 `isSafe` 校验 |
| 已知漏洞 | CVE-2025-32429（`getdeleteddocuments.vm` 的 `sort` 注入，CVSS 9.3，正是该 liveData 端点）已在 16.10.6 / 17.3.0 修复，18.1.0 已包含（`checkOrderBySafe`） |
| 实证 | 本地运行 `checkOrderBySafe` / sort 正则 / dir 白名单对 `' OR 1 = 1 --`、`';DELETE FROM User; --` 的判定：**全部被拒绝或替换为 `desc`** |
| 为什么 IAST 报"执行到 2835" | 管理员账号有 PR，`checkSearchQueryAllowed` 的 `if (!hasProgrammingRights())` 跳过校验，payload 真实执行到 `session.createQuery`；但攻击者无 PR，会被拦截 |
| 结论 | 对无 PR 攻击者不可利用；REST 端点无 `query` 参数（仅 namespace/properties/matchAll/sort/descending/offset/limit）；二开认证类（OALoginAction 等）全部参数化 |

### 3.4 XXE：`org.apache.tika.utils.XMLReaderUtils.parseSAX:628` —— 误报

| 项目 | 内容 |
|---|---|
| 报告 | `/xwiki/bin/upload/...` 的 `body` 参数 → `SAXParser.parse(InputStream, DefaultHandler)` |
| 链路 | 上传 Office 文档 → `XWikiAttachment.resetMimeType` → `xwiki-platform-tika-detect/TikaUtils` → `Tika.detect()` → `DefaultZipContainerDetector` → `OPCPackageDetector.parseOOXMLContentTypes`（解析压缩包内 `[Content_Types].xml`）→ `XMLReaderUtils.parseSAX` |
| 部署版本 | Apache Tika 3.2.3（`tika-core-3.2.3.jar`） |
| 防护 | `getSAXParserFactory()`（XMLReaderUtils.java:225-235）：`FEATURE_SECURE_PROCESSING=true`、`external-general-entities=false`、`external-parameter-entities=false`、`load-external-dtd=false` + Xerces SecurityManager（防实体膨胀 DoS）；`parseSAX:628` 再用 `OfflineContentHandler` 包裹（"extra layer of defense against external entity vulnerabilities"） |
| 实证 | 用 18.1.0 同款 LSParser 配置解析 CVE-2022-24898 PoC payload（`<!ENTITY xxe SYSTEM 'file:///etc/passwd'>`）：外部实体未加载，解析文本为空 |
| 已知漏洞 | CVE-2022-24898（xwiki-commons-xml XXE，修复于 12.10.10 / 13.4.4 / 13.8-rc-1）、CVE-2023-27480（XAR 导入 XXE，修复于 13.10.11 / 14.4.7 / 14.10-rc-1）——18.1.0 均已包含 |
| 结论 | 误报；`/bin/upload` 是 multipart 附件上传，`body` 非标准参数；即使 XAR 导入解析 XML 也走已防护的 `XMLUtils.parse` |

### 3.5 SSTI：`InternalVelocityEngine.java:233` —— 误报

| 项目 | 内容 |
|---|---|
| 报告 | liveData 请求参数被拼进 Velocity 模板内容，`evaluate()` 动态执行 `#set($cmd='calc')` 等 |
| 实际 | `InternalVelocityEngine:233` = `template.getTemplate().merge(...)`，执行的是**已编译的 VelocityTemplate**；liveData 渲染的是**服务器端固定模板**（`getdeleteddocuments.vm`、`getdocuments.vm`、`XWiki.LiveTableResults` 等），用户参数（`dir` / `sort` / `filters.*` / `sourceParams.template`）只是模板内的**变量值（数据）**，不作为 Velocity 指令执行 |
| 权限门 | `VelocityTemplateEvaluator.evaluateContent`：`checkAccess(Right.SCRIPT, ...)`——模板作者必须有脚本权限才执行；无 PR 用户另有受限 Velocity 上下文（secure mode） |
| 真正 SSTI 前提 | ① 用户能提供/注入模板源码——liveData 只能选服务器已存在模板，**不满足**；② 某个模板把 `$request.xxx` 拼进 `$services.velocity.evaluate(...)`——标准模板**没有** |
| 结论 | 误报；`#set($cmd='calc')`、`$` 作为参数值只会被当文本输出 / 白名单校验 |

---

## 4. 共性根因

1. **管理员账号测试**：使所有"需要 PR/SCRIPT 才执行"的路径全部走通，制造"确认执行"假象；
2. **粗粒度污点传播**：只认 sink，不识别参数化绑定、白名单/正则、`isSafe` 校验、容器/Tika 防护配置、权限门；
3. **版本不匹配**：部分报告引用旧版本源码类名/行号，与实际部署不符。

---

## 5. 建议处置清单

| # | 动作 | 负责方 | 状态 |
|---|---|---|---|
| 1 | 路径穿越修复（`Utils.getPage` + `AbstractResourceSkin`）纳入版本管理，走代码评审后发布 | 开发 | 代码已完成，待评审发布 |
| 2 | IAST 上改用**无 PR 普通账号**复测全部 6 项，预期全部不成立（HQL 抛 `requires programming right`；XXE 不加载实体；SSTI 参数只当文本） | 安全 | 待执行 |
| 3 | 在 IAST/SAST 报告备注部署版本（XWiki 18.1.0 / Tika 3.2.3），说明已包含 CVE-2025-32429、CVE-2022-24898、CVE-2023-27480 修复 | 安全 | 待执行 |
| 4 | **扫描 wiki 数据库页面中的二开模板拼接**（详见第 6 节） | 开发+安全 | 待执行 |
| 5 | SAST 工具将 Tomcat `org.apache.catalina.*`、Tika `org.apache.tika.utils.*` 标记为受信任库 | 安全 | 待执行 |
| 6 | 保持 XWiki / Tomcat / Tika 补丁更新，跟踪 XWiki 安全公告 | 运维 | 持续 |

---

## 6. 处置清单第 4 点详解：扫描 wiki 数据库页面中的二开模板拼接

### 6.1 为什么必须扫

XWiki 的 **wiki 页面内容存在数据库（MySQL `xwiki` 库的 `xwikidoc` 表），不在 Git 仓库里**。平台自带模板（`getdocuments.vm`、`getdeleteddocuments.vm`、`XWiki.LiveTableResults` 等）经核查全部安全；但**公司二开的 wiki 页面**（自定义 live table 结果页、自定义表单、自定义 REST 处理页等）可能直接在 Velocity 里把 `$request.xxx` 拼进 HQL 或 Velocity 求值——这是唯一能让"参数 → HQL 注入 / SSTI"真正成立的地方。

### 6.2 表结构

> **先区分"库"和"表"**：XWiki 页面内容存在 MySQL 的 **`xwiki` 库**（连接串见 `WEB-INF/hibernate.cfg.xml`，如 `jdbc:mysql://localhost/xwiki`）里的 **`xwikidoc` 表**（注意：18.1.0 表名是小写 `xwikidoc`，不是 `XWIKIDOCUMENT`；列名是大写 `XWD_*`）。

| 列 | 含义 |
|---|---|
| `XWD_FULLNAME` | 页面全名（如 `XWiki.MyPage`） |
| `XWD_CONTENT` | 页面内容（Velocity/XWiki 语法源码），CLOB/TEXT |

> 注意：页面内容可能在"当前版本"和"历史版本"（`xwikiarchives` 表）都有；扫描当前版本即可，重点看线上生效的页面。

### 6.3 扫描 SQL（按数据库类型选择）

**MySQL（库名 `xwiki`，表名小写 `xwikidoc`）：**

```sql
-- 先进入 XWiki 库（库名以 hibernate.cfg.xml 的 connection.url 为准）
USE xwiki;

-- (a) 所有包含 searchDocuments 的页面（HQL 脚本入口）
SELECT XWD_FULLNAME, LEFT(XWD_CONTENT, 500)
FROM xwikidoc
WHERE XWD_CONTENT LIKE '%searchDocuments%';

-- (b) 更精确：searchDocuments 与 $request 同现（疑似拼接）
SELECT XWD_FULLNAME
FROM xwikidoc
WHERE XWD_CONTENT LIKE '%searchDocuments%'
  AND (XWD_CONTENT LIKE '%$request%' OR XWD_CONTENT LIKE '%request.get%');

-- (c) 直接拼 query.hql 的页面
SELECT XWD_FULLNAME
FROM xwikidoc
WHERE XWD_CONTENT LIKE '%query.hql(%'
  AND (XWD_CONTENT LIKE '%$request%' OR XWD_CONTENT LIKE '%request.get%');

-- (d) 把请求参数当 Velocity 代码求值的页面（SSTI 风险）
SELECT XWD_FULLNAME
FROM xwikidoc
WHERE XWD_CONTENT LIKE '%velocity.evaluate%'
  AND (XWD_CONTENT LIKE '%$request%' OR XWD_CONTENT LIKE '%request.get%');

-- (e) 使用 #template / #include 且参数来自请求
SELECT XWD_FULLNAME
FROM xwikidoc
WHERE (XWD_CONTENT LIKE '%#template(%' OR XWD_CONTENT LIKE '%#include(%')
  AND (XWD_CONTENT LIKE '%$request%' OR XWD_CONTENT LIKE '%request.get%');

-- 兜底：如果表名/库名不确定，先确认实际表名（应能搜到 xwikidoc）
SHOW TABLES LIKE '%doc%';
SELECT table_schema, table_name FROM information_schema.tables
WHERE table_name LIKE '%xw%' OR table_name LIKE '%doc%';
```

**H2（XWiki 自带默认数据库，表名也是 `xwikidoc`）：**

```sql
SELECT XWD_FULLNAME, SUBSTRING(XWD_CONTENT, 1, 500)
FROM xwikidoc
WHERE XWD_CONTENT LIKE '%searchDocuments%';
-- 其余查询把 LEFT() 换成 SUBSTRING() 即可
```

**PostgreSQL / Oracle**：表名 `xwikidoc`、列名相同，把 `LEFT()` 换成 `SUBSTRING()` / `SUBSTR()`。

### 6.4 判定标准：危险 vs 安全

| 写法 | 判定 | 原因 |
|---|---|---|
| `$xwiki.searchDocuments("where doc.name = '" + $request.name + "'")` | 🔴 危险，需修复 | 请求值直接拼进 HQL 字符串 |
| `$xwiki.searchDocuments("where doc.name = ?", [$request.name])` | 🟢 安全 | 占位符 + 参数绑定 |
| `$services.query.hql("... where doc.name = ?").bindValue(1, $request.name)` | 🟢 安全 | 占位符 + 参数绑定 |
| `$services.velocity.evaluate($request.template)` 或 `#template($request.xxx)` | 🔴 危险，需修复（SSTI） | 请求值被当作模板代码 |
| `$request.xxx` 仅用于输出显示（配合 `$escapetool.xml()`） | 🟢 安全 | 只是数据输出 |

### 6.5 修复示例

```velocity
## 🔴 错误：直接拼接
#set ($where = "where doc.name = '$!request.name'")
#set ($docs = $xwiki.searchDocuments($where))

## 🟢 正确：占位符 + 参数绑定
#set ($where = "where doc.name = ?")
#set ($docs = $services.query.hql("select doc.fullName from XWikiDocument doc $where")
  .bindValue(1, $request.name).execute())
```

> 额外提示：即使是无 PR 用户，`$services.velocity.evaluate(...)` / `#template($request.x)` 这类写法也应彻底移除——不要依赖权限门作为唯一防线（纵深防御）。

### 6.6 备选扫描方式（不直接连数据库时）

1. **XAR 导出扫描**：管理界面 → 导出全部页面为 XAR（zip），解压后对 XML 文件做同样 grep：
   ```bash
   grep -rlE "searchDocuments|velocity\.evaluate|query\.hql\(" xar/ | xargs grep -l '\$request' 
   ```
2. **页面源码视图**：对命中的页面用 `?viewer=code` 或管理界面查看源码确认。
3. **XWiki 搜索**：可用脚本服务/搜索接口按内容检索（大库性能一般，推荐直接查库）。

### 6.7 注意事项

- 修改 wiki 页面需要相应编辑权限，建议先备份/导出再改；
- 修复后同步检查该页面的历史版本，防止旧版本被恢复；
- 将扫描结果与处置记录（页面名、问题、修复人、日期）归档。

---

## 7. 代码改动清单（路径穿越修复）

```
M  xwiki-platform-core/xwiki-platform-oldcore/src/main/java/com/xpn/xwiki/web/Utils.java
M  xwiki-platform-core/xwiki-platform-oldcore/src/main/java/com/xpn/xwiki/internal/skin/AbstractResourceSkin.java
M  xwiki-platform-core/xwiki-platform-oldcore/src/test/java/com/xpn/xwiki/internal/skin/ClassLoaderSkinTest.java
M  xwiki-platform-core/xwiki-platform-oldcore/src/test/java/com/xpn/xwiki/internal/skin/EnvironmentSkinTest.java
A  xwiki-platform-core/xwiki-platform-oldcore/src/test/java/com/xpn/xwiki/web/UtilsTest.java
```

验证命令（Java 21，临时禁用 `.mvn/extensions.xml` 中的 Develocity 扩展以绕过兼容性问题，测试后已恢复）：

```bash
mvn -s ~/.m2/settings-xwiki.xml -f pom.xml \
  -pl xwiki-platform-core/xwiki-platform-oldcore \
  -Dtest=ClassLoaderSkinTest,EnvironmentSkinTest,UtilsTest \
  -Dxwiki.spoon.skip=true -Dlicense.skip=true -Dxwiki.checkstyle.skip=true \
  -o test
# 结果：Tests run: 16, Failures: 0, Errors: 0, BUILD SUCCESS
```

---

## 8. 附录：相关 CVE 速查

| CVE | 组件 | 问题 | 修复版本 |
|---|---|---|---|
| CVE-2025-32429 | xwiki-platform（getdeleteddocuments.vm） | HQL 注入（sort） | 16.10.6 / 17.3.0-rc-1 |
| CVE-2022-24898 | xwiki-commons-xml | XXE | 12.10.10 / 13.4.4 / 13.8-rc-1 |
| CVE-2023-27480 | xwiki-platform-xar-model | XAR 导入 XXE | 13.10.11 / 14.4.7 / 14.10-rc-1 |
| CVE-2022-29253 | xwiki-platform-oldcore | 模板 API 路径穿越 | 13.10.3 / 14.0 |

当前部署 18.1.0 均已包含上述修复。

---

## 9. 附录：后续安全测试防误判操作指引（权限设置）

### 9.1 为什么会出现误判（权限视角）

XWiki 18.1.0 的权限模型（`Right.java`）：

- `ADMIN` 权限的 RightSet **隐式包含 `SCRIPT`**（有 admin 自动有脚本权限）；
- `SCRIPT`（脚本权限）默认 **DENY**，需要显式授予；
- `PROGRAM`（编程权限，PR）默认 DENY，只授予主管理员（superadmin），且 PR 隐式包含 admin。

因此：**用管理员账号跑 IAST/SAST，所有"需要 PR/SCRIPT 才执行"的路径全部放行**（HQL 的 `isSafe` 校验跳过、Velocity 模板作者检查通过、`$services.velocity.evaluate` 可执行），工具便把这些路径标成"污点确认执行"，产生 HQL/XXE/SSTI 误判。

### 9.2 核心原则：换账号，不关权限

> 不要为了迁就测试去关闭正常用户的 view/edit 等权限（会破坏系统功能）。
> 正确做法是：**用无 admin/PR/SCRIPT 的普通测试账号复测**，并**检查全局是否把 PR/SCRIPT 授得太宽**（如有则收紧）。

### 9.3 创建无特权测试账号（首选）

1. 管理界面 → 用户 → 创建用户，例如 `iast_tester`；
2. **不要**加入 `XWikiAdminGroup`，不授予 `admin` / `programming` / `script`；
3. 用 `iast_tester` 登录后让 IAST 扫描（或在 IAST 平台把该账号设为扫描身份）；
4. 预期效果：HQL 抛 `requires programming right`、Velocity 模板作者 SCRIPT 检查失败、`$services.velocity.evaluate` 被拒——误判消失。

默认情况下普通用户无 admin → 无 SCRIPT → 无 PR，创建后无需额外"关"权限。

### 9.4 检查并收紧全局权限

**默认基线**：只有 `XWikiAdminGroup` 有 admin（隐含 script）；`programming` 只给主管理员。若未改过，则无需关闭，问题仅在测试账号。

检查位置：

- 管理界面 → 权限（`XWiki.XWikiPreferences` 页面 / Administration → Users & Rights）；
- 重点：`XWiki.XWikiAllGroup`（所有用户）、`XWiki.XWikiGuestGroup`（匿名）是否被授予 `admin` / `programming` / `script` → **如有，必须移除**；
- 二开业务组、子空间/页面级是否单独授予了 `script` / `programming` → 收紧为仅管理员。

数据库辅助检查 SQL（表 `xwikiobjects` + `xwikistrings`/`xwikilargestrings`，`XWS_ID = XWO_ID`）：

```sql
USE xwiki;
-- 列出所有授予了 programming 或 script 的 XWikiRights 条目
SELECT o.XWO_NAME AS page, o.XWO_NUMBER AS num,
  (SELECT GROUP_CONCAT(s.XWS_VALUE SEPARATOR ', ')
     FROM xwikistrings s WHERE s.XWS_ID=o.XWO_ID AND s.XWS_NAME='levels') AS levels,
  (SELECT GROUP_CONCAT(l.XWL_VALUE SEPARATOR ', ')
     FROM xwikilargestrings l WHERE l.XWL_ID=o.XWO_ID AND l.XWL_NAME='groups') AS groups,
  (SELECT GROUP_CONCAT(l.XWL_VALUE SEPARATOR ', ')
     FROM xwikilargestrings l WHERE l.XWL_ID=o.XWO_ID AND l.XWL_NAME='users') AS users
FROM xwikiobjects o
WHERE o.XWO_CLASSNAME='XWiki.XWikiRights'
  AND EXISTS (SELECT 1 FROM xwikistrings s
              WHERE s.XWS_ID=o.XWO_ID AND s.XWS_NAME='levels'
                AND (s.XWS_VALUE LIKE '%programming%' OR s.XWS_VALUE LIKE '%script%'));
```

预期结果：仅出现授给 `XWikiAdminGroup` / `superadmin` 的条目；若出现授给 `XWiki.XWikiAllGroup` / 匿名 / 业务组的条目，即为需要收紧的对象。

### 9.5 IAST / SAST 平台配置

- **IAST**：排除管理员会话流量，或指定用 `iast_tester` 身份扫描；
- **SAST**：将 `org.apache.catalina.*`、`org.apache.tika.*`、`org.hibernate.*`、`com.xpn.xwiki.store.hibernate` 等执行点标记为受信任库 / 已缓解，消除 sink 类误报。

### 9.6 操作清单小结

| # | 动作 | 说明 |
|---|---|---|
| 1 | 创建 `iast_tester` 普通账号（无 admin/PR/SCRIPT） | 首选，最简单有效 |
| 2 | 用该账号复测全部报告 | 预期全部在权限门处被拦截 |
| 3 | 检查 AllGroup/匿名/业务组是否被授 programming/script/admin | 有则移除（默认不应有） |
| 4 | IAST 排除管理员会话；SAST 白名单可信库 | 平台侧配置 |
