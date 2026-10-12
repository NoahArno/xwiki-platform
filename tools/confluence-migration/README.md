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
（建议 `Migration.Import`，语法 `xwiki/2.1`，用有编程权限的用户保存）。它把 B 侧剩下的活都包了：

- 列出所有包，显示 manifest 期望值 vs XWiki 现状，给出**参数已预填**的转换链接（点一下就到上面那张表）；
- 导入完成、数字对上之后，点 **注册为空间** 或 **一键注册全部已完成的空间**，
  由它在 `<SpaceKey>.WebHome` 上挂一个 `SpaceCode.SpaceClass` 对象。

## 四、对账 + 注册空间

**对账**：刷新索引页，或直接拿 `manifest.csv` 对：

```csv
spaceKey,packageDir,pageCount,blogPostCount,attachmentCount,attachmentBytes,exportedAt
SPC,SPC,2,0,6,121718,2026-10-10 16:13:42
ds,ds,10,0,20,785398,2026-10-10 16:13:42
```

导入器把一个 Confluence 页面落成一个**嵌套空间** `<SpaceKey>.<PageTitle>`，内容在该空间的
`WebHome`，所以「页面数」= 该前缀下 `*.WebHome` 文档数（不含自动生成的 `<SpaceKey>.WebPreferences`）。
数字对不上就是没导完或导坏了。重导是幂等的（导入器会先清掉上次导入产生的页面）。

**注册空间**：Confluence 的一个空间导入后，落点就是顶层空间 `<SpaceKey>.WebHome`，
所以**不需要移动任何内容**，只差一个标记对象。点击索引页的注册按钮即可，规则如下：

| 项 | 规则 |
|---|---|
| 判定口径 | 顶层空间的 `WebHome` 上有没有 `SpaceCode.SpaceClass` 对象（与 `Spaces` 空间目录、`SpaceManager` 完全一致） |
| 空间名 | 取 `<SpaceKey>.WebHome` 的标题（导入器从 Confluence 带过来，如 `ds` → `Demonstration Space`）；没有则用空间 Key |
| 类型 | 默认 `public`；Key 以 `~` 开头的（Confluence 个人空间）记 `personal` |
| owner | 记当前操作者（本工具不迁用户，无法还原原 owner） |
| 前置条件 | **只有现状与 manifest 完全一致**的包才允许注册；数字对不上会拒绝，避免把没导完的空间标成已完成 |
| 幂等 | 已注册的空间会跳过，不会重复挂对象 |

> 这一步不修改任何 `xwiki-platform` 代码——「是不是空间」只由那条 XWQL 判定，
> 注册本质上就是给已有页面加一个对象，用脚本 API 即可完成。

## 五、作者（创建人/修改人）

页面、博客、评论、附件、空间描述的作者与创建时间都会随包导出，导入后落到对应的 XWiki 用户上，
**不需要迁账号**（账号由 OA 同步按工号建，认证走 SSO）。

导入器把 Confluence 用户名转成 XWiki 用户引用的规则（`ConfluenceConverter`）：

1. `userIdMapping` 显式映射（`confluence用户名=XWiki用户名`，`|` 分隔）；
2. 特例 `admin` → `Admin`；
3. `userFormat`；
4. **默认：只把非法字符换成 `_`**。

工号是纯字母数字，走第 4 条后**原样保留**，所以结果就是 `XWiki.<工号>` ——
只要 XWiki 里已经有这个工号的用户，作者就直接对上，无需任何配置。

前提与注意：

- **XWiki 里必须已存在该用户**。不存在时 XWiki 不会报错，但页面上会显示一个不存在的用户引用；
- 关掉 `usersEnabled` **不影响**作者映射——它只决定要不要发 `InternalUser` 实体；
- Confluence 库里 `CREATOR` 为 NULL 的内容很常见（实测有一个空间的 30 行全是 NULL），
  这类内容导入后作者会回退成执行导入的人，属于数据本身没带作者，不是丢数据；
- Confluence 用户名与工号**不一致**时（比如英文名、带域后缀），用 `userIdMapping` 兜底。

> **重导会清掉空间标记**：导入器默认先删掉上次导入产生的页面，所以 `<SpaceKey>.WebHome` 上的
> `SpaceCode.SpaceClass` 对象也会一起没。重导后回索引页再点一次「注册为空间」即可。

**评论**也会一起迁移：正文、作者、时间，以及**回复层级**。两个容易踩的点：

- 挂在**附件**上的评论会被归到附件所属的页面上。导入器只认页面作为评论的宿主
  （`ConfluenceXMLPackage.readCommentObject` 只把它挂到 Page 下），不这样处理评论会被**静默丢弃**；
- 回复的父评论有两种写法，实测都遇到过：UI 建的回复用 `PARENTCOMMENTID`，部分 API 写入的回复
  把父评论放在 `PAGEID` 上。两种都兼容。

> 导出依赖 `CONTENT.PARENTCOMMENTID` 列（Confluence 7.3.3 实测存在）。

## 六、排错

| 现象 | 处理 |
|---|---|
| 报错"数据库密码看起来是加密的" | 用 `--password` 传明文 |
| `inventory` 报 `ATTACHMENTDATA` 行数不为 0 | 附件在数据库里而非文件系统，本工具的附件定位规则不适用 |
| `validate` 报附件文件缺失 | 附件目录指错，或 Confluence 清理过数据，用快照重导 |
| Filter 页面里没有 `confluence+xml` | 导入器没装 |
| 「管理 → 扩展」搜不到扩展 | `xwiki.properties` 的 `extension.repositories` 是空的，只能手工装 `WEB-INF/lib` |
| 注册按钮点了没反应 / 报"现状与 manifest 不一致" | 包还没导完或导坏了，先按上面的对账口径查一遍 |
| 注册成功但页面顶部不显示空间信息 | `SpaceClassDocumentInitializer` 把 `SpaceCode.SpaceSheet` 绑成了 class sheet，但该页面不存在（404），所以对象不会被渲染。不影响「是不是空间」（目录能列出来），只是显示缺一块 |

## 附：完整参数

```bash
java -cp "$CP" $ALIAS help
```

配置来源：`--confluence-home` 指向含 `confluence.cfg.xml` 的目录，本工具从中读取
`hibernate.connection.url` / `username` / `password` 与 `attachments.dir`。

## 附：当前不迁移的内容

用户与组实体、权限、历史版本、增量同步（导入时对应开关关掉）。草稿与回收站按策略一律不导出。

作者/创建时间是迁的（见[第五节](#五作者创建人修改人)），但**不建账号**——账号归 OA 同步。
