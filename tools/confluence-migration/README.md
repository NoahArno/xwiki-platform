# Confluence → XWiki 迁移工具

从 Confluence 数据库 + 附件目录**只读**生成 XWiki 可导入的包，再手动导入到 XWiki。

流程：**A 侧指定空间 → 导出到目录 → 人工拷到 B → 在 B 上导入**。
已在 Confluence 7.3.3 + XWiki 18.1.0 上实测通过。

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

```bash
# 1. 体检：有哪些空间、各多大（建议先跑）
java -cp "$CP" $ALIAS inventory --confluence-home /data/confluence --csv /tmp/inventory.csv

# 2. 导出：--spaces 逗号分隔；也可 --space 重复书写 / --all / --space-file / --exclude-spaces
java -cp "$CP" $ALIAS export \
  --confluence-home /data/confluence --spaces DS,SPC --output /mnt/migration

# 3. 校验：每次导出后都该跑
java -cp "$CP" $ALIAS validate --package /mnt/migration
```

产出：

```
/mnt/migration/
  manifest.csv                      # A 写定、B 只读；导入后按它对账
  <SPACEKEY>/{entities.xml, exportDescriptor.properties, attachments/...}
```

## 二、搬运到 B

```bash
rsync -av --delete /mnt/migration/ user@B:<permanentDir>/migration/
```

`<permanentDir>` 见 `webapps/xwiki/WEB-INF/xwiki.properties` 的 `environment.permanentDirectory`。

## 三、B 侧：导入

前置：XWiki 已装 **Confluence 导入器**（`confluence+xml`）与 **Filter Streams Converter**。
以管理员打开 `/xwiki/bin/view/Filter/`，Input type 里能看到
`Confluence XML input stream (confluence+xml)` 即可。

一个空间导一次：

| 字段 | 填什么 |
|---|---|
| Input type | `Confluence XML input stream (confluence+xml)` |
| Input configuration → source | `file:<permanentDir>/migration/<SPACEKEY>` |
| Output type | `XWiki instance output stream (xwiki+instance)` |
| `historyEnabled` / `usersEnabled` / `groupsEnabled` / `rightsEnabled` | 都改成 `false` |
| `archivedDocumentsEnabled` / `archivedSpacesEnabled` | 都改成 `false` |
| 其余 | 保持默认（作者映射另见第五节） |

点 **Convert**，进度看 *Current conversion*。

包多时把 [`xwiki-side/ImportPage.groovy`](xwiki-side/ImportPage.groovy) 粘到一个新页面
（建议 `Migration.Import`，语法 `xwiki/2.1`，用有编程权限的用户保存）：它列出所有包、
显示 manifest 期望值 vs XWiki 现状、给出**参数已预填**的转换链接，对账通过后还能一键注册空间。

## 四、对账 + 注册空间

**落点**：Confluence 页面 → 嵌套空间 `<SpaceKey>.<标题>` 的 `WebHome`；
Confluence 博客 → `<SpaceKey>.Blog.<标题>`（普通文档）。导入器还会自建
`<SpaceKey>.Blog.WebHome` 与 `<SpaceKey>.WebPreferences` 两个壳文档。

**对账**：期望值来自 `manifest.csv`（`pageCount` / `blogPostCount` / `attachmentCount` /
`attachmentBytes`），现状按上面的落点口径数（页面数要排掉 `.Blog` 那个博客空间）。
数字对不上就是没导完或导坏了。重导是幂等的（导入器会先清掉上次导入产生的页面）。

**注册空间**：Confluence 空间导入后的落点就是顶层空间 `<SpaceKey>.WebHome`，
**不需要移动任何内容**，只差一个标记对象。判定口径与 `Spaces` 空间目录、`SpaceManager` 一致：
该 WebHome 上有没有 `SpaceCode.SpaceClass`。

| 项 | 规则 |
|---|---|
| 空间名 | 取 `<SpaceKey>.WebHome` 的标题（导入器从 Confluence 带来）；没有则用空间 Key |
| 类型 | 默认 `public`；Key 以 `~` 开头（Confluence 个人空间）记 `personal` |
| owner | 记当前操作者（不迁账号，无法还原原 owner） |
| 前提 | **只有现状与 manifest 完全一致**才允许注册，避免把没导完的空间标成已完成 |
| 幂等 | 已注册的跳过 |

> **重导会清掉空间标记**：导入器默认先删掉上次导入产生的页面，`<SpaceKey>.WebHome` 上的
> 对象也会一起没；回索引页再点一次即可。注册不修改任何 `xwiki-platform` 代码，
> 只是给已有页面加一个对象。

## 五、作者与用户

页面、博客、评论、附件、空间描述的作者与创建时间都会随包导出，并落到对应的 XWiki 用户上。
**不迁账号**——账号由 OA 同步按工号建，认证走 SSO。

Confluence 用户名 → XWiki 用户引用的规则（`ConfluenceConverter`），按优先级：

1. `userIdMapping` 显式映射（`confluence用户名=XWiki用户名`，`|` 分隔）；
2. 特例 `admin` → `Admin`；
3. `userFormat`（`${username}` / `${username._lowerCase}` / `${username._clean}` 等）；
4. 默认：只把 `.`、空格、`/` 换成 `_`，**其余原样保留，大小写不变**。

工号是纯字母数字，走第 4 条原样通过：`S1100` → `XWiki.S1100`，`wx` → `XWiki.wx`。
**只要 XWiki 里存在同名同大小写的用户文档，就零配置直接对上。**

三个实测确认的点：

- **`admin` 被写死映射成 `XWiki.Admin`（大写 A）**。标准 XWiki 的管理员文档是这个名字，
  但本 fork 里是 `XWiki.admin`（小写），于是 admin 建的内容会挂到一个不存在的用户上。
  这条排在 `userFormat` **之前**，所以只有 `userIdMapping=admin=admin` 能修。
- **用户名大小写**：Confluence 的用户管理界面强制小写，但那是**表单层校验**，
  从 AD/LDAP 同步进来的用户不经过它、保留目录里的原样大小写（所以内网出现 `S1100` 很正常）。
  迁移时大小写原样带过去；若 XWiki 侧大小写不一致，用 `userFormat=${username._lowerCase}`（批量）
  或 `userIdMapping`（逐个）统一。
- **`CREATOR` 为 NULL 的内容很常见**（实测有一个空间 30 行全是 NULL），这类内容导入后
  作者会回退成执行导入的人——是源数据没带作者，不是丢数据。

前提：**XWiki 里必须已存在该用户**，否则 XWiki 不报错，但页面上是一个不存在的用户引用。
关掉 `usersEnabled` **不影响**作者映射（它只决定要不要发 `InternalUser` 实体）。

> 导出依赖 `CONTENT.PARENTCOMMENTID` 列（7.3.3 实测存在）。

## 六、评论与 @提及

评论（正文、作者、时间、**回复层级**）与 **@提及** 都会一起迁移：

- 挂在**附件**上的评论会归到附件所属的页面上——导入器只认页面作评论宿主，
  不这样处理评论会被静默丢弃；
- 提及会被转成 XWiki 的 `{{mention}}`；导出时会扫一遍正文，把"只被提及、不是任何作者"的人
  也写进包，否则提及会退化成一个不存在的用户引用（且不报错）。

## 七、排错

| 现象 | 处理 |
|---|---|
| 报错"数据库密码看起来是加密的" | 用 `--password` 传明文 |
| `inventory` 报 `ATTACHMENTDATA` 行数不为 0 | 附件在数据库里而非文件系统，本工具的附件定位规则不适用 |
| `validate` 报附件文件缺失 | 附件目录指错，或 Confluence 清理过数据，用快照重导 |
| Filter 页面里没有 `confluence+xml` | 导入器没装 |
| 「管理 → 扩展」搜不到扩展 | `xwiki.properties` 的 `extension.repositories` 是空的，只能手工装 `WEB-INF/lib` |
| 注册报"现状与 manifest 不一致" | 包还没导完或导坏了，先按第四节对账 |
| 注册成功但页面顶部不显示空间信息 | 类 sheet `SpaceCode.SpaceSheet` 在本 fork 里不存在，对象不会被渲染；不影响"是不是空间" |

## 附：完整参数

```bash
java -cp "$CP" $ALIAS help
```

`--confluence-home` 指向含 `confluence.cfg.xml` 的目录，本工具从中读取
`hibernate.connection.url` / `username` / `password` 与 `attachments.dir`。

## 附：不迁移的内容

用户与组实体、权限、历史版本、增量同步、关注/监视（Confluence 的导出格式里也没有）。
草稿与回收站按策略不导出。收藏（Confluence 的 `favourite` 标签）本身随包导出，
但需要 XWiki 侧装 Favorites Application 才会生效。
