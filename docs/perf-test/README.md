# XWiki 性能测试

6 个场景的 JMeter 脚本 + 造数脚本。设计文档：`docs/superpowers/specs/2026-08-20-jmeter-perf-test-design.md`

## 场景与脚本

| 场景 | 脚本 | 说明 |
|---|---|---|
| 1. 首页并发 | `jmeter/01-homepage.jmx` | 默认匿名访问首页 |
| 2. 简单页并发 | `jmeter/02-page-simple.jmx` | 内容/宏少的页面（`perf.simplePageUrl`） |
| 3. 复杂页并发 | `jmeter/03-page-complex.jmx` | 内容/宏多的页面（`perf.complexPageUrl`） |
| 4. 简单内容编辑保存 | `jmeter/04-edit-save-simple.jmx` | 含 solo/split/shared 三种模式 |
| 5. 复杂内容编辑保存 | `jmeter/05-edit-save-complex.jmx` | 同 04，内容用复杂模板 |
| 6. 500 菜单首页并发 | `jmeter/06-homepage-500menu.jmx` | 首页 + 导航树数据接口（需先造菜单空间 + 管理员账号） |

## 环境准备

1. **JDK 21**（必须）：JMeter 5.6.3 的 Groovy 3 不支持 Java 25。运行：
   ```bash
   export JAVA_HOME=/Users/noaharno/Library/Java/JavaVirtualMachines/ms-21.0.10/Contents/Home
   export PATH=$JAVA_HOME/bin:$PATH
   ```
2. 安装 JMeter 5.6.3（本机已装于 `~/tools/apache-jmeter-5.6.3/`）
3. 启动 XWiki：`http://localhost:8080/xwiki`（admin/admin）
4. 修改 `perf.properties`（实例地址、账号、页面路径）
5. 造数：`bash scripts/prepare-data.sh`（创建压测账号 + 500 菜单空间，幂等可重跑）
6. **压测环境建议**（xwiki.properties）：
   ```properties
   # 禁用认证失败锁定（3 次失败/300 秒会永久锁定账号，压测密码错误将锁死）
   authentication.enabled=false
   ```

## 运行

```bash
cd docs/perf-test
# 非 GUI 运行 + CSV 结果
jmeter -n -t jmeter/01-homepage.jmx -q perf.properties -l results/01-homepage.csv
# 生成 HTML 报告
jmeter -n -t jmeter/01-homepage.jmx -q perf.properties -e -o results/01-report
```

并发/时长在 `perf.properties` 或命令行覆盖（`-Jperf.threads=100 -Jperf.duration=600`）。

### 保存场景（04/05）三种模式切换

默认启用 **solo**（同一个人编辑保存同一页面）。用 GUI 打开 jmx，右键启用目标 Thread Group：

| 模式 | 模拟场景 | 说明 |
|---|---|---|
| `保存-单人单页(solo)` | 1 人反复编辑保存 1 页 | 账号 `perf.user`，页面 `perf.savePage` |
| `保存-多人多页(split)` | N 人各自编辑自己的页面 | 每线程账号 `PerfUser{线程号}`，页面 `{savePage}_{线程号}`（需造数用户 ≥ 线程数） |
| `保存-多人同页(shared)` | N 人同时编辑 1 页 | AJAX 保存，冲突返回 409（预期，失败率 ≈ 冲突率） |

**场景 5（复杂内容）**：`perf.properties` 里 `perf.saveContentFile` 指向 `data/save-content-complex.txt`，
或运行时覆盖：`-Jperf.saveContentFile=data/save-content-complex.txt`。

## 认证说明

- 所有请求用 **Basic Auth 头**认证（无登录流程，不污染压测结果）。
- 认证头由 JSR223 Groovy 脚本动态生成（JMeter 5.6.3 无 `__base64Encode` 函数）；
  split/shared 模式每线程用独立账号 `PerfUser{线程号}`。
- `01-homepage.jmx` 默认匿名；如需带认证测首页，把 TestPlan 级变量 `authHeader` 改为
  `Basic ${__base64Encode(${perf.user}:${perf.pass})}` 无效（函数不存在），请参照 02 的
  JSR223 方式。
- 场景 6 用**管理员账号**（`perf.menuUser`/`perf.menuPass`）：导航树按权限过滤，
  注册用户无显式 view 权限时导航树为空，测不到 500 菜单。

## 结果解读

- **shared 模式的 409 冲突是预期现象**（多人同页必然冲突），失败率 ≈ 冲突率。
- **场景 6 的首页请求在 500 空间下可能 ConnectionClosedException**：这是服务器真实压力
  （首页渲染 + 树查询重），正是该场景要测的瓶颈；压测机资源充足时表现会更好。
- 保存测试会产生测试页面与版本历史，跑完用 `bash scripts/cleanup-data.sh` 清理。

## 已知限制与注意事项

- **JMeter 必须 Java 21**（Groovy 3 不支持 Java 25，JSR223 脚本会报 class major version 69）。
- 本机 XWiki 曾因 `data/database` 目录缺失导致 hsqldb 重启丢数据，已创建该目录；若换环境
  注意确认 `environment.permanentDirectory` 下数据库目录存在。
- 编辑锁：GET edit 带 `force=1` 参数强制加锁（本 fork 的 `EditForm.lockForce`），
  避免压测中跨用户锁冲突 423。
- 压测机与被测机分离，不要在同一台机器上跑。
- 压测前清空旧结果：`rm -rf results/*`
- 简单/复杂页 URL 内网测试时填入 `perf.properties`。
