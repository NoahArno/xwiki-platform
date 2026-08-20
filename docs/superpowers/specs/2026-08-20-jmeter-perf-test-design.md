# XWiki 性能测试 JMeter 脚本与造数脚本 — 设计文档

日期：2026-08-20
状态：已确认（用户已批准）

## 背景与目标

公司用本仓库二开的 XWiki 替换 Confluence，需要对以下 6 个场景做并发性能测试：

1. 访问首页（`localhost:8080/xwiki/bin/view/Main`）的并发性能
2. 访问"内容/宏较少"页面的并发性能
3. 访问"内容/宏较多"页面的并发性能
4. 编辑保存"内容/宏较少"页面的并发性能
5. 编辑保存"内容/宏较多"页面的并发性能
6. 左侧菜单栏约 500 个菜单项时的性能

交付物：
- 对应场景的 JMeter 脚本（`.jmx`）
- 菜单栏造数脚本（及配套：测试账号、清理脚本）

关键约束：**登录行为不能影响压测结果**（不能把登录流程计入被测请求，也不能因登录机制污染服务器状态）。

## 已验证的技术事实（探索结论）

### 认证机制（fork 定制）

- `MyFormAuthenticator`（默认，`xwiki.authentication=form`）和 `MyBasicAuthenticator`（`=basic`）的
  `processLogin()` 都**先调用 `MyBasicAuthenticator.checkLogin()` 检查每个请求的 `Authorization: Basic` 头**
  （`XWikiAuthServiceImpl.java:112-181`）。
- Basic Auth 每请求认证路径**不注册 SessionRegistry**（`MyBasicAuthenticator.java:160-164` 注释明确说明），
  因此不会触发该 fork 的"单会话强制"逻辑，同账号多线程并发互不干扰。
- 表单登录被定制：配置了 `oaHomepage` 时 `showLogin()` 直接重定向 OA（`MyFormAuthenticator.java:100-105`），
  JMeter 表单登录不可靠。
- 实测：`curl -u user:pass` 访问受保护页面（`Menu/` 等）返回 200；REST 新建的用户（含密码）立即可以
  Basic Auth 登录访问。

**结论：JMeter 每个请求直接带 `Authorization: Basic base64(user:pass)` 头即可——无登录步骤，
登录零污染，同账号多线程安全。**

### 编辑保存流程

- `SaveAction.action()` 先做 CSRF 校验：`request.getParameter("form_token")` → `csrf.isTokenValid(token)`
  （`XWikiAction.java:1104-1115`）。token 绑定 session，编辑页 HTML 内嵌：
  `<input type="hidden" name="form_token" value="..." />`。
- 编辑页（`?editor=wiki`）同时内嵌版本字段（实测）：
  - `previousVersion`（如 `1.1`）、`isNew`（`true/false`）、`editingVersionDate`（毫秒时间戳）
- 保存 POST 参数：`content`（`EditForm` 读 `request.getParameter("content")`）、`form_token`、
  `previousVersion`、`editingVersionDate`、`isNew`、`xredirect`、`title`、`language`、`comment` 等。
- 非 AJAX 保存成功 → 302 到 view URL。
- **版本冲突检测仅在 `Utils.isAjaxRequest(context) && previousVersion != null` 时执行**
  （`SaveAction.java:283`）：AJAX 请求（带 `X-Requested-With: XMLHttpRequest`）版本不匹配时返回冲突
  （不保存），非 AJAX 请求直接覆盖保存（版本递增）。

### 左侧菜单结构

- 左侧菜单 = 标准 `PanelsNavigation` 导航面板，**服务端渲染空间树**（登录后首页 HTML 直接输出
  `<li><a href="/xwiki/bin/view/{Space}/">` 顶层节点）。
- 未登录首页导航面板不渲染树（实测内容为空）。
- 树的子节点由 jsTree 异步加载（`/xwiki/bin/get/Main/WebHome?outputSyntax=plain&sheet=XWiki.DocumentTree&...`）。
- **500 个菜单项 = 500 个顶层空间（每个含 WebHome）**。

### REST API（造数通道，均实测可用）

- 创建页面：`PUT /xwiki/rest/wikis/xwiki/spaces/{Space}/pages/{Page}`
  `Content-Type: application/json`，body `{"title":..., "content":..., "syntax":"xwiki/2.1"}` → 201
- 创建对象：`POST /xwiki/rest/wikis/xwiki/spaces/{Space}/pages/{Page}/objects`
  body `{"className":"XWiki.XWikiUsers","properties":[{"name":"email","value":...},{"name":"password","value":...},{"name":"password_confirmation","value":...}]}` → 201
  （`PUT /objects/{class}/{num}` 仅用于更新已有对象，会 404）
- 删除页面：`DELETE /xwiki/rest/wikis/xwiki/spaces/{Space}/pages/{Page}` → 204
- REST 创建的 XWikiUsers 对象密码可直接用于 Basic Auth 登录（实测 200）

## 方案决策

| 决策点 | 选择 | 理由 |
|---|---|---|
| 登录方式 | **每请求 Basic Auth 头**（`Authorization: Basic ${__base64Encode(user:pass)}`） | 无登录步骤；不注册 SessionRegistry 不踢会话；同账号多线程安全；表单登录被 OA 劫持 |
| 脚本组织 | 6 个场景 = 8 个独立 `.jmx` + 公共 `perf.properties`（`-q` 加载） | 每场景独立运行/独立结果；参数集中在一个文件 |
| 保存目标页面 | solo/shared 用同一页面；split 每线程独立页面（`页面名_${__threadNum}`） | 避免不必要的版本冲突噪音（split）；shared 模式专门测冲突路径 |
| 保存内容 | 固定模板（属性 `perf.saveContentSimple/Complex`），不随页面提取 | 保证"保存复杂内容"的服务端成本真实可控 |
| 菜单造数 | REST 批量创建 500 个顶层空间（每个含 WebHome） | 菜单 = 空间树；REST 无 CSRF，简单可靠 |

## 脚本结构

```
docs/perf-test/
├── README.md                     # 使用说明（参数含义、运行命令、结果解读、注意事项）
├── perf.properties               # 公共参数（JMeter -q 加载）
├── jmeter/
│   ├── 01-homepage.jmx           # 场景1：首页并发（默认匿名，可开认证）
│   ├── 02-page-simple.jmx        # 场景2：简单页并发（带认证）
│   ├── 03-page-complex.jmx       # 场景3：复杂页并发（带认证）
│   ├── 04-edit-save-simple.jmx   # 场景4：简单内容保存（含 solo/split/shared 3 个 TG）
│   ├── 05-edit-save-complex.jmx  # 场景5：复杂内容保存（含 solo/split/shared 3 个 TG）
│   └── 06-homepage-500menu.jmx   # 场景6：500 菜单首页并发（带认证）
└── scripts/
    ├── prepare-data.sh           # 造数：用户组 + 500 菜单空间（幂等、可断点续跑）
    └── cleanup-data.sh           # 清理：删除造数产生的用户/空间/测试数据
```

### 公共参数（perf.properties）

| 参数 | 默认 | 说明 |
|---|---|---|
| `perf.host` / `perf.port` / `perf.context` | localhost / 8080 / xwiki | 目标实例 |
| `perf.user` / `perf.pass` | PerfTestUser / 由 prepare-data.sh 设置 | Basic Auth 账号（solo 模式用） |
| `perf.userPrefix` | PerfUser | split/shared 模式账号前缀（`PerfUser${__threadNum}`） |
| `perf.simplePageUrl` | Sandbox/TestPage1 | 简单页路径（用户提供后替换） |
| `perf.complexPageUrl` | Sandbox/TestPage2 | 复杂页路径（用户提供后替换） |
| `perf.savePage` | PerfTest/SimplePage | 保存目标页面前缀（solo/shared 用此页，split 加线程后缀） |
| `perf.saveContentSimple` / `perf.saveContentComplex` | 见下 | 保存内容模板（复杂版含大量宏） |
| `perf.threads.*` / `perf.rampUp.*` / `perf.duration.*` | 见各 jmx | 并发数/爬坡/时长（每 TG 独立） |
| `perf.useAuth` | false（01）/ true（其余） | 是否带认证头 |

### 场景请求流

- **01/02/03/06**：单 HTTP GET（`${perf.context}/bin/view/${pageUrl}`），HTTP Header Manager
  （Basic Auth 头，`perf.useAuth` 控制），响应断言 200。
- **04/05**（每个 TG 相同流程，仅账号/页面/请求头不同）：
  1. `GET /xwiki/bin/edit/{targetPage}?editor=wiki`
     - 正则提取：`form_token`、`previousVersion`、`editingVersionDate`、`isNew`
     - 断言 200
  2. `POST /xwiki/bin/save/{targetPage}`（shared 模式为 `?async=true` + `X-Requested-With: XMLHttpRequest`）
     - 参数：`content=${saveContent}`、`form_token`、`previousVersion`、`editingVersionDate`、
       `isNew`、`title`、`xredirect=/xwiki/bin/view/{targetPage}`
     - solo/split 断言 302；shared 断言 200（成功）或 409（冲突，**预期现象**，失败率≈冲突率）

三种保存模式差异表：

| 模式 | 账号 | 目标页面 | 保存方式 | 断言 |
|---|---|---|---|---|
| solo | `perf.user`（1 人） | `perf.savePage`（1 页） | 非 AJAX | 302 |
| split | `PerfUser${__threadNum}` | `${savePage}_${__threadNum}` | 非 AJAX | 302 |
| shared | `PerfUser${__threadNum}` | `perf.savePage`（同 1 页） | AJAX + 版本参数 | 200 / 409（冲突） |

### 造数脚本（prepare-data.sh）

shell + curl（macOS/Linux 通用），REST 通道：

1. **测试用户组**：创建 `PerfTestUser`（单测账号）+ `PerfUser1..N`（split/shared 用，默认 N=50，
   参数可配）。每用户：PUT 用户页 + POST XWikiUsers 对象（email/password/password_confirmation）。
2. **500 菜单空间**：循环创建 `MenuSpace0001..MenuSpace0500`（数量/前缀可配），每个 PUT WebHome 页面
   （简单内容）。空间随页面创建自动生成。
3. 幂等：已存在则跳过（先 HEAD/GET 检查或 PUT 后判断 201/409）；支持断点续跑（失败重试、进度输出、
   中途中断后重跑只补缺失项）。

### 清理脚本（cleanup-data.sh）

- 删除用户组（DELETE 用户页面，含 XWikiUsers 对象）
- 删除菜单空间（DELETE 每个空间 WebHome 页面——XWiki 删除 WebHome 即删空间）
- 可选：删除保存测试产生的页面（`${savePage}_*`）

## 注意事项（写入 README）

- 压测前先跑 `prepare-data.sh`（生成账号 + 500 空间）
- 保存测试会产生版本历史与测试页面，属预期数据污染，跑完用 cleanup-data.sh 清理
- shared 模式的 409 冲突是预期现象：失败率即冲突率，用于评估多人同页场景
- 500 菜单场景需要登录（导航树登录后才渲染），场景 6 默认带认证
- 真实压测机与被测机分离（JMeter 不要在 Tomcat 同一台跑，避免抢资源）
- 简单页/复杂页 URL 由内网测试时填入 perf.properties（`perf.simplePageUrl`/`perf.complexPageUrl`）

## 实现顺序

1. `docs/perf-test/perf.properties` + README 骨架
2. `scripts/prepare-data.sh` + `cleanup-data.sh`（先在本地实例验证）
3. 简单只读场景：`01-homepage.jmx`（验证基本结构 + 匿名）
4. `02-page-simple.jmx` / `03-page-complex.jmx` / `06-homepage-500menu.jmx`（带认证变体）
5. 保存场景：`04-edit-save-simple.jmx`（3 个 TG 的完整流程 + 提取器 + 断言），复制出 `05`
6. 本地全量验证（造数 → 各脚本冒烟跑通）→ 结果核对
