# OA 单点登录 — 部署与配置指南

> 面向 DevOps 和运维人员的完整部署指南，包含架构说明、配置参数、故障排查。

## 架构

```
OA 系统                         XWiki (Tomcat)
──────                         ──────
用户点击 OA 入口 ──→ /xwiki/oa-login?pid=&userLoginId=&timestamp=&sign=
                                      │
                                      ▼
                              OALoginAction (HttpServlet)
                              ① 校验签名 MD5(pid + userLoginId + timestamp + KEY)
                              ② 查找用户 findUserByLoginId()
                              ③ 写入 HttpSession PRINCIPAL
                              ④ 302 → XWiki 首页
```

OALoginAction 是一个**独立 Servlet**（非 XWiki Action），部署在 `/xwiki/oa-login`，绕过 XWiki 认证过滤器链。

## 1. 构建

```bash
cd xwiki-platform
mvn -s ~/.m2/settings-xwiki.xml -f pom.xml \
  -pl xwiki-platform-core/xwiki-platform-oldcore -am \
  -Dxwiki.spoon.skip=true -Dlicense.skip=true -DskipTests=true -Dxwiki.checkstyle.skip=true install

cp xwiki-platform-core/xwiki-platform-oldcore/target/xwiki-platform-oldcore-18.1.0.jar \
   <tomcat>/webapps/xwiki/WEB-INF/lib/
```

## 2. 配置 xwiki.cfg

在 `<tomcat>/webapps/xwiki/WEB-INF/xwiki.cfg` 中添加：

```properties
# OA 单点登录密钥（与 OA 系统约定）
xwiki.authentication.oa.key=<OA系统分配给你们的密钥>
```

## 3. 注册 Servlet

在 `<tomcat>/webapps/xwiki/WEB-INF/web.xml` 的 `</web-app>` 之前添加：

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
```

## 4. 重启 Tomcat

```bash
<tomcat>/bin/shutdown.sh
<tomcat>/bin/startup.sh
```

## 5. 在 OA 系统中配置回调地址

```
http://<your-server>/xwiki/oa-login?pid=<应用ID>&userLoginId=<工号>&timestamp=<时间戳>&sign=<MD5签名>
```

### 签名算法

```
sign = MD5(pid + userLoginId + timestamp + KEY)
```

参数全部按字符串拼接，无分隔符。`KEY` 与 xwiki.cfg 中的 `xwiki.authentication.oa.key` 一致。

### 示例 (KEY = `1789713hkj12h3k123kjh`)

| 参数 | 值 |
|------|-----|
| pid | 1000 |
| userLoginId | admin |
| timestamp | 12312312312213 |
| KEY | 1789713hkj12h3k123kjh |
| 拼接串 | `1000admin123123123122131789713hkj12h3k123kjh` |
| sign | `1ee87e8bcd0147c20db8484222685d03` |

URL: `http://localhost:8080/xwiki/oa-login?pid=1000&userLoginId=admin&timestamp=12312312312213&sign=1ee87e8bcd0147c20db8484222685d03`

## 6. 验证

```bash
# 生成测试签名（替换成实际的 KEY）
echo -n "1000admin$(date +%s%3N)你的OA密钥" | md5
```

浏览器访问：
```
http://localhost:8080/xwiki/oa-login?pid=1000&userLoginId=admin&timestamp=<时间戳>&sign=<MD5值>
```

自动跳转到 XWiki 首页 → 部署成功。

## 7. 错误码

| HTTP 状态 | 错误信息 | 原因 |
|-----------|---------|------|
| 403 | 缺少必填参数（pid、userLoginId、timestamp、sign） | 参数不完整 |
| 403 | 系统配置错误：OA 密钥未设置 | xwiki.cfg 未配置 key |
| 403 | 签名校验失败 | sign 不匹配 |
| 403 | 当前用户 xxx 不存在 | 用户未在 XWiki 中创建 |
| 302 | (跳转首页) | 登录成功 |

## 8. 会话与认证配置

### 8.1 Session 有效期

XWiki web.xml **未**设置 `<session-timeout>`，因此使用 **Tomcat 默认值 30 分钟**。

在 `<tomcat>/webapps/xwiki/WEB-INF/web.xml` 中添加以覆盖：

```xml
<session-config>
    <session-timeout>30</session-timeout>  <!-- 分钟，Tomcat 默认 30 -->
</session-config>
```

### 8.2 Remember-Me Cookie 有效期

配置项：`xwiki.cfg` → `xwiki.authentication.cookielife`

**默认值：15 天**。取值范围：小数天数（如 `0.5` = 12 小时）。

Cookie max-age 计算公式（`MyPersistentLoginManager.setMaxAge()`）：
```
maxAge = 60 × 60 × 24 × cookielife
```

```properties
# xwiki.cfg - Remember-Me cookie 有效期（天）
xwiki.authentication.cookielife=15
```

相关配置：

| 属性 | 默认值 | 说明 |
|------|--------|------|
| `xwiki.authentication.cookielife` | 15 | Cookie 有效期（天） |
| `xwiki.authentication.validationKey` | — | Cookie 签名密钥 |
| `xwiki.authentication.encryptionKey` | — | Cookie 加密密钥 |
| `xwiki.authentication.useip` | true | 绑定客户端 IP |
| `xwiki.authentication.always` | 0 | 1 = 每次请求都重新验证 cookie |
| `xwiki.authentication.protection` | all | Cookie 保护级别 |

### 8.3 Token 过期后自动跳转 OA 首页

**XWiki 原生不支持。** 当前行为：
- Session/cookie 过期后，访问受保护页面 → 302 重定向到 XWiki 登录页
- 没有独立的 "session 已过期" 处理逻辑

**需求：无论通过什么方式登录（OA、密码），token 过期后都跳转到 OA 首页，而非 XWiki 登录页。**

因为公司统一使用 OA 作为认证入口，XWiki 自带的登录页不再需要对外暴露。

**实现方案：修改 `MyFormAuthenticator.showLogin()`，直接跳转 OA 首页。**

当前调用链：
```
XWikiCachingRightService.checkAccess()  →  MyFormAuthenticator.showLogin()
  → 302 redirect → XWiki 登录页
```

改动后：
```
XWikiCachingRightService.checkAccess()  →  MyFormAuthenticator.showLogin()
  → 302 redirect → OA 首页（xwiki.authentication.oa.homepage）
```

具体改动点（后续实现）：

1. **`xwiki.cfg` 新增配置**：
   ```properties
   # OA 首页地址，token 过期后跳转到此地址（替代 XWiki 自带登录页）
   xwiki.authentication.oa.homepage=http://oa.company.com/
   ```

2. **修改 `MyFormAuthenticator.showLogin()`**（`xwiki-platform-core/xwiki-platform-oldcore/src/main/java/com/xpn/xwiki/user/impl/xwiki/MyFormAuthenticator.java` 第 80-105 行）：
   - 读取 `xwiki.authentication.oa.homepage` 配置
   - 如果配置了该值，直接 `response.sendRedirect(oaHomepage)` 而非跳转 XWiki 登录页
   - 如果未配置，保持原行为（跳 XWiki 登录页），向后兼容

3. **不需要** session 标记或区分登录方式——所有用户统一走 OA 首页。

> **注意**：此方案会使 XWiki 自带登录页对普通用户不可见。管理员可通过直接访问 `/xwiki/bin/login/XWiki/XWikiLogin` 使用密码登录。

### 8.4 限制同时登录（踢出前一个会话）

> **状态：暂不实现。**

**XWiki 原生不支持。** 当前 `MyFormAuthenticator` 中唯一的会话管理是：

```java
// 同一浏览器换用户登录时，清除当前 session
if (request.getUserPrincipal() != null && !username.equals(request.getRemoteUser())) {
    request.getSession().invalidate();
}
```

这只是清掉**同一个浏览器**的旧会话，不是真正的跨设备单会话强制。

**实现方案：自建 Session 管理表。**

核心思路：在数据库中维护一张 `oa_user_session` 表，记录每个用户当前的 sessionId。每次 OA 登录时更新该表，`XWikiContextInitializationFilter` 中校验当前 session 是否与表中一致。

表结构：
```sql
CREATE TABLE oa_user_session (
    username    VARCHAR(768) NOT NULL PRIMARY KEY,
    session_id  VARCHAR(255) NOT NULL,
    login_time  TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

流程：
1. OA 登录 → `OALoginAction` 写入/更新 `oa_user_session`，username → sessionId
2. 每次请求 → Filter 查 `oa_user_session`：当前 sessionId 是否匹配
3. 不匹配 → 说明被其他登录踢出 → 302 跳转 OA 首页（或返回错误页）

> **注意**：此方案需要在 OALoginAction 和 Filter 中新增数据库写入/查询逻辑。

## 9. 关键源码

| 文件 | 作用 |
|------|------|
| `xwiki-platform-core/xwiki-platform-oldcore/src/main/java/com/xpn/xwiki/web/OALoginAction.java` | OA 登录 Servlet |
| `xwiki-platform-core/xwiki-platform-oldcore/src/main/java/com/xpn/xwiki/user/impl/xwiki/MyFormAuthenticator.java` | 表单认证（登录/跳转逻辑） |
| `xwiki-platform-core/xwiki-platform-oldcore/src/main/java/com/xpn/xwiki/user/impl/xwiki/MyPersistentLoginManager.java` | Remember-Me Cookie 管理 |
| `xwiki-platform-core/xwiki-platform-oldcore/src/main/java/com/xpn/xwiki/web/XWikiContextInitializationFilter.java` | 请求上下文初始化/认证检查 |
| `xwiki-platform-core/xwiki-platform-oldcore/src/main/java/com/xpn/xwiki/web/LogoutAction.java` | 登出处理 |
| `xwiki-platform-core/xwiki-platform-oldcore/src/main/java/com/xpn/xwiki/internal/user/MyPersistentLoginManagerProvider.java` | xwiki.cfg → Cookie 配置桥接 |
| `xwiki-platform-tools/xwiki-platform-tool-configuration-resources/src/main/resources/xwiki.cfg.vm` | 配置模板 |
