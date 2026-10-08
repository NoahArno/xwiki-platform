# confluence-db-migration

绕开 Confluence 自己的导出引擎，**直接从 Confluence 数据库 + 附件存储生成 XWiki 导入器可读的导出包**。

## 为什么不用 Confluence 的导出

原有流程是"在 Confluence 里逐个空间导出 XML → 通过 XWiki 的 Filter Streams Converter 上传导入"。在
Confluence 7.3.3 上，大空间导出会以各种方式失败（内存问题为主），且**不能靠调大堆内存解决**；
Confluence 官方也没有"导出时排除附件"的选项（[CONFSERVER-104157](https://jira.atlassian.com/browse/CONFSERVER-104157) 至今未实现）。
加上 100+ 公共空间、300+ 个人空间的规模，人工逐个操作不可行。

本工具把导出侧整体替换为对数据库的只读访问，从而获得完全可控的分片、重试与断点能力。

## 前置条件

- JDK 17+
- 对 Confluence 数据库的**只读**访问
- 对 Confluence 附件目录的**只读**访问

> ⚠️ **务必对数据库快照运行，不要直接连生产库。**
> Confluence 是活的：开发过程中实测到一整棵草稿子树（含 5 个附件）在几分钟内被 Confluence
> 自身的清理任务删除。若在导出过程中源数据发生变化，产出的包会出现"页面引用了尚未生成的附件"
> 这类**内部自相矛盾**，而导入器不会报错——这是静默丢数据。
>
> 推荐做法：`mysqldump --single-transaction` 生产库 → 恢复到一台临时 MySQL → 让本工具连它。
> 这样同时省掉了长事务撑大 undo log 的问题。

## 构建与运行

```bash
cd tools/confluence-migration
mvn -o test        # 跑测试
mvn -o package     # 打包
```

运行（`mysql-connector-j` 只在运行期需要）：

```bash
MYSQL=$HOME/.m2/repository/com/mysql/mysql-connector-j/8.0.33/mysql-connector-j-8.0.33.jar
CP="target/classes:$MYSQL"
```

### 1. 数据体检

在动任何迁移之前先跑这个。它一次性回答三个决定方案形状的问题。

```bash
java -cp "$CP" org.xwiki.contrib.confluence.migration.cli.Main inventory \
  --confluence-home /data/confluence \
  --threshold-mib 500 \
  --csv /tmp/inventory.csv
```

输出包含：

| 输出 | 用途 |
|---|---|
| `CONTENT_STATUS` 取值分布 | 确认回收站/草稿的排除规则是否命中真实的取值 |
| `ATTACHMENTDATA` 行数 | 判断附件在数据库还是文件系统；非 0 会告警 |
| 每空间页面数 / 附件数 / 附件体积 | 分片阈值与装箱策略的输入 |
| 空间分类 | 判断"为全部空间做 DB 直读生成器"是否划算 |

### 2. 导出

```bash
# 单个空间
java -cp "$CP" ...Main export \
  --confluence-home /data/confluence --space DS --output /mnt/migration

# 全部空间（单个空间失败不会中断整批）
java -cp "$CP" ...Main export \
  --confluence-home /data/confluence --all --output /mnt/migration
```

产出的是**已解压目录**而不是 zip：

```
/mnt/migration/<SPACEKEY>/
  entities.xml
  exportDescriptor.properties
  attachments/<容器ID>/<附件ID>/<版本>
```

**为什么不打包成 zip**：XWiki 导入器的 `ConfluenceXMLPackage.fromFile` 对目录是**就地读取**，
对 zip 则会先 `FileUtils.copyInputStreamToFile` 全量解压到临时目录。用目录形式可以省掉
打包与解压两次全量 IO，也省掉 XWiki 服务器上等量的临时磁盘。

导入时让 XWiki 用 `file:` 源指向该目录即可，例如
`source=file:/mnt/migration/DS`，无需经过浏览器上传，因此不受 500M 上传限制。

### 3. 校验

**每次导出后都应该跑。** 它检查引用完整性与附件文件齐全——这是"静默丢数据"的主要防线。

```bash
java -cp "$CP" ...Main validate --package /mnt/migration
```

## 设计要点

### 迁移范围

按既定策略，**草稿（`draft`）与回收站（`trashed`）一律不导出**。理由见
`MigrationPolicy` 的类注释：导入器本身就会丢弃它们；它们不是稳定的数据；
草稿页可能没有空间归属，而这是导入器注释里点名的已知问题。

体检与导出共用 `MigrationPolicy` 这一份判定，因此体检报出的体量与导出实际产出始终一致。

### 附件定位

附件字节存放在文件系统的 `attachments.dir` 下，而路径**不是存在数据库里的**，是由 ID 计算出来的
（这正是 `ATTACHMENTDATA` 表为空的原因）。算错一个字符，附件就会静默丢失。

规则复刻自 `confluence-7.3.3.jar` 的
`HierarchicalContentFileSystemHelper`（内部使用 `IdMultiPartHashGenerator(3, 250, 2)`），
见 `AttachmentPathRule`，并由 31 条真实样本构成的回归测试保护。

附件所属的"容器"取自 `CONTENT.PAGEID`——**不是** `PARENTID`（那是页面层级父节点），
也**不是** `PARENTCCID`（7.3.3 中为空）。

### 引用完整性

只写出指向"本包内包含的实体"的引用。指向被排除对象（草稿页上的附件、不在本空间的页面）的
引用会被丢弃并记为告警。`PackageValidator` 会在导出后把这件事再验证一遍。

### 当前未覆盖的范围

| 项 | 说明 |
|---|---|
| 用户与组 | `InternalUser` / `InternalGroup` / `HibernateMembership` 未生成；导入时用 `usersEnabled=false` |
| 权限 | `SpacePermission` / `ContentPermission` 未生成；导入时用 `rightsEnabled=false` |
| 历史版本 | 只导出当前版本；导入时用 `historyEnabled=false` |
| 博客 | 代码路径已支持，但本地实例无博客数据可验证 |
| 增量同步 | 尚未实现（属于第二阶段） |

> 以上四项**按当前迁移范围已明确不需要**：本工具只负责把数据迁过去。

## B 侧：自动导入

A 侧产出包之后，B 侧（XWiki）需要一个东西把它导进去。见 [`xwiki-side/`](xwiki-side/README.md)：

- 生成器跑在 A（Confluence 那台，读本机 MySQL + 本机附件目录）
- 包经 `rsync` 到 B 的 `<permanentDir>/migration/`
- B 上的一个 Scheduler (Groovy) 作业定时扫描该目录并逐个导入，写 `ledger.csv` 台账

**为什么不从 A 远程调 B 的接口**：触发导入需要 `Right.PROGRAM`，而你们的 XWiki 走自定义 OA SSO，
让 A 去 HTTP 触发 B 需要额外的服务账号并打通 SSO。"往目录丢文件、XWiki 自己扫"不需要任何外部认证。

## A、B 之间的契约

`export` 会在输出根目录写 `manifest.csv`：

```csv
spaceKey,packageDir,pageCount,blogPostCount,attachmentCount,attachmentBytes,exportedAt
ds,ds,10,0,20,785398,2026-10-08 10:59:38
```

- `packageDir` 是**相对路径**：包从 A 搬到 B 后绝对路径必然不同，相对路径才不会失效
- 清单由 A 写定、B 只读；导入状态记在 B 自己的 `ledger.csv` 里，两边互不覆盖
- 清单里的预期数量用于导入后核对——否则"导进去了但少了一半页面"没有任何信号

## 配置来源

`--confluence-home` 指向包含 `confluence.cfg.xml` 的目录，本工具从中读取
`hibernate.connection.url` / `username` / `password` 与 `attachments.dir`。

若该文件中的密码被 Confluence 加密（形如 `{...}`），本工具会明确报错并要求改用
`--password` 显式传入，而不是拿一个解不开的密文去连库。

## 开发环境备忘

本工具是在一个本地 Confluence 7.3.3（MySQL 8.0.46，附件在文件系统）上开发并验证的。
Confluence 自带的 MySQL 驱动可直接复用，无需安装 `mysql` 客户端：

```bash
CF=/path/to/confluence
java -cp $CF/atlassian-confluence-7.3.3/confluence/WEB-INF/lib/mysql-connector-j-8.0.33.jar \
     Probe.java
```
