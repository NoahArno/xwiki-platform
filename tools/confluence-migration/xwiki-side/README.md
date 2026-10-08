# B 侧（XWiki）：迁移包自动导入

配合 A 侧的 `confluence-migration export` 使用。整体流程：

```
A 机器（Confluence）                          B 机器（XWiki）
  生成器读 MySQL + 附件目录
        │
        ▼
  产出 <输出根>/<SPACEKEY>/ + manifest.csv
        │
        └──── rsync ────▶  <B 的 permanentDir>/migration/
                                    │
                                    ▼
                          本目录的 Groovy 作业定时扫描
                          → 逐个调 Confluence 导入器
                          → 写 ledger.csv
```

## 为什么是这个形态

**为什么不让 A 远程调 B 的接口**：触发导入需要 `Right.PROGRAM`，而你们的 XWiki 走自定义 OA SSO。
让 A 去 HTTP 触发 B 需要额外的服务账号并打通 SSO。"往目录丢文件、XWiki 自己扫"不需要任何外部认证，
而且任一方重启都不会丢状态。

**为什么用 Groovy 而不是 Velocity**：Groovy 有完整 Java 访问权限，读清单、列目录、写台账都是普通 File IO。
在 Velocity 里做文件系统操作要靠组件查找绕路，既笨拙又难排错。

## 前置条件

**XWiki 上必须已安装 Confluence 导入器扩展**（`confluence+xml` 输入流）。
可以在「管理 → 扩展」里搜索 `Confluence XML` 安装，或从扩展仓库手动安装
`org.xwiki.contrib.confluence:confluence-xml` 及其依赖。

> 验证方法：安装后在「管理 → 扩展」里能看到该扩展，或访问任意页面用
> `{{velocity}}$services.filter.availableInputStreams{{/velocity}}` 看输出里是否含 `confluence+xml`。

## 部署步骤

### 1. 建立目录

```bash
# 在 B 上，permanentDir 见 webapps/xwiki/WEB-INF/xwiki.properties 的 environment.permanentDirectory
mkdir -p <permanentDir>/migration
```

### 2. 从 A 搬运包

```bash
# 在 A 上
rsync -av --delete /data/migration/ user@B:<permanentDir>/migration/
```

`manifest.csv` 会一起过去。它由 A 生成、只读，描述"应该导入什么、各预期多少内容"，
供人工核对；导入状态记在 B 自己的 `ledger.csv` 里，两边互不覆盖。

### 3. 创建 Scheduler 作业

在 B 的 XWiki 里：

1. 新建页面，例如 `Migration.ImportJob`
2. 添加对象，类选 **`XWiki.SchedulerJobClass`**
3. 填写字段：

   | 字段 | 值 |
   |---|---|
   | Job Name | `Confluence Import` |
   | Job Class | `com.xpn.xwiki.plugin.scheduler.GroovyJob` |
   | Cron Expression | `0 0/5 * * * ?`（每 5 分钟一次；按包大小调整） |
   | Status | `Normal` |
   | Job Script | `ImportSchedulerJob.groovy` 的全文 |

4. **用有编程权限的用户保存**（作业执行时会检查 `Right.PROGRAM`）
5. 页面 `Scheduler.WebHome` 上能看到该作业，可手动 Trigger 一次做冒烟测试

### 4. 冒烟测试

**先只放一个小空间的包**，手动触发一次，确认：

- Scheduler 作业日志里出现 `[confluence-import]` 打头的输出
- `<permanentDir>/migration/ledger.csv` 里该空间记成 `DONE`
- XWiki 里确实出现了对应的空间与页面

通过后再把剩下的包 `rsync` 过去，让定时任务自己啃。

## 脚本行为

| 项 | 行为 |
|---|---|
| 扫描对象 | `<permanentDir>/migration/` 下**含 `entities.xml` 的子目录**，目录名即空间 Key |
| 每次执行 | 最多导入 `MAX_PACKAGES_PER_RUN` 个包（默认 1，避免一个坏包拖住整批） |
| 并发保护 | `.import.lock` 防止定时器重叠；锁超过 `LOCK_STALE_MINUTES` 视为陈旧可抢占 |
| 台账 | `ledger.csv`，制表符分隔：`<空间Key> ⇥ <DONE/FAILED> ⇥ <时间> ⇥ <说明>` |
| 断点续跑 | 每个包导完立即写台账；重跑会跳过 `DONE`，重试 `FAILED` |
| 导入选项 | 只导数据：`contentsEnabled`/`attachmentsEnabled` 开，`usersEnabled`/`groupsEnabled`/`rightsEnabled`/`historyEnabled` 关 |
| 同步等待 | 用 `job.join()` 等导入完成。Scheduler 跑在后台线程，阻塞是安全的，也省掉了"异步 + 轮询"的整套状态机 |

## 已知限制

- **同步等待意味着单个超大空间会长时间占用作业线程**。这是刻意的取舍（换来无状态机、无轮询）；
  代价是 Cron 间隔应大于单个包的平均导入耗时，否则会大量"跳过"。当前实现靠锁保证不会重叠。
- **作业运行期间重启 XWiki**：锁文件会残留，需等 `LOCK_STALE_MINUTES` 或手工删除 `.import.lock`；
  台账里该包仍是 `FAILED`/未记录，会被重跑。
- **`ledger.csv` 只增不改**：重复执行同一个包会追加新行。解析时以最后一次为准。
- 脚本输出进的是 Scheduler 作业日志，不是 XWiki 日志文件。

## 这个脚本的验证程度（重要）

B 侧代码**未在真实 XWiki 上执行过**，原因：本地 XWiki 走自定义 OA SSO 且 superadmin 被禁用，
无法在不改动你们环境的前提下完成认证。

已经做到的验证：

1. **Groovy 编译通过** —— 用 XWiki 18.1.0 自带的 `groovy-5.0.4.jar` 与整个 `WEB-INF/lib` 作为
   classpath，`GroovyClassLoader.parseClass()` 成功，证明语法与所有 import 都成立。
2. **API 签名经 Java 编译器确认** —— Groovy 是动态类型，编译脚本查不出方法签名错误，
   因此把脚本里每一处调用用静态类型重写为 `ApiContractCheck.java` 并用 `javac` 编译通过，
   覆盖 `Utils.getComponent`、`Environment.getPermanentDirectory`、`JobExecutor.execute`、
   `FilterStreamConverterJobRequest` 构造、`FilterStreamType.unserialize`、`Job.join`、
   `JobStatus.getState/getError`。

**尚未验证**：运行时行为——作业是否真的被 Scheduler 调度、导入是否真的成功、
`ledger.csv` 是否如预期写入。这些必须按上面「冒烟测试」在真实 XWiki 上跑一遍。
