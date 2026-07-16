# OA 单点登录 (SSO) 集成设计

> 2026-07-15 | 状态：待评审

## 1. 需求概述

集成公司内部 OA 系统的单点登录功能。OA 系统在用户登录后，通过回调 URL 将认证信息传递到 XWiki 系统，XWiki 进行签名校验后自动完成用户登录。

### 1.1 交互流程

```
User → OA → 回调 http://xwiki-server/xwiki/bin/oalogin/
         ?pid={pid}
         &userLoginId={工号/用户名}
         &timestamp={时间戳}
         &sign={MD5签名}
```

### 1.2 签名规则

`sign = MD5(pid + userLoginId + timestamp + KEY)`

其中 `KEY` 为 OA 系统分配给本应用的固定密钥。

### 1.3 校验流程

1. 提取请求参数：`pid`、`userLoginId`、`timestamp`、`sign`
2. 从 `xwiki.cfg` 读取 `KEY`
3. 计算 `MD5(pid + userLoginId + timestamp + KEY)` 并与传入的 `sign` 比对（大小写不敏感）
4. 匹配成功 → 在 XWiki 中查找 `userLoginId` 对应的用户
5. 用户存在 → 设置登录态，重定向至首页
6. 用户不存在 → 返回错误信息"当前用户 xxx 不存在"

## 2. 关键决策

| 决策项 | 选择 | 说明 |
|--------|------|------|
| URL 路径 | 新建独立路径 `/xwiki/bin/oalogin/` | 不与现有登录路径耦合 |
| 用户不存在 | 拒绝登录 | 不自动创建用户，显示错误信息 |
| KEY 存储 | `xwiki.cfg` → `xwiki.authentication.oa.key` | 与 XWiki 现有配置方式一致 |
| 与密码登录关系 | 两者共存 | 互不影响，各自独立运作 |
| Timestamp 校验 | 不做时效校验 | 仅校验签名 |

## 3. 文件变更清单

| 操作 | 文件路径 | 说明 |
|------|---------|------|
| 新建 | `xwiki-platform-core/xwiki-platform-oldcore/src/main/java/com/xpn/xwiki/web/OALoginAction.java` | OA 登录 Action 类 |
| 新建 | `xwiki-platform-core/xwiki-platform-oldcore/src/main/resources/templates/oalogin.vm` | OA 登录错误展示模板 |
| 修改 | `xwiki.cfg`（部署时） | 添加 `xwiki.authentication.oa.key` 配置项 |

> 注：`@Named("oalogin")` 注解会被 XWiki 内置 Action 分发器自动路由为 `/xwiki/bin/oalogin/`，无需额外配置 URL 映射。

## 4. 架构设计

### 4.1 调用链路

```
OA 系统
  │
  │  GET /xwiki/bin/oalogin/?pid=...&userLoginId=...&timestamp=...&sign=...
  ▼
XWiki Action 分发器 (根据 @Named("oalogin") 路由)
  │
  ▼
OALoginAction.action()
  │
  ├── ① 参数提取 & 完整性检查
  ├── ② 读取 OA KEY (xwiki.cfg → 内存缓存)
  ├── ③ MD5 签名计算 & 比对
  ├── ④ XWiki 用户查找 (XWiki.exists → HQL 回退)
  ├── ⑤ 设置登录态 → SecurityRequestWrapper.setUserPrincipal()
  │         + UserAuthenticatedEventNotifier.notify()
  └── ⑥ 重定向至首页
```

### 4.2 与现有认证体系的关系

```
┌─────────────────────────────────────────────┐
│               SecurityFilter                 │
│                    │                         │
│     ┌──────────────┼──────────────┐          │
│     ▼              ▼              ▼          │
│  /login/      /loginsubmit/   /oalogin/     │
│  (LoginAction) (LoginSubmit    (OALoginAction)
│                  Action)                     │
│     │              │              │          │
│     ▼              ▼              ▼          │
│  XWikiAuthServiceImpl.checkAuth()            │
│     │              │              │          │
│     ▼              ▼              ▼          │
│  密码认证        表单提交认证    OA签名认证    │
│                                      │       │
│                    ┌─────────────────┘       │
│                    ▼                         │
│          SecurityRequestWrapper              │
│            .setUserPrincipal()               │
└─────────────────────────────────────────────┘
```

OALoginAction 完全独立于 LoginAction/LoginSubmitAction，不修改任何现有认证代码。

## 5. 核心代码设计

### 5.1 OALoginAction

```java
@Component
@Named("oalogin")
@Singleton
public class OALoginAction extends XWikiAction
{
    @Override
    public boolean action(XWikiContext context) throws XWikiException
    {
        HttpServletRequest  request  = context.getRequest().getHttpServletRequest();
        HttpServletResponse response = context.getResponse();

        // ① 提取 OA 参数
        String pid         = request.getParameter("pid");
        String userLoginId = request.getParameter("userLoginId");
        String timestamp   = request.getParameter("timestamp");
        String sign        = request.getParameter("sign");

        // ② 参数完整性检查
        if (StringUtils.isAnyBlank(pid, userLoginId, timestamp, sign)) {
            context.put("message", "oa_missing_params");
            return true;
        }

        // ③ 读取 OA KEY
        String oaKey = context.getWiki().Param("xwiki.authentication.oa.key");
        if (StringUtils.isBlank(oaKey)) {
            context.put("message", "oa_key_not_configured");
            return true;
        }

        // ④ MD5 签名校验
        String computedSign = DigestUtils.md5Hex(pid + userLoginId + timestamp + oaKey);
        if (!computedSign.equalsIgnoreCase(sign)) {
            context.put("message", "oa_sign_verification_failed");
            return true;
        }

        // ⑤ 查找 XWiki 用户
        String user = findUserByLoginId(userLoginId, context);
        if (user == null) {
            context.put("message", "当前用户 " + userLoginId + " 不存在");
            return true;
        }

        // ⑥ 设置登录态
        String principalName = context.getWikiId() + ":" + user;
        SimplePrincipal principal = new SimplePrincipal(principalName);

        SecurityRequestWrapper wrappedRequest =
            new SecurityRequestWrapper(request, null, null, "FORM");
        wrappedRequest.setUserPrincipal(principal);

        UserAuthenticatedEventNotifier notifier =
            Utils.getComponent(UserAuthenticatedEventNotifier.class);
        notifier.notify(principalName);

        // ⑦ 重定向到首页
        String redirectUrl = context.getURLFactory().createURL(
            context.getWiki().getDefaultSpace(context),
            context.getWiki().getDefaultPage(context), "view", context
        ).toString();
        response.sendRedirect(response.encodeRedirectURL(redirectUrl));
        return false;
    }

    @Override
    public String render(XWikiContext context) throws XWikiException
    {
        String msg = (String) context.get("message");
        if (StringUtils.isNotBlank(msg)) {
            context.getResponse().setStatus(HttpServletResponse.SC_FORBIDDEN);
        }
        return "oalogin";
    }
}
```

### 5.2 用户查找逻辑

```java
private String findUserByLoginId(String username, XWikiContext context) throws XWikiException
{
    // 精确查找 XWiki.<username> 文档
    DocumentReference userRef = new DocumentReference(context.getWikiId(), "XWiki", username);
    if (context.getWiki().exists(userRef, context)) {
        return "XWiki." + username;
    }

    // 回退 HQL（兼容大小写不敏感数据库如 MySQL）
    String sql = "select distinct doc.fullName from XWikiDocument as doc";
    Object[][] whereParams = new Object[][] {
        { "doc.space", "XWiki" },
        { "doc.name", username }
    };
    List<String> list = context.getWiki().search(sql, whereParams, context);
    return list.isEmpty() ? null : list.get(0);
}
```

### 5.3 错误模板 (oalogin.vm)

```html
<!DOCTYPE html>
<html>
<head>
    <title>OA 单点登录</title>
</head>
<body>
    <h1>OA 登录失败</h1>
    <p class="errormessage">$!escapetool.html($message)</p>
</body>
</html>
```

## 6. 配置

### 6.1 xwiki.cfg

```properties
# OA 单点登录密钥
xwiki.authentication.oa.key=<OA颁发的密钥>
```

### 6.2 OA 回调地址

```
http://<xwiki-host>/xwiki/bin/oalogin/?pid=<应用ID>&userLoginId=<工号>&timestamp=<时间戳>&sign=<MD5签名>
```

## 7. 错误场景

| 场景 | HTTP 状态码 | message |
|------|------------|---------|
| 缺少必填参数 | 403 | `oa_missing_params` |
| OA KEY 未配置 | 403 | `oa_key_not_configured` |
| 签名校验失败 | 403 | `oa_sign_verification_failed` |
| 用户不存在 | 403 | `当前用户 xxx 不存在` |

## 8. 依赖

- `commons-codec`（DigestUtils.md5Hex）— XWiki 已有此依赖
- `org.securityfilter` — XWiki 已有此依赖
- `org.xwiki.component` — XWiki 核心依赖

> 无新增外部依赖。

## 9. 安全性考量

- OA KEY 存储在服务器端配置文件，不暴露给客户端
- 签名使用 MD5，仅用于请求合法性验证（OA 端已通过认证）
- 登录态由 SecurityFilter 框架管理，与现有安全机制一致

## 10. 部署步骤

1. 编译 `xwiki-platform-oldcore` 模块：`mvn install -pl xwiki-platform-core/xwiki-platform-oldcore -am`
2. 将编译后的 JAR 部署到 Tomcat 的 `WEB-INF/lib/`
3. 在 `xwiki.cfg` 中添加 `xwiki.authentication.oa.key` 配置
4. 重启 Tomcat
5. 在 OA 系统中配置回调地址
