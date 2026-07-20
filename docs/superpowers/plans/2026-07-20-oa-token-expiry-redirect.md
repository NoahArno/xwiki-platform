# Token 过期自动跳转 OA 首页 — Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 用户 session/cookie 过期后，自动跳转到 OA 首页而非 XWiki 登录页，无论用户通过什么方式登录。

**Architecture:** 在 `MyFormAuthenticator` 中新增 `oaHomepage` 字段，在 `XWikiAuthServiceImpl.getAuthenticator()` 初始化时从 `xwiki.cfg` 读取并注入。两个 `showLogin()` 方法检测到该字段有值时直接 302 跳转 OA 首页，跳过 XWiki 登录页。

**Tech Stack:** Java, XWiki oldcore, SecurityFilter 2.0

## Global Constraints

- 配置 key: `xwiki.authentication.oa.homepage`，写在 `xwiki.cfg`
- 未配置时保持现有行为（跳 XWiki 登录页），向后兼容
- 适用于**所有用户**（不区分 OA 登录还是密码登录）
- 管理员仍可通过直接 URL `/xwiki/bin/login/XWiki/XWikiLogin` 使用密码登录

---

### Task 1: 修改 MyFormAuthenticator — 新增 OA 首页跳转逻辑

**Files:**
- Modify: `xwiki-platform-core/xwiki-platform-oldcore/src/main/java/com/xpn/xwiki/user/impl/xwiki/MyFormAuthenticator.java`

**Interfaces:**
- Consumes: 无（新增字段 + setter + showLogin 中判断逻辑）
- Produces: `MyFormAuthenticator.setOaHomepage(String)` — 供 XWikiAuthServiceImpl 调用

- [ ] **Step 1: 新增 oaHomepage 字段和 setter**

在类顶部（Logger 声明下方附近）新增字段：

```java
private String oaHomepage;
```

新增 setter（在 `getUserAuthenticatedEventNotifier()` 方法后）：

```java
/**
 * @param oaHomepage the OA homepage URL to redirect to when the session expires;
 *                   if null or blank, the standard XWiki login page is used
 */
public void setOaHomepage(String oaHomepage)
{
    this.oaHomepage = oaHomepage;
}
```

- [ ] **Step 2: 修改 showLogin(request, response) — 无 context 版本**

在 `showLogin(HttpServletRequest request, HttpServletResponse response)` 方法开头（第 80 行 `{` 之后）加入判断：

```java
@Override
public void showLogin(HttpServletRequest request, HttpServletResponse response) throws IOException
{
    // If OA homepage is configured, redirect there instead of showing XWiki login page
    if (StringUtils.isNotBlank(this.oaHomepage)) {
        response.sendRedirect(this.oaHomepage);
        return;
    }

    String savedRequestId = request.getParameter(SavedRequestManager.getSavedRequestIdentifier());
    // ... 其余代码不变 ...
```

即：在第 82 行 `String savedRequestId = ...` 之前插入 OA 首页判断。

- [ ] **Step 3: 修改 showLogin(request, response, context) — 有 context 版本**

在 `showLogin(HttpServletRequest request, HttpServletResponse response, XWikiContext context)` 方法开头（第 65 行 `{` 之后）加入判断：

```java
@Override
public void showLogin(HttpServletRequest request, HttpServletResponse response, XWikiContext context)
    throws IOException
{
    // If OA homepage is configured, redirect there instead of showing XWiki login page
    if (StringUtils.isNotBlank(this.oaHomepage)) {
        response.sendRedirect(this.oaHomepage);
        return;
    }

    if ("1".equals(request.getParameter("basicauth"))) {
        // ... 其余代码不变 ...
```

即：在第 68 行 `if ("1".equals(...))` 之前插入 OA 首页判断。

- [ ] **Step 4: 构建验证**

```bash
cd xwiki-platform
mvn -s ~/.m2/settings-xwiki.xml -f pom.xml \
  -pl xwiki-platform-core/xwiki-platform-oldcore -am \
  -Dxwiki.spoon.skip=true -Dlicense.skip=true -DskipTests=true -Dxwiki.checkstyle.skip=true install
```

预期：BUILD SUCCESS

- [ ] **Step 5: Commit**

```bash
git add xwiki-platform-core/xwiki-platform-oldcore/src/main/java/com/xpn/xwiki/user/impl/xwiki/MyFormAuthenticator.java
git commit -m "feat: add OA homepage redirect in MyFormAuthenticator when session expires"
```

---

### Task 2: 修改 XWikiAuthServiceImpl — 读取配置并注入

**Files:**
- Modify: `xwiki-platform-core/xwiki-platform-oldcore/src/main/java/com/xpn/xwiki/user/impl/xwiki/XWikiAuthServiceImpl.java`

**Interfaces:**
- Consumes: `MyFormAuthenticator.setOaHomepage(String)` from Task 1
- Produces: 无（初始化时注入配置）

- [ ] **Step 1: 在 getAuthenticator() 中读取并设置 OA homepage**

在 `XWikiAuthServiceImpl.getAuthenticator()` 方法中，`authenticator.init(fconfig, sconfig)` 之后、`this.authenticators.put(wikiName, authenticator)` 之前（约第 169 行后），新增：

```java
// Configure OA homepage redirect for session expiry
if (authenticator instanceof MyFormAuthenticator) {
    String oaHomepage = xwiki.Param("xwiki.authentication.oa.homepage");
    if (StringUtils.isNotBlank(oaHomepage)) {
        ((MyFormAuthenticator) authenticator).setOaHomepage(oaHomepage);
        LOGGER.info("OA homepage redirect configured: {}", oaHomepage);
    }
}
```

注意：`StringUtils.isNotBlank` 已经在文件头部 import（line 33），无需新增 import。

- [ ] **Step 2: 构建验证**

```bash
cd xwiki-platform
mvn -s ~/.m2/settings-xwiki.xml -f pom.xml \
  -pl xwiki-platform-core/xwiki-platform-oldcore -am \
  -Dxwiki.spoon.skip=true -Dlicense.skip=true -DskipTests=true -Dxwiki.checkstyle.skip=true install
```

预期：BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add xwiki-platform-core/xwiki-platform-oldcore/src/main/java/com/xpn/xwiki/user/impl/xwiki/XWikiAuthServiceImpl.java
git commit -m "feat: read xwiki.authentication.oa.homepage and inject into MyFormAuthenticator"
```

---

### Task 3: 更新配置模板 xwiki.cfg.vm

**Files:**
- Modify: `xwiki-platform-tools/xwiki-platform-tool-configuration-resources/src/main/resources/xwiki.cfg.vm`

- [ ] **Step 1: 在认证配置区域新增注释模板**

在 `xwiki.cfg.vm` 的认证相关配置区域（`xwiki.authentication.loginsubmitpage` 附近），新增：

```properties
#-# OA SSO: When the user's session expires, redirect to this URL instead of the XWiki login page.
#-# If not set (commented out), the standard XWiki login page is used.
# xwiki.authentication.oa.homepage=http://oa.company.com/
```

- [ ] **Step 2: Commit**

```bash
git add xwiki-platform-tools/xwiki-platform-tool-configuration-resources/src/main/resources/xwiki.cfg.vm
git commit -m "docs: add xwiki.authentication.oa.homepage template to xwiki.cfg.vm"
```

---

### 验证步骤（部署后）

1. 配置 `xwiki.cfg` 中的 `xwiki.authentication.oa.homepage`
2. 登录 XWiki
3. 等待 session 过期（或手动清除 cookie）
4. 访问 XWiki 任意页面
5. 预期：302 重定向到 OA 首页（而非 XWiki 登录页）
6. 注释掉 `xwiki.authentication.oa.homepage`，重启 → 预期恢复跳 XWiki 登录页（向后兼容）
