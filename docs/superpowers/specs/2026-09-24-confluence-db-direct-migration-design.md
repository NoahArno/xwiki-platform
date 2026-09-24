# Confluence → XWiki 自动化迁移（DB 直读生成导出包）设计

- 日期：2026-09-24
- 状态：待评审
- 相关仓库：`xwiki-platform`（目标）、`../confluence`（Confluence 导入器 fork，分支 `noah`）

## 1. 背景与问题

现有迁移流程是手动的两步：① 在 Confluence 里逐个空间导出 XML 包；② 通过 XWiki 的 Filter Streams Converter 上传并导入。

规模：**100+ 公共空间，300+ 个人空间**。

两个痛点：

1. **导出侧不可靠**。部分空间体积大，Confluence 导出会以各种方式失败（内存问题为主，且**不能通过简单调大堆内存解决**）。Confluence 官方至今没有"导出时排除附件"的选项（[CONFSERVER-104157](https://jira.atlassian.com/browse/CONFSERVER-104157) 仍是未实现的 feature request），大空间 XML 导出的失败在官方 KB 中主要归因于 [OOM](https://support.atlassian.com/confluence/kb/cannot-create-or-restore-xml-backup-due-to-outofmemory-errors-in-confluence-data-center/)。
2. **人工成本不可接受**。400+ 个空间，没人愿意逐个手工操作。

约束：不使用 XWiki 官方商业版迁移工具（Confluence Migration Toolkit）。

## 2. 决策与建议

| 决策 | 结论 | 状态 | 理由 |
|---|---|---|---|
| 技术路线 | **路线 3：绕开 Confluence 导出引擎，直接从数据库 + 附件存储生成 Confluence-XML 兼容包** | ✅ 已确认 | Confluence 导出不可靠且无法通过调参修复；DB 直读可完全控制分片/重试/断点 |
| 回收站与草稿 | **一律不导出** | ✅ 已确认 | 见 §4.4。体积浪费且导入时本来就被静默丢弃 |
| 增量能力 | **v1 不做**，先跑通全量 | ✅ 已确认 | 用户明确要求。但数据源是 DB，后续补 watermark 增量成本低 |
| 关键路径 | **先做子项目 0（数据体检）** | ✅ 已确认 | 它是子项目 1 的输入，且可能修正架构比例 |
| 包投放形式 | **已解压的目录**，不打包成 zip | 🔶 待确认 | 见 §4.5，可跳过打包+解压两次全量 IO |
| 落地拓扑 | 共享挂载 / 直写 XWiki 磁盘 / HTTP `url:` 三选一 | 🔶 待确认（§10-1） | 影响部署与磁盘规划 |
| 生成器语言 | **Java** | 🔶 待确认 | 可复用导入器自身的测试夹具做 TDD，验证闭环成本最低 |
| 验收标准 | **行为等价**（两端 XWiki 渲染输出对比），非 XML diff | 🔶 待确认（§9.2） | 唯一能防住"静默丢数据"的方法 |

## 3. 环境事实（在本地 Confluence 上实测）

本地开发环境：`/Users/noaharno/programer/software/confluence`，**Confluence 7.3.3**（`data/backups/backup-2026_06_27.zip` 中有真实导出包）。

| 事实 | 实测值 | 证据 |
|---|---|---|
| Confluence 版本 | **7.3.3** | `exportDescriptor.properties` 中 `createdByVersionNumber=7.3.3` |
| 数据库 | **MySQL 8.0.46** @ `localhost:3306/confluence` | `data/confluence.cfg.xml` 的 `hibernate.connection.url`；JDBC 连接实测 |
| 附件存储 | **文件系统，非 DB BLOB** | `data/confluence.cfg.xml`：`<property name="attachments.dir">${confluenceHome}/attachments</property>`；且 `ATTACHMENTDATA` 表**存在但 0 行** |
| 附件文件数 | 31（本地） | `data/attachments/ver003/...` |
| 空间数 | 2（本地：`ds`=98305，`SPC`=98306） | `SELECT SPACEID,SPACEKEY FROM SPACES` |

> **重要**：本地实例体量很小，**不能**用来推断生产环境的体积分布。生产环境的体量需由 §7 的体检脚本产出。
> 但本地实例的**结构**（表结构、路径规则、状态词表）与生产一致，可直接复用。

## 4. 关键已验证技术结论

以下结论均已用真实数据验证，不是推测。实现时可直接依赖（但仍建议保留对应的回归测试）。

### 4.1 包目录布局

导入器（`ConfluenceXMLPackage.fromFile`）接受**目录**或 zip；目录会被**就地读取**。

```
<root>/
  entities.xml
  exportDescriptor.properties
  attachments/<containerId>/<attachmentId>/<version>
```

- 常量：`FILE_ENTITIES="entities.xml"`、`FILE_DESCRIPTOR="exportDescriptor.properties"`、`ATTACHMENTS="attachments"`
- `plugin-data/`、`resources/` 等目录导入器不读，无需生成
- 真实参考包结构见 `data/backups/backup-2026_06_27.zip`

### 4.2 导入器只认 19 种实体

`ConfluenceXMLPackage` 的白名单（第 677–732 行）：

```
Space, SpaceDescription, Page, BlogPost, Comment, ContentEntityObject,
ContentPermissionSet, ContentPermission, SpacePermission, Attachment,
BodyContent, ContentProperty, InternalGroup, InternalUser, Label,
Labelling, HibernateMembership, ConfluenceUserImpl, PageTemplate
```

实测真实导出中包含但**导入器忽略**的实体：`ChangedValueEntity`(246)、`AuditRecordEntity`(109)、`AffectedObjectEntity`(106)、`ConfluenceBandanaRecord`(91)、`OutgoingLink`(60)、`InternalUserAttribute`、`BucketPropertySetItem`、`Notification`、`PersonalInformation`、`GlobalDescription`、`Draft`、`DirectoryMapping`、`DirectoryImpl`、`ConfluenceRememberMeToken`、`ApplicationImpl`、`AliasedKey`。

**生成器无需产出这些类型** —— 这是最大的工作量削减点。

### 4.3 `exportDescriptor.properties` 的最小必填字段

导入器只读两个字段：

- `source`（`ConfluenceInputFilterStream:150`）→ 用于自动判定 Confluence 实例类型（server/cloud）。**必须写 `source=server`**。
- `spaceKey`（`ConfluenceXMLPackage:1394`）→ 仅 `ignoreExtraneousSpaces()`（`extraneousSpacesEnabled` 特性）使用，可选。

其余字段（`buildNumber`、`exportType`、`backupAttachments` 等）导入器不读，可原样照抄参考包。

### 4.4 内容状态词表：DB 与 XML **一致**

| 来源 | 取值 |
|---|---|
| `CONTENT.CONTENT_STATUS`（MySQL 实测） | `current`(49) / `draft`(20) |
| `entities.xml` 的 `<property name="contentStatus">`（真实包实测） | `<![CDATA[current]]>` / `<![CDATA[draft]]>`，共 69 处 |
| `SPACES.SPACESTATUS` | `CURRENT` / `ARCHIVED`（本实例只有 `CURRENT`） |

**DB 与 XML 使用同一套词表**，映射是直通，不存在先前担心的 `trashed`→`deleted` 重命名。

导入器对状态的过滤行为：

| 状态 | 默认行为 | 控制参数 |
|---|---|---|
| `deleted`（回收站） | **永远跳过** | 无（`mustIgnoreContent`） |
| `draft`（草稿） | **永远跳过** | 无（`mustIgnoreContent`） |
| `archived`（文档） | 跳过 | `archivedDocumentsEnabled` |
| `ARCHIVED`（空间） | 跳过 | `archivedSpacesEnabled` |

> 因已决定不导出回收站/草稿，生成器应在 SQL 层直接排除，而不是依赖导入器丢弃——这样包更小，且绕开已知缺陷（`ConfluenceXMLPackage:2058` 注释：*"some draft pages don't have spaces and this causes issues"*，本地实例中已真实复现，见 §4.6）。

### 4.5 输入源语义（`ConfluenceXMLPackage.setSource` / `fromFile`）

```java
if (source instanceof FileInputSource)      fromFile(file);
else if (URLInputSource && protocol=="file") fromFile(file);
else if (source instanceof InputStreamInputSource) fromStream(...);
```

```java
fromFile(File f) { if (f.isDirectory()) directory = f;        // 就地，零拷贝
                   else fromStream(new FileInputStream(f)); } // 全量解压到 temp/
```

`DefaultInputSourceReferenceParser` 支持前缀：`file:`、`url:`、`string:`、`resource:`、`attach:`。

**结论**：把包生成为**目录**并让 XWiki 以 `file:<dir>` 读取，可跳过打包与解压两次全量 IO；若跨机投放，`url:` 亦可（但 XWiki 会全量解压到 `temp/`）。

### 4.6 附件定位算法（**已证明，31/31 精确匹配**）

附件字节在 `<attachments.dir>/` 下，路径是**计算**出来的，`ATTACHMENTDATA` 表为空因此不存储路径。

算法位于 `confluence-7.3.3.jar` 的 `com.atlassian.confluence.pages.persistence.dao.filesystem.HierarchicalContentFileSystemHelper`，它构造 `new IdMultiPartHashGenerator(3, 250, 2)`（split=3, modulo=250, parts=2）。

```
path = "ver003/"
     + (spaceId == null ? "nonspaced" : dirs(gen(spaceId)) + spaceId + "/")
     + dirs(gen(containerId)) + containerId + "/"
     + attachmentId + "/"
     + version

gen(id):
    s = String.valueOf(id); pos = s.length(); out = []
    for i in 0..1:
        pos -= 3
        p = (pos > 0) ? s.substring(pos, pos+3) : s.substring(0, pos+3)
        out.add(parseInt(p) % 250)
        if pos <= 0: break
    pad(out, 2)              // parts=2
    return out

dirs(list) = "a/b/"           // 各元素以 "/" 连接
```

**容器 ID 的取法**：`CONTENT.PAGEID`（不是 `PARENTCCID`——该列在本版本为 null，也不是 `PARENTID`——那是页面层级父节点，也不是 `CONTENT_RELATION`——该表为空）。

验证结果：以 `backup-2026_06_27.zip` 对应的真实实例为样本，用上述公式重算全部 31 个附件路径，与磁盘上 31 个真实路径**完全一致（0 漏算、0 多算）**。

边界情况：`id < 1000` 时 `gen()` 的补位行为需按反编译结果对齐（本地无样本），实现时须补单元测试。

## 5. 架构总览

```
Confluence MySQL（只读；建议用 mysqldump 恢复到 scratch 实例）
Confluence attachments.dir（文件系统）
                 │
                 ▼
        ┌──────────────────┐
        │   cf-exporter    │   ← 分片计划
        │  (Java, CLI)     │
        └────────┬─────────┘
                 │ 产出「已解压目录」
    ┌────────────┼────────────┐
    ▼            ▼            ▼
 /mnt/mig/DS/  /mnt/mig/SPC/ ...
   shard-001/    shard-001/
     entities.xml
     exportDescriptor.properties
     attachments/<containerId>/<attachmentId>/<version>
                 │
                 ▼
      ┌──────────────────────────┐
      │ migration-orchestrator   │  逐分片：
      │  $services.filter        │    startConvert(
      │   .startConvert(...)     │      "confluence+xml",
      └───────────┬──────────────┘      {source:"file:/mnt/mig/DS/shard-001",
                     │                    root:"DS"},
                     ▼                    "xwiki+instance", {})
            ┌─────────────────┐      )
            │ 迁移台账 ledger  │
            │ 状态/重试/报告    │
            └─────────────────┘
```

### 导入侧已有的自动化能力（无需改造 XWiki）

- `xwiki-platform-filter-script` 暴露 `$services.filter.startConvert(inputType, inputProperties, outputType, outputProperties)`，返回带状态/日志的 `Job`。
- Job 状态可经 `Filter.FilterStreamJobJSON` 查询（`$services.filter.currentJob`）。
- **本版本 XWiki 没有 filter streams 的 REST 端点**（已确认 `xwiki-platform-rest` 中无相关资源），因此自动化入口是脚本服务（wiki 页面 Velocity/Groovy + 调度器），或外部进程调用该页面。

## 6. 子项目分解

| # | 子项目 | 职责 | 关键路径 |
|---|---|---|---|
| 0 | **数据体检** | 空间清单与分类；确认状态词表在生产环境的实际取值 | ✅ 是 |
| 1 | **`cf-exporter`** | DB → Confluence-XML 兼容目录，可分片 | ✅ 是 |
| 2 | 迁移编排器 | 驱动导入、台账、重试、报告 | 否（无包无法测） |
| 3 | 空间映射与切分策略 | Confluence space → XWiki `root`；分片粒度 | 否（配置而非代码） |

**本设计文档覆盖子项目 0 与子项目 1**（关键路径）。子项目 2、3 另立文档。

## 7. 子项目 0：数据体检

目的：用真实分布驱动分片参数与架构比例判断，而非拍脑袋。

产出：

1. **状态词表在生产环境的实际取值** —— `SELECT CONTENT_STATUS, COUNT(*) FROM CONTENT GROUP BY CONTENT_STATUS`。本地无法回答（本地无回收站内容），必须在生产执行。
2. **空间清单表**：每空间的 `SPACEKEY` / `SPACESTATUS` / 页面数 / 附件数 / 附件总字节 / 最大单附件。（注意：附件体积在文件系统上，SQL 只能给出计数；体积需遍历 `attachments.dir` 或由 `gen()` 反查。）
3. **空间分类**：直接导 / 要分片 / 要特判。

> ⚠️ 该结果可能**推翻架构比例**：若 400 个空间中绝大多数都很小，则"为全部空间做 DB 直读生成器"的投入产出比需要重新评估。

## 8. 子项目 1：`cf-exporter` 设计

```
cf-exporter/
  db/          JdbcConfluenceSource   连接、分批取数、流式游标
  model/       实体 POJO（仅 §4.2 白名单中的 19 类）
  mapping/     StatusMapper、DateMapper、CdataEscaper     ← 保真度核心
  emit/        XmlEmitter             手写流式输出 <hibernate-generic>
  shard/       ShardPlanner           按体积装箱，产出分片计划
  attachment/  AttachmentResolver     §4.6 的路径算法
  cli/         Main
```

### 三个刻意的设计选择

1. **手写流式 XML 输出**，不用对象模型在内存里拼树 —— 要精确控制 CDATA/格式，且必须能处理任意大的空间而不 OOM。
2. **两遍扫描**：Pass 1 只统计体积生成装箱计划；Pass 2 按计划产出。
3. **对 scratch 实例跑，不碰生产**：`mysqldump` 生产库 → 恢复到临时 MySQL → 生成器只连它。理由：零生产负载 + **可重复**（同一 dump 永远产出同一个包，回归测试才有意义）。同时天然规避 MySQL 长事务撑大 undo log 的问题。

### MySQL 相关注意

- `ATTACHMENTDATA` 为空（文件系统存储），因此**不需要**流式读 LONGBLOB，先前担心的 Connector/J 全量缓冲导致 OOM 的风险不存在。
- 但 `entities.xml` 的正文（`BODYCONTENT.BODY`，`mediumtext`）仍需分批/流式读取。
- 一致性快照：推荐直接依赖 `mysqldump --single-transaction` 产物，而非在生成器里开长事务。

### 分片策略

- **内容片**与**附件片**分开成片：内容（`entities.xml` 中的 Page/BodyContent/Comment/Label/…）走一批；附件走另一批（体积大头，按 `<containerId>` 分组 + 字节装箱）。
- 导入器原生支持两阶段：`contentsEnabled` / `attachmentsEnabled`。
- **待实验确认**：附件子片若只含 `Attachment` 对象而不含其 container page 骨架，导入器可能报 `Can't find page with id` 并静默跳过。见 §9 与 §10。

## 9. 验证策略（本文档的核心）

路线 3 的真正风险不是分片，而是**格式保真度**——"造出一个看起来像、但不是的东西"。

### 9.1 黄金基准

本地 `data/backups/` 中已有真实导出包，且小空间导出在 7.3.3 上是可用的。据此：

```
1. 取一个真实导出包 R（本地已有，或从生产导一个小空间）
2. 恢复到 scratch MySQL
3. 生成器对同一空间产出 G
4. 结构化 diff R 与 G 的 entities.xml（忽略时间戳/顺序）
5. 用真实 XWiki 导入，做行为等价对比
6. 加上附件后重复 4-5
```

### 9.2 验收标准（行为等价，而非 XML diff）

> 把参考包 R 与生成包 G **分别导入两个 XWiki 空间**，逐页对比两端 XWiki 文档源码与**渲染输出**。一致才算通过。

理由：XML diff 只能证明"长得像"，行为等价才能证明"是同一个东西"。这把尺子定死之后，后续分片、附件、增量的正确性都能用同一标准衡量。

### 9.3 附件路径的回归测试

§4.6 的算法必须有单元测试覆盖：输入 `(spaceId, containerId, attachmentId, version)`，断言输出路径。测试数据即本地 31 个真实样本。

## 10. 风险与未决项

| # | 项 | 影响 | 处理 |
|---|---|---|---|
| 1 | **生成器与 XWiki 的落地拓扑未定** | 影响部署与磁盘规划 | 三选一：共享挂载 / 直写 XWiki 磁盘 / HTTP `url:` |
| 2 | **附件子片是否需带 container page 骨架** | 若不带，附件可能被静默跳过 | 用 §9 的行为等价验证在第一周内实验确定 |
| 3 | **回收站/草稿的级联引用** | 正常页面若引用被丢弃对象会产生死链 | 生成器需明确策略（补空壳 / 断引用 / 重写链接）；台账记录分片边界以便事后修 |
| 4 | **生产环境体量未知** | 影响分片参数与架构比例 | 由子项目 0 产出 |
| 5 | **跨片引用断裂** | 分片边界必然切断跨片链接 | 与 XWiki 官方 `includedPages` 同源副作用；靠后续 URL 映射修，但须记录边界 |
| 6 | `id < 1000` 的 `gen()` 边界行为 | 理论上一旦出现会错定位附件 | 按反编译结果对齐并补测试（本地无样本） |
| 7 | **生产环境状态词表未确认** | 若生产存在 `deleted`/`archived`，排除规则需相应扩展 | 由子项目 0 回答 |

## 11. 明确不做的事（YAGNI）

- v1 **不做增量/watermark 同步**（用户明确要求先跑通全量）
- **不做** Confluence 端插件/脚本改造（路线 3 的卖点就是完全不碰 Confluence 运行时）
- **不生成**导入器白名单之外的实体类型
- **不生成**回收站、草稿内容
- **不改造** XWiki 导入器（复用 `confluence+xml` 输入流原样）

## 12. 附：本地环境复现方式

无需安装 `mysql` 客户端 —— Confluence 自带 MySQL JDBC 驱动，用 JDK 单文件源码模式即可连库：

```bash
CF=/Users/noaharno/programer/software/confluence
# 写一个单文件 Java 探针（JDBC URL 见 data/confluence.cfg.xml，本地凭据 root/root）
java -cp $CF/atlassian-confluence-7.3.3/confluence/WEB-INF/lib/mysql-connector-j-8.0.33.jar \
     Probe.java

# 参考包解剖
unzip -l $CF/data/backups/backup-2026_06_27.zip
```

§4.6 的附件路径算法由该方式验证得出（31/31 精确匹配）。实现时应把该验证固化为单元测试，
测试数据取 `$CF/data/attachments/` 的 31 个真实路径 + 对应 DB 行的 `(SPACEID, PAGEID, CONTENTID, VERSION)`。

参考包中的真实 `exportDescriptor.properties`：

```properties
source=server
buildNumber=6169
createdByVersionNumber=7.3.3
exportType=all
createdByBuildNumber=8401
backupAttachments=true
defaultUsersGroup=confluence-users
```
