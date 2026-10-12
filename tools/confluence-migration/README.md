# Confluence → XWiki 迁移工具

从 Confluence 数据库 + 附件目录直接生成 XWiki 可导入的包，再手动导入到 XWiki。

流程：**A 侧指定空间 → 导出到目录 → 人工拷到 B → 在 B 上导入**。

---

## 一、A 侧：导出

前置：JDK 17+；对 Confluence 数据库与附件目录的**只读**访问。

> ⚠️ 对**数据库快照**跑，不要直连生产库。Confluence 是活的，导出期间源数据变化会产出
> "页面引用了尚未生成的附件"这类包，而导入器不会报错——这是静默丢数据。

```bash
cd tools/confluence-migration
mvn -o package

MYSQL=$HOME/.m2/repository/com/mysql/mysql-connector-j/8.0.33/mysql-connector-j-8.0.33.jar
CP="target/classes:$MYSQL"
ALIAS=org.xwiki.contrib.confluence.migration.cli.Main
```

**1. 看有哪些空间**（可选，但建议先跑）

```bash
java -cp "$CP" $ALIAS inventory --confluence-home /data/confluence --csv /tmp/inventory.csv
```

**2. 导出指定的空间**

```bash
# 用 --spaces 指定（逗号分隔）
java -cp "$CP" $ALIAS export \
  --confluence-home /data/confluence \
  --spaces DS,SPC \
  --output /mnt/migration

# --space 可重复书写；--all 导全部；--space-file 从文件读（可直接喂上面的 CSV）
```

也可以排除某些空间：`--exclude-spaces ARCHIVE,TEST`。

**3. 校验产出**

```bash
java -cp "$CP" $ALIAS validate --package /mnt/migration
```

输出：

```
/mnt/migration/
  manifest.csv                      # A 写定、B 只读；导入后按它对账
  <SPACEKEY>/
    entities.xml
    exportDescriptor.properties
    attachments/<容器ID>/<附件ID>/<版本>
```

## 二、搬运到 B

```bash
rsync -av --delete /mnt/migration/ user@B:<permanentDir>/migration/
```

`<permanentDir>` 见 `webapps/xwiki/WEB-INF/xwiki.properties` 的 `environment.permanentDirectory`。

## 三、B 侧：导入

前置（一次性）：XWiki 上已装 **Confluence 导入器**（`confluence+xml`）和
**Filter Streams Converter** 应用。以管理员打开 `/xwiki/bin/view/Filter/`，Input type 里能看到
`Confluence XML input stream (confluence+xml)` 即可。

浏览器打开 `/xwiki/bin/view/Filter/`，一个空间导一次：

| 字段 | 填什么 |
|---|---|
| Input type | `Confluence XML input stream (confluence+xml)` |
| Input configuration → source | `file:<permanentDir>/migration/<SPACEKEY>` |
| Output type | `XWiki instance output stream (xwiki+instance)` |
| `historyEnabled` / `usersEnabled` / `groupsEnabled` / `rightsEnabled` | 都改成 `false` |
| `archivedDocumentsEnabled` / `archivedSpacesEnabled` | 都改成 `false` |
| 其余 | 保持默认 |

点 **Convert**，进度看页面上的 *Current conversion*。

包多的时候，把 [`xwiki-side/ImportPage.groovy`](xwiki-side/ImportPage.groovy) 全文粘到一个新页面
（建议 `Migration.Import`，语法 `xwiki/2.1`，用有编程权限的用户保存）当索引用：它会列出所有包、
显示 manifest 的期望值 vs XWiki 现状，并给出**参数已预填**的转换链接，点一下就到上面那张表。

## 四、对账

刷新索引页，或直接拿 `manifest.csv` 对：

```csv
spaceKey,packageDir,pageCount,blogPostCount,attachmentCount,attachmentBytes,exportedAt
SPC,SPC,2,0,6,121718,2026-10-10 16:13:42
ds,ds,10,0,20,785398,2026-10-10 16:13:42
```

导入器把一个 Confluence 页面落成一个**嵌套空间** `<SpaceKey>.<PageTitle>`，内容在该空间的
`WebHome`，所以「页面数」= 该前缀下 `*.WebHome` 文档数（不含自动生成的 `<SpaceKey>.WebPreferences`）。
数字对不上就是没导完或导坏了。重导是幂等的（导入器会先清掉上次导入产生的页面）。

## 五、排错

| 现象 | 处理 |
|---|---|
| 报错"数据库密码看起来是加密的" | 用 `--password` 传明文 |
| `inventory` 报 `ATTACHMENTDATA` 行数不为 0 | 附件在数据库里而非文件系统，本工具的附件定位规则不适用 |
| `validate` 报附件文件缺失 | 附件目录指错，或 Confluence 清理过数据，用快照重导 |
| Filter 页面里没有 `confluence+xml` | 导入器没装 |
| 「管理 → 扩展」搜不到扩展 | `xwiki.properties` 的 `extension.repositories` 是空的，只能手工装 `WEB-INF/lib` |

## 附：完整参数

```bash
java -cp "$CP" $ALIAS help
```

配置来源：`--confluence-home` 指向含 `confluence.cfg.xml` 的目录，本工具从中读取
`hibernate.connection.url` / `username` / `password` 与 `attachments.dir`。

## 附：当前不迁移的内容

用户与组、权限、历史版本、增量同步（导入时对应开关关掉）。草稿与回收站按策略一律不导出。
