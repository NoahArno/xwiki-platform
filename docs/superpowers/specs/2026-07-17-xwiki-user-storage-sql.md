# XWiki 用户与组存储 — SQL 参考

> 用于 OA 用户数据同步时直接通过 SQL 操作 XWiki 数据库。

## 核心要点

- XWiki **没有**独立的用户表，用户数据存储为通用 EAV（Entity-Attribute-Value）模型
- 用户是 `xwikidoc` 中的文档 + 附着的 `XWiki.XWikiUsers` 对象
- 组是 `xwikidoc` 中的文档 + 附着的 `XWiki.XWikiGroups` 对象
- `company` 字段是**自由文本**，不是结构化的部门/机构
- 组织架构应映射为**组**

## 涉及的表

| 表名 | Hibernate 类 | 作用 |
|------|------------|------|
| `xwikidoc` | `XWikiDocument` | 文档（用户/组本身） |
| `xwikiobjects` | `BaseObject` | 附着到文档的对象（class 名区分用户/组） |
| `xwikistrings` | `StringProperty` | 字符串类型属性值 |
| `xwikiintegers` | `IntegerProperty` | 整数类型属性值 |
| `xwikilargestrings` | `LargeStringProperty` | 大文本属性值 |
| `xwikidates` | `DateProperty` | 日期类型属性值 |

### 核心列说明

**xwikidoc:**
| 列名 | 说明 |
|------|------|
| `XWD_ID` | 文档主键 |
| `XWD_FULLNAME` | 文档全名（如 `XWiki.admin`） |
| `XWD_WEB` | 空间名（用户都在 `XWiki` 中） |
| `XWD_NAME` | 文档名（用户名） |
| `XWD_HIDDEN` | 是否隐藏 |

**xwikiobjects:**
| 列名 | 说明 |
|------|------|
| `XWO_ID` | 对象主键 |
| `XWO_NAME` | 所属文档全名（对应 `xwikidoc.XWD_FULLNAME`） |
| `XWO_CLASSNAME` | 类名：`XWiki.XWikiUsers` 或 `XWiki.XWikiGroups` |
| `XWO_NUMBER` | 对象序号 |

**xwikistrings:**
| 列名 | 说明 |
|------|------|
| `XWS_ID` | 对应 `xwikiobjects.XWO_ID` |
| `XWS_NAME` | 属性名（如 `first_name`, `email`, `company`） |
| `XWS_VALUE` | 属性值 |

**xwikiintegers:**
| 列名 | 说明 |
|------|------|
| `XWI_ID` | 对应 `xwikiobjects.XWO_ID` |
| `XWI_NAME` | 属性名（如 `active`） |
| `XWI_VALUE` | 属性值 |

## 用户属性清单

来自 `XWikiUsersDocumentInitializer`（`xwiki-platform-core/xwiki-platform-oldcore/src/main/java/com/xpn/xwiki/internal/mandatory/XWikiUsersDocumentInitializer.java`）：

| 属性名 | 类型 | 存储表 | 说明 |
|--------|------|--------|------|
| `first_name` | String | `xwikistrings` | 名 |
| `last_name` | String | `xwikistrings` | 姓 |
| `email` | String | `xwikistrings` | 邮箱 |
| `password` | String | `xwikipasswords` | 密码 |
| `validkey` | String | `xwikipasswords` | 验证密钥 |
| `active` | Integer | `xwikiintegers` | 是否激活 (1=激活, 0=禁用) |
| `company` | String | `xwikistrings` | 公司（自由文本） |
| `blog` | String | `xwikistrings` | 博客 |
| `blogfeed` | String | `xwikistrings` | 博客 RSS |
| `comment` | TextArea | `xwikilargestrings` | 备注 |
| `imtype` | StaticList | `xwikilists` | IM 类型 |
| `imaccount` | String | `xwikistrings` | IM 账号 |
| `editor` | StaticList | `xwikilists` | 默认编辑器 |
| `usertype` | StaticList | `xwikilists` | 用户类型 |
| `underline` | StaticList | `xwikilists` | 链接下划线样式 |
| `displayHiddenDocuments` | Boolean | `xwikiintegers` | 显示隐藏文档 |
| `timezone` | Timezone | `xwikistrings` | 时区 |
| `skin` | Page | `xwikistrings` | 皮肤 |
| `avatar` | String | `xwikistrings` | 头像 |
| `phone` | String | `xwikistrings` | 电话 |
| `address` | TextArea | `xwikilargestrings` | 地址 |
| `extensionConflictSetup` | Boolean | `xwikiintegers` | 扩展冲突设置 |
| `email_checked` | Boolean | `xwikiintegers` | 邮箱已验证 |

## SQL 查询

### 查所有用户

```sql
SELECT d.XWD_FULLNAME
FROM xwikidoc d
INNER JOIN xwikiobjects o ON o.XWO_NAME = d.XWD_FULLNAME
WHERE o.XWO_CLASSNAME = 'XWiki.XWikiUsers';
```

### 查单个用户的所有字符串属性

```sql
SELECT s.XWS_NAME AS field, s.XWS_VALUE AS value
FROM xwikiobjects o
INNER JOIN xwikistrings s ON s.XWS_ID = o.XWO_ID
WHERE o.XWO_CLASSNAME = 'XWiki.XWikiUsers'
  AND o.XWO_NAME = 'XWiki.admin';   -- 替换为实际用户名
```

### 查单个用户的所有整数属性

```sql
SELECT i.XWI_NAME AS field, i.XWI_VALUE AS value
FROM xwikiobjects o
INNER JOIN xwikiintegers i ON i.XWI_ID = o.XWO_ID
WHERE o.XWO_CLASSNAME = 'XWiki.XWikiUsers'
  AND o.XWO_NAME = 'XWiki.admin';
```

### 查所有用户的常用字段

```sql
SELECT
    d.XWD_FULLNAME                                    AS username,
    s_first.XWS_VALUE                                 AS first_name,
    s_last.XWS_VALUE                                  AS last_name,
    s_email.XWS_VALUE                                 AS email,
    s_company.XWS_VALUE                               AS company,
    s_phone.XWS_VALUE                                 AS phone,
    COALESCE(i_active.XWI_VALUE, 1)                   AS active
FROM xwikidoc d
INNER JOIN xwikiobjects o
    ON o.XWO_NAME = d.XWD_FULLNAME
    AND o.XWO_CLASSNAME = 'XWiki.XWikiUsers'
LEFT JOIN xwikistrings s_first
    ON s_first.XWS_ID = o.XWO_ID AND s_first.XWS_NAME = 'first_name'
LEFT JOIN xwikistrings s_last
    ON s_last.XWS_ID = o.XWO_ID AND s_last.XWS_NAME = 'last_name'
LEFT JOIN xwikistrings s_email
    ON s_email.XWS_ID = o.XWO_ID AND s_email.XWS_NAME = 'email'
LEFT JOIN xwikistrings s_company
    ON s_company.XWS_ID = o.XWO_ID AND s_company.XWS_NAME = 'company'
LEFT JOIN xwikistrings s_phone
    ON s_phone.XWS_ID = o.XWO_ID AND s_phone.XWS_NAME = 'phone'
LEFT JOIN xwikiintegers i_active
    ON i_active.XWI_ID = o.XWO_ID AND i_active.XWI_NAME = 'active';
```

### 查所有组

```sql
SELECT d.XWD_FULLNAME AS group_name
FROM xwikidoc d
INNER JOIN xwikiobjects o ON o.XWO_NAME = d.XWD_FULLNAME
WHERE o.XWO_CLASSNAME = 'XWiki.XWikiGroups';
```

### 查某个用户所属的所有组

```sql
SELECT d.XWD_FULLNAME AS group_name
FROM xwikidoc d
INNER JOIN xwikiobjects o
    ON o.XWO_NAME = d.XWD_FULLNAME
    AND o.XWO_CLASSNAME = 'XWiki.XWikiGroups'
INNER JOIN xwikistrings s
    ON s.XWS_ID = o.XWO_ID
    AND s.XWS_NAME = 'member'
WHERE s.XWS_VALUE IN ('XWiki.admin', 'admin', 'xwiki:XWiki.admin');
-- XWiki 会用多种格式查找组成员，建议用 IN 覆盖所有变体
```

### 查某个组下所有成员

```sql
SELECT s.XWS_VALUE AS member_name
FROM xwikiobjects o
INNER JOIN xwikistrings s
    ON s.XWS_ID = o.XWO_ID
    AND s.XWS_NAME = 'member'
WHERE o.XWO_CLASSNAME = 'XWiki.XWikiGroups'
  AND o.XWO_NAME = 'XWiki.XWikiAdminGroup';
```

### 创建用户的 SQL（完整流程）

创建用户需要同时写入多张表，建议通过 XWiki API（`XWiki.createUser()` 或 `XWiki.createEmptyUser()`）创建，不要直接写 SQL。如必须直接操作数据库，需写入：

```sql
-- 1. 在 xwikidoc 中创建文档记录
INSERT INTO xwikidoc (XWD_ID, XWD_FULLNAME, XWD_NAME, XWD_TITLE, XWD_WEB, XWD_SPACE,
    XWD_CREATION_DATE, XWD_DATE, XWD_CONTENT_UPDATE_DATE, XWD_CONTENT, XWD_AUTHOR,
    XWD_CONTENT_AUTHOR, XWD_CREATOR, XWD_VERSION, XWD_CUSTOM_CLASS, XWD_PARENT,
    XWD_DEFAULT_TEMPLATE, XWD_VALIDATION_SCRIPT, XWD_COMMENT, XWD_MINOREDIT,
    XWD_SYNTAX_ID, XWD_HIDDEN, XWD_ELEMENTS, XWD_TRANSLATION, XWD_LANGUAGE,
    XWD_DEFAULT_LANGUAGE)
VALUES (..., 'XWiki.zhangsan', 'zhangsan', 'zhangsan', 'XWiki', 'XWiki', ...);

-- 2. 在 xwikiobjects 中创建 XWiki.XWikiUsers 对象
INSERT INTO xwikiobjects (XWO_ID, XWO_NAME, XWO_CLASSNAME, XWO_NUMBER)
VALUES (..., 'XWiki.zhangsan', 'XWiki.XWikiUsers', 0);

-- 3. 在 xwikistrings 中写入属性值（每个属性一行）
INSERT INTO xwikistrings (XWS_ID, XWS_NAME, XWS_VALUE)
VALUES (..., 'first_name', '张三');

INSERT INTO xwikistrings (XWS_ID, XWS_NAME, XWS_VALUE)
VALUES (..., 'last_name', '');

INSERT INTO xwikistrings (XWS_ID, XWS_NAME, XWS_VALUE)
VALUES (..., 'email', 'zhangsan@company.com');

INSERT INTO xwikistrings (XWS_ID, XWS_NAME, XWS_VALUE)
VALUES (..., 'company', '技术部');

INSERT INTO xwikistrings (XWS_ID, XWS_NAME, XWS_VALUE)
VALUES (..., 'phone', '13800138000');

-- 4. 在 xwikiintegers 中写入 active = 1
INSERT INTO xwikiintegers (XWI_ID, XWI_NAME, XWI_VALUE)
VALUES (..., 'active', 1);

-- 5. 在 xwikiproperties 中写入属性元数据（每个属性一行）
-- 这一步 Hibernate 会自动处理
```

> **强烈建议用 API 而非直接 SQL**：XWiki 的用户创建过程还会触发事件、设置权限、加入默认组、生成 ID 等。直接写 SQL 可能破坏数据完整性。

### 创建用户的 API 方式（推荐）

```java
// Java API - 创建空用户（不设密码，适用于 OA 登录）
XWiki xwiki = context.getWiki();
Map<String, String> map = new HashMap<>();
map.put("first_name", "张三");
map.put("last_name", "");
map.put("email", "zhangsan@company.com");
map.put("company", "技术部");
map.put("active", "1");

// 推荐方式
xwiki.createEmptyUser("zhangsan", "edit", context);

// 或更底层的 API
DocumentReference userClassRef = new DocumentReference(
    context.getWikiId(), "XWiki", "XWikiUsers");
xwiki.createUser("zhangsan", map,
    getRelativeEntityReferenceResolver().resolve("XWiki.XWikiUsers", EntityType.DOCUMENT),
    "", Syntax.XWIKI_1_0.toIdString(), "edit", context);
```

### 将用户加入组

```java
xwiki.setUserDefaultGroup("XWiki.zhangsan", context);
// 或手动指定组
xwiki.addUserToGroup("XWiki.zhangsan", "XWiki.技术部", context);
```
