# 知悉 (DOAP) OAuth2 单点登录集成设计

> 2026-07-22 | 状态：已实现

## 1. 需求概述

集成公司内部知悉系统的 OAuth2 单点登录功能。知悉使用 DOAP 认证中心，提供标准 OAuth2 Authorization Code Flow 接口。用户登录知悉后点击 XWiki 入口，通过 OAuth2 协议完成 XWiki 的自动登录。

### 1.1 与现有 OA SSO 的区别

| 维度 | OA SSO（已有） | 知悉 OAuth2（本次新增） |
|------|:---:|:---:|
| 协议 | 自定义 MD5 签名回调 | 标准 OAuth2 Authorization Code Flow |
| 认证方式 | MD5(pid + userLoginId + timestamp + KEY) | 授权码 → access_token → user_info |
| Servlet 路径 | `/xwiki/oa-login` | `/xwiki/zhixi-login` |
| 用户字段 | URL 参数 `userLoginId` | DOAP user_info 接口返回 `username`（工号） |
| 两者关系 | 完全独立，共存不干扰 |

### 1.2 交互流程

```
用户                 XWiki                       知悉 DOAP
 │                     │                            │
 │  ① 点击"知悉登录"    │                            │
 ├────────────────────>│ GET /xwiki/zhixi-login     │
 │                     │                            │
 │  ② 302 重定向        │                            │
 │<────────────────────┤                            │
 │                     │                            │
 │  ③ 浏览器跟随重定向   │                            │
 ├─────────────────────────────────────────────────>│ GET /api/sso/oauth/authorize
 │                     │                            │ ?client_id=xxx
 │                     │                            │ &response_type=code
 │                     │                            │ &redirect_uri=...
 │                     │                            │ &state=<random>
 │                     │                            │
 │  ④ 用户在知悉完成登录  │                            │
 │                     │                            │
 │  ⑤ 302 回调 XWiki    │                            │
 │<─────────────────────────────────────────────────│ Location: /xwiki/zhixi-login
 │                     │                            │ ?code=xxx&state=xxx
 │                     │                            │
 │  ⑥ 浏览器跟随重定向   │                            │
 ├────────────────────>│ GET /xwiki/zhixi-login     │
 │                     │ ?code=xxx&state=xxx        │
 │                     │                            │
 │                     │  ⑦ 校验 state（防 CSRF）    │
 │                     │                            │
 │                     │  ⑧ POST 换 token（后端）    │
 │                     ├───────────────────────────>│ /api/sso/oauth/token
 │                     │<───────────────────────────│ {access_token, ...}
 │                     │                            │
 │                     │  ⑨ GET 用户信息（后端）      │
 │                     ├───────────────────────────>│ /api/sso/oauth/user_info
 │                     │<───────────────────────────│ {username, name, email, enabled}
 │                     │                            │
 │                     │  ⑩ 校验 enabled + 查找用户  │
 │                     │  ⑪ 设置登录态               │
 │                     │                            │
 │  ⑫ 302 跳转 XWiki 首页│                           │
 │<────────────────────┤                            │
```

## 2. 关键决策

| 决策项 | 选择 | 说明 |
|--------|------|------|
| 实现形式 | 独立 HttpServlet | 与 OALoginAction 模式一致，绕过 SecurityFilter |
| 依赖 | 零新增 Maven 依赖 | 仅用已有的 httpclient5 + jackson + commons-codec |
| 用户不存在 | 拒绝登录 | 不自动创建用户，与 OA SSO 行为一致 |
| `enabled=false` | 拒绝登录 | 提示"当前用户 xxx 已被禁用" |
| CSRF 防护 | 始终带 state 参数 | 随机生成，存入 HTTP Session，回调时比对 |
| 域名切换 | `xwiki.authentication.zhixi.doap-host` 配置 | 按环境填入对应值即可 |
| Token 存储 | 不落盘 | access_token 仅在内存使用，用完即弃 |

## 3. 文件变更清单

| 操作 | 文件路径 | 说明 |
|------|---------|------|
| **新建** | `xwiki-platform-core/xwiki-platform-oldcore/src/main/java/com/xpn/xwiki/web/ZhixiOAuth2LoginAction.java` | OAuth2 登录 Servlet（~340 行） |
| **新建** | `xwiki-platform-core/xwiki-platform-oldcore/src/test/java/com/xpn/xwiki/web/ZhixiOAuth2LoginActionTest.java` | 单元测试（8 个用例） |
| **修改** | `xwiki-platform-core/xwiki-platform-web/xwiki-platform-web-war/src/main/webapp/WEB-INF/web.xml` | 注册 OALoginAction (`/oa-login`) + ZhixiOAuth2LoginAction (`/zhixi-login`) |
| **修改** | `xwiki-platform-tools/xwiki-platform-tool-configuration-resources/src/main/resources/xwiki.cfg.vm` | 新增 OA + 知悉 OAuth2 共 5 个配置项模板 |

## 4. OAuth2 端点说明（知悉 DOAP API）

> DOAP 认证中心地址：测试环境 `http://sit-doap.mis.bcs`，UAT `http://uat-doap.mis.bcs`，生产 `http://doap.mis.bcs`

| 步骤 | DOAP 端点 | 方法 | 关键参数 |
|------|----------|------|---------|
| 授权 | `/api/sso/oauth/authorize` | GET | `client_id`, `response_type=code`, `redirect_uri`, `state` |
| 令牌 | `/api/sso/oauth/token` | POST | Query String: `client_id`, `client_secret`, `code`, `grant_type=authorization_code`, `redirect_uri` |
| 用户信息 | `/api/sso/oauth/user_info` | GET | Header: `Authorization: Bearer {access_token}` |

**令牌端点响应：**
```json
{
    "access_token": "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCJ9...",
    "token_type": "bearer",
    "expires_in": 3599,
    "scope": "read",
    "jti": "871182a7-f4e7-491f-90ba-59b48c410c16"
}
```

**用户信息端点响应：**
```json
{
    "username": "工号",
    "name": "中文名",
    "email": "邮箱",
    "enabled": true,
    "authorities": [
        { "authority": "ADMIN", "description": "" }
    ]
}
```

## 5. 架构设计

### 5.1 与现有认证体系的关系

```
┌────────────────────────────────────────────────────────┐
│                  SecurityFilter                         │
│                      │                                  │
│       ┌──────────────┼──────────────┬──────────────┐   │
│       ▼              ▼              ▼              ▼   │
│    /login/     /loginsubmit/   /oa-login/   /zhixi-login/
│    (表单页)     (表单提交)     (OA SSO)     (知悉OAuth2)
│       │              │              │              │   │
│       ▼              ▼              ▼              ▼   │
│  XWikiAuthServiceImpl        OALoginAction   ZhixiOAuth2
│       │                     (HttpServlet)   LoginAction
│       ▼                                      (HttpServlet)
│  MyFormAuthenticator                              │   │
│  (密码认证)                                       │   │
│       │                                           │   │
│       └─────────── 三种登录方式 ──────────────────┘   │
│                       │                              │
│                       ▼                              │
│             SecurityRequestWrapper                   │
│               .setUserPrincipal()                    │
└──────────────────────────────────────────────────────┘
```

ZhixiOAuth2LoginAction 完全独立于现有认证代码，不修改 XWikiAuthServiceImpl、MyFormAuthenticator 等。

### 5.2 核心类结构

```
ZhixiOAuth2LoginAction extends HttpServlet
│
├── doGet(request, response)           ← 唯一 HTTP 入口
│   ├── code == null
│   │   └── startAuthorization()       ← 生成 state → 302 DOAP
│   └── code != null
│       └── handleCallback()           ← 完整回调处理
│           ├── 校验 state（防 CSRF）
│           ├── exchangeToken()        ← HTTP POST → DOAP /token
│           ├── fetchUserInfo()        ← HTTP GET  → DOAP /user_info
│           ├── 校验 enabled
│           ├── findUserByLoginId()    ← XWiki 用户查找
│           └── loginAndRedirect()     ← 设置登录态 → 302 首页
│
├── initializeXWikiContext()           ← 复用 OALoginAction 模式
├── writeError()                       ← 错误 HTML 响应
│
└── 内部类:
    ├── ZhixiTokenResponse             ← JSON → POJO（@JsonProperty snake_case 映射）
    └── ZhixiUserInfo                  ← JSON → POJO
```

### 5.3 用户查找逻辑

与 OA SSO 完全一致的双重查找策略：

1. **精确查找** `DocumentReference.exists()` — 检查 `XWiki.<username>` 文档是否存在
2. **HQL 回退** `select distinct doc.fullName from XWikiDocument as doc where doc.space='XWiki' and doc.name=<username>` — 兼容大小写不敏感数据库（如 MySQL）

## 6. 配置

### 6.1 xwiki.cfg（部署时添加）

```properties
# ─── 知悉 (DOAP) OAuth2 单点登录 ───
# DOAP 认证中心地址（按环境修改）：
#   测试: http://sit-doap.mis.bcs
#   UAT:  http://uat-doap.mis.bcs
#   生产: http://doap.mis.bcs
xwiki.authentication.zhixi.doap-host=http://sit-doap.mis.bcs

# OAuth2 客户端凭证（由认证中心分配）
xwiki.authentication.zhixi.client-id=<your_client_id>
xwiki.authentication.zhixi.client-secret=<your_client_secret>

# XWiki 回调地址（必须在知悉认证中心注册）
xwiki.authentication.zhixi.redirect-uri=http://<xwiki-host>/xwiki/zhixi-login
```

### 6.2 web.xml

`/oa-login` 和 `/zhixi-login` 的 Servlet 注册已内置在 `web.xml` 中，部署时无需手动添加。

### 6.3 部署时只需做

| HTTP 状态 | 错误信息 | 触发条件 |
|-----------|---------|---------|
| 403 | 系统配置错误：知悉 OAuth2 未配置 | `doap-host` 或 `client-id` 未设置 |
| 403 | CSRF 校验失败 | state 参数不匹配 |
| 403 | 获取访问令牌失败 | token 端点返回非 200 |
| 403 | 获取用户信息失败 | user_info 端点返回非 200 |
| 403 | 当前用户 xxx 已被禁用 | DOAP 返回 `enabled: false` |
| 403 | 当前用户 xxx 不存在 | 用户在 XWiki 中不存在 |
| 500 | 系统内部错误 | 未知异常 |

## 8. 与 session 过期的配合

当用户 session 或 Remember-Me cookie 过期后，现有配置 `xwiki.authentication.oa.homepage` 可将用户重定向到知悉门户首页，用户重新登录知悉后再次点击 XWiki 入口即可：

```properties
# xwiki.cfg
xwiki.authentication.oa.homepage=http://doap.mis.bcs/
```

配合 `xwiki.properties` 中的 `url.trustedDomains` 确保重定向不被 SafeRedirectFilter 拦截。

## 9. 用户管理

- XWiki 用户需**提前创建**（文档名 = 知悉工号），如 `XWiki.admin`
- 管理员仍可通过 `/xwiki/bin/login/XWiki/XWikiLogin` 使用密码登录
- 知悉 OAuth2 登录与 OA SSO 登录、密码登录三者完全独立共存

## 10. 部署步骤

1. 编译 `xwiki-platform-oldcore` 模块：
   ```bash
   mvn -s ~/.m2/settings-xwiki.xml -f pom.xml \
     -pl xwiki-platform-core/xwiki-platform-oldcore -am \
     -Dxwiki.spoon.skip=true -Dlicense.skip=true -DskipTests=true -Dxwiki.checkstyle.skip=true \
     install
   ```

2. 将 JAR 部署到 Tomcat：
   ```bash
   cp xwiki-platform-core/xwiki-platform-oldcore/target/xwiki-platform-oldcore-18.1.0.jar \
      <tomcat>/webapps/xwiki/WEB-INF/lib/
   ```

3. **web.xml 配置**：`/oa-login` 和 `/zhixi-login` 的 Servlet 注册已内置在 `web.xml` 模板中，无需手动添加。如需确认，检查 `<tomcat>/webapps/xwiki/WEB-INF/web.xml` 中存在以下内容：
   ```xml
   <!-- OA SSO login servlet -->
   <servlet>
     <servlet-name>OALoginServlet</servlet-name>
     <servlet-class>com.xpn.xwiki.web.OALoginAction</servlet-class>
     <load-on-startup>0</load-on-startup>
   </servlet>
   <servlet-mapping>
     <servlet-name>OALoginServlet</servlet-name>
     <url-pattern>/oa-login</url-pattern>
   </servlet-mapping>

   <!-- 知悉 (DOAP) OAuth2 SSO login servlet -->
   <servlet>
     <servlet-name>ZhixiOAuth2LoginServlet</servlet-name>
     <servlet-class>com.xpn.xwiki.web.ZhixiOAuth2LoginAction</servlet-class>
     <load-on-startup>0</load-on-startup>
   </servlet>
   <servlet-mapping>
     <servlet-name>ZhixiOAuth2LoginServlet</servlet-name>
     <url-pattern>/zhixi-login</url-pattern>
   </servlet-mapping>
   ```

4. **xwiki.cfg 配置**：配置项模板已内置在 `xwiki.cfg` 中，只需取消注释并填入实际值。在 `<tomcat>/webapps/xwiki/WEB-INF/xwiki.cfg` 中找到 `# SSO (Single Sign-On)` 段：
   ```properties
   # OA SSO
   xwiki.authentication.oa.key=<OA颁发的密钥>
   xwiki.authentication.oa.homepage=http://oa.company.com/

   # 知悉 (DOAP) OAuth2 SSO
   xwiki.authentication.zhixi.doap-host=http://sit-doap.mis.bcs
   xwiki.authentication.zhixi.client-id=<实际client_id>
   xwiki.authentication.zhixi.client-secret=<实际client_secret>
   xwiki.authentication.zhixi.redirect-uri=http://<实际xwiki域名>/xwiki/zhixi-login
   ```

5. **xwiki.properties 配置**：如果 `xwiki.authentication.oa.homepage` 或 `zhixi.redirect-uri` 指向外部域名，需在 `<tomcat>/webapps/xwiki/WEB-INF/xwiki.properties` 中将域名加入白名单，否则 redirect 会被 SafeRedirectFilter 拦截：
   ```properties
   url.trustedDomains=oa.company.com,doap.mis.bcs
   ```

6. 在知悉认证中心注册回调地址 `http://<xwiki-host>/xwiki/zhixi-login`

7. 重启 Tomcat

8. 验证：浏览器访问 `http://<xwiki-host>/xwiki/zhixi-login`，应被重定向到知悉登录页

## 11. 技术细节

- **HTTP 客户端**：`org.apache.hc.client5` (httpclient5-5.6)，已在依赖树中
- **JSON 解析**：`com.fasterxml.jackson.databind.ObjectMapper`，使用 `@JsonProperty` 处理 DOAP API 的 snake_case 字段名
- **CSRF state**：32 字节 SecureRandom → Base64 URL-encoded → HTTP Session
- **Jakarta → javax 桥接**：使用 `org.xwiki.jakartabridge.servlet.JakartaServletBridge.toJavax()`，与 OALoginAction 一致
- **XWiki 上下文初始化**：`Utils.prepareContext()` + `ServletContainerInitializer` + `XWiki.getXWiki()` + `prepareResources()`，与 OALoginAction 完全一致
- **登录态设置**：`SimplePrincipal` → `SecurityRequestWrapper.setUserPrincipal()` + Session attribute + `UserAuthenticatedEventNotifier`
