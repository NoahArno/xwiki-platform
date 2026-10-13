# Confluence → XWiki 迁移工具

从 Confluence 数据库 + 附件目录**只读**生成 XWiki 可导入的包，再手动导入到 XWiki。

流程：**A 侧指定空间 → 导出到目录 → 人工拷到 B → 在 B 上导入**。
已在 Confluence 7.3.3 + XWiki 18.1.0 上实测通过。

## 一、A 侧：导出

前置：JDK 17+；对 Confluence 数据库与附件目录的**只读**访问。

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
显示 manifest 期望值 vs XWiki 现状、给出**参数已预填**的转换链接，对账通过后还能一键注册空间，
`~` 开头的个人空间包会多一步「准备个人空间包」（原因见第六节）。

## 四、对账 + 注册空间

**落点**：Confluence 页面 → 嵌套空间 `<SpaceKey>.<标题>` 的 `WebHome`；
Confluence 博客 → `<SpaceKey>.Blog.<标题>`（普通文档）。导入器还会自建
`<SpaceKey>.Blog.WebHome` 与 `<SpaceKey>.WebPreferences` 两个壳文档。

**对账**：期望值来自 `manifest.csv`（`pageCount` / `blogPostCount` / `attachmentCount` /
`attachmentBytes`），现状按上面的落点口径数（页面数要排掉 `.Blog` 那个博客空间）。
数字对不上就是没导完或导坏了。重导是幂等的（导入器会先清掉上次导入产生的页面）。
个人空间的「现状」只数导入器建的文档（带 `Confluence.Code.ConfluencePageClass`），
用户自己在那个空间里的页面单列一行提示、不计入对账。

**注册空间**：Confluence 空间导入后的落点就是顶层空间 `<SpaceKey>.WebHome`，
**不需要移动任何内容**，只差一个标记对象。判定口径与 `Spaces` 空间目录、`SpaceManager` 一致：
该 WebHome 上有没有 `SpaceCode.SpaceClass`。

| 项 | 规则 |
|---|---|
| 空间名 | 取 `<SpaceKey>.WebHome` 的标题（导入器从 Confluence 带来）；没有则用空间 Key |
| 类型 | 默认 `public`；`~` 开头的个人空间见第六节 |
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

## 六、个人空间（Confluence 的 `~user`）

Confluence 里以 `~` 开头的空间是个人空间。本 fork 的个人空间不是 `<SpaceKey>`，而是
**`<用户名>的个人空间`**：`XWiki.<用户名>` 资料页上的「进入我的个人空间」按钮，判定的是
`<用户名>的个人空间.WebHome` 存在、且上面 `SpaceCode.SpaceClass.type == 'personal'`
（见 `Spaces.UserProfilePersonalSpaceUIX` / `Spaces.MyPersonalSpace`）。

导入器不认识这个约定，只会把 `~test01` 的内容落在空间 `~test01`；它也没有参数能换目标空间
（`rootSpace` 只是父空间前缀，`spaceRenamingFormat` 是防覆盖改名用的）。所以分三步：

1. 在索引页点 **「准备个人空间包」**。它只改包里的空间身份两处：`entities.xml` 里 `Space` 对象的
   `key`/`lowerKey`、`exportDescriptor.properties` 的 `spaceKey`，`~test01` → `test01的个人空间`。
   页面/博客/附件都是按空间 id 引用的，所以不用改任何内容或链接，也不需要导入后的改名作业。
   这一步幂等，并在包目录留下标记文件 `.xwiki-personal-space`；A 侧重新导出并 `rsync --delete`
   覆盖后标记会消失，页面自动回到「未准备」。
2. 点「导入 →」走 Convert。个人空间包会**额外带 `overwriteProtectionMode=NONE`**：导入器默认
   在目标空间发现非 Confluence 内容时会把这次导入改名（`test01的个人空间_`）而不是写进去，
   而个人空间天生就「已经有东西」（fork 建的空间标记页、用户自己的笔记），不关掉就合并不了。
3. 对账通过后点「注册为空间」，它按 fork 的口径写：

| 项 | 值 |
|---|---|
| 文档 | `<用户名>的个人空间.WebHome`，标题也是 `<用户名>的个人空间` |
| name / key | 都是 `<用户名>的个人空间` |
| type | `personal` |
| owner | `xwiki:XWiki.<用户名>`（**用户还不存在也照写**：内网账号由 OA 同步，先迁内容后建人是常态） |
| description | `<用户名> 的个人空间`（走翻译键 `spaces.personal.description`） |

三条边界：

- **用户名**就是 Confluence 空间 key 去掉 `~`、原样大小写，不跟随 `userFormat` / `userIdMapping`
  （内网是纯字母数字工号，两者本来就一致）。
- **合并**：目标个人空间已存在（用户点过「开通我的个人空间」，或上次迁移留下的）时直接写进去——
  同名页面以 Confluence 为准，用户自己不同名的页面保留。
- **旧的 `~user` 空间**：早期用旧脚本导过的内容会留在 `~user` 里成孤儿，页面会提示
  「检测到旧的 `~user` 空间……请手工清理」。工具不删数据。

## 七、排错

| 现象 | 处理 |
|---|---|
| 报错"数据库密码看起来是加密的" | 用 `--password` 传明文 |
| `inventory` 报 `ATTACHMENTDATA` 行数不为 0 | 附件在数据库里而非文件系统，本工具的附件定位规则不适用 |
| `validate` 报附件文件缺失 | 附件目录指错，或 Confluence 清理过数据，用快照重导 |
| Filter 页面里没有 `confluence+xml` | 导入器没装 |
| 「管理 → 扩展」搜不到扩展 | `xwiki.properties` 的 `extension.repositories` 是空的，只能手工装 `WEB-INF/lib` |
| 注册报"现状与 manifest 不一致" | 包还没导完或导坏了，先按第四节对账 |
| 点「注册为空间」跳到 `/bin/view/<空间>.<页面>` 显示找不到页面 | 页面里自己拼了 `doc.fullName`。`/bin/view/` 的路径只认 `<空间>/<页面>` 两段，`<空间>.<页面>` 会被当成空间名。自链接一律用 `xwiki.getURL(doc.documentReference, 'view', ...)` |
| 个人空间包没准备就点了「导入」 | 内容会落到旧的 `~test01`（导入器不认识个人空间约定）。点「准备个人空间包」后重导，旧的 `~test01` 空间手工清理 |
| 合并后个人空间变成了 `test01的个人空间_` | 这个包没带 `overwriteProtectionMode=NONE`（旧版页面，或手工在 Filter 页填的参数）。删掉带下划线的空间，用索引页生成的链接重导 |
| 资料页上「进入我的个人空间」不出现 | 该按钮只对已登录用户渲染，且要求 `<用户名>的个人空间.WebHome` 上的 `SpaceCode.SpaceClass.type == 'personal'`；在索引页点一次「注册为空间」会补齐 |
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
