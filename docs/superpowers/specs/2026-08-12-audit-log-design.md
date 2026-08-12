# 登录/登出审计日志（AuditLog）设计

> 2026-08-12 | 状态：待评审

## 1. 需求概述

安全组要求支持审计日志：提供一个页面，可查看用户的**登录成功、登录失败、主动登出**记录，覆盖所有登录入口（OA 跳转登录、知悉 OAuth2 登录、表单登录、基本认证）。

约束（来自需求方）：
- 只保留**最近 100 条**记录，超出删除最旧；
- 功能可通过配置**开关**关闭（该功能仅用于通过安全检查，平时可关）；
- 仅**管理员**（`XWiki.AdminGroup`）可查看页面；
- 即使最多 100 条，页面仍要**分页**展示。

## 2. 范围与关键决策

| 维度 | 决策 | 说明 |
|---|---|---|
| 事件类型 | `LOGIN_SUCCESS` / `LOGIN_FAILURE` / `LOGOUT` | 登出只记**主动**退出（`/bin/logout`）；会话超时/被新登录踢下线不记 |
| 登录渠道 | `OA` / `ZHIXI` / `FORM` / `BASIC` | 登出事件渠道为空（与登录方式无关） |
| 记录字段 | 时间、用户、事件类型、渠道、IP、详情 | 详情记录失败原因等 |
| 保留策略 | 最近 100 条 | 插入后 XWQL 计数，超出按 `creationDate` 升序删除最旧 |
| 分页 | 每页 20 条，URL 参数 `page` 翻页 | 即使总量 ≤ 100 也分页 |
| 功能开关 | `xwiki.authentication.audit-log.enabled`，默认 `1` | 设 `0` 即关闭写入，页面显示"功能已关闭" |
| 页面权限 | 仅 `XWiki.AdminGroup` 可查看 | XAR 页面配置查看权限 |
| 实现形式 | 新建 `xwiki-platform-audit-log` 模块（api/default/ui） | 与 OA 同步模块同构 |
| 存储 | XClass 文档，每条记录一个文档 | 复用 OA 同步的成熟模式，可 XWQL 查询 |

## 3. 模块结构

```
xwiki-platform-core/xwiki-platform-audit-log/
├── xwiki-platform-audit-log-api      # LoginAuditService 接口 + LoginEventType / LoginChannel 枚举
│                                      #   仅依赖 xwiki-commons-component-api，不依赖 oldcore（避免循环）
├── xwiki-platform-audit-log-default  # DefaultLoginAuditService + AuditLogRecordClassInitializer
│                                      #   依赖 oldcore + api
└── xwiki-platform-audit-log-ui       # XAR 页面 AuditLog.WebHome（依赖 default）
```

- 在 `xwiki-platform-core/pom.xml` 注册 `<module>xwiki-platform-audit-log</module>`；
- `xwiki-platform-oldcore/pom.xml` 增加对 `xwiki-platform-audit-log-api` 的依赖；
- oldcore 各登录/登出入口通过 `Utils.getComponent(LoginAuditService.class)` 调用（与 `SessionRegistry` 用法一致）；
- 审计写入**失败只记日志，绝不阻断登录/登出主流程**（try/catch 包裹）。

## 4. 数据模型

XClass：`AuditLog.LogRecordClass`（由 `AuditLogRecordClassInitializer`（`AbstractMandatoryClassInitializer`）创建）。

| 字段 | 类型 | 说明 |
|---|---|---|
| eventType | String | LOGIN_SUCCESS / LOGIN_FAILURE / LOGOUT |
| channel | String | OA / ZHIXI / FORM / BASIC（LOGOUT 为空） |
| userName | String | `wiki:用户名`（失败时可记录原始输入的用户名） |
| ip | String | 客户端 IP（`request.getRemoteAddr()`） |
| detail | TextArea | 失败原因等备注 |
| eventTime | Date | 事件发生时间 |

存储方式：每条记录一个隐藏文档（`AuditLog.LogRecord<时间戳-随机>`），文档挂一个 `AuditLog.LogRecordClass` 对象。页面用 XWQL 查询：
```
from doc.object(AuditLog.LogRecordClass) as record order by doc.creationDate desc
```

## 5. 事件采集点（oldcore 5 处钩子）

| 位置 | 记录内容 |
|---|---|
| `OALoginAction.doGet` | 成功（签名校验通过且用户存在）；失败（缺参 / 签名校验失败 / 用户不存在） |
| `ZhixiOAuth2LoginAction` | 成功（`loginAndRedirect`）；失败（state 校验失败 / token 换取失败 / user_info 失败 / 用户不存在 / 用户被禁用） |
| `MyFormAuthenticator` | 表单登录成功 + 失败；cookie 自动重登成功（归 FORM 渠道） |
| `MyBasicAuthenticator` | 仅 `processLogin(String username, ...)`（显式凭据提交路径）记成功 + 失败；`checkLogin` / `processLogin(SecurityRequestWrapper, ...)`（每请求校验路径）**不记**（同 `SessionRegistry` 的取舍：避免 API 客户端刷屏） |
| `LogoutAction.action` | `session.invalidate()` 前从 session principal（`org.securityfilter.filter.SecurityFilter.PRINCIPAL`）取用户名，记 LOGOUT |

## 6. 保留策略与分页

- **保留**：`DefaultLoginAuditService.record()` 保存新记录后，XWQL 统计总数；超过 100 条则按 `creationDate` 升序删除最旧记录直到 100 条。
- **分页**：页面每页 20 条；XWQL 查询 `setLimit(20).setOffset((page-1)*20)`，并用 `count()` 显示总数与总页数；URL 参数 `page` 控制当前页（默认第 1 页），上一页/下一页链接跳转。

## 7. 配置开关

- `xwiki.cfg`：`xwiki.authentication.audit-log.enabled`，默认 `1`（开），设 `0` 关闭。
- `DefaultLoginAuditService.record()` 每次写入前检查开关，关闭时直接返回。
- 在 `xwiki-platform-tools/xwiki-platform-tool-configuration-resources/src/main/resources/xwiki.cfg.vm` 增加配置项模板（与 OA/知悉配置同款注释风格）。

## 8. 页面设计（AuditLog.WebHome，XAR 隐藏页）

- 位置：`AuditLog.WebHome`，`<hidden>true</hidden>`，查看权限仅 `XWiki.AdminGroup`；
- 内容（Velocity + XWQL）：
  - 顶部提示：功能开关状态（开启/关闭）；"仅保留最近 100 条记录"；
  - 表格列：**时间｜用户｜事件｜渠道｜IP｜详情**，事件/渠道中文展示（如 登录成功/登录失败/登出，OA/知悉/表单/基本认证）；
  - 分页：每页 20 条，显示"共 N 条 / M 页"，上一页/下一页链接；
  - 关闭状态：显示"审计日志功能已关闭（xwiki.authentication.audit-log.enabled=0）"。
- 不做筛选/导出（YAGNI；最多 100 条，分页足够）。

## 9. 测试计划

- `LoginAuditServiceTest`（audit-log-default）：
  1. 开关开启时 record 成功写入；
  2. 开关关闭（enabled=0）时不写入；
  3. 超过 100 条时最旧记录被删除、总量回到 100；
  4. 写入异常不抛出（不阻断调用方）。
- 按 `SessionRegistryTest` 的方式离线运行单测（`-o` + 指定 `-pl` + `-Dtest=...`）。
- 部署后手工验证：
  1. OA 跳转登录成功 → 页面出现"登录成功/OA"记录；
  2. 知悉 OAuth2 登录成功 → 页面出现"登录成功/知悉"记录；
  3. 表单密码错误 → 页面出现"登录失败/表单"+原因；
  4. 点退出（`/bin/logout`）→ 页面出现"登出"记录；
  5. 非管理员访问页面 → 无权限；
  6. `xwiki.cfg` 关闭开关 → 新登录不再产生记录，页面显示"功能已关闭"。

## 10. 文件变更清单

```
A  xwiki-platform-core/xwiki-platform-audit-log/pom.xml
A  xwiki-platform-core/xwiki-platform-audit-log/xwiki-platform-audit-log-api/pom.xml
A  xwiki-platform-core/xwiki-platform-audit-log/xwiki-platform-audit-log-api/src/main/java/org/xwiki/audit/log/LoginAuditService.java
A  xwiki-platform-core/xwiki-platform-audit-log/xwiki-platform-audit-log-api/src/main/java/org/xwiki/audit/log/LoginEventType.java
A  xwiki-platform-core/xwiki-platform-audit-log/xwiki-platform-audit-log-api/src/main/java/org/xwiki/audit/log/LoginChannel.java
A  xwiki-platform-core/xwiki-platform-audit-log/xwiki-platform-audit-log-default/pom.xml
A  xwiki-platform-core/xwiki-platform-audit-log/xwiki-platform-audit-log-default/src/main/java/org/xwiki/audit/log/internal/DefaultLoginAuditService.java
A  xwiki-platform-core/xwiki-platform-audit-log/xwiki-platform-audit-log-default/src/main/java/org/xwiki/audit/log/internal/AuditLogRecordClassInitializer.java
A  xwiki-platform-core/xwiki-platform-audit-log/xwiki-platform-audit-log-default/src/main/resources/META-INF/components.txt
A  xwiki-platform-core/xwiki-platform-audit-log/xwiki-platform-audit-log-default/src/test/java/org/xwiki/audit/log/internal/LoginAuditServiceTest.java
A  xwiki-platform-core/xwiki-platform-audit-log/xwiki-platform-audit-log-ui/pom.xml
A  xwiki-platform-core/xwiki-platform-audit-log/xwiki-platform-audit-log-ui/src/main/resources/AuditLog/WebHome.xml
M  xwiki-platform-core/pom.xml                                        # 注册新模块
M  xwiki-platform-core/xwiki-platform-oldcore/pom.xml                 # 增加 audit-api 依赖
M  xwiki-platform-core/xwiki-platform-oldcore/src/main/java/com/xpn/xwiki/web/OALoginAction.java
M  xwiki-platform-core/xwiki-platform-oldcore/src/main/java/com/xpn/xwiki/web/ZhixiOAuth2LoginAction.java
M  xwiki-platform-core/xwiki-platform-oldcore/src/main/java/com/xpn/xwiki/user/impl/xwiki/MyFormAuthenticator.java
M  xwiki-platform-core/xwiki-platform-oldcore/src/main/java/com/xpn/xwiki/user/impl/xwiki/MyBasicAuthenticator.java
M  xwiki-platform-core/xwiki-platform-oldcore/src/main/java/com/xpn/xwiki/web/LogoutAction.java
M  xwiki-platform-tools/xwiki-platform-tool-configuration-resources/src/main/resources/xwiki.cfg.vm
```

## 11. 部署与验证

- 构建：`mvn -s ~/.m2/settings-xwiki.xml -f pom.xml -pl xwiki-platform-core/xwiki-platform-audit-log/... -am -Dxwiki.spoon.skip=true -Dlicense.skip=true -DskipTests=true -Dxwiki.checkstyle.skip=true install`
- 部署：将 api/default/ui 三个 jar（及 XAR 页面）部署到 Tomcat 的 `WEB-INF/lib` / 扩展目录，重启后安装 XAR；
- 按第 9 节手工验证清单逐项复测。
