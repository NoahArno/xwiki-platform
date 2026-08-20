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
| 6. 500 菜单首页并发 | `jmeter/06-homepage-500menu.jmx` | 需先造 500 菜单空间 + 登录 |

## 环境准备

1. 安装 JMeter：`brew install jmeter`（或官网 tarball，需 5.5+）
2. 启动本机 XWiki：`http://localhost:8080/xwiki`（admin/admin）
3. 修改 `perf.properties`（实例地址、账号、页面路径）
4. 造数：`bash scripts/prepare-data.sh`（创建压测账号 + 500 菜单空间，见脚本头部参数说明）

## 运行

```bash
cd docs/perf-test
jmeter -n -t jmeter/01-homepage.jmx -q perf.properties -l results/01-homepage.csv
# 非 GUI 运行；-l 输出原始采样数据，再用 GUI 打开结果或 -e -o 生成 HTML 报告
jmeter -n -t jmeter/01-homepage.jmx -q perf.properties -e -o results/01-report
```

保存场景（04/05）默认只启用 solo 模式：用 GUI 打开 jmx，右键启用目标 Thread Group（solo/split/shared），
保存后命令行运行。三个模式的差异见设计文档。

## 认证说明

- 所有请求用 **Basic Auth 头**认证（无登录流程，不污染压测结果），由脚本内 `perf.user`/`perf.pass` 生成。
- `01-homepage.jmx` 默认匿名；如需带认证测首页，在 GUI 中把 Test Plan 级变量 `authHeader`
  的值从空改为 `Basic ${__base64Encode(${perf.user}:${perf.pass})}`。
- split/shared 模式每线程用独立账号 `PerfUser1..N`（需 prepare-data.sh 创建）。

## 结果解读

- shared 模式的 **409 冲突是预期现象**（多人同页必然冲突），失败率 ≈ 冲突率，用于评估该场景的服务器处理能力。
- 保存测试会产生测试页面与版本历史，跑完用 `bash scripts/cleanup-data.sh` 清理。

## 注意事项

- 压测机与被测机分离，不要在同一台机器上跑。
- 压测前清空旧结果：`rm -rf results/*`
- 简单/复杂页 URL 内网测试时填入 `perf.properties`。
