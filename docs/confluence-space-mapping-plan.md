# XWiki 仿 Confluence 空间方案

## 1. 目标

基于单 Wiki + 顶级页面树，实现接近 Confluence 的两类空间能力：

- 团队空间 / 项目空间：每个空间独立组织文档、权限、导航和首页
- 个人空间：每个用户一个独立区域，用于个人主页、博客、草稿和个人知识沉淀

不采用 Subwiki。原因是当前目标更偏向产品层面的“空间体验”和“逻辑隔离”，而不是实例级隔离。

## 2. 核心设计原则

- 一个 Confluence Space 不直接映射为 XWiki 的技术 `space`，而是映射为“一个顶级根页面及其整棵子树”
- 所有空间都必须有统一的空间元数据，否则后续权限、导航、搜索和迁移都会失控
- 团队空间和个人空间共享同一套抽象，但在创建规则、权限默认值和展示入口上分流
- 尽量复用 XWiki 已有能力，而不是重造底层

当前仓库里已经有可复用基础：

- 导航配置：`PanelsCode.NavigationConfiguration`
- Space 级偏好页：`<Space>.WebPreferences`
- 空间仪表盘模板：`Dashboard.SpaceDashboardTemplate`
- 用户主页 / 用户面板：`Dashboard.XWikiUserDashboardSheet`、`XWiki.AdminUserProfileSheet`

## 3. 信息架构

### 3.1 顶层区域

建议把所有“空间根页面”统一挂在两个顶层目录下：

- 团队空间根：`Spaces`
- 个人空间根：`People`

示例：

- `Spaces.HR.WebHome`
- `Spaces.DevPlatform.WebHome`
- `People.ZhangSan.WebHome`

这样做的好处：

- 团队空间和个人空间可以统一治理
- 顶层导航更容易裁剪
- 搜索、权限、统计、迁移脚本都更容易按前缀识别

### 3.2 空间根页面

每个空间以一个根页面代表，根页面下挂完整子树。

示例：

- `Spaces.DevPlatform.WebHome`
- `Spaces.DevPlatform.MeetingNotes.WebHome`
- `Spaces.DevPlatform.Architecture.API-Gateway`

个人空间同理：

- `People.ZhangSan.WebHome`
- `People.ZhangSan.Blog.WebHome`
- `People.ZhangSan.Drafts.Idea-001`

### 3.3 空间元数据

建议新增一个“空间元数据类”，挂在每个空间根页面上，例如：

- 类名：`XWiki.SpaceDescriptorClass`

建议字段：

- `spaceKey`
- `spaceType`
- `displayName`
- `description`
- `owner`
- `admins`
- `visibility`
- `navigationRoot`
- `homepageTemplate`
- `blogRoot`
- `archived`

字段含义：

- `spaceKey`：对标 Confluence Space Key，要求全局唯一
- `spaceType`：`team` / `personal`
- `owner`：团队空间可填主负责人；个人空间固定为用户本人
- `admins`：空间管理员组或用户列表
- `visibility`：`private` / `internal` / `public`
- `navigationRoot`：默认等于当前根页面，但保留扩展空间
- `homepageTemplate`：首页模板标识
- `blogRoot`：博客根页面引用
- `archived`：空间是否归档

这个类是整个方案的关键。后续所有逻辑不要靠页面路径字符串硬编码判断。

## 4. 团队空间方案

### 4.1 创建规则

创建团队空间时，系统自动完成：

- 在 `Spaces` 下生成根页面
- 写入 `SpaceDescriptorClass`
- 初始化默认首页
- 初始化默认子页面
- 初始化默认权限
- 初始化左侧导航配置

建议默认生成结构：

- `WebHome`
- `Pages`
- `MeetingNotes`
- `Decisions`
- `Blog`
- `Templates`

这不是强制文档结构，只是创建时的启动骨架。

### 4.2 首页

团队空间首页建议以现有 `Dashboard.SpaceDashboardTemplate` 为基础定制成“空间首页”，内容包含：

- 空间简介
- 最近更新
- 常用入口
- 子页面树
- 空间博客入口
- 空间成员

不要直接把默认 dashboard 原样暴露出来。Confluence 用户对“空间首页”的预期是稳定、结构化，而不是可随意拼装的小组件页。

### 4.3 导航

导航应切成两层：

- 全局导航：只展示 `Spaces`、`People`、搜索、最近访问等
- 空间内导航：进入某空间后，左侧树只展示该空间根页面下的内容

实现上建议：

- 复用现有 `Panels.Navigation` 和 `PanelsCode.NavigationConfiguration`
- 增加“当前空间上下文”判断
- 当当前页面属于某空间子树时，导航树根切换为该空间根页面

这一步很重要。它决定用户是否会感知到“空间是独立的”。

### 4.4 权限

团队空间默认按空间根页面继承：

- 浏览权限
- 编辑权限
- 评论权限
- 删除权限
- 管理权限

建议引入空间角色：

- `SpaceAdmin`
- `SpaceEditor`
- `SpaceViewer`

映射方式：

- 每个团队空间创建对应组，或通过对象把用户映射到角色
- 权限只在空间根设置，子页面默认继承

这样比逐页授权更可控，也更接近 Confluence。

## 5. 个人空间方案

### 5.1 目标定义

个人空间不是普通用户资料页的别名，而是一个真正的内容区域。它至少要承载：

- 个人主页
- 个人博客
- 草稿 / 笔记
- 个人资料延展信息

也就是说，`XWiki.<User>` 用户资料页仍然保留，但它更多是“身份档案”；个人空间则是“个人内容空间”。

### 5.2 路径模型

建议每个用户对应一个固定空间根：

- `People.<UserName>.WebHome`

示例：

- `People.Alice.WebHome`
- `People.Alice.Blog.WebHome`
- `People.Alice.Drafts.WebHome`

不建议把个人内容直接塞进 `XWiki.<User>` 页面体系，原因是：

- 用户资料页语义和内容空间语义不同
- 权限、导航和后续迁移不方便
- 用户资料页通常是系统页，不适合承载大量业务内容

### 5.3 创建时机

建议支持两种模式：

- 惰性创建：用户第一次访问“我的空间”时自动创建
- 预创建：管理员批量为已有用户初始化个人空间

优先建议惰性创建，原因是：

- 降低初始化成本
- 避免给从不使用知识库的人生成大量空页面

### 5.4 默认结构

建议个人空间默认生成：

- `WebHome`
- `Blog`
- `Drafts`
- `Notes`
- `About`

其中：

- `WebHome`：个人主页
- `Blog`：个人博客列表或博客入口
- `Drafts`：默认仅本人可见
- `Notes`：个人知识沉淀
- `About`：补充个人介绍

### 5.5 权限模型

个人空间默认值建议：

- `WebHome` 和 `Blog`：公司内可读
- `Drafts`：仅本人和管理员可读写
- 其他页面：默认继承个人空间根权限

更完整的做法是给个人空间定义可切换模式：

- `private`：仅本人可见
- `internal`：公司内可见，只有本人可编辑
- `public`：对匿名或外部开放，通常不建议默认启用

### 5.6 与用户资料页关系

建议：

- 用户头像、邮箱、组织信息仍来自 `XWiki.<User>`
- 用户资料页增加一个“个人空间”入口
- 个人空间首页展示用户基本资料摘要，但不反向承载完整 profile 编辑逻辑

这样能保持身份数据和内容数据分层。

## 6. 博客模型

Confluence 里的个人空间通常承载个人博客，团队空间也常有团队博客。

建议统一处理：

- 每个空间都有一个 `Blog` 根页面
- `team` 空间和 `personal` 空间的博客实现共用一套页面模板

可选实现：

- 简化版：`Blog` 下普通子页面按时间倒序聚合展示
- 完整版：引入独立博客类对象，区分“博客文章”和“普通页面”

如果当前阶段目标是快速替换 Confluence，建议先做简化版：

- 博客文章本质仍是普通页面
- 通过 `BlogPostClass` 标识为博客文章
- 按空间范围聚合展示

优点：

- 与现有页面、权限、附件、评论完全兼容
- 迁移 Confluence blog post 时成本低

## 7. URL 与路由建议

底层文档引用仍可保留 XWiki 原生格式，但对外入口建议统一产品化：

- 团队空间首页：`/spaces/{spaceKey}`
- 团队空间子页：`/spaces/{spaceKey}/{pagePath}`
- 个人空间首页：`/people/{userName}`
- 个人博客：`/people/{userName}/blog`

内部映射：

- `{spaceKey}` -> `Spaces.<NormalizedName>.WebHome`
- `{userName}` -> `People.<UserName>.WebHome`

如果当前阶段不改路由，也至少要保证：

- 页面标题、面包屑、侧边栏、创建页入口都使用“空间”术语
- 用户不会频繁看到底层 `Spaces.xxx` / `People.xxx` 技术路径

## 8. 搜索与范围隔离

搜索需要支持三种范围：

- 全站
- 当前团队空间
- 当前个人空间

进入空间后，搜索默认带当前空间过滤条件，这一点很关键。

否则用户会觉得“虽然叫空间，但搜索结果还是全站混在一起”。

建议搜索过滤依据：

- 当前页面所属空间根页面
- 或空间元数据对象中的 `spaceKey`

## 9. 创建、迁移与生命周期

### 9.1 创建流程

团队空间：

1. 输入 `spaceKey`、名称、描述、管理员
2. 生成根页面和默认结构
3. 绑定权限和角色
4. 完成导航和首页初始化

个人空间：

1. 根据用户标识定位 `People.<UserName>.WebHome`
2. 若不存在则自动创建
3. 写入 `spaceType=personal`
4. 初始化默认权限和博客区域

### 9.2 迁移映射

Confluence 团队空间：

- `Confluence Space` -> `Spaces.<SpaceName>.WebHome`
- `Space Home` -> `WebHome`
- `Space Pages` -> 子页面树
- `Space Blog` -> `Blog` 子树

Confluence 个人空间：

- `Personal Space` -> `People.<UserName>.WebHome`
- `Profile Home / Personal Blog` -> `WebHome` / `Blog`

### 9.3 生命周期

空间状态建议支持：

- `active`
- `archived`
- `deleted`

归档空间行为：

- 默认只读
- 搜索结果降权或隐藏
- 导航默认折叠或从常规入口移除

## 10. 实施路线

### 第一阶段：先做产品语义闭环

目标是让用户先感知到“有空间”。

范围：

- 定义 `SpaceDescriptorClass`
- 建立 `Spaces` / `People` 根目录
- 团队空间创建器
- 个人空间惰性创建
- 空间首页模板
- 左侧导航切换到当前空间根
- 用户资料页增加“个人空间”入口

### 第二阶段：补齐体验

范围：

- 空间范围搜索
- 博客聚合页
- 空间成员管理界面
- 空间归档能力
- 统一的“创建空间 / 创建个人空间”管理页

### 第三阶段：路由和迁移增强

范围：

- `/spaces/*`、`/people/*` 产品化路由
- Confluence 空间批量导入映射
- 空间统计、审计、容量管理

## 11. 对当前项目的建议结论

这次定制不建议把“空间”理解成 XWiki 底层模型改造，而应该理解成一层产品抽象：

- 底层仍是 XWiki 页面树
- 上层定义“团队空间”和“个人空间”
- 用统一元数据、统一模板、统一导航和统一权限把它产品化

这样能以较低改造成本获得接近 Confluence 的使用体验，同时保留 XWiki 单实例、统一搜索、统一扩展的优势。

## 12. 下一步建议

建议下一步直接进入实现设计，先拆 4 个最小可落地任务：

1. 新增 `SpaceDescriptorClass`
2. 实现团队空间和个人空间的页面结构生成器
3. 改导航面板，让其按“当前空间根”展示页面树
4. 在用户资料页增加“个人空间”入口，并接入惰性创建逻辑

这 4 步完成后，系统层面就已经具备“Confluence 风格空间”的基本骨架。

## 13. 迁移兼容设计

### 13.1 基本判断

本方案会影响迁移后的落地结构，但不阻断 `Confluence XML` 和 `Filter Streams Converter Application` 的使用。

应当把迁移流程拆成两段：

1. 原生导入：负责把 Confluence 导出包中的页面、附件、评论、历史和基础权限导进 XWiki
2. 空间整形：负责把导入结果整理成你们定义的“团队空间 / 个人空间”模型

换句话说，官方迁移工具负责“把内容导进来”，你们的定制逻辑负责“把内容变成产品最终形态”。

### 13.2 兼容原则

为了兼容迁移工具，空间方案需要遵守以下原则：

- 不要求导入器一次性产出最终页面结构
- 不要求导入器直接写入 `SpaceDescriptorClass`
- 不要求导入器天然识别团队空间和个人空间
- 所有空间产品化逻辑都允许在导入后补写

这几条很重要。否则方案会和官方导入路径强耦合，后期升级迁移器或替换导入方式都会变得困难。

### 13.3 推荐迁移架构

建议把 Confluence 迁移流程固定为：

1. 导入阶段
2. 识别阶段
3. 重定位阶段
4. 补元数据阶段
5. 权限修正阶段
6. 首页 / 导航修正阶段
7. 校验阶段

其中：

- 导入阶段：使用 `Confluence XML` + `Filter Streams Converter`
- 其余阶段：使用你们自己的整形脚本、批处理作业或后台管理页面

### 13.4 团队空间迁移策略

Confluence 团队空间建议映射为：

- `Confluence Space` -> `Spaces.<NormalizedSpaceName>.WebHome`

如果迁移工具支持导入时指定 `root space`，优先建议：

- 所有团队空间统一导入到 `Spaces` 根下

这样可以减少后续重定位成本。

导入完成后，对每个团队空间执行：

- 生成或补写 `SpaceDescriptorClass`
- 生成 `spaceKey`
- 设置 `spaceType=team`
- 绑定团队空间首页模板
- 初始化 `Blog` 入口
- 初始化团队空间管理员和角色组
- 修正导航根到该空间根页面

如果迁移工具无法直接导到 `Spaces` 根下，则允许先导到中间位置，再通过整形脚本移动。

### 13.5 个人空间迁移策略

Confluence 个人空间不要直接沿用团队空间处理方式。

建议目标模型固定为：

- `Confluence Personal Space` -> `People.<UserName>.WebHome`

但迁移工具通常不会天然理解“personal space 应导入 People 树”。因此个人空间建议单独处理：

- 导入前识别 personal space 清单
- 分批导入 personal space
- 导入后重定位到 `People.<UserName>`
- 补写 `spaceType=personal`
- 生成个人空间默认子结构
- 修正默认权限

个人空间导入后的默认结构可以分两种情况：

- 如果 Confluence 个人空间本身已有博客和页面树，则保留原内容，只补缺失结构
- 如果内容较少，则可补建 `Blog`、`Drafts`、`Notes`、`About`

### 13.6 空间识别规则

迁移后必须有一套稳定的空间识别规则，不能依赖人工判断。

建议识别顺序如下：

1. 优先使用导入元数据中的 Confluence `spaceKey`
2. 如果有 personal space 标志，则识别为 `personal`
3. 若空间所有者与空间名称满足个人空间规则，也可作为辅助判断
4. 无法识别时，先标记为 `team-pending`

建议在整形阶段保留一张迁移映射表，例如：

- `sourceType`
- `sourceSpaceKey`
- `sourceSpaceName`
- `sourceOwner`
- `targetRoot`
- `targetSpaceType`
- `migrationStatus`

这样后续做重跑、回滚、补偿都更容易。

### 13.7 重定位策略

重定位指的是把导入后的页面树移动到最终目标结构。

建议规则：

- 团队空间移动到 `Spaces.<NormalizedName>`
- 个人空间移动到 `People.<UserName>`
- 迁移时保留页面树层级、附件、评论和引用关系

重定位必须是显式阶段，不要和导入阶段混写。原因是：

- 出问题时更容易定位
- 可以重复执行
- 可以单独对个人空间做补偿

### 13.8 元数据补写

迁移工具导入结束后，必须补写以下最小元数据：

- `spaceKey`
- `spaceType`
- `displayName`
- `owner`
- `visibility`
- `navigationRoot`
- `blogRoot`

其中：

- 团队空间的 `spaceKey` 优先继承 Confluence Space Key
- 个人空间的 `spaceKey` 建议采用 `PERSONAL_<UserName>` 或更稳定的内部命名规则

不要在迁移后靠页面路径反推全部语义。路径只能作为辅助依据，不能替代元数据。

### 13.9 权限迁移与修正

官方迁移能处理一部分空间权限，但你们的产品模型还需要二次修正。

团队空间需要补的通常是：

- 空间角色组映射
- 根页面统一继承策略
- 管理员组绑定

个人空间需要补的通常是：

- `Drafts` 默认仅本人和管理员可见
- `WebHome` / `Blog` 按公司策略决定是否内部可见
- 用户本人自动获得该个人空间管理权

因此建议权限处理分两层：

- 第一层：接受官方导入的基础 rights
- 第二层：按 `spaceType` 和公司规则做标准化修正

### 13.10 首页和导航修正

迁移工具不会自动生成你们定义的空间首页体验，因此导入后需要做统一修正：

- 团队空间：首页绑定团队空间模板
- 个人空间：首页绑定个人空间模板
- 当前空间导航根切换到最终根页面
- 加入空间博客入口和成员入口

如果迁移原始首页内容价值较高，可以把它保留为：

- `ImportedHome`

然后新的 `WebHome` 作为产品化首页，把原内容嵌进去或链接过去。

### 13.11 推荐的最小迁移后整形脚本职责

建议后续实现一个“迁移后整形作业”，至少完成以下动作：

1. 扫描导入后的空间根
2. 判断 `team` / `personal`
3. 计算最终目标路径
4. 移动到 `Spaces` 或 `People`
5. 补写 `SpaceDescriptorClass`
6. 绑定首页模板
7. 修正导航根
8. 修正基础权限
9. 输出迁移报告

迁移报告建议包含：

- 成功空间数
- 失败空间数
- 未识别空间列表
- 路径冲突列表
- 权限修正告警

### 13.12 风险点

需要特别注意以下风险：

- Confluence personal space 识别不稳定
- 已存在同名用户或同名空间导致路径冲突
- 迁移后的链接、宏和附件引用在重定位后需要验证
- 导入工具生成的权限对象不一定符合你们的角色模型
- 用户资料页与个人空间之间可能出现“双主页”认知冲突

因此，个人空间迁移建议先小批量试迁，不要一上来全量跑。

### 13.13 最终建议

如果你们明确要长期依赖官方迁移工具，那么空间方案应当遵循这一条总原则：

- `官方迁移负责内容导入`
- `你们的定制逻辑负责空间产品化`

不要试图把所有空间语义都塞进导入器配置里。最稳的做法，是把迁移兼容性设计成“可导入、可重定位、可补元数据、可重复整形”的流水线。
