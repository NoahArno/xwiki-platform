# 科技外包用户 Excel 初次全量导入 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 新增一个"上传 Excel 即导入"的能力，把存量科技外包人员按 `staff_id` 建号并全部加入「开发中心科技外包组」，行为与 MQ 增量链路完全一致。

**Architecture:** 不复制任何同步逻辑：把 `DoapUserSynchronizer` 里"建号"和"入组"抽成可复用方法，MQ 路径与 Excel 路径调用同一份代码。Excel 导入分两阶段——用线程池并发建号（每个任务用 `ExecutionContextRunnable` 获得独立的 ExecutionContext 与 Hibernate session），再回到请求线程串行批量入组（组文档是单点，不能并发写）。

**Tech Stack:** Java 17、Apache POI 5.5.1（`poi-ooxml`）、XWiki 18 组件体系（`@Component`）、JAX-RS（`OASyncRESTResource`）、JUnit 5 + Mockito（`@OldcoreTest` / `MockitoOldcore`）、Maven。

**设计文档:** `docs/superpowers/specs/2026-09-15-outsourcing-excel-import-design.md`

---

## 计划开始时必须先知道的三件事

### 1. 本计划会顺带修复一个严重的现存 bug（务必先读）

`DoapUserSynchronizer.addMemberIfMissing` 当前实现：

```java
BaseObject memberObject = groupDoc.getXObject(groupClassRef);   // ← 返回该类的“第一个”对象
if (memberObject == null) {
    memberObject = groupDoc.newXObject(groupClassRef, context);
}
memberObject.setStringValue("member", userFullName);
```

`XWikiDocument.getXObject(DocumentReference)` 的语义是「返回该类的**第一个**非空对象」
（`xwiki-platform-oldcore/src/main/java/com/xpn/xwiki/doc/XWikiDocument.java` L3132-3146：
遍历 `getXObjects().get(...)`，取到第一个非 null 就 `break`）。

因此当组里已有成员、而新用户**不是**成员时，这段代码会把**第一个成员的 member 值覆盖掉**，
而不是新增一个成员对象。净效果是「开发中心科技外包组」永远只能容纳**一个**外包用户，
每来一个新用户就把第一个人挤掉。

现有单测 `DoapUserSynchronizerTest` 只测了单个用户，所以从未暴露。

**本计划 Task 4 修复它**，并补一个"连续加 3 个用户，3 个都在组里"的回归测试。
修复方式：为每个待加成员 `newXObject`，绝不复用已有对象。

### 2. 为什么"原始字节流上传"而不是 multipart（已核实）

XWiki 的 REST 前置过滤器只在 `Content-Type` 属于**浏览器简单请求类型**时才校验 CSRF：

`xwiki-platform-rest-server/src/main/java/org/xwiki/rest/internal/PreMatchingRequestFilter.java` L74-77：

```java
private static final List<String> SIMPLE_CONTENT_TYPES =
    List.of("application/x-www-form-urlencoded", "multipart/form-data", "text/plain");
```

L231-243：仅当 `POST` 且媒体类型匹配上述之一时，才要求 `XWiki-Form-Token` 头，否则 403。

结论：`multipart/form-data` 会**额外要求 CSRF token**；`application/octet-stream` 天然豁免。
这也是现有 `OASyncRESTResource.trigger`（用 `application/json`）不需要 token 的原因。
因此本计划使用 `application/octet-stream` + `InputStream`，**不要**改成 multipart。

### 3. 并发为什么安全（已核实）

- `Execution` 的上下文是 `ThreadLocal<Stack<ExecutionContext>>`
  （`xwiki-commons-core/.../context/internal/DefaultExecution.java` L45）
- Hibernate session 存放在 `ExecutionContext` 的属性 `hibsession` 上
  （`xwiki-platform-oldcore/.../internal/store/hibernate/HibernateStore.java` L642-647），
  而 `XWikiHibernateBaseStore.getSession()` 就是转发到它（L204-207）
- 平台自带 `org.xwiki.context.concurrent.ExecutionContextRunnable`
  （`xwiki-commons-core/.../context/concurrent/ExecutionContextRunnable.java`），
  负责「新建干净上下文 → run → finally removeContext」

`DefaultExecution.removeContext()` 只移除 ThreadLocal（L99-102），**不会**关闭 session。
所以每个并发任务结束后必须显式 `context.getWiki().getStore().cleanUp(context)` 释放连接。
本计划把这件事集中在 `ExcelImportExecutor` 内完成。

---

## 执行环境注意事项（Task 1 实测发现）

### 为什么每条 `-pl` 命令都带上了 `xwiki-platform-oa-sync-api`

Task 4 之后 `oa-sync-default` 会用到 Task 1 在 **api 模块**新增的 `getExcelImportThreads()`。
若 `-pl` 只写 `oa-sync-default`，Maven 会从本地仓库解析 api 的**旧 jar**，
于是 `@Override` 报「方法不会覆盖或实现超类型的方法」。

因此所有 `-pl` 都写成 api + default 两个模块一起进 reactor
（Maven 会按依赖顺序先构建 api）。**不要**改成 `-am`：那会把 oldcore、rest-server 等
上游模块全部拉进 reactor 重新构建，极慢。

`xwiki-platform-oa-sync-ui` 以 `runtime` scope 依赖 `oa-sync-default`（见其 pom），
因此构建 ui 时也要把 api + default 一起放进 reactor，否则会解析到本地仓库里的旧 jar。

### 为什么每条测试命令都带 `-DfailIfNoTests=false`

api 模块进 reactor 后，`-Dtest=SomeTest` 在 api 模块匹配不到任何测试，
surefire 会报 `No tests were executed!` 并以非 0 退出，导致整个构建失败。
`-DfailIfNoTests=false` 让"该模块没有匹配到测试"不算失败。

### 沙箱对 `~/.m2` 的限制

若执行环境是 workspace-write 沙箱，`~/.m2` 可能**只读**，`install` 会失败
（`Operation not permitted`）。可行的适配方式（不污染真实本地仓库）：

```bash
# 一次性：克隆一份可写的本地仓库副本（APFS clonefile，约 10s）
cp -c -a ~/.m2/repository /tmp/dsh-m2repo

# 之后所有 maven 命令都追加
-o -Dmaven.repo.local=/tmp/dsh-m2repo
```

另外仓库自带的 Develocity 扩展（`.mvn/extensions.xml`）在部分 Maven 环境下会初始化报错；
若构建因它失败，可临时改名后重试，**构建完必须改回原名**：

```bash
mv .mvn/extensions.xml .mvn/extensions.xml.bak   # 构建前
mv .mvn/extensions.xml.bak .mvn/extensions.xml   # 构建后务必还原
```

这个环境下通常需要同时使用「离线 + 重定向本地仓库」，因为改掉扩展文件后，
`xwiki-commons-pom` 里无版本的 `com.gradle:develocity-maven-extension` 会触发远程版本解析。

### 为什么注入字段不写 `private`

本模块的单测约定是**直接 new 出组件再赋值字段**（见 `DefaultOASyncServiceTest`、
`DefaultOASyncConfigurationTest`），所以 `@Inject` 字段一律用**包级可见**，
不写 `private`：

```java
    @Inject
    OASyncConfiguration configuration;      // 包级可见，便于同包单测赋值
```

这不是偷懒，而是本模块既有约定（`DefaultOASyncService`、`DefaultOASyncConfiguration` 都是如此）。
如果写成 `private`，同包的测试就会报「字段是 private 访问控制」而无法编译。
Task 3 / Task 5 / Task 6 的实现代码块都已按此口径写好。

---

## 文件结构

### 新增

| 文件 | 职责 |
|---|---|
| `.../internal/excel/OutsourcingExcelParseResult.java` | 解析产物：`usernames` + `errors` + `warnings` |
| `.../internal/excel/OutsourcingExcelParser.java` | POI 解析：单 sheet、跳表头、取 B 列、去空去重、校验工号合法性 |
| `.../internal/excel/ExcelImportResult.java` | 导入统计：total/created/existed/joinedGroup/alreadyMember/failed + errors/warnings |
| `.../internal/excel/ExcelImportExecutor.java` | 有界线程池 + `ExecutionContextRunnable` 包装 + 上下文清理 + 整体超时 |
| `.../internal/excel/OutsourcingExcelImportService.java` | 编排：解析 → 并发建号 → 串行入组 → 统计 → 写同步记录 |
| `.../test/.../internal/excel/OutsourcingExcelParserTest.java` | 解析器单测 |
| `.../test/.../internal/excel/ExcelImportExecutorTest.java` | 执行器单测（纯 Mockito，不需要 XWiki 容器） |
| `.../test/.../internal/excel/OutsourcingExcelImportServiceTest.java` | 编排/统计/失败隔离单测（纯 Mockito） |

（`...` = `xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-default/src/main/java/org/xwiki/oa/sync`，
测试侧同理把 `main` 换成 `test`）

### 修改

| 文件 | 改动 |
|---|---|
| `.../internal/mq/DoapUserSynchronizer.java` | 抽出 `ensureUser`（返回 `EnsuredUser`）与 `addMembersToOutsourcingGroup`（批量、分块、**修复成员覆盖 bug**）；MQ 的 `sync()` 改为调用它们 |
| `.../internal/rest/OASyncRESTResource.java` | 新增 `POST /oa-sync/outsourcing-excel`，做管理员权限校验 |
| `xwiki-platform-oa-sync-default/src/main/resources/META-INF/components.txt` | 按任务追加注册：解析器（Task 2）、执行器（Task 3）、编排服务（Task 5）；Task 7 只核对 |
| `xwiki-platform-oa-sync-default/pom.xml` | 增加 `poi-ooxml` 依赖 |
| `xwiki-platform-oa-sync-api/.../OASyncConfiguration.java` | 新增 `getExcelImportThreads()` |
| `.../internal/configuration/DefaultOASyncConfiguration.java` | 实现 `getExcelImportThreads()`（默认 4，钳制到 1..8） |
| `.../test/.../internal/configuration/DefaultOASyncConfigurationTest.java` | 补线程数配置用例 |
| `.../test/resources/logback-test.xml` | 屏蔽 POI 在 HSSF 上的 WARN（否则 `CaptureConsoleExtension` 断言失败，见 Task 2 Step 5 注） |
| `xwiki-platform-tools/xwiki-platform-tool-configuration-resources/src/main/resources/xwiki.cfg.vm` | 增加 `xwiki.oa-sync.excel.threads` 注释项 |
| `xwiki-platform-oa-sync-ui/src/main/resources/OASync/WebHome.xml` | 增加「上传 Excel 导入」一节 |

### 与设计文档的一处有意收紧

设计文档 §3 提到了 `ensureUser` + `addToOutsourcingGroup` + 批量 `ensureUsers` 三个方法。
本计划改为**两个**方法：`ensureUser`（单人幂等建号）与 `addMembersToOutsourcingGroup`（批量入组），
并让 **MQ 的单人路径也调用这个批量方法**（传单元素集合）。

理由：只有一个入组实现，MQ 与 Excel 在结构上不可能漂移；同时 bug 修复只写一次。
线程池/并发不放进 `DoapUserSynchronizer`（那是 MQ 语义类），而是放在 `ExcelImportExecutor`。

---

## Task 1: 依赖与线程数配置

**Files:**
- Modify: `xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-default/pom.xml`
- Modify: `xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-api/src/main/java/org/xwiki/oa/sync/OASyncConfiguration.java`
- Modify: `xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-default/src/main/java/org/xwiki/oa/sync/internal/configuration/DefaultOASyncConfiguration.java`
- Test: `xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-default/src/test/java/org/xwiki/oa/sync/internal/configuration/DefaultOASyncConfigurationTest.java`
- Modify: `xwiki-platform-tools/xwiki-platform-tool-configuration-resources/src/main/resources/xwiki.cfg.vm`

- [ ] **Step 1: 写失败的配置测试**

在 `DefaultOASyncConfigurationTest` 末尾（最后一个 `}` 之前）追加：

```java
    @Test
    void excelImportThreadsDefaultsToFour()
    {
        this.config.configuration = this.source;

        assertEquals(4, this.config.getExcelImportThreads());
    }

    @Test
    void excelImportThreadsIsClampedToValidRange()
    {
        this.config.configuration = this.source;

        when(this.source.getProperty("xwiki.oa-sync.excel.threads", Integer.class)).thenReturn(0);
        assertEquals(1, this.config.getExcelImportThreads());

        when(this.source.getProperty("xwiki.oa-sync.excel.threads", Integer.class)).thenReturn(99);
        assertEquals(8, this.config.getExcelImportThreads());

        when(this.source.getProperty("xwiki.oa-sync.excel.threads", Integer.class)).thenReturn(6);
        assertEquals(6, this.config.getExcelImportThreads());
    }
```

- [ ] **Step 2: 运行测试确认失败**

Run:
```bash
mvn -s ~/.m2/settings-xwiki.xml -f pom.xml \
  -pl xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-api,\
xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-default \
  -Dtest=DefaultOASyncConfigurationTest -DfailIfNoTests=false -Dxwiki.spoon.skip=true -Dlicense.skip=true -Dxwiki.checkstyle.skip=true test
```
Expected: 编译失败，`cannot find symbol: method getExcelImportThreads()`

- [ ] **Step 3: 在 api 接口声明配置项**

`OASyncConfiguration.java`，在 `getRecordSpace()` 默认方法**之前**插入：

```java
    /**
     * @return the number of worker threads used to create users during the outsourcing Excel import,
     *         default 4, clamped to 1..8
     */
    int getExcelImportThreads();
```

- [ ] **Step 4: 实现配置读取**

`DefaultOASyncConfiguration.java`，插入到私有方法 `getInt` **之前**
（该实现类没有 `getRecordSpace`，它沿用接口的默认方法）：

```java
    @Override
    public int getExcelImportThreads()
    {
        int threads = getInt("xwiki.oa-sync.excel.threads", 4);
        return Math.max(1, Math.min(threads, 8));
    }
```

- [ ] **Step 5: 运行测试确认通过**

Run: 同 Step 2
Expected: `Tests run: ... Failures: 0, Errors: 0`

- [ ] **Step 6: 增加 poi-ooxml 依赖**

`xwiki-platform-oa-sync-default/pom.xml`，在 `jackson-databind` 依赖之后、RocketMQ 注释块之前插入：

```xml
    <!-- Apache POI：解析外包用户 Excel（.xlsx / .xls）。
         版本由根 pom 的 dependencyManagement 统一管理（poi.version=5.5.1），此处不写版本。
         用 compile 而非 provided：poi-ooxml 需要保证运行期一定在 WEB-INF/lib 中，
         provided 若无人提供会直接 ClassNotFoundException。 -->
    <dependency>
      <groupId>org.apache.poi</groupId>
      <artifactId>poi-ooxml</artifactId>
    </dependency>
```

- [ ] **Step 7: 确认依赖可解析**

Run:
```bash
mvn -s ~/.m2/settings-xwiki.xml -f pom.xml \
  -pl xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-api,\
xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-default \
  -DskipTests=true -Dxwiki.spoon.skip=true -Dlicense.skip=true -Dxwiki.checkstyle.skip=true install
```
Expected: `BUILD SUCCESS`

- [ ] **Step 8: 在 xwiki.cfg 模板补充配置项**

`xwiki-platform-tools/xwiki-platform-tool-configuration-resources/src/main/resources/xwiki.cfg.vm`，
在 `# xwiki.doap-mq.consume-thread-max=8` 之后、空行之前插入：

```properties

#-# Excel 全量导入科技外包用户时的建号并发线程数（默认 4，有效范围 1..8）
# xwiki.oa-sync.excel.threads=4
```

- [ ] **Step 9: 提交**

```bash
git add xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-default/pom.xml \
  xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-api/src/main/java/org/xwiki/oa/sync/OASyncConfiguration.java \
  xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-default/src/main/java/org/xwiki/oa/sync/internal/configuration/DefaultOASyncConfiguration.java \
  xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-default/src/test/java/org/xwiki/oa/sync/internal/configuration/DefaultOASyncConfigurationTest.java \
  xwiki-platform-tools/xwiki-platform-tool-configuration-resources/src/main/resources/xwiki.cfg.vm
git commit -m "外包Excel导入：引入 poi-ooxml 依赖与建号线程数配置"
```

---

## Task 2: Excel 解析器

**Files:**
- Create: `xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-default/src/main/java/org/xwiki/oa/sync/internal/excel/OutsourcingExcelParseResult.java`
- Create: `xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-default/src/main/java/org/xwiki/oa/sync/internal/excel/OutsourcingExcelParser.java`
- Test: `xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-default/src/test/java/org/xwiki/oa/sync/internal/excel/OutsourcingExcelParserTest.java`

- [ ] **Step 1: 写失败的解析器测试**

创建 `OutsourcingExcelParserTest.java`：

```java
/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.xwiki.oa.sync.internal.excel;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link OutsourcingExcelParser}.
 */
class OutsourcingExcelParserTest
{
    @TempDir
    Path tempDir;

    private final OutsourcingExcelParser parser = new OutsourcingExcelParser();

    @Test
    void readsStaffIdsFromColumnBAndSkipsHeader() throws Exception
    {
        Path file = xlsx(new String[] { "姓名", "staff_id", "部门" },
            new String[] { "张三", "T001", "科技部" },
            new String[] { "李四", "T002", "科技部" });

        OutsourcingExcelParseResult result = parse(file);

        assertEquals(List.of("T001", "T002"), result.getUsernames());
        assertTrue(result.getErrors().isEmpty());
        assertTrue(result.getWarnings().isEmpty());
    }

    @Test
    void trimsWhitespaceAroundStaffId() throws Exception
    {
        Path file = xlsx(new String[] { "姓名", "staff_id" },
            new String[] { "张三", "  T001  " });

        assertEquals(List.of("T001"), parse(file).getUsernames());
    }

    @Test
    void readsNumericCellWithoutScientificNotation() throws Exception
    {
        Path file = numericXlsx(new String[] { "姓名", "staff_id" },
            new String[] { "张三", "10086" });

        assertEquals(List.of("10086"), parse(file).getUsernames());
    }

    @Test
    void deduplicatesRepeatedStaffIdAsWarning() throws Exception
    {
        Path file = xlsx(new String[] { "姓名", "staff_id" },
            new String[] { "张三", "T001" },
            new String[] { "张三重复", "T001" });

        OutsourcingExcelParseResult result = parse(file);

        assertEquals(List.of("T001"), result.getUsernames());
        assertTrue(result.getErrors().isEmpty());
        assertEquals(1, result.getWarnings().size());
        assertTrue(result.getWarnings().get(0).contains("第 3 行"));
    }

    @Test
    void skipsBlankRowsSilently() throws Exception
    {
        Path file = xlsx(new String[] { "姓名", "staff_id" },
            new String[] { "张三", "T001" },
            new String[] { null, null },
            new String[] { "李四", "T002" });

        OutsourcingExcelParseResult result = parse(file);

        assertEquals(List.of("T001", "T002"), result.getUsernames());
        assertTrue(result.getErrors().isEmpty());
    }

    @Test
    void reportsRowWithContentButEmptyStaffIdAsError() throws Exception
    {
        Path file = xlsx(new String[] { "姓名", "staff_id" },
            new String[] { "张三", "" });

        OutsourcingExcelParseResult result = parse(file);

        assertTrue(result.getUsernames().isEmpty());
        assertEquals(1, result.getErrors().size());
        assertTrue(result.getErrors().get(0).contains("第 2 行"));
    }

    @Test
    void rejectsStaffIdWithIllegalCharacters() throws Exception
    {
        Path file = xlsx(new String[] { "姓名", "staff_id" },
            new String[] { "张三", "T.001" });

        OutsourcingExcelParseResult result = parse(file);

        assertTrue(result.getUsernames().isEmpty());
        assertEquals(1, result.getErrors().size());
        assertTrue(result.getErrors().get(0).contains("非法字符"));
    }

    @Test
    void rejectsTooLongStaffId() throws Exception
    {
        Path file = xlsx(new String[] { "姓名", "staff_id" },
            new String[] { "张三", "T".repeat(256) });

        OutsourcingExcelParseResult result = parse(file);

        assertTrue(result.getUsernames().isEmpty());
        assertEquals(1, result.getErrors().size());
        assertTrue(result.getErrors().get(0).contains("过长"));
    }

    @Test
    void parsesLegacyXlsFormat() throws Exception
    {
        Path file = xls(new String[] { "姓名", "staff_id" },
            new String[] { "张三", "T001" });

        assertEquals(List.of("T001"), parse(file).getUsernames());
    }

    @Test
    void failsWhenWorkbookHasNoSheet() throws Exception
    {
        Path file = this.tempDir.resolve("empty.xlsx");
        try (XSSFWorkbook workbook = new XSSFWorkbook();
            OutputStream out = Files.newOutputStream(file)) {
            workbook.write(out);
        }

        try {
            parse(file);
            org.junit.jupiter.api.Assertions.fail("应当抛出 IOException");
        } catch (IOException e) {
            assertTrue(e.getMessage().contains("没有任何 sheet"));
        }
    }

    @Test
    void returnsNoUsernameWhenOnlyHeaderRowExists() throws Exception
    {
        Path file = xlsx(new String[] { "姓名", "staff_id" });

        OutsourcingExcelParseResult result = parse(file);

        assertTrue(result.getUsernames().isEmpty());
        assertTrue(result.getErrors().isEmpty());
    }

    private OutsourcingExcelParseResult parse(Path file) throws IOException
    {
        try (InputStream input = Files.newInputStream(file)) {
            return this.parser.parse(input);
        }
    }

    private Path xlsx(String[]... rows) throws IOException
    {
        return write(new XSSFWorkbook(), rows);
    }

    private Path numericXlsx(String[]... rows) throws IOException
    {
        try (XSSFWorkbook workbook = new XSSFWorkbook()) {
            Sheet sheet = workbook.createSheet("Sheet1");
            for (int r = 0; r < rows.length; r++) {
                Row row = sheet.createRow(r);
                for (int c = 0; c < rows[r].length; c++) {
                    if (rows[r][c] == null) {
                        continue;
                    }
                    // 工号列（B）刻意写成数字型单元格，验证 DataFormatter 的取数行为
                    if (c == OutsourcingExcelParser.STAFF_ID_COLUMN && r > 0) {
                        row.createCell(c).setCellValue(Double.parseDouble(rows[r][c]));
                    } else {
                        row.createCell(c).setCellValue(rows[r][c]);
                    }
                }
            }
            return save(workbook, "numeric.xlsx");
        }
    }

    private Path xls(String[]... rows) throws IOException
    {
        return write(new HSSFWorkbook(), rows);
    }

    private Path write(Workbook workbook, String[]... rows) throws IOException
    {
        try (Workbook wb = workbook) {
            Sheet sheet = wb.createSheet("Sheet1");
            for (int r = 0; r < rows.length; r++) {
                Row row = sheet.createRow(r);
                for (int c = 0; c < rows[r].length; c++) {
                    if (rows[r][c] != null) {
                        row.createCell(c).setCellValue(rows[r][c]);
                    }
                }
            }
            return save(wb, "test.xlsx");
        }
    }

    private Path save(Workbook workbook, String name) throws IOException
    {
        Path file = this.tempDir.resolve(name);
        try (OutputStream out = Files.newOutputStream(file)) {
            workbook.write(out);
        }
        return file;
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run:
```bash
mvn -s ~/.m2/settings-xwiki.xml -f pom.xml \
  -pl xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-api,\
xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-default \
  -Dtest=OutsourcingExcelParserTest -DfailIfNoTests=false -Dxwiki.spoon.skip=true -Dlicense.skip=true -Dxwiki.checkstyle.skip=true test
```
Expected: 编译失败，找不到 `OutsourcingExcelParseResult` / `OutsourcingExcelParser`

- [ ] **Step 3: 实现解析结果**

创建 `OutsourcingExcelParseResult.java`：

```java
/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.xwiki.oa.sync.internal.excel;

import java.util.ArrayList;
import java.util.List;

/**
 * 外包 Excel 的解析产物：去重后的工号，以及逐行错误与警告。
 * <p>
 * 错误会让导入整体变成 {@code PARTIAL_FAILURE}；警告只记录，不影响状态。
 *
 * @version $Id$
 */
public class OutsourcingExcelParseResult
{
    private final List<String> usernames = new ArrayList<>();

    private final List<String> errors = new ArrayList<>();

    private final List<String> warnings = new ArrayList<>();

    /**
     * @return the deduplicated staff ids, in file order
     */
    public List<String> getUsernames()
    {
        return this.usernames;
    }

    /**
     * @return the row-level errors (these fail the import)
     */
    public List<String> getErrors()
    {
        return this.errors;
    }

    /**
     * @return the row-level warnings (informational, do not fail the import)
     */
    public List<String> getWarnings()
    {
        return this.warnings;
    }
}
```

- [ ] **Step 4: 实现解析器**

创建 `OutsourcingExcelParser.java`：

```java
/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.xwiki.oa.sync.internal.excel;

import java.io.IOException;
import java.io.InputStream;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

import javax.inject.Singleton;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.xwiki.component.annotation.Component;

/**
 * 解析科技外包用户 Excel：只读第一个 sheet，第 1 行是表头，
 * 从 B 列取 {@code staff_id}，其余列全部忽略。
 * <p>
 * 行级问题不中断解析：非法行记入错误/警告，其余行照常返回。
 *
 * @version $Id$
 */
@Component(roles = OutsourcingExcelParser.class)
@Singleton
public class OutsourcingExcelParser
{
    /**
     * {@code staff_id} 所在列（0-based）：B 列。
     */
    public static final int STAFF_ID_COLUMN = 1;

    /**
     * 表头行号（0-based）：第 1 行。
     */
    public static final int HEADER_ROW_INDEX = 0;

    /**
     * 工号最大长度：xwikidoc 的文档名上限为 255。
     */
    private static final int MAX_STAFF_ID_LENGTH = 255;

    /**
     * XWiki 文档名中会导致嵌套空间或引用异常的字符。
     */
    private static final Pattern ILLEGAL_STAFF_ID = Pattern.compile("[.\\\\/:*?\"<>|\\s]");

    /**
     * @param input the uploaded workbook content
     * @return the parsed staff ids plus row-level errors and warnings
     * @throws IOException if the content is not a readable workbook, or has no sheet at all
     */
    public OutsourcingExcelParseResult parse(InputStream input) throws IOException
    {
        OutsourcingExcelParseResult result = new OutsourcingExcelParseResult();
        try (Workbook workbook = WorkbookFactory.create(input)) {
            if (workbook.getNumberOfSheets() == 0) {
                throw new IOException("Excel 文件没有任何 sheet");
            }
            Sheet sheet = workbook.getSheetAt(0);
            DataFormatter formatter = new DataFormatter();
            Set<String> seen = new LinkedHashSet<>();
            for (int rowIndex = HEADER_ROW_INDEX + 1; rowIndex <= sheet.getLastRowNum(); rowIndex++) {
                parseRow(sheet.getRow(rowIndex), rowIndex, formatter, seen, result);
            }
        }
        return result;
    }

    private void parseRow(Row row, int rowIndex, DataFormatter formatter, Set<String> seen,
        OutsourcingExcelParseResult result)
    {
        if (row == null || isBlankRow(row, formatter)) {
            return;
        }

        String staffId;
        try {
            Cell cell = row.getCell(STAFF_ID_COLUMN);
            staffId = cell == null ? "" : formatter.formatCellValue(cell).trim();
        } catch (Exception e) {
            result.getErrors().add("第 " + displayRow(rowIndex) + " 行读取失败: " + e.getMessage());
            return;
        }

        if (staffId.isEmpty()) {
            result.getErrors().add("第 " + displayRow(rowIndex) + " 行 staff_id 为空");
            return;
        }
        if (staffId.length() > MAX_STAFF_ID_LENGTH) {
            result.getErrors().add("第 " + displayRow(rowIndex) + " 行 staff_id 过长: "
                + staffId.length() + " 字符");
            return;
        }
        if (ILLEGAL_STAFF_ID.matcher(staffId).find()) {
            result.getErrors().add("第 " + displayRow(rowIndex) + " 行 staff_id 含非法字符: " + staffId);
            return;
        }
        if (!seen.add(staffId)) {
            result.getWarnings().add("第 " + displayRow(rowIndex) + " 行 staff_id 重复，已跳过: " + staffId);
            return;
        }
        result.getUsernames().add(staffId);
    }

    private boolean isBlankRow(Row row, DataFormatter formatter)
    {
        for (int column = row.getFirstCellNum(); column < row.getLastCellNum(); column++) {
            Cell cell = row.getCell(column);
            if (cell != null && !formatter.formatCellValue(cell).trim().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private int displayRow(int rowIndex)
    {
        return rowIndex + 1;
    }
}
```

- [ ] **Step 5: 运行测试确认通过**

Run: 同 Step 2
Expected: `Tests run: 11, Failures: 0, Errors: 0`

> 若 `readsNumericCellWithoutScientificNotation` 失败：说明 `DataFormatter` 返回了带小数点的值。
> 此时把该断言改为 `assertEquals(List.of("10086"), ...)` 之外的处理方式前，
> **必须先跟需求方确认**——真实 Excel 的 `staff_id` 列若为数字格式，取值方式会影响正确性。
> （实测该断言通过：`DataFormatter` 无参构造对数字型单元格返回的就是 `"10086"`。）

> **前置改动（Task 2 实测发现）**：`parsesLegacyXlsFormat` 用到了老式 `.xls`（HSSF），
> POI 读写 HSSF 时会向控制台输出两条 WARN（`SummaryInformation property set came back as null`），
> 撞上 XWiki 测试基类的 `CaptureConsoleExtension`「测试不得向控制台输出」断言而使构建失败。
> 需在 `xwiki-platform-oa-sync-default/src/test/resources/logback-test.xml` 追加
> （沿用该文件既有的 `org.xwiki.oa.sync` off 的同类写法）：
>
> ```xml
>   <!-- POI 在读写老式 .xls（HSSF）时会 WARN "SummaryInformation property set came back as null"，
>        会触发 CaptureConsoleExtension 的"测试不得向控制台输出"断言 -->
>   <logger name="org.apache.poi" level="error"/>
> ```

- [ ] **Step 6: 提交**

```bash
git add xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-default/src/main/java/org/xwiki/oa/sync/internal/excel \
  xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-default/src/test/java/org/xwiki/oa/sync/internal/excel
git commit -m "外包Excel导入：新增 POI 解析器（单sheet/跳表头/B列/去重/工号校验）"
```

### Task 2 复审后的修订（代码为准，见提交 `6b7f63eb432`）

Task 2 通过规格审查后，代码质量审查实测出三个会导致"静默建出错误用户"或"500 且不落失败记录"的问题。
**上面 Task 2 的代码块是修订前的版本**，实际代码已按下面修订（**以仓库代码为准**）：

| # | 问题 | 修订 |
|---|---|---|
| I1 | `DataFormatter` 按**显示格式**渲染，把语义不同的单元格伪装成合法工号：数字 + `#,##0` → `"10,086"`（逗号不在非法字符集）→ 静默建出错误用户；日期格式 → `"2026-01-02"`；公式 → 公式文本 | 改为按单元格类型取值：`FORMULA` → 坏行；`DateUtil.isCellDateFormatted` → 坏行；`NUMERIC` → `NumberToTextConverter.toText(...)`；其余才用 `DataFormatter` |
| I2 | `trim()` 只去 ASCII ≤U+0020，Java `\s` 只匹配 `[ \t\n\x0B\f\r]`，全角空格 U+3000 / NBSP U+00A0 会留在工号里 | `trim()` → `strip()`；非法字符集并入 `\p{Zs}` |
| I3 | `WorkbookFactory.create` 对用户可上传的坏文件抛 **unchecked** 异常（`EmptyFileException` / `NotOfficeXmlFileException`），下游只 `catch (IOException)` → HTTP 500 且**不写 FAILURE 记录**（违反设计 §7.2） | 新增 `openWorkbook(InputStream)`，`catch (RuntimeException)` → 转 `IOException` |
| M1 | `isBlankRow` 在 `try` 之外，整行扫描异常会中断整个导入 | 判定挪进 `try`；循环起点 `Math.max(0, row.getFirstCellNum())` |
| M3 | `OutsourcingExcelParser` 标了 `@Component` 却没进 `components.txt` | 本任务追加注册（见下"组件注册的时机"） |
| M5 | `.xls` 用例存成了 `test.xlsx`；同一用例内多次保存互相覆盖 | `.xls` 存 `.xls`；`save()` 用递增计数器生成唯一文件名；补"第二个 sheet 被忽略""255 接受 / 256 拒绝"用例 |
| M6 | 注释称"文档名上限 255"，与 `xwiki.hbm.xml`（`length="768"`）不符 | 注释改为"255 是刻意收紧的上限，不是数据库硬限制" |

新增用例 9 个，解析器用例总数 11 → 20，全模块回归 79 个通过。

### Task 2 第二批复审后的修订（见提交 `304053e4cd3`）

第一轮修复本身又被复审抓出**一个 Critical 回归**，值得记下来——它是"采纳建议反而引入新问题"的典型案例：

| # | 问题 | 修订 |
|---|---|---|
| Critical | 第一轮把数值型改用 `NumberToTextConverter.toText()`，但该类**刻意模仿 Excel，只输出 15 位有效数字**（javadoc 原文 `No more than 15 significant figures are output (java does 18)`）。实测 `1234567890123456` → `"1234567890123460"`（**错了 4**）。于是 ≥16 位有效数字的数值工号从"**显式报错**"变成了"**静默取到被四舍五入的错误工号并报 SUCCESS**"——正是第一轮要消灭的那类问题。阈值是 16 位有效数字，**不是 2^53** | 取文本后用 `Double.parseDouble(text) != value` 回读比对，丢精度即记错误。补用例 `rejectsNumericStaffIdBeyondExcelPrecision` |
| Minor | `failsWhenContentIsNotAWorkbook` 喂 ASCII 文本，而 ASCII 文本本来就是 checked `IOException`，**修复前也通过**，没钉住 I3 | 改为构造 `PK\x03\x04` 伪 zip（POI 抛 unchecked 的 `NotOfficeXmlFileException`） |
| Minor | 公式用例用 `"T001"`，修复前也报错（只是文案不同），钉的是文案而非"静默接受"路径 | 改为普通引用型公式 `=A2`（修复前会静默把公式文本当工号） |
| Minor | `openWorkbook` 里 `catch (IOException e) { throw e; }` 纯冗余 | 删除 |
| Minor | 首尾 NBSP 被 `\p{Zs}` 判成非法字符而非裁掉；从 Word/邮件粘贴的表格会因此把可修复行变成错误行 | 新增 `normalize()` 裁掉首尾 `[\s\p{Zs}]`（只裁首尾，内部仍由非法字符集拦截） |

解析器用例总数 20 → 22，全模块回归 81 个通过。

**方法论提醒（复审验证手段，值得复用）**：审查员把**新用例拿去跑修复前的代码**，
发现 20 个用例里只有 6 个真的会失败——这直接暴露了"名不副实"的用例。
加回归用例后应做这个反向验证，否则不知道自己到底钉住了什么。

### Task 2 未采纳的审查意见（经技术评估，非疏漏）
- **M2**（零行 sheet 报 SUCCESS）：判定为设计文档措辞问题而非代码缺陷，已改设计文档 §7.2。
  复审确认理由成立，并要求措辞**同时覆盖两种形态**：(a)「只有表头/零数据行」与 (b)「工作表本身零行（连表头都没有）」
  都返回 `SUCCESS(0)`——已按此补齐。（改代码会破坏"只导表头"的合法场景。）
- **M4**（不校验表头）：设计 §7.3 有意选择位置固定不校验；且真实 Excel 的 B1 表头文字尚未确认，
  加告警会误报。复审撤回原建议，并要求在设计文档里**显式记下已知后果**——
  已在 §7.3 写明"第 1 行无条件丢弃 ⇒ 无表头会静默丢第一个人，且列错位不可察"，
  并把"导入后核对组内成员数"列为当前唯一兜底手段。
- **M7**（logback 用 `off` 而非 `error`）：复审已核对上游基础 `logback-test.xml` 对第三方噪声包
  确实用 `level="error"`，**审查员主动撤回**该条，维持现状。
- **M8**（上传体积上限）：接受为**后续项**，已在设计文档 §11 风险 5 点明它必须落在
  **Task 4 的 REST 入口**（解析器侧不该有、也无法知道上传大小），超限应按 §7.2 记 `FAILURE` 并返回 400。

### 组件注册的时机（由 M3 修订）

`components.txt` 的条目由**创建该组件的任务**负责追加，不要集中到 Task 7
（否则"标注即注册"不成立，漏注册只在运行期才暴露）：

| 任务 | 追加的条目 |
|---|---|
| Task 2 | `org.xwiki.oa.sync.internal.excel.OutsourcingExcelParser`（已完成） |
| Task 3 | `org.xwiki.oa.sync.internal.excel.ExcelImportExecutor` |
| Task 5 | `org.xwiki.oa.sync.internal.excel.OutsourcingExcelImportService` |

Task 7 只做 UI + 核对三个条目齐全。

---

## Task 3: 并发执行器

**Files:**
- Create: `xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-default/src/main/java/org/xwiki/oa/sync/internal/excel/ExcelImportExecutor.java`
- Test: `xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-default/src/test/java/org/xwiki/oa/sync/internal/excel/ExcelImportExecutorTest.java`
- Modify: `xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-default/src/main/resources/META-INF/components.txt`

- [ ] **Step 1: 写失败的执行器测试**

创建 `ExcelImportExecutorTest.java`：

```java
/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.xwiki.oa.sync.internal.excel;

import java.util.List;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.xwiki.component.manager.ComponentManager;
import org.xwiki.context.Execution;
import org.xwiki.context.ExecutionContextManager;
import org.xwiki.oa.sync.OASyncConfiguration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link ExcelImportExecutor}.
 */
class ExcelImportExecutorTest
{
    private final ComponentManager componentManager = mock(ComponentManager.class);

    private final OASyncConfiguration configuration = mock(OASyncConfiguration.class);

    private final ExecutionContextManager executionContextManager = mock(ExecutionContextManager.class);

    private final Execution execution = mock(Execution.class);

    private final ExcelImportExecutor executor = new ExcelImportExecutor();

    @BeforeEach
    void setUp() throws Exception
    {
        when(this.configuration.getExcelImportThreads()).thenReturn(2);
        when(this.componentManager.getInstance(ExecutionContextManager.class)).thenReturn(this.executionContextManager);
        when(this.componentManager.getInstance(Execution.class)).thenReturn(this.execution);

        this.executor.configuration = this.configuration;
        this.executor.componentManager = this.componentManager;
        this.executor.contextProvider = () -> null;
        this.executor.initialize();
    }

    @AfterEach
    void tearDown() throws Exception
    {
        this.executor.dispose();
    }

    @Test
    void runsEveryTaskOnAPoolThreadWithItsOwnContext() throws Exception
    {
        List<String> results = this.executor.runAll(List.of(
            () -> Thread.currentThread().getName(),
            () -> Thread.currentThread().getName()));

        assertEquals(2, results.size());
        assertTrue(results.get(0).startsWith("oa-excel-import-"), results.get(0));
        assertTrue(results.get(1).startsWith("oa-excel-import-"), results.get(1));
        // 每个任务都初始化并清理一次自己的执行上下文
        verify(this.executionContextManager, times(2)).initialize(org.mockito.ArgumentMatchers.any());
        verify(this.execution, times(2)).removeContext();
    }

    @Test
    void returnsTaskResultsInSubmissionOrder() throws Exception
    {
        List<String> results = this.executor.runAll(List.of(
            () -> "a",
            () -> "b",
            () -> "c"));

        assertEquals(List.of("a", "b", "c"), results);
    }

    @Test
    void returnsNullForFailedTaskWithoutFailingTheOthers() throws Exception
    {
        Callable<String> failing = () -> {
            throw new IllegalStateException("boom");
        };

        List<String> results = this.executor.runAll(List.of(() -> "ok", failing, () -> "ok2"));

        assertEquals(3, results.size());
        assertEquals("ok", results.get(0));
        assertNull(results.get(1));
        assertEquals("ok2", results.get(2));
    }

    @Test
    void runsAllTasksWhenListIsEmpty() throws Exception
    {
        assertTrue(this.executor.runAll(List.of()).isEmpty());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run:
```bash
mvn -s ~/.m2/settings-xwiki.xml -f pom.xml \
  -pl xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-api,\
xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-default \
  -Dtest=ExcelImportExecutorTest -DfailIfNoTests=false -Dxwiki.spoon.skip=true -Dlicense.skip=true -Dxwiki.checkstyle.skip=true test
```
Expected: 编译失败，找不到 `ExcelImportExecutor`

- [ ] **Step 3: 实现执行器**

创建 `ExcelImportExecutor.java`：

```java
/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.xwiki.oa.sync.internal.excel;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xwiki.component.annotation.Component;
import org.xwiki.component.manager.ComponentLifecycleException;
import org.xwiki.component.manager.ComponentManager;
import org.xwiki.component.phase.Disposable;
import org.xwiki.component.phase.Initializable;
import org.xwiki.context.concurrent.ExecutionContextRunnable;
import org.xwiki.oa.sync.OASyncConfiguration;

import com.xpn.xwiki.XWikiContext;

/**
 * 外包 Excel 导入的并发执行器：把一个工号的处理任务丢进有界线程池，
 * 每个任务拥有独立的 XWiki 执行上下文（因而有独立的 Hibernate session），
 * 任务结束后释放该上下文的存储会话。
 * <p>
 * 线程池大小来自 {@code xwiki.oa-sync.excel.threads}；整体等待受
 * {@value #TIMEOUT_MINUTES} 分钟超时约束，超时/异常的任务在结果列表中占位 {@code null}。
 *
 * @version $Id$
 */
@Component(roles = ExcelImportExecutor.class)
@Singleton
public class ExcelImportExecutor implements Initializable, Disposable
{
    private static final Logger LOGGER = LoggerFactory.getLogger(ExcelImportExecutor.class);

    /**
     * 整体等待超时（分钟）：超过后请求直接返回已拿到的部分结果。
     */
    private static final long TIMEOUT_MINUTES = 10;

    private static final AtomicInteger THREAD_COUNTER = new AtomicInteger();

    @Inject
    OASyncConfiguration configuration;

    @Inject
    ComponentManager componentManager;

    @Inject
    Provider<XWikiContext> contextProvider;

    private ExecutorService executorService;

    @Override
    public void initialize()
    {
        int threads = this.configuration.getExcelImportThreads();
        this.executorService = Executors.newFixedThreadPool(threads, runnable -> {
            Thread thread = new Thread(runnable, "oa-excel-import-" + THREAD_COUNTER.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        });
        LOGGER.info("外包 Excel 导入线程池已初始化: threads=[{}]", threads);
    }

    /**
     * 并发执行全部任务并等待结束，结果与入参顺序一一对应；失败/超时的位置为 {@code null}。
     *
     * @param tasks the per-user tasks
     * @param <T> the task result type
     * @return one result per task, in the same order
     */
    public <T> List<T> runAll(List<Callable<T>> tasks)
    {
        List<Future<T>> futures = new ArrayList<>(tasks.size());
        for (Callable<T> task : tasks) {
            futures.add(this.executorService.submit(contextualize(task)));
        }

        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(TIMEOUT_MINUTES);
        List<T> results = new ArrayList<>(futures.size());
        for (Future<T> future : futures) {
            results.add(await(future, deadline));
        }
        return results;
    }

    private <T> T await(Future<T> future, long deadline)
    {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            LOGGER.error("外包 Excel 导入整体超时，剩余任务结果被丢弃");
            return null;
        }
        try {
            return future.get(remaining, TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            LOGGER.error("外包 Excel 导入任务超时", e);
            return null;
        } catch (ExecutionException e) {
            LOGGER.error("外包 Excel 导入任务异常", e.getCause());
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    /**
     * 把任务包进一个独立的执行上下文，并在结束时释放该上下文的存储会话。
     * <p>
     * {@link ExecutionContextRunnable#run()} 只负责移除上下文（{@code DefaultExecution.removeContext()}
     * 仅清 ThreadLocal），因此这里额外调用 {@code store.cleanUp(context)} 结束事务、释放连接。
     */
    private <T> Callable<T> contextualize(Callable<T> task)
    {
        return () -> {
            AtomicReference<T> value = new AtomicReference<>();
            AtomicReference<RuntimeException> failure = new AtomicReference<>();

            new ExecutionContextRunnable(() -> {
                try {
                    value.set(task.call());
                } catch (Exception e) {
                    failure.set(new RuntimeException(e));
                } finally {
                    cleanupStore();
                }
            }, this.componentManager).run();

            if (failure.get() != null) {
                throw failure.get();
            }
            return value.get();
        };
    }

    private void cleanupStore()
    {
        XWikiContext context = this.contextProvider.get();
        if (context != null && context.getWiki() != null) {
            context.getWiki().getStore().cleanUp(context);
        }
    }

    @Override
    public void dispose() throws ComponentLifecycleException
    {
        if (this.executorService != null) {
            this.executorService.shutdownNow();
            this.executorService = null;
        }
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: 同 Step 2
Expected: `Tests run: 4, Failures: 0, Errors: 0`

- [ ] **Step 5: 注册组件**

在 `xwiki-platform-oa-sync-default/src/main/resources/META-INF/components.txt` **末尾追加一行**：

```
org.xwiki.oa.sync.internal.excel.ExcelImportExecutor
```

（注册由创建组件的任务负责，别留给 Task 7，否则漏注册只在运行期暴露。）

- [ ] **Step 6: 提交**

```bash
git add xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-default/src/main/java/org/xwiki/oa/sync/internal/excel/ExcelImportExecutor.java \
  xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-default/src/test/java/org/xwiki/oa/sync/internal/excel/ExcelImportExecutorTest.java \
  xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-default/src/main/resources/META-INF/components.txt
git commit -m "外包Excel导入：新增并发执行器（每任务独立上下文+会话清理+整体超时）"
```

### Task 3 复审后的修订（代码为准，见提交 `f20a14d5f90`）

**上面 Task 3 的代码块有 bug**，实际代码已按下面修订（**以仓库代码为准**）：

| # | 问题 | 修订 |
|---|---|---|
| **C1** | **超时分支丢弃已完成结果**：`await` 在 `remaining <= 0` 时直接 `return null`，**不检查 `future.isDone()`**。futures 是**按提交顺序**等待的，所以一个慢任务会把它后面**所有早已成功**的任务一起毒化成 `null`。实测（pool=2、任务 0 睡 300ms、任务 1/2 立即成功、deadline 150ms）：现状 `[null, null, null]`，应为 `[null, fast-1, fast-2]`。后果：一次挂起（DB 锁/连接池耗尽）就让整批**已成功建号**的工号在统计与同步记录里全变成失败，管理员报告错误 | 循环开头加 `if (future.isDone()) { harvest(...); continue; }`；到期时改用 `collectRemaining(...)`：已完成且未取消的**收割**，否则 `cancel(false)` + `null` |
| I1 | 超时后不取消，**排队中的任务也会继续跑**（`newFixedThreadPool` 队列无界），HTTP 响应结束后仍可能持续很久 | 到期时对剩余 future `cancel(false)`（能挡住排队任务，运行中的挡不住，靠建号幂等 + 重跑兜底） |
| I2 | `TIMEOUT_MINUTES` 是 `private static final long` 且参与 `TimeUnit.MINUTES.toNanos(...)` 编译期常量表达式，**值被内联**，超时分支连反射都改不了 → 不可测（C1 正因此潜伏） | 改为包级可见**非 final** 字段 `long timeoutNanos`，并补超时回归用例 |
| M1 | `cleanupStore()` 在 `finally` 里，一旦抛出会**覆盖任务本身的异常**，且 `failure` 永远读不到 | 整体包 try/catch + ERROR 日志，保证永不改变任务结果 |
| M2 | `InterruptedException` 分支无日志，被中断会静默把后面全报成失败 | 加 WARN |
| M3 | 日志无任务标识、超时逐条打 ERROR | `await`/`harvest` 日志带 `index`；整体超时只打一条（带未完成数量） |
| M4 | `dispose()` 把字段置 null，之后 `runAll` 变成裸 NPE | 不置 null，让 `submit` 抛 `RejectedExecutionException` |

用例 4 → 6（新增超时回归 + cleanUp 真实调用；另加固 2 个原有弱断言），全模块回归 92 → 94。

**方法论提醒**：这次 Critical 来自"超时分支不可测"，而修复者被要求做**反证**——
临时还原修复前语义，确认新用例确实变红
（`AssertionFailedError: 早已完成的任务必须被收割 ==> expected: <fast-1> but was: <null>`），
再改回来。加回归用例后应做这个反向验证。

**一个容易写错的细节**：`InOrder` 钉"`cleanUp` 早于 `removeContext`"时**必须用单线程池**。
多线程池下两个任务真并发，交错顺序不确定（可能是 `cleanUp₁, cleanUp₂, removeContext₁, removeContext₂`），
写成 `inOrder.verify(store, times(2)).cleanUp(...)` + `inOrder.verify(execution, times(2)).removeContext()`
这种"先全部再全部"的形式会**不稳定失败**。正确做法是线程数设为 1 + 逐次交替 verify。

**收口时的诚实性核对（复审的反证结论，别过度声称）**：6 个用例里**只有超时回归用例
`keepsResultsOfAlreadyFinishedTasksWhenOneTaskTimesOut` 真正钉住了本次 C1 修复**
（反证时唯一变红）；`returnsTaskResultsInSubmissionOrderEvenWhenCompletionOrderDiffers`、
上下文实例互异（`ArgumentCaptor` + `assertNotSame`）两个加固用例在修复前也通过。
而 cleanUp 用例的 `InOrder` 部分是**承重**的——把 `cleanupStore()` 挪到 `ExecutionContextRunnable`
外面后，只有 `InOrder` 断言失败，`verify(store, times(2))` 仍然通过。
所以：加固用例是"回归防护"，不是"修复验证"，两者别混为一谈。

**未采纳**（经技术评估）：M5 的接口形状（`null` 同时表示"失败/合法 null/未等待到"三种含义）
与队列无界无背压——属计划级最小主义，单管理员操作场景可接受；已在 Task 5 的规格里明确承接 `null` 的语义。

---

## Task 4: 重构 DoapUserSynchronizer 并修复成员覆盖 bug

**Files:**
- Modify: `xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-default/src/main/java/org/xwiki/oa/sync/internal/mq/DoapUserSynchronizer.java`
- Test: `xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-default/src/test/java/org/xwiki/oa/sync/internal/mq/DoapUserSynchronizerTest.java`

- [ ] **Step 1: 先写暴露 bug 的失败测试**

在 `DoapUserSynchronizerTest` 的 `skipsBlankStaffId()` 方法之后插入：

```java
    @Test
    void keepsAllMembersWhenAddingSeveralUsers() throws Exception
    {
        this.synchronizer.sync(message("outsourcing", "10086", false, "active"), this.context);
        this.synchronizer.sync(message("outsourcing", "10087", false, "active"), this.context);
        this.synchronizer.sync(message("outsourcing", "10088", false, "active"), this.context);

        assertEquals(List.of("XWiki.10086", "XWiki.10087", "XWiki.10088"), groupMembers());
    }

    @Test
    void batchAddIsIdempotent() throws Exception
    {
        int first = this.synchronizer.addMembersToOutsourcingGroup(
            List.of("XWiki.10086", "XWiki.10087"), this.context);
        int second = this.synchronizer.addMembersToOutsourcingGroup(
            List.of("XWiki.10086", "XWiki.10087"), this.context);

        assertEquals(2, first);
        assertEquals(0, second);
        assertEquals(List.of("XWiki.10086", "XWiki.10087"), groupMembers());
    }

    @Test
    void batchAddSkipsMembersAlreadyPresent() throws Exception
    {
        this.synchronizer.sync(message("outsourcing", "10086", false, "active"), this.context);

        int added = this.synchronizer.addMembersToOutsourcingGroup(
            List.of("XWiki.10086", "XWiki.10087"), this.context);

        assertEquals(1, added);
        assertEquals(List.of("XWiki.10086", "XWiki.10087"), groupMembers());
    }

    @Test
    void batchAddCreatesTheGroupWhenMissing() throws Exception
    {
        assertTrue(groupDoc().isNew());

        this.synchronizer.addMembersToOutsourcingGroup(List.of("XWiki.10086"), this.context);

        assertEquals(DoapMqConstants.OUTSOURCING_GROUP_NAME, groupDoc().getTitle());
        assertEquals(List.of("XWiki.10086"), groupMembers());
    }

    @Test
    void ensureUserReportsCreatedThenExisted() throws Exception
    {
        DoapUserSynchronizer.EnsuredUser created = this.synchronizer.ensureUser("10086", this.context);
        DoapUserSynchronizer.EnsuredUser existed = this.synchronizer.ensureUser("10086", this.context);

        assertTrue(created.isCreated());
        assertEquals("XWiki.10086", created.getFullName());
        assertFalse(existed.isCreated());
        assertEquals("XWiki.10086", existed.getFullName());
    }

    private List<String> groupMembers() throws Exception
    {
        List<BaseObject> objects = groupDoc().getXObjects(
            new DocumentReference(this.context.getWikiId(), "XWiki", "XWikiGroups"));
        if (objects == null) {
            return List.of();
        }
        List<String> members = new ArrayList<>();
        for (BaseObject object : objects) {
            if (object != null) {
                members.add(object.getStringValue("member"));
            }
        }
        return members;
    }
```

同时补 import（`checkstyle` 会因未使用的 import 报错，只加这三个）：

```java
import java.util.ArrayList;
import java.util.List;

import com.xpn.xwiki.objects.BaseObject;
```

- [ ] **Step 2: 运行测试确认失败**

Run:
```bash
mvn -s ~/.m2/settings-xwiki.xml -f pom.xml \
  -pl xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-api,\
xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-default \
  -Dtest=DoapUserSynchronizerTest -DfailIfNoTests=false -Dxwiki.spoon.skip=true -Dlicense.skip=true -Dxwiki.checkstyle.skip=true test
```
Expected: 编译失败（`addMembersToOutsourcingGroup` / `EnsuredUser` / `ensureUser` 不存在）。
编译通过后，`keepsAllMembersWhenAddingSeveralUsers` 也会断言失败——这正是要修的 bug。

- [ ] **Step 3: 重构实现（含 bug 修复）**

删除 `DoapUserSynchronizer.java` 中 `package org.xwiki.oa.sync.internal.mq;` 之后的**全部**内容
（含原有 import 区块），用下面这段完整内容替换：

```java
package org.xwiki.oa.sync.internal.mq;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xwiki.component.annotation.Component;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.EntityReference;
import org.xwiki.oa.sync.mq.DoapMqConstants;
import org.xwiki.oa.sync.mq.model.DoapUserMessage;

import com.xpn.xwiki.XWiki;
import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;
import com.xpn.xwiki.user.api.XWikiUser;

/**
 * 将 DOAP MQ 消息中的外包用户同步到 XWiki：
 * <ul>
 *   <li>只处理 {@code type=outsourcing} 的消息，其余直接丢弃；</li>
 *   <li>工号 {@code staffId} 作为 XWiki 用户名，用户不存在则新建；</li>
 *   <li>所有外包用户加入统一权限组「开发中心科技外包组」；</li>
 *   <li>{@code isDeleted=true} 或 {@code status=dismission} 时禁用用户，否则保持原状态（不重新启用）。</li>
 * </ul>
 * 「建号」与「入组」抽成 {@link #ensureUser(String, XWikiContext)} 与
 * {@link #addMembersToOutsourcingGroup(Collection, XWikiContext)}，
 * 供 MQ 增量与 Excel 全量导入共用，保证两条链路结果状态一致。
 *
 * @version $Id$
 */
@Component(roles = DoapUserSynchronizer.class)
@Singleton
public class DoapUserSynchronizer
{
    private static final Logger LOGGER = LoggerFactory.getLogger(DoapUserSynchronizer.class);

    /**
     * 批量入组时单次保存的成员数上限。
     */
    private static final int GROUP_CHUNK_SIZE = 200;

    /**
     * 建号结果。
     */
    public static final class EnsuredUser
    {
        private final String fullName;

        private final boolean created;

        /**
         * @param fullName the user document full name, e.g. {@code XWiki.10086}
         * @param created whether the user was created by this call
         */
        public EnsuredUser(String fullName, boolean created)
        {
            this.fullName = fullName;
            this.created = created;
        }

        /**
         * @return the user document full name, e.g. {@code XWiki.10086}
         */
        public String getFullName()
        {
            return this.fullName;
        }

        /**
         * @return whether the user was created by this call
         */
        public boolean isCreated()
        {
            return this.created;
        }
    }

    /**
     * 处理一条外包用户消息。
     *
     * @param message the parsed MQ message
     * @param context the XWiki context
     * @throws XWikiException if user/group operations fail
     */
    public void sync(DoapUserMessage message, XWikiContext context) throws XWikiException
    {
        String type = message.getString(DoapMqConstants.FIELD_TYPE);
        if (!DoapMqConstants.USER_TYPE_OUTSOURCING.equals(type)) {
            LOGGER.info("DOAP MQ 非外包用户消息，丢弃: msgId=[{}] type=[{}]", message.getMsgId(), type);
            return;
        }

        String username = message.getString(DoapMqConstants.FIELD_STAFF_ID);
        if (username == null || username.isBlank()) {
            LOGGER.warn("DOAP MQ 外包用户消息缺少工号 staffId，丢弃: msgId=[{}]", message.getMsgId());
            return;
        }
        username = username.trim();

        // 1. 用户不存在则新建
        EnsuredUser user = ensureUser(username, context);

        // 2. 加入统一权限组「开发中心科技外包组」
        addMembersToOutsourcingGroup(Collections.singletonList(user.getFullName()), context);

        // 3. 禁用判断：isDeleted=true 或 status=dismission → 禁用；否则保持原状态（不重新启用）
        boolean isDeleted = Boolean.TRUE.equals(message.getBoolean(DoapMqConstants.FIELD_IS_DELETED));
        String status = message.getString(DoapMqConstants.FIELD_STATUS);
        boolean dismission = status != null && status.trim().equals(DoapMqConstants.STATUS_DISMISSION);
        if (isDeleted || dismission) {
            DocumentReference userRef = new DocumentReference(context.getWikiId(), "XWiki", username);
            XWikiUser xwikiUser = new XWikiUser(userRef);
            if (!xwikiUser.isDisabled(context)) {
                xwikiUser.setDisabled(true, context);
                LOGGER.info("DOAP MQ 禁用用户: [{}] isDeleted=[{}] status=[{}]", username, isDeleted, status);
            }
        } else {
            LOGGER.info("DOAP MQ 用户状态无需变更: [{}] isDeleted=[{}] status=[{}]", username, isDeleted, status);
        }
    }

    /**
     * 幂等建号：用户不存在则按工号新建。
     *
     * @param username the staff id, also the XWiki user name
     * @param context the XWiki context
     * @return the ensured user (full name + whether it was created now)
     * @throws XWikiException if the user cannot be created
     */
    public EnsuredUser ensureUser(String username, XWikiContext context) throws XWikiException
    {
        XWiki wiki = context.getWiki();
        DocumentReference userRef = new DocumentReference(context.getWikiId(), "XWiki", username);
        XWikiDocument userDoc = wiki.getDocument(userRef, context);
        if (!userDoc.isNew()) {
            return new EnsuredUser(userDoc.getFullName(), false);
        }

        wiki.createUser(username, Collections.emptyMap(), context);
        userDoc = wiki.getDocument(userRef, context);
        LOGGER.info("OA 用户同步新建用户: [{}]", username);
        return new EnsuredUser(userDoc.getFullName(), true);
    }

    /**
     * 把若干成员批量加入统一外包权限组，返回真正新增的成员数。
     * <p>
     * 串行执行（组文档是单点，不能并发写）；按已有成员求差集后分块保存，
     * 每个待加成员都创建**新的** XWikiGroups 对象，绝不覆盖已有成员。
     *
     * @param memberFullNames the member full names, e.g. {@code XWiki.10086}
     * @param context the XWiki context
     * @return how many members were actually added
     * @throws XWikiException if the group cannot be created or saved
     */
    public int addMembersToOutsourcingGroup(Collection<String> memberFullNames, XWikiContext context)
        throws XWikiException
    {
        if (memberFullNames == null || memberFullNames.isEmpty()) {
            return 0;
        }

        XWiki wiki = context.getWiki();
        DocumentReference groupRef =
            new DocumentReference(context.getWikiId(), "XWiki", DoapMqConstants.OUTSOURCING_GROUP_NAME);
        EntityReference groupClassRef =
            wiki.getGroupClass(context).getDocumentReference().removeParent(groupRef.getWikiReference());
        ensureGroup(groupRef, context);

        List<String> toAdd = computeMissingMembers(wiki.getDocument(groupRef, context), groupClassRef, memberFullNames);
        if (toAdd.isEmpty()) {
            return 0;
        }

        for (int start = 0; start < toAdd.size(); start += GROUP_CHUNK_SIZE) {
            List<String> chunk = toAdd.subList(start, Math.min(start + GROUP_CHUNK_SIZE, toAdd.size()));
            XWikiDocument modified = wiki.getDocument(groupRef, context).clone();
            for (String memberFullName : chunk) {
                BaseObject memberObject = modified.newXObject(groupClassRef, context);
                memberObject.setStringValue("member", memberFullName);
            }
            wiki.saveDocument(modified,
                "OA sync: 批量添加 " + chunk.size() + " 名成员到组 " + groupRef.getName(), context);
        }
        LOGGER.info("OA 用户同步入组: 组=[{}] 新增成员=[{}]", DoapMqConstants.OUTSOURCING_GROUP_NAME, toAdd.size());
        return toAdd.size();
    }

    private List<String> computeMissingMembers(XWikiDocument groupDoc, EntityReference groupClassRef,
        Collection<String> memberFullNames)
    {
        Set<String> existing = new HashSet<>();
        List<BaseObject> objects = groupDoc.getXObjects(groupClassRef);
        if (objects != null) {
            for (BaseObject object : objects) {
                if (object != null) {
                    String member = object.getStringValue("member");
                    if (member != null && !member.isBlank()) {
                        existing.add(member.trim());
                    }
                }
            }
        }

        List<String> toAdd = new ArrayList<>();
        for (String memberFullName : memberFullNames) {
            if (memberFullName != null && !memberFullName.isBlank() && existing.add(memberFullName.trim())) {
                toAdd.add(memberFullName.trim());
            }
        }
        return toAdd;
    }

    private void ensureGroup(DocumentReference groupRef, XWikiContext context) throws XWikiException
    {
        XWiki wiki = context.getWiki();
        XWikiDocument groupDoc = wiki.getDocument(groupRef, context);
        if (groupDoc.isNew()) {
            XWikiDocument doc = new XWikiDocument(groupRef);
            doc.setTitle(DoapMqConstants.OUTSOURCING_GROUP_NAME);
            wiki.saveDocument(doc, "DOAP MQ sync: 创建权限组 " + DoapMqConstants.OUTSOURCING_GROUP_NAME, context);
            LOGGER.info("DOAP MQ 创建权限组: [{}]", DoapMqConstants.OUTSOURCING_GROUP_NAME);
        }
    }
}
```

要点：
- 删掉了 `GroupManager` 注入与 `addMemberIfMissing`（后者就是 bug 所在）
- `sync()` 的对外行为（丢弃规则/建号/入组/禁用）逐条保持
- 入组改为「读组文档求差集 → 每成员 `newXObject` → 分块保存」

- [ ] **Step 4: 运行测试确认通过**

Run: 同 Step 2
Expected: `Tests run: 11, Failures: 0, Errors: 0`（原有 6 个 + 新增 5 个）

- [ ] **Step 5: 回归确认 MQ 其余行为未变**

Run:
```bash
mvn -s ~/.m2/settings-xwiki.xml -f pom.xml \
  -pl xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-api,\
xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-default \
  -Dtest='DoapUser*Test' -DfailIfNoTests=false -Dxwiki.spoon.skip=true -Dlicense.skip=true -Dxwiki.checkstyle.skip=true test
```
Expected: 全部通过（`DoapUserSynchronizerTest`、`DoapUserMessageParserTest`、`DoapUserMessageDispatcherTest`、`DoapUserMessageHandlersTest`）

- [ ] **Step 6: 提交**

```bash
git add xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-default/src/main/java/org/xwiki/oa/sync/internal/mq/DoapUserSynchronizer.java \
  xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-default/src/test/java/org/xwiki/oa/sync/internal/mq/DoapUserSynchronizerTest.java
git commit -m "外包同步：修复加组成员覆盖已有成员的 bug，并抽出可复用的建号/批量入组方法

原 addMemberIfMissing 用 getXObject(组类) 取对象，而该 API 返回第一个对象，
导致新增成员时覆盖第一个已有成员，组内始终只能保留一个人。
改为每个待加成员 newXObject，并支持批量分块保存。"
```

---

## Task 5: 导入统计与编排服务

**Files:**
- Create: `xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-default/src/main/java/org/xwiki/oa/sync/internal/excel/ExcelImportResult.java`
- Create: `xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-default/src/main/java/org/xwiki/oa/sync/internal/excel/OutsourcingExcelImportService.java`
- Test: `xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-default/src/test/java/org/xwiki/oa/sync/internal/excel/OutsourcingExcelImportServiceTest.java`
- Modify: `xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-default/src/main/resources/META-INF/components.txt`

> **必须显式承接 `ExcelImportExecutor.runAll` 的 `null` 语义**（Task 3 复审留下的计划级洞）。
> `runAll` 的 `results[i] == null` 同时表示三种情况：任务**确定失败**、任务返回了合法 `null`、
> **整体超时没等到**。编排层无法区分它们，因此：
>
> - 一律计入 `failed`，错误文案必须写成"**建号失败或超时**"（不要只写"失败"，那会误导）
> - 后果是**报告不完整而非数据损坏**：`ensureUser` 幂等、组成员只是字符串引用，
>   给"尚未建号"的用户入组不会报错；超时被 `cancel(false)` 掉的工号下次重跑会得到"已存在"
> - 因此**绝不能**在存在 `null` 时把本次导入判为 `SUCCESS`；有 `null` 就必须是 `PARTIAL_FAILURE`
> - 运维语义：管理员看到 `failed > 0` 直接重跑即可（幂等），不需要人工清理
>
> 当前计划的服务实现已经有 `(outcome == null ? "任务超时或异常" : outcome.getMessage())` 的写法，
> 符合上述要求；这一段是把它从"碰巧写对"变成**显式约定**，避免后续改动丢掉。

- [ ] **Step 1: 写失败的服务测试**

创建 `OutsourcingExcelImportServiceTest.java`：

```java
/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.xwiki.oa.sync.internal.excel;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.xwiki.oa.sync.internal.mq.DoapUserSynchronizer;
import org.xwiki.oa.sync.internal.store.SyncRecordStore;
import org.xwiki.oa.sync.model.OASyncResult;

import com.xpn.xwiki.XWikiContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link OutsourcingExcelImportService}.
 */
class OutsourcingExcelImportServiceTest
{
    private final OutsourcingExcelParser parser = mock(OutsourcingExcelParser.class);

    private final DoapUserSynchronizer synchronizer = mock(DoapUserSynchronizer.class);

    private final ExcelImportExecutor executor = mock(ExcelImportExecutor.class);

    private final SyncRecordStore recordStore = mock(SyncRecordStore.class);

    private final XWikiContext context = mock(XWikiContext.class);

    private final OutsourcingExcelImportService service = new OutsourcingExcelImportService();

    @BeforeEach
    void setUp() throws Exception
    {
        this.service.parser = this.parser;
        this.service.synchronizer = this.synchronizer;
        this.service.executor = this.executor;
        this.service.recordStore = this.recordStore;
        this.service.contextProvider = () -> this.context;

        // 执行器改为同步内联执行，使编排逻辑可确定性验证
        doAnswer(invocation -> {
            List<Callable<?>> tasks = invocation.getArgument(0);
            List<Object> results = new ArrayList<>(tasks.size());
            for (Callable<?> task : tasks) {
                results.add(task.call());
            }
            return results;
        }).when(this.executor).runAll(anyList());
    }

    @Test
    void countsCreatedExistedAndJoinedMembers() throws Exception
    {
        parseReturns("T001", "T002", "T003");
        when(this.synchronizer.ensureUser(eq("T001"), any()))
            .thenReturn(new DoapUserSynchronizer.EnsuredUser("XWiki.T001", true));
        when(this.synchronizer.ensureUser(eq("T002"), any()))
            .thenReturn(new DoapUserSynchronizer.EnsuredUser("XWiki.T002", false));
        when(this.synchronizer.ensureUser(eq("T003"), any()))
            .thenReturn(new DoapUserSynchronizer.EnsuredUser("XWiki.T003", true));
        when(this.synchronizer.addMembersToOutsourcingGroup(anyCollection(), any())).thenReturn(2);

        ExcelImportResult result = this.service.importFrom(stream(), this.context);

        assertEquals(3, result.getTotal());
        assertEquals(2, result.getCreated());
        assertEquals(1, result.getExisted());
        assertEquals(2, result.getJoinedGroup());
        assertEquals(1, result.getAlreadyMember());
        assertEquals(0, result.getFailed());
        assertTrue(result.getErrors().isEmpty());
    }

    @Test
    void isolatesAFailingUserAndStillProcessesTheRest() throws Exception
    {
        parseReturns("T001", "T002");
        when(this.synchronizer.ensureUser(eq("T001"), any()))
            .thenReturn(new DoapUserSynchronizer.EnsuredUser("XWiki.T001", true));
        when(this.synchronizer.ensureUser(eq("T002"), any()))
            .thenThrow(new RuntimeException("建号失败"));
        when(this.synchronizer.addMembersToOutsourcingGroup(anyCollection(), any())).thenReturn(1);

        ExcelImportResult result = this.service.importFrom(stream(), this.context);

        assertEquals(2, result.getTotal());
        assertEquals(1, result.getCreated());
        assertEquals(1, result.getFailed());
        assertEquals(1, result.getErrors().size());
        assertTrue(result.getErrors().get(0).contains("T002"));
        // 失败用户不进组
        verify(this.synchronizer).addMembersToOutsourcingGroup(eq(List.of("XWiki.T001")), eq(this.context));
    }

    @Test
    void writesSyncRecordWithPartialFailureStatus() throws Exception
    {
        parseReturns("T001");
        when(this.synchronizer.ensureUser(eq("T001"), any()))
            .thenThrow(new RuntimeException("建号失败"));
        when(this.synchronizer.addMembersToOutsourcingGroup(anyCollection(), any())).thenReturn(0);

        this.service.importFrom(stream(), this.context);

        org.mockito.ArgumentCaptor<OASyncResult> captor =
            org.mockito.ArgumentCaptor.forClass(OASyncResult.class);
        verify(this.recordStore).save(captor.capture(), eq(this.context));
        assertEquals(OutsourcingExcelImportService.TYPE, captor.getValue().getType());
        assertEquals(OASyncResult.Status.PARTIAL_FAILURE, captor.getValue().getStatus());
        verify(this.recordStore).cleanup(OutsourcingExcelImportService.TYPE, this.context);
    }

    @Test
    void writesSyncRecordWithSuccessStatusWhenAllGood() throws Exception
    {
        parseReturns("T001");
        when(this.synchronizer.ensureUser(eq("T001"), any()))
            .thenReturn(new DoapUserSynchronizer.EnsuredUser("XWiki.T001", true));
        when(this.synchronizer.addMembersToOutsourcingGroup(anyCollection(), any())).thenReturn(1);

        this.service.importFrom(stream(), this.context);

        org.mockito.ArgumentCaptor<OASyncResult> captor =
            org.mockito.ArgumentCaptor.forClass(OASyncResult.class);
        verify(this.recordStore).save(captor.capture(), eq(this.context));
        assertEquals(OASyncResult.Status.SUCCESS, captor.getValue().getStatus());
    }

    @Test
    void propagatesParseFailureAndRecordsItAsFailure() throws Exception
    {
        when(this.parser.parse(any(InputStream.class))).thenThrow(new IOException("不是合法的工作簿"));

        try {
            this.service.importFrom(stream(), this.context);
            org.junit.jupiter.api.Assertions.fail("应当抛出 IOException");
        } catch (IOException expected) {
            assertTrue(expected.getMessage().contains("不是合法的工作簿"));
        }

        org.mockito.ArgumentCaptor<OASyncResult> captor =
            org.mockito.ArgumentCaptor.forClass(OASyncResult.class);
        verify(this.recordStore).save(captor.capture(), eq(this.context));
        assertEquals(OASyncResult.Status.FAILURE, captor.getValue().getStatus());
        verify(this.synchronizer, never()).ensureUser(any(), any());
    }

    private void parseReturns(String... usernames)
    {
        OutsourcingExcelParseResult parsed = new OutsourcingExcelParseResult();
        parsed.getUsernames().addAll(List.of(usernames));
        when(this.parser.parse(any(InputStream.class))).thenAnswer(invocation -> parsed);
    }

    private InputStream stream()
    {
        return new ByteArrayInputStream(new byte[] { 1, 2, 3 });
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run:
```bash
mvn -s ~/.m2/settings-xwiki.xml -f pom.xml \
  -pl xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-api,\
xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-default \
  -Dtest=OutsourcingExcelImportServiceTest -DfailIfNoTests=false -Dxwiki.spoon.skip=true -Dlicense.skip=true -Dxwiki.checkstyle.skip=true test
```
Expected: 编译失败，找不到 `ExcelImportResult` / `OutsourcingExcelImportService`

- [ ] **Step 3: 实现统计对象**

创建 `ExcelImportResult.java`：

```java
/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.xwiki.oa.sync.internal.excel;

import java.util.ArrayList;
import java.util.List;

/**
 * 一次外包 Excel 导入的统计结果。
 *
 * @version $Id$
 */
public class ExcelImportResult
{
    private int total;

    private int created;

    private int existed;

    private int joinedGroup;

    private int alreadyMember;

    private int failed;

    private final List<String> errors = new ArrayList<>();

    private final List<String> warnings = new ArrayList<>();

    /**
     * @return the number of parsed (deduplicated) staff ids
     */
    public int getTotal()
    {
        return this.total;
    }

    /**
     * @param total the number of parsed staff ids
     */
    public void setTotal(int total)
    {
        this.total = total;
    }

    /**
     * @return how many users were created
     */
    public int getCreated()
    {
        return this.created;
    }

    /**
     * Increment the created counter.
     */
    public void incrementCreated()
    {
        this.created++;
    }

    /**
     * @return how many users already existed
     */
    public int getExisted()
    {
        return this.existed;
    }

    /**
     * Increment the already-existed counter.
     */
    public void incrementExisted()
    {
        this.existed++;
    }

    /**
     * @return how many members were actually added to the outsourcing group
     */
    public int getJoinedGroup()
    {
        return this.joinedGroup;
    }

    /**
     * @param joinedGroup how many members were added
     */
    public void setJoinedGroup(int joinedGroup)
    {
        this.joinedGroup = joinedGroup;
    }

    /**
     * @return how many users were already members of the outsourcing group
     */
    public int getAlreadyMember()
    {
        return this.alreadyMember;
    }

    /**
     * @param alreadyMember how many users were already members
     */
    public void setAlreadyMember(int alreadyMember)
    {
        this.alreadyMember = alreadyMember;
    }

    /**
     * @return how many users failed
     */
    public int getFailed()
    {
        return this.failed;
    }

    /**
     * Increment the failure counter.
     */
    public void incrementFailed()
    {
        this.failed++;
    }

    /**
     * @return the errors (fail the import)
     */
    public List<String> getErrors()
    {
        return this.errors;
    }

    /**
     * @return the warnings (informational only)
     */
    public List<String> getWarnings()
    {
        return this.warnings;
    }
}
```

- [ ] **Step 4: 实现编排服务**

创建 `OutsourcingExcelImportService.java`：

```java
/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.xwiki.oa.sync.internal.excel;

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xwiki.component.annotation.Component;
import org.xwiki.oa.sync.internal.mq.DoapUserSynchronizer;
import org.xwiki.oa.sync.internal.store.SyncRecordStore;
import org.xwiki.oa.sync.model.OASyncResult;
import org.xwiki.oa.sync.model.OASyncTriggerType;

import com.xpn.xwiki.XWikiContext;

/**
 * 科技外包用户 Excel 全量导入编排：解析 → 并发建号 → 串行批量入组 → 统计 → 写同步记录。
 * <p>
 * 只增量补齐：建号幂等、入组幂等，不触碰 Excel 之外的用户。
 *
 * @version $Id$
 */
@Component(roles = OutsourcingExcelImportService.class)
@Singleton
public class OutsourcingExcelImportService
{
    /**
     * 同步记录类型。
     */
    public static final String TYPE = "outsourcing-excel";

    private static final Logger LOGGER = LoggerFactory.getLogger(OutsourcingExcelImportService.class);

    @Inject
    OutsourcingExcelParser parser;

    @Inject
    DoapUserSynchronizer synchronizer;

    @Inject
    ExcelImportExecutor executor;

    @Inject
    SyncRecordStore recordStore;

    @Inject
    Provider<XWikiContext> contextProvider;

    /**
     * 导入一个 Excel。
     *
     * @param excel the uploaded workbook content
     * @param context the request XWiki context
     * @return the import statistics
     * @throws IOException if the workbook cannot be parsed at all (a FAILURE record is still written)
     */
    public ExcelImportResult importFrom(InputStream excel, XWikiContext context) throws IOException
    {
        OutsourcingExcelParseResult parsed;
        try {
            parsed = this.parser.parse(excel);
        } catch (IOException e) {
            LOGGER.error("外包 Excel 导入: 解析失败", e);
            saveFailureRecord(e.getMessage(), context);
            throw e;
        }

        ExcelImportResult result = new ExcelImportResult();
        result.setTotal(parsed.getUsernames().size());
        result.getErrors().addAll(parsed.getErrors());
        result.getWarnings().addAll(parsed.getWarnings());
        LOGGER.info("外包 Excel 导入: 解析完成 有效工号=[{}] 行级错误=[{}] 警告=[{}]", parsed.getUsernames().size(),
            parsed.getErrors().size(), parsed.getWarnings().size());

        List<String> usernames = parsed.getUsernames();

        // 阶段 1：并发建号（每个任务有独立的执行上下文与 Hibernate session）
        List<Callable<UserOutcome>> tasks = new ArrayList<>(usernames.size());
        for (String username : usernames) {
            tasks.add(() -> ensureUser(username));
        }
        List<UserOutcome> outcomes = this.executor.runAll(tasks);

        List<String> memberFullNames = new ArrayList<>(usernames.size());
        for (int index = 0; index < usernames.size(); index++) {
            UserOutcome outcome = outcomes.get(index);
            if (outcome == null || outcome.getStatus() == UserOutcome.Status.FAILED) {
                result.incrementFailed();
                result.getErrors().add("用户 [" + usernames.get(index) + "] 建号失败: "
                    + (outcome == null ? "任务超时或异常" : outcome.getMessage()));
                continue;
            }
            if (outcome.getStatus() == UserOutcome.Status.CREATED) {
                result.incrementCreated();
            } else {
                result.incrementExisted();
            }
            memberFullNames.add(outcome.getFullName());
        }

        // 阶段 2：串行批量入组（组文档单点，不能并发写）
        try {
            int added = this.synchronizer.addMembersToOutsourcingGroup(memberFullNames, context);
            result.setJoinedGroup(added);
            result.setAlreadyMember(memberFullNames.size() - added);
        } catch (Exception e) {
            LOGGER.error("外包 Excel 导入: 批量入组失败", e);
            result.getErrors().add("批量加入「开发中心科技外包组」失败: " + e.getMessage());
        }

        saveRecord(result, context);
        LOGGER.info("外包 Excel 导入完成: 总数=[{}] 新建=[{}] 已存在=[{}] 入组=[{}] 已在组=[{}] 失败=[{}]",
            result.getTotal(), result.getCreated(), result.getExisted(), result.getJoinedGroup(),
            result.getAlreadyMember(), result.getFailed());
        return result;
    }

    private UserOutcome ensureUser(String username)
    {
        XWikiContext context = this.contextProvider.get();
        try {
            DoapUserSynchronizer.EnsuredUser ensured = this.synchronizer.ensureUser(username, context);
            return new UserOutcome(username,
                ensured.isCreated() ? UserOutcome.Status.CREATED : UserOutcome.Status.EXISTED,
                ensured.getFullName(), null);
        } catch (Exception e) {
            LOGGER.error("外包 Excel 导入建号失败: [{}]", username, e);
            return new UserOutcome(username, UserOutcome.Status.FAILED, null, e.getMessage());
        }
    }

    private void saveRecord(ExcelImportResult result, XWikiContext context)
    {
        OASyncResult record = new OASyncResult(TYPE, LocalDate.now(), OASyncTriggerType.MANUAL,
            LocalDateTime.now());
        record.setTotalCount(result.getTotal());
        record.setSuccessCount(result.getCreated() + result.getExisted());
        record.setFailCount(result.getFailed());
        record.setStatus(result.getFailed() == 0
            ? OASyncResult.Status.SUCCESS : OASyncResult.Status.PARTIAL_FAILURE);
        record.getErrorLog().addAll(result.getErrors());
        record.getErrorLog().addAll(result.getWarnings());
        record.setEndTime(LocalDateTime.now());
        try {
            this.recordStore.save(record, context);
            this.recordStore.cleanup(TYPE, context);
        } catch (Exception e) {
            LOGGER.error("外包 Excel 导入: 保存同步记录失败", e);
        }
    }

    private void saveFailureRecord(String message, XWikiContext context)
    {
        OASyncResult record = new OASyncResult(TYPE, LocalDate.now(), OASyncTriggerType.MANUAL,
            LocalDateTime.now());
        record.setStatus(OASyncResult.Status.FAILURE);
        record.getErrorLog().add("Excel 解析失败: " + message);
        record.setEndTime(LocalDateTime.now());
        try {
            this.recordStore.save(record, context);
            this.recordStore.cleanup(TYPE, context);
        } catch (Exception e) {
            LOGGER.error("外包 Excel 导入: 保存失败记录失败", e);
        }
    }

    /**
     * 单个工号的处理结果。
     */
    static final class UserOutcome
    {
        /**
         * 建号结果状态。
         */
        enum Status
        {
            /**
             * 本次新建。
             */
            CREATED,

            /**
             * 用户已存在。
             */
            EXISTED,

            /**
             * 处理失败。
             */
            FAILED
        }

        private final String username;

        private final Status status;

        private final String fullName;

        private final String message;

        UserOutcome(String username, Status status, String fullName, String message)
        {
            this.username = username;
            this.status = status;
            this.fullName = fullName;
            this.message = message;
        }

        Status getStatus()
        {
            return this.status;
        }

        String getFullName()
        {
            return this.fullName;
        }

        String getMessage()
        {
            return this.message;
        }

        @Override
        public String toString()
        {
            return "UserOutcome{username='" + this.username + "', status=" + this.status + '}';
        }
    }
}
```

- [ ] **Step 5: 运行测试确认通过**

Run: 同 Step 2
Expected: `Tests run: 5, Failures: 0, Errors: 0`

- [ ] **Step 6: 注册组件**

在 `xwiki-platform-oa-sync-default/src/main/resources/META-INF/components.txt` **末尾追加一行**：

```
org.xwiki.oa.sync.internal.excel.OutsourcingExcelImportService
```

- [ ] **Step 7: 提交**

```bash
git add xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-default/src/main/java/org/xwiki/oa/sync/internal/excel \
  xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-default/src/test/java/org/xwiki/oa/sync/internal/excel \
  xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-default/src/main/resources/META-INF/components.txt
git commit -m "外包Excel导入：新增导入统计与编排服务（并发建号+串行入组+同步记录）"
```

---

## Task 6: REST 端点与权限校验

**Files:**
- Modify: `xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-default/src/main/java/org/xwiki/oa/sync/internal/rest/OASyncRESTResource.java`
- Test: `xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-default/src/test/java/org/xwiki/oa/sync/internal/rest/OASyncRESTResourceTest.java`

- [ ] **Step 1: 写失败的权限测试**

创建 `OASyncRESTResourceTest.java`：

```java
/*
 * See the NOTICE file distributed with this work for additional
 * information regarding copyright ownership.
 *
 * This is free software; you can redistribute it and/or modify it
 * under the terms of the GNU Lesser General Public License as
 * published by the Free Software Foundation; either version 2.1 of
 * the License, or (at your option) any later version.
 *
 * This software is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this software; if not, write to the Free
 * Software Foundation, Inc., 51 Franklin St, Fifth Floor, Boston, MA
 * 02110-1301 USA, or see the FSF site: http://www.fsf.org.
 */
package org.xwiki.oa.sync.internal.rest;

import java.io.ByteArrayInputStream;

import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;

import org.junit.jupiter.api.Test;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.oa.sync.internal.excel.OutsourcingExcelImportService;
import org.xwiki.security.authorization.AccessDeniedException;
import org.xwiki.security.authorization.ContextualAuthorizationManager;
import org.xwiki.security.authorization.Right;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Unit tests for the admin-only Excel import endpoint of {@link OASyncRESTResource}.
 */
class OASyncRESTResourceTest
{
    private final ContextualAuthorizationManager authorizationManager =
        mock(ContextualAuthorizationManager.class);

    private final OutsourcingExcelImportService importService = mock(OutsourcingExcelImportService.class);

    private final OASyncRESTResource resource = new OASyncRESTResource();

    @Test
    void rejectsNonAdminWithForbidden() throws Exception
    {
        this.resource.authorizationManager = this.authorizationManager;
        this.resource.excelImportService = this.importService;
        // AccessDeniedException 没有 String 构造函数，用真实的引用构造
        doThrow(new AccessDeniedException(Right.ADMIN,
            new DocumentReference("xwiki", "XWiki", "Admin"),
            new DocumentReference("xwiki", "XWiki", "WebHome")))
            .when(this.authorizationManager).checkAccess(Right.ADMIN);

        WebApplicationException exception = assertThrows(WebApplicationException.class,
            () -> this.resource.importOutsourcingExcel(new ByteArrayInputStream(new byte[0])));

        assertEquals(Response.Status.FORBIDDEN.getStatusCode(), exception.getResponse().getStatus());
        verify(this.importService, never()).importFrom(any(), any());
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run:
```bash
mvn -s ~/.m2/settings-xwiki.xml -f pom.xml \
  -pl xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-api,\
xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-default \
  -Dtest=OASyncRESTResourceTest -DfailIfNoTests=false -Dxwiki.spoon.skip=true -Dlicense.skip=true -Dxwiki.checkstyle.skip=true test
```
Expected: 编译失败，`importOutsourcingExcel` 不存在，且 `authorizationManager`/`excelImportService` 字段不存在

- [ ] **Step 3: 实现端点**

在 `OASyncRESTResource.java` 中：

(a) 补充 import：

```java
import java.io.IOException;
import java.io.InputStream;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response.Status;

import org.xwiki.oa.sync.internal.excel.ExcelImportResult;
import org.xwiki.oa.sync.internal.excel.OutsourcingExcelImportService;
import org.xwiki.security.authorization.AccessDeniedException;
import org.xwiki.security.authorization.ContextualAuthorizationManager;
import org.xwiki.security.authorization.Right;
```

> `MediaType` 与 `Response` 已在原文件中导入，不要重复导入。

(b) 增加注入字段（放在现有 `oaSyncService` 字段之后）：

```java
    @Inject
    ContextualAuthorizationManager authorizationManager;

    @Inject
    OutsourcingExcelImportService excelImportService;
```

(c) 在 `trigger(...)` 方法之后新增端点方法：

```java
    /**
     * 上传科技外包用户 Excel 并执行初次全量导入：按 staff_id 建号 + 全部加入「开发中心科技外包组」。
     * 只增量补齐，不动 Excel 之外的用户；重复导入幂等。
     * <p>
     * 用原始字节流而非 multipart：{@code application/octet-stream} 不在 XWiki REST 的
     * 浏览器简单请求类型列表内（{@code PreMatchingRequestFilter}），因而无需 CSRF token。
     *
     * @param body the .xlsx content
     * @return JSON summary of the import
     * @throws Exception if the workbook cannot be parsed
     */
    @POST
    @Path("/outsourcing-excel")
    @Consumes(MediaType.APPLICATION_OCTET_STREAM)
    @Produces(MediaType.APPLICATION_JSON)
    public Response importOutsourcingExcel(InputStream body) throws Exception
    {
        try {
            this.authorizationManager.checkAccess(Right.ADMIN);
        } catch (AccessDeniedException e) {
            // 该端点会创建用户，必须限制为管理员：XWiki.createUser 自身不做任何权限校验
            throw new WebApplicationException("需要管理员权限才能导入外包用户",
                Response.Status.FORBIDDEN);
        }

        XWikiContext context = getXWikiContext();
        try {
            ExcelImportResult result = this.excelImportService.importFrom(body, context);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", result.getFailed() == 0
                ? OASyncResult.Status.SUCCESS.name() : OASyncResult.Status.PARTIAL_FAILURE.name());
            response.put("total", result.getTotal());
            response.put("created", result.getCreated());
            response.put("existed", result.getExisted());
            response.put("joinedGroup", result.getJoinedGroup());
            response.put("alreadyMember", result.getAlreadyMember());
            response.put("failed", result.getFailed());
            response.put("errors", result.getErrors());
            return Response.ok(OBJECT_MAPPER.writeValueAsString(response)).build();
        } catch (IOException e) {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("status", OASyncResult.Status.FAILURE.name());
            response.put("message", "Excel 文件无法解析: " + e.getMessage());
            return Response.status(Response.Status.BAD_REQUEST)
                .entity(OBJECT_MAPPER.writeValueAsString(response))
                .type(MediaType.APPLICATION_JSON).build();
        } finally {
            context.getWiki().getStore().cleanUp(context);
        }
    }
```

(d) 补充 `jakarta.ws.rs.WebApplicationException` 的 import：

```java
import jakarta.ws.rs.WebApplicationException;
```

- [ ] **Step 4: 运行测试确认通过**

Run: 同 Step 2
Expected: `Tests run: 1, Failures: 0, Errors: 0`

- [ ] **Step 5: 加上传体积上限（显式验收项，不允许省略）**

**为什么必须有**：`WorkbookFactory.create(InputStream)` 是 DOM 式加载，整个文件进内存，
内存占用随**文件大小**而非行数增长，超大文件可以把堆打爆。
800 行不是问题，但端点是管理员可反复调用的入口，必须有闸门。

实现要点（与现有 `catch (IOException)` 分支复用同一套"文件级失败"处理：

```java
    /**
     * 上传体积上限：10 MiB。800 行的 xlsx 通常远小于此，超限只可能是传错文件或恶意构造。
     */
    private static final long MAX_UPLOAD_BYTES = 10L * 1024 * 1024;
```

在读取 body 前先限制输入流，超限时按 §7.2 记 `FAILURE` 并返回 400（不要抛 500）。
用 `jakarta.ws.rs.core.StreamingOutput` 之外的最简做法：包一层计数输入流，
或在写文件前用 `body.readNBytes((int) MAX_UPLOAD_BYTES + 1)` 判断长度：

```java
        byte[] content = body.readNBytes((int) MAX_UPLOAD_BYTES + 1);
        if (content.length > MAX_UPLOAD_BYTES) {
            this.excelImportService.recordFailure("上传文件超过 " + (MAX_UPLOAD_BYTES / 1024 / 1024) + " MiB 上限", context);
            return Response.status(Response.Status.BAD_REQUEST)
                .entity("{\"status\":\"FAILURE\",\"message\":\"上传文件超过体积上限\"}")
                .type(MediaType.APPLICATION_JSON).build();
        }
        ExcelImportResult result = this.excelImportService.importFrom(new ByteArrayInputStream(content), context);
```

> 若 `OutsourcingExcelImportService` 届时没有公开的"只记失败"入口，
> 就给 `saveFailureRecord(String, XWikiContext)` 开一个包可见的 `recordFailure(...)` 方法仅供本端点使用，
> 不要为此新增公开 API 到 api 模块。

**验收**：构造一个大于 10 MiB 的文件上传，必须返回 **400**（不是 500），
且 `OASync.WebHome` 的最近同步记录里出现一条 `FAILURE` 记录。

- [ ] **Step 6: 提交**

```bash
git add xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-default/src/main/java/org/xwiki/oa/sync/internal/rest/OASyncRESTResource.java \
  xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-default/src/test/java/org/xwiki/oa/sync/internal/rest/OASyncRESTResourceTest.java
git commit -m "外包Excel导入：新增 REST 上传端点（管理员权限校验+原始字节流+体积上限）"
```

---

## Task 7: 组件注册与页面 UI

**Files:**
- Modify: `xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-default/src/main/resources/META-INF/components.txt`
- Modify: `xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-ui/src/main/resources/OASync/WebHome.xml`

- [ ] **Step 1: 核对组件注册齐全**

Task 2 已注册 `OutsourcingExcelParser`、Task 3 注册 `ExcelImportExecutor`、Task 5 注册
`OutsourcingExcelImportService`（注册由创建组件的任务负责，避免漏注册只在运行期暴露）。
这里只需核对三条都在 `components.txt` 里：

```bash
grep -n "internal.excel" \
  xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-default/src/main/resources/META-INF/components.txt
```

Expected: 恰好 3 行，分别对应解析器、执行器、编排服务。缺哪条就补哪条。

**仅靠 grep 不够**：现有单测都是用 `new` 构造组件（Task 3/5 的测试还 mock 掉了依赖），
所以 `components.txt` 里的**类名写错**或类不存在，要到运行期才会暴露。
补一个构建期就能失败的测试，放在
`xwiki-platform-oa-sync-default/src/test/java/org/xwiki/oa/sync/internal/excel/ExcelComponentsRegistrationTest.java`：

```java
package org.xwiki.oa.sync.internal.excel;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 校验 components.txt 里登记的 Excel 导入组件类真实存在且可加载。
 * 单测都是 new/mock 出来的，漏注册或类名写错只会在运行期暴露，这里用构建期断言兜住。
 */
class ExcelComponentsRegistrationTest
{
    private static final String PREFIX = "org.xwiki.oa.sync.internal.excel.";

    @Test
    void everyExcelComponentIsListedAndLoadable() throws Exception
    {
        List<String> declared = readComponents();
        List<String> expected = List.of(
            PREFIX + "OutsourcingExcelParser",
            PREFIX + "ExcelImportExecutor",
            PREFIX + "OutsourcingExcelImportService");
        for (String className : expected) {
            assertTrue(declared.contains(className), className + " 未登记进 components.txt");
            Class.forName(className);
        }
    }

    private List<String> readComponents() throws Exception
    {
        List<String> lines = new ArrayList<>();
        try (InputStream input = getClass().getClassLoader()
            .getResourceAsStream("META-INF/components.txt")) {
            assertTrue(input != null, "classpath 上找不到 META-INF/components.txt");
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    String trimmed = line.trim();
                    if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                        lines.add(trimmed);
                    }
                }
            }
        }
        return lines;
    }
}
```

Run:
```bash
mvn -s ~/.m2/settings-xwiki.xml -f pom.xml \
  -pl xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-api,\
xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-default \
  -Dtest=ExcelComponentsRegistrationTest -DfailIfNoTests=false \
  -Dxwiki.spoon.skip=true -Dlicense.skip=true -Dxwiki.checkstyle.skip=true \
  -o -Dmaven.repo.local=/tmp/dsh-m2repo -Duser.home=/tmp/dsh-home test
```
Expected: `Tests run: 1, Failures: 0, Errors: 0`（此时 Task 3/5 已注册全部三行）

- [ ] **Step 2: 页面增加上传区块**

`OASync/WebHome.xml` 是 XAR 页面 XML，`<content>` 内的 HTML 必须做 XML 转义。
在 `{{/html}}`（第 62 行，手动触发区块的结尾）之后、`== 最近同步记录 ==` 之前插入：

```text

== Excel 全量导入：科技外包 ==

按 `staff_id` 建号并全部加入「开发中心科技外包组」，只补齐 Excel 中存在的人，可重复执行。

{{html}}
&lt;p&gt;
&lt;input type=&quot;file&quot; id=&quot;oa-excel-file&quot; accept=&quot;.xlsx&quot; /&gt;
&lt;button class=&quot;btn btn-primary&quot; id=&quot;oa-excel-upload&quot;&gt;上传并导入&lt;/button&gt;
&lt;span id=&quot;oa-excel-status&quot; style=&quot;margin-left:8px&quot;&gt;&lt;/span&gt;
&lt;/p&gt;
&lt;script&gt;
(function () {
  var fileInput = document.getElementById(&#x27;oa-excel-file&#x27;);
  var button = document.getElementById(&#x27;oa-excel-upload&#x27;);
  var status = document.getElementById(&#x27;oa-excel-status&#x27;);
  button.addEventListener(&#x27;click&#x27;, function () {
    if (!fileInput.files || fileInput.files.length === 0) {
      new XWiki.widgets.Notification(&#x27;请先选择 .xlsx 文件&#x27;, &#x27;warning&#x27;);
      return;
    }
    button.disabled = true;
    status.textContent = &#x27;导入中，请勿关闭页面…&#x27;;
    fetch(&#x27;$request.contextPath/rest/oa-sync/outsourcing-excel&#x27;, {
      method: &#x27;POST&#x27;,
      headers: { &#x27;Content-Type&#x27;: &#x27;application/octet-stream&#x27; },
      body: fileInput.files[0]
    }).then(function (resp) {
      return resp.json().catch(function () { return {}; }).then(function (data) {
        if (resp.ok) {
          var level = data.status === &#x27;SUCCESS&#x27; ? &#x27;done&#x27; : &#x27;warning&#x27;;
          new XWiki.widgets.Notification(
            &#x27;导入完成：总数=&#x27; + data.total + &#x27; 新建=&#x27; + data.created
            + &#x27; 已存在=&#x27; + data.existed + &#x27; 入组=&#x27; + data.joinedGroup
            + &#x27; 已在组=&#x27; + data.alreadyMember + &#x27; 失败=&#x27; + data.failed, level);
        } else {
          new XWiki.widgets.Notification(&#x27;导入失败：&#x27; + (data.message || resp.status), &#x27;error&#x27;);
        }
      });
    }).catch(function (err) {
      new XWiki.widgets.Notification(&#x27;导入请求失败：&#x27; + err, &#x27;error&#x27;);
    }).finally(function () {
      button.disabled = false;
      status.textContent = &#x27;&#x27;;
    });
  });
})();
&lt;/script&gt;
{{/html}}
```

- [ ] **Step 3: 校验 XAR 页面 XML 可被打包**

Run:
```bash
mvn -s ~/.m2/settings-xwiki.xml -f pom.xml \
  -pl xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-api,\
xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-default,\
xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-ui \
  -DskipTests=true -Dxwiki.spoon.skip=true -Dlicense.skip=true -Dxwiki.checkstyle.skip=true install
```
Expected: `BUILD SUCCESS`，且 `xwiki-platform-oa-sync-ui/target/xwiki-platform-oa-sync-ui-18.1.0.xar` 被重新生成

- [ ] **Step 4: 提交**

```bash
git add xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-default/src/main/resources/META-INF/components.txt \
  xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-ui/src/main/resources/OASync/WebHome.xml
git commit -m "外包Excel导入：注册组件并在 OASync 页面增加上传入口"
```

---

## Task 8: 全量构建与端到端验证

**Files:** 无代码改动（验证任务）

- [ ] **Step 1: 跑通模块全部单测**

Run:
```bash
mvn -s ~/.m2/settings-xwiki.xml -f pom.xml \
  -pl xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-api,\
xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-default \
  -DfailIfNoTests=false -Dxwiki.spoon.skip=true -Dlicense.skip=true -Dxwiki.checkstyle.skip=true test
```
Expected: `BUILD SUCCESS`，无 `Failures` / `Errors`

- [ ] **Step 2: 构建并安装 api / default / ui**

Run:
```bash
mvn -s ~/.m2/settings-xwiki.xml -f pom.xml \
  -pl xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-api,\
xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-default,\
xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-ui \
  -am -DskipTests=true -Dxwiki.spoon.skip=true -Dlicense.skip=true -Dxwiki.checkstyle.skip=true install
```
Expected: `BUILD SUCCESS`

- [ ] **Step 3: 部署到本地实例**

```bash
cp xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-api/target/xwiki-platform-oa-sync-api-18.1.0.jar \
   xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-default/target/xwiki-platform-oa-sync-default-18.1.0.jar \
   /Users/noaharno/programer/software/xwiki/apache-tomcat-11.0.22/webapps/xwiki/WEB-INF/lib/
```

同时确认 `poi-ooxml-5.5.1.jar`（及 `poi-ooxml-lite`、`xmlbeans`）已在同一目录；
若缺失，从 `~/.m2/repository` 对应路径补拷。

然后重启 Tomcat：

```bash
/Users/noaharno/programer/software/xwiki/apache-tomcat-11.0.22/bin/shutdown.sh
/Users/noaharno/programer/software/xwiki/apache-tomcat-11.0.22/bin/startup.sh
```

- [ ] **Step 4: 用 curl 直接验证端点（不经过 UI）**

准备一个真实/样例 Excel（B 列是 `staff_id`，第 1 行表头）后执行：

```bash
curl -sS -u "Admin:admin" -X POST \
  -H "Content-Type: application/octet-stream" \
  --data-binary @/tmp/outsourcing.xlsx \
  http://localhost:8080/xwiki/rest/oa-sync/outsourcing-excel
```

Expected: JSON，形如
`{"status":"SUCCESS","total":N,"created":X,"existed":Y,"joinedGroup":Z,"alreadyMember":W,"failed":0,"errors":[]}`

同时**记录总耗时**（`curl -w '%{time_total}\n'`），供后续判断是否需要调线程数。

- [ ] **Step 5: 幂等复跑**

再执行一次 Step 4 的同一条命令。

Expected: `"created":0` 且 `"joinedGroup":0`，`"alreadyMember"` 等于去重后的工号数，`"status":"SUCCESS"`

- [ ] **Step 6: 校验组内成员数与抽样用户**

在 wiki 里打开 `XWiki.开发中心科技外包组`，确认 XWikiGroups 对象数量等于 Excel 去重后的工号数
（注意：若该组此前已有成员，则是「原有 + 新增」的总数）。

再抽样 3 个工号，确认：

- `http://localhost:8080/xwiki/bin/view/XWiki/<staffId>` 页面存在且是用户资料页
- 用户资料页的「组」标签里能看到「开发中心科技外包组」

- [ ] **Step 7: 验证非管理员被拒绝**

```bash
curl -sS -o /dev/null -w '%{http_code}\n' -u "someNonAdminUser:password" -X POST \
  -H "Content-Type: application/octet-stream" \
  --data-binary @/tmp/outsourcing.xlsx \
  http://localhost:8080/xwiki/rest/oa-sync/outsourcing-excel
```

Expected: `403`

- [ ] **Step 8: 验证非法文件返回 400**

```bash
curl -sS -o /dev/null -w '%{http_code}\n' -u "Admin:admin" -X POST \
  -H "Content-Type: application/octet-stream" \
  --data-binary "not-an-excel" \
  http://localhost:8080/xwiki/rest/oa-sync/outsourcing-excel
```

Expected: `400`

- [ ] **Step 9: 页面验证**

访问 `http://localhost:8080/xwiki/bin/view/OASync/WebHome`：

- 「Excel 全量导入：科技外包」区块存在，文件选择 + 按钮可用
- 上传后出现完成提示，且 `最近同步记录` 表格出现一条 `outsourcing-excel` 记录

- [ ] **Step 10: 记录实测结果**

把 Step 4 的耗时、Step 6 的成员数核对结果写进实施记录（或 PR 描述）。
若总耗时明显超过预期，回到 `xwiki.oa-sync.excel.threads` 调参（范围 1..8）后重测。

---

## 完成标准

- [ ] `DoapUserSynchronizerTest` 含「连续加 3 个用户，3 个都在组里」的回归测试并通过（bug 已修）
- [ ] 全部新增单测通过：`OutsourcingExcelParserTest`、`ExcelImportExecutorTest`、`OutsourcingExcelImportServiceTest`、`OASyncRESTResourceTest`
- [ ] 现有 MQ 相关单测全绿，`sync()` 对外行为未变
- [ ] 端到端：上传真实 Excel 成功建号并全部入组；重跑幂等；非管理员 403；非法文件 400
- [ ] 页面出现导入入口，且同步记录可见

### 关于「并发建号不重复创建同一用户」

设计文档 §10 列了这一项，实现上由两道保证覆盖，不需要额外的并发测试：

1. **解析期去重**：`OutsourcingExcelParser` 用 `LinkedHashSet` 去重，同一份 Excel 里不可能出现两个相同工号
   （Task 2 `deduplicatesRepeatedStaffIdAsWarning` 覆盖）
2. **建号幂等**：`ensureUser` 先判 `isNew()`，即使并发也不会重复建号
   （Task 4 `ensureUserReportsCreatedThenExisted` 覆盖）

因此并发场景下不存在两个任务处理同一工号的可能。
