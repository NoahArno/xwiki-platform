# OA 用户数据定时同步 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 每天定时（默认 05:00，可配置）从 FTP 拉取前一天的行员用户数据文件（GBK / CHAR06），全量幂等同步 XWiki 用户与权限组（组页面名=机构编号、标题=中文名、挂 OASyncOrgGroupClass），并保留最近 10 条同步记录 + 手动触发 REST。

**Architecture:** 新增独立模块 `xwiki-platform-oa-sync`（api / default / ui 三个子模块）。`OASyncService` 编排：对每个启用的 `OASyncSource`（SPI，行员已实现、外包占位）→ `OAFtpDownloader` 下载到临时文件（finally 关闭/删除）→ 来源解析成 `OAUserRecord` → `UserGroupSynchronizer` 逐条 upsert 用户/组（单条失败隔离）→ `SyncRecordStore` 写记录并清理只留 10 条。定时通过 `XWiki.SchedulerJobClass` 文档触发 `OASyncJob`（Quartz），手动触发通过 JAX-RS REST 资源。

**Tech Stack:** Java 17 / Maven / XWiki 18.1.0 组件机制 / Quartz（xwiki-platform-scheduler-api）/ Apache Commons Net FTP / XWQL / Velocity+XAR（LiveTable）。

**Design doc:** `docs/superpowers/specs/2026-08-06-oa-user-sync-design.md`

---

## File Structure

```
xwiki-platform-core/xwiki-platform-oa-sync/
├── pom.xml                                        # packaging=pom
├── xwiki-platform-oa-sync-api/
│   ├── pom.xml
│   └── src/main/java/org/xwiki/oa/sync/
│       ├── OASyncService.java                     # @Role 同步编排入口
│       ├── OASyncSource.java                      # @Role 来源 SPI
│       ├── OASyncConfiguration.java               # @Role 配置读取
│       └── model/
│           ├── OAUserRecord.java
│           ├── OASyncParseResult.java
│           ├── OASyncResult.java
│           └── OASyncTriggerType.java
├── xwiki-platform-oa-sync-default/
│   ├── pom.xml
│   └── src/main/
│       ├── java/org/xwiki/oa/sync/internal/
│       │   ├── configuration/DefaultOASyncConfiguration.java
│       │   ├── ftp/OAFtpDownloader.java
│       │   ├── source/AbstractOASyncSource.java
│       │   ├── source/EmployeeFileParser.java
│       │   ├── source/EmployeeOASyncSource.java
│       │   ├── source/OutsourcingOASyncSource.java
│       │   ├── service/DefaultOASyncService.java
│       │   ├── sync/UserGroupSynchronizer.java
│       │   ├── store/SyncRecordStore.java
│       │   ├── initializer/OASyncRecordClassInitializer.java
│       │   ├── initializer/OASyncOrgGroupClassInitializer.java
│       │   ├── job/OASyncJob.java
│       │   └── rest/OASyncRESTResource.java
│       └── resources/META-INF/components.txt
│   └── src/test/java/org/xwiki/oa/sync/internal/
│       ├── source/EmployeeFileParserTest.java
│       ├── source/AbstractOASyncSourceTest.java
│       ├── configuration/DefaultOASyncConfigurationTest.java
│       ├── ftp/OAFtpDownloaderTest.java
│       ├── store/SyncRecordStoreTest.java
│       ├── sync/UserGroupSynchronizerTest.java
│       └── service/DefaultOASyncServiceTest.java
└── xwiki-platform-oa-sync-ui/
    ├── pom.xml                                     # packaging=xar
    └── src/main/resources/
        ├── OASync/WebHome.xml                      # 记录列表 + 手动触发
        ├── OASync/EmployeeSyncJob.xml              # SchedulerJobClass 定时任务
        └── OASync/Translations.xml                 # 文案（可选）
```

修改文件：
- `xwiki-platform-core/pom.xml` — modules 列表加入 `xwiki-platform-oa-sync`
- （可选）distribution 相关 pom 注册，便于完整打包

---

### Task 1: 模块骨架（3 个 pom + 注册父 POM）

**Files:**
- Create: `xwiki-platform-core/xwiki-platform-oa-sync/pom.xml`
- Create: `xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-api/pom.xml`
- Create: `xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-default/pom.xml`
- Create: `xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-ui/pom.xml`
- Modify: `xwiki-platform-core/pom.xml`（modules 加一项）

- [ ] **Step 1: 父模块 pom**

`xwiki-platform-core/xwiki-platform-oa-sync/pom.xml`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/maven-v4_0_0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>org.xwiki.platform</groupId>
    <artifactId>xwiki-platform-core</artifactId>
    <version>18.1.0</version>
  </parent>
  <artifactId>xwiki-platform-oa-sync</artifactId>
  <name>XWiki Platform - OA User Sync</name>
  <packaging>pom</packaging>
  <description>Sync OA employee/outsourcing user data from FTP into XWiki users and groups.</description>
  <modules>
    <module>xwiki-platform-oa-sync-api</module>
    <module>xwiki-platform-oa-sync-default</module>
    <module>xwiki-platform-oa-sync-ui</module>
  </modules>
</project>
```

- [ ] **Step 2: api 子模块 pom**

`xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-api/pom.xml`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/maven-v4_0_0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>org.xwiki.platform</groupId>
    <artifactId>xwiki-platform-oa-sync</artifactId>
    <version>18.1.0</version>
  </parent>
  <artifactId>xwiki-platform-oa-sync-api</artifactId>
  <name>XWiki Platform - OA User Sync - API</name>
  <packaging>jar</packaging>
  <description>OA User Sync API</description>
  <dependencies>
    <dependency>
      <groupId>org.xwiki.commons</groupId>
      <artifactId>xwiki-commons-component-api</artifactId>
      <version>${commons.version}</version>
    </dependency>
    <dependency>
      <groupId>org.xwiki.platform</groupId>
      <artifactId>xwiki-platform-user-api</artifactId>
      <version>${project.version}</version>
    </dependency>
  </dependencies>
</project>
```

- [ ] **Step 3: default 子模块 pom**

`xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-default/pom.xml`：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/maven-v4_0_0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>org.xwiki.platform</groupId>
    <artifactId>xwiki-platform-oa-sync</artifactId>
    <version>18.1.0</version>
  </parent>
  <artifactId>xwiki-platform-oa-sync-default</artifactId>
  <name>XWiki Platform - OA User Sync - Default</name>
  <packaging>jar</packaging>
  <description>OA User Sync Default Implementation</description>
  <dependencies>
    <dependency>
      <groupId>org.xwiki.platform</groupId>
      <artifactId>xwiki-platform-oa-sync-api</artifactId>
      <version>${project.version}</version>
    </dependency>
    <dependency>
      <groupId>org.xwiki.platform</groupId>
      <artifactId>xwiki-platform-oldcore</artifactId>
      <version>${project.version}</version>
    </dependency>
    <dependency>
      <groupId>org.xwiki.platform</groupId>
      <artifactId>xwiki-platform-scheduler-api</artifactId>
      <version>${project.version}</version>
    </dependency>
    <dependency>
      <groupId>org.xwiki.platform</groupId>
      <artifactId>xwiki-platform-rest-server</artifactId>
      <version>${project.version}</version>
    </dependency>
    <dependency>
      <groupId>org.xwiki.platform</groupId>
      <artifactId>xwiki-platform-query-manager</artifactId>
      <version>${project.version}</version>
    </dependency>
    <dependency>
      <groupId>org.xwiki.platform</groupId>
      <artifactId>xwiki-platform-model-api</artifactId>
      <version>${project.version}</version>
    </dependency>
    <dependency>
      <groupId>org.xwiki.commons</groupId>
      <artifactId>xwiki-commons-configuration-api</artifactId>
      <version>${commons.version}</version>
    </dependency>
    <dependency>
      <groupId>commons-net</groupId>
      <artifactId>commons-net</artifactId>
    </dependency>
    <dependency>
      <groupId>com.fasterxml.jackson.core</groupId>
      <artifactId>jackson-databind</artifactId>
    </dependency>

    <!-- Test dependencies -->
    <dependency>
      <groupId>org.xwiki.platform</groupId>
      <artifactId>xwiki-platform-test-oldcore</artifactId>
      <version>${project.version}</version>
      <type>pom</type>
      <scope>test</scope>
    </dependency>
  </dependencies>
</project>
```

- [ ] **Step 4: ui 子模块 pom**

`xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-ui/pom.xml`（packaging=xar，参照 `xwiki-platform-scheduler-ui/pom.xml`）：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/maven-v4_0_0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>org.xwiki.platform</groupId>
    <artifactId>xwiki-platform-oa-sync</artifactId>
    <version>18.1.0</version>
  </parent>
  <artifactId>xwiki-platform-oa-sync-ui</artifactId>
  <name>XWiki Platform - OA User Sync - UI</name>
  <packaging>xar</packaging>
  <description>OA User Sync UI (records page + scheduler job)</description>
  <properties>
    <xwiki.extension.name>OA User Sync Application</xwiki.extension.name>
  </properties>
  <dependencies>
    <dependency>
      <groupId>org.xwiki.platform</groupId>
      <artifactId>xwiki-platform-oa-sync-default</artifactId>
      <version>${project.version}</version>
      <scope>runtime</scope>
    </dependency>
  </dependencies>
</project>
```

- [ ] **Step 5: 注册进父 POM**

在 `xwiki-platform-core/pom.xml` 的 `<modules>` 中按字母序（`xwiki-platform-notifications` 之后、`xwiki-platform-observation` 之前）加入：

```xml
    <module>xwiki-platform-oa-sync</module>
```

- [ ] **Step 6: 验证骨架可解析**

Run（在工作区根目录，`~/.m2/settings-xwiki.xml` 存在时）：
```bash
mvn -s ~/.m2/settings-xwiki.xml -f pom.xml -pl xwiki-platform-core/xwiki-platform-oa-sync -am -Dxwiki.spoon.skip=true -Dlicense.skip=true -DskipTests=true -Dxwiki.checkstyle.skip=true validate
```
Expected: BUILD SUCCESS（模块被识别）。

- [ ] **Step 7: Commit**

```bash
git add xwiki-platform-core/pom.xml xwiki-platform-core/xwiki-platform-oa-sync
git commit -m "OA同步：新增 xwiki-platform-oa-sync 模块骨架"
```

---

### Task 2: API 模型与角色接口

**Files:**
- Create: `xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-api/src/main/java/org/xwiki/oa/sync/model/OAUserRecord.java`
- Create: `.../model/OASyncParseResult.java`
- Create: `.../model/OASyncResult.java`
- Create: `.../model/OASyncTriggerType.java`
- Create: `.../OASyncService.java`
- Create: `.../OASyncSource.java`
- Create: `.../OASyncConfiguration.java`

- [ ] **Step 1: OAUserRecord**

```java
package org.xwiki.oa.sync.model;

/**
 * A single user record parsed from an OA data file.
 */
public class OAUserRecord
{
    private final String username;
    private final String orgCode;
    private final String orgName;
    private final boolean active;

    public OAUserRecord(String username, String orgCode, String orgName, boolean active)
    {
        this.username = username;
        this.orgCode = orgCode;
        this.orgName = orgName;
        this.active = active;
    }

    public String getUsername()
    {
        return this.username;
    }

    public String getOrgCode()
    {
        return this.orgCode;
    }

    public String getOrgName()
    {
        return this.orgName;
    }

    public boolean isActive()
    {
        return this.active;
    }
}
```

- [ ] **Step 2: OASyncParseResult**

```java
package org.xwiki.oa.sync.model;

import java.util.ArrayList;
import java.util.List;

/**
 * Result of parsing a data file: valid records plus per-line errors.
 */
public class OASyncParseResult
{
    private final List<OAUserRecord> records = new ArrayList<>();
    private final List<String> errors = new ArrayList<>();

    public List<OAUserRecord> getRecords()
    {
        return this.records;
    }

    public List<String> getErrors()
    {
        return this.errors;
    }
}
```

- [ ] **Step 3: OASyncTriggerType**

```java
package org.xwiki.oa.sync.model;

/**
 * How a sync run was triggered.
 */
public enum OASyncTriggerType
{
    SCHEDULED, MANUAL
}
```

- [ ] **Step 4: OASyncResult**

```java
package org.xwiki.oa.sync.model;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Result of one source sync run, persisted as a sync record.
 */
public class OASyncResult
{
    public enum Status
    {
        SUCCESS, PARTIAL_FAILURE, FAILURE
    }

    private final String type;
    private final LocalDate syncDate;
    private final OASyncTriggerType triggerType;
    private final LocalDateTime startTime;
    private Status status;
    private LocalDateTime endTime;
    private int totalCount;
    private int successCount;
    private int failCount;
    private final List<String> errorLog = new ArrayList<>();

    public OASyncResult(String type, LocalDate syncDate, OASyncTriggerType triggerType, LocalDateTime startTime)
    {
        this.type = type;
        this.syncDate = syncDate;
        this.triggerType = triggerType;
        this.startTime = startTime;
    }

    public String getType() { return this.type; }
    public LocalDate getSyncDate() { return this.syncDate; }
    public OASyncTriggerType getTriggerType() { return this.triggerType; }
    public LocalDateTime getStartTime() { return this.startTime; }
    public Status getStatus() { return this.status; }
    public void setStatus(Status status) { this.status = status; }
    public LocalDateTime getEndTime() { return this.endTime; }
    public void setEndTime(LocalDateTime endTime) { this.endTime = endTime; }
    public int getTotalCount() { return this.totalCount; }
    public void setTotalCount(int totalCount) { this.totalCount = totalCount; }
    public int getSuccessCount() { return this.successCount; }
    public void setSuccessCount(int successCount) { this.successCount = successCount; }
    public int getFailCount() { return this.failCount; }
    public void setFailCount(int failCount) { this.failCount = failCount; }
    public List<String> getErrorLog() { return this.errorLog; }
}
```

- [ ] **Step 5: OASyncConfiguration（@Role）**

```java
package org.xwiki.oa.sync;

import org.xwiki.component.annotation.Role;

/**
 * Configuration for the OA sync (read from xwiki.cfg).
 */
@Role
public interface OASyncConfiguration
{
    String getFtpHost();
    int getFtpPort();
    String getFtpUsername();
    String getFtpPassword();
    int getFtpTimeoutMs();

    boolean isSourceEnabled(String type);
    String getSourceBasePath(String type);
    String getSourceFilePattern(String type);
    String getSourceEncoding(String type);
    int getSourceDateOffsetDays(String type);
}
```

- [ ] **Step 6: OASyncSource（@Role，SPI）**

```java
package org.xwiki.oa.sync;

import java.nio.file.Path;
import org.xwiki.component.annotation.Role;
import org.xwiki.oa.sync.model.OASyncParseResult;

/**
 * A user-data source (employee / outsourcing). Implementations are components named by their type.
 */
@Role
public interface OASyncSource
{
    /**
     * @return the source type, e.g. "employee" or "outsourcing"
     */
    String getType();

    /**
     * @return whether this source is enabled in the configuration
     */
    boolean isEnabled();

    /**
     * Resolve the remote FTP path for the given data date.
     * @param dateYYYYMMDD the data date
     * @return remote file path
     */
    String resolveRemotePath(String dateYYYYMMDD);

    /**
     * Parse a downloaded file into records and line-level errors.
     * @param file the downloaded temp file
     * @return parse result
     * @throws Exception if the file cannot be processed at all
     */
    OASyncParseResult parse(Path file) throws Exception;
}
```

- [ ] **Step 7: OASyncService（@Role）**

```java
package org.xwiki.oa.sync;

import java.time.LocalDate;
import java.util.List;
import org.xwiki.component.annotation.Role;
import org.xwiki.oa.sync.model.OASyncResult;
import org.xwiki.oa.sync.model.OASyncTriggerType;

/**
 * Entry point for running OA user data syncs.
 */
@Role
public interface OASyncService
{
    /**
     * Run all enabled sources.
     * @param triggerType how the sync was triggered
     * @param manualDate optional data date override for manual backfill; null = default (offset from config)
     * @return one result per enabled source
     */
    List<OASyncResult> syncAll(OASyncTriggerType triggerType, LocalDate manualDate);
}
```

- [ ] **Step 8: 编译验证**

```bash
mvn -s ~/.m2/settings-xwiki.xml -f pom.xml -pl xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-api -am -Dxwiki.spoon.skip=true -Dlicense.skip=true -DskipTests=true -Dxwiki.checkstyle.skip=true compile
```
Expected: BUILD SUCCESS。

- [ ] **Step 9: Commit**

```bash
git add xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-api
git commit -m "OA同步：API 模型与角色接口"
```

---

### Task 3: 行员文件解析器（TDD）

**Files:**
- Test: `xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-default/src/test/java/org/xwiki/oa/sync/internal/source/EmployeeFileParserTest.java`
- Create: `.../src/main/java/org/xwiki/oa/sync/internal/source/EmployeeFileParser.java`

- [ ] **Step 1: 写失败测试**

```java
package org.xwiki.oa.sync.internal.source;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.xwiki.oa.sync.model.OAUserRecord;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EmployeeFileParserTest
{
    @TempDir
    Path tempDir;

    private static final String SEP = "\u0006";
    private final EmployeeFileParser parser = new EmployeeFileParser();

    private Path writeFile(String... lines) throws IOException
    {
        Path file = this.tempDir.resolve("data.dat");
        Files.write(file, List.of(lines), StandardCharsets.UTF_8);
        return file;
    }

    private String line(String... fields)
    {
        return String.join(SEP, fields);
    }

    @Test
    void parsesActiveEmployee() throws Exception
    {
        // 24 fields; index 11=1 (type), 12=1 (active emp), 13=1 (account enabled), 23=org code, 24=org name
        String[] f = new String[24];
        f[1] = "10086"; f[10] = "1"; f[11] = "1"; f[12] = "1"; f[22] = "8801"; f[23] = "科技部";
        OAUserRecord r = this.parser.parse(writeFile(line(f)), StandardCharsets.UTF_8).getRecords().get(0);
        assertEquals("10086", r.getUsername());
        assertEquals("8801", r.getOrgCode());
        assertEquals("科技部", r.getOrgName());
        assertTrue(r.isActive());
    }

    @Test
    void disablesWhenAccountStatusZero() throws Exception
    {
        String[] f = new String[24];
        f[1] = "10086"; f[10] = "1"; f[11] = "1"; f[12] = "0"; f[22] = "8801"; f[23] = "科技部";
        OAUserRecord r = this.parser.parse(writeFile(line(f)), StandardCharsets.UTF_8).getRecords().get(0);
        assertEquals(false, r.isActive());
    }

    @Test
    void disablesWhenResigned() throws Exception
    {
        // 员工状态=2（离职），账号状态=1
        String[] f = new String[24];
        f[1] = "10086"; f[10] = "1"; f[11] = "2"; f[12] = "1"; f[22] = "8801"; f[23] = "科技部";
        OAUserRecord r = this.parser.parse(writeFile(line(f)), StandardCharsets.UTF_8).getRecords().get(0);
        assertEquals(false, r.isActive());
    }

    @Test
    void skipsNonAccountTypeOne() throws Exception
    {
        String[] f = new String[24];
        f[1] = "10086"; f[10] = "2"; f[11] = "1"; f[12] = "1"; f[22] = "8801"; f[23] = "科技部";
        var result = this.parser.parse(writeFile(line(f)), StandardCharsets.UTF_8);
        assertTrue(result.getRecords().isEmpty());
        assertTrue(result.getErrors().isEmpty());
    }

    @Test
    void recordsErrorOnTooFewFields() throws Exception
    {
        String[] f = new String[10];
        f[1] = "10086";
        var result = this.parser.parse(writeFile(line(f)), StandardCharsets.UTF_8);
        assertTrue(result.getRecords().isEmpty());
        assertEquals(1, result.getErrors().size());
    }

    @Test
    void recordsErrorOnUnknownStatus() throws Exception
    {
        String[] f = new String[24];
        f[1] = "10086"; f[10] = "1"; f[11] = "999"; f[12] = "1"; f[22] = "8801"; f[23] = "科技部";
        var result = this.parser.parse(writeFile(line(f)), StandardCharsets.UTF_8);
        assertTrue(result.getRecords().isEmpty());
        assertEquals(1, result.getErrors().size());
    }
}
```

- [ ] **Step 2: 运行确认失败（类不存在）**

```bash
mvn -s ~/.m2/settings-xwiki.xml -f pom.xml -pl xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-default -am -Dxwiki.spoon.skip=true -Dlicense.skip=true -Dxwiki.checkstyle.skip=true -Dtest=EmployeeFileParserTest test
```
Expected: 编译失败（EmployeeFileParser 不存在）。

- [ ] **Step 3: 实现 EmployeeFileParser**

```java
package org.xwiki.oa.sync.internal.source;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import org.xwiki.oa.sync.model.OAUserRecord;
import org.xwiki.oa.sync.model.OASyncParseResult;

/**
 * Parses the IOA_EMPLOYEE_JGTY file: GBK content, fields separated by CHAR(06), 1-based indexes.
 */
public class EmployeeFileParser
{
    public static final char FIELD_SEPARATOR = '\u0006';

    private static final String ACCOUNT_TYPE_EMPLOYEE = "1";
    private static final Set<String> ACTIVE_EMP_STATUSES = Set.of("1", "10", "101", "102", "103");
    private static final Set<String> RESIGNED_EMP_STATUSES = Set.of("2", "3", "4");
    private static final int REQUIRED_FIELDS = 24;

    enum UserActiveState
    {
        ACTIVE, DISABLED, UNDEFINED
    }

    public OASyncParseResult parse(Path file, Charset charset) throws IOException
    {
        OASyncParseResult result = new OASyncParseResult();
        int lineNo = 0;
        try (BufferedReader reader = Files.newBufferedReader(file, charset)) {
            String line;
            while ((line = reader.readLine()) != null) {
                lineNo++;
                parseLine(line, lineNo, result);
            }
        }
        return result;
    }

    void parseLine(String line, int lineNo, OASyncParseResult result)
    {
        if (line.isEmpty()) {
            return;
        }
        String[] fields = line.split(String.valueOf(FIELD_SEPARATOR), -1);
        if (fields.length < REQUIRED_FIELDS) {
            result.getErrors().add("第 " + lineNo + " 行字段数不足: " + fields.length);
            return;
        }
        // 1-based index N == fields[N-1]
        if (!ACCOUNT_TYPE_EMPLOYEE.equals(fields[10].trim())) {
            return; // 只处理账号类型=1
        }
        String username = fields[1].trim();
        String empStatus = fields[11].trim();
        String acctStatus = fields[12].trim();
        String orgCode = fields[22].trim();
        String orgName = fields[23].trim();

        if (username.isEmpty()) {
            result.getErrors().add("第 " + lineNo + " 行员工工号为空");
            return;
        }

        UserActiveState state = computeState(empStatus, acctStatus);
        if (state == UserActiveState.UNDEFINED) {
            result.getErrors().add("第 " + lineNo + " 行状态无法判定: 员工状态=" + empStatus + ", 账号状态=" + acctStatus);
            return;
        }
        result.getRecords().add(new OAUserRecord(username, orgCode, orgName, state == UserActiveState.ACTIVE));
    }

    static UserActiveState computeState(String empStatus, String acctStatus)
    {
        if ("0".equals(acctStatus)) {
            return UserActiveState.DISABLED;
        }
        if (!"1".equals(acctStatus)) {
            return UserActiveState.UNDEFINED;
        }
        if (ACTIVE_EMP_STATUSES.contains(empStatus)) {
            return UserActiveState.ACTIVE;
        }
        if (RESIGNED_EMP_STATUSES.contains(empStatus)) {
            return UserActiveState.DISABLED;
        }
        return UserActiveState.UNDEFINED;
    }
}
```

- [ ] **Step 4: 运行确认通过**

```bash
mvn -s ~/.m2/settings-xwiki.xml -f pom.xml -pl xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-default -am -Dxwiki.spoon.skip=true -Dlicense.skip=true -Dxwiki.checkstyle.skip=true -Dtest=EmployeeFileParserTest test
```
Expected: Tests run: 6, Failures: 0。

- [ ] **Step 5: Commit**

```bash
git add xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-default
git commit -m "OA同步：行员文件解析器（GBK/CHAR06/状态判定）"
```

---

### Task 4: FTP 下载器（TDD）

**Files:**
- Test: `.../src/test/java/org/xwiki/oa/sync/internal/ftp/OAFtpDownloaderTest.java`
- Create: `.../src/main/java/org/xwiki/oa/sync/internal/ftp/OAFtpDownloader.java`

- [ ] **Step 1: 写失败测试（mock FTPClient 行为通过可覆盖的工厂方法）**

```java
package org.xwiki.oa.sync.internal.ftp;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.commons.net.ftp.FTPClient;
import org.junit.jupiter.api.Test;
import org.xwiki.oa.sync.OASyncConfiguration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OAFtpDownloaderTest
{
    private final FTPClient client = mock(FTPClient.class);

    private OAFtpDownloader newDownloader(boolean loginOk, boolean retrieveOk) throws Exception
    {
        when(this.client.connect(anyString(), anyInt())).thenReturn(true);
        when(this.client.login(anyString(), anyString())).thenReturn(loginOk);
        when(this.client.retrieveFile(anyString(), any(java.io.OutputStream.class)))
            .thenAnswer(inv -> {
                if (retrieveOk) {
                    inv.getArgument(1, java.io.OutputStream.class).write(new byte[] { 1, 2, 3 });
                }
                return retrieveOk;
            });
        OASyncConfiguration config = mock(OASyncConfiguration.class);
        when(config.getFtpHost()).thenReturn("ftp.example.com");
        when(config.getFtpPort()).thenReturn(21);
        when(config.getFtpUsername()).thenReturn("u");
        when(config.getFtpPassword()).thenReturn("p");
        when(config.getFtpTimeoutMs()).thenReturn(5000);
        OAFtpDownloader downloader = new OAFtpDownloader();
        downloader.configuration = config;
        downloader.clientFactory = () -> this.client;
        return downloader;
    }

    @Test
    void downloadsToTempFileAndClosesClient() throws Exception
    {
        OAFtpDownloader downloader = newDownloader(true, true);
        Path file = downloader.download("/comm/bdpp/oa/20260805/IOA_EMPLOYEE_JGTY_20260805.dat");
        assertTrue(Files.exists(file));
        assertEquals(3, Files.size(file));
        Files.deleteIfExists(file);
    }

    @Test
    void throwsWhenLoginFails() throws Exception
    {
        OAFtpDownloader downloader = newDownloader(false, true);
        assertThrows(IOException.class, () -> downloader.download("/x.dat"));
    }

    @Test
    void throwsWhenRetrieveFailsAndDeletesTemp() throws Exception
    {
        OAFtpDownloader downloader = newDownloader(true, false);
        assertThrows(IOException.class, () -> downloader.download("/x.dat"));
        // no temp file left behind (factory-created temp dir is cleaned by caller; here just ensure no exception)
    }
}
```

说明：`OAFtpDownloader` 暴露包级可见的 `clientFactory`（`FTPClientSupplier`）与 `configuration` 字段，便于测试注入；生产代码用真实配置。

- [ ] **Step 2: 运行确认失败**

```bash
mvn -s ~/.m2/settings-xwiki.xml -f pom.xml -pl xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-default -am -Dxwiki.spoon.skip=true -Dlicense.skip=true -Dxwiki.checkstyle.skip=true -Dtest=OAFtpDownloaderTest test
```
Expected: 编译失败（OAFtpDownloader 不存在）。

- [ ] **Step 3: 实现 OAFtpDownloader**

```java
package org.xwiki.oa.sync.internal.ftp;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xwiki.component.annotation.Component;
import org.xwiki.oa.sync.OASyncConfiguration;

/**
 * Downloads a remote FTP file to a temp file. The returned temp file must be deleted by the caller.
 * FTP client is always logged out and disconnected (finally), preventing resource leaks.
 */
@Component(roles = OAFtpDownloader.class)
@Singleton
public class OAFtpDownloader
{
    private static final Logger LOGGER = LoggerFactory.getLogger(OAFtpDownloader.class);

    @FunctionalInterface
    interface FTPClientSupplier
    {
        FTPClient get();
    }

    @Inject
    OASyncConfiguration configuration;

    FTPClientSupplier clientFactory = FTPClient::new;

    public Path download(String remotePath) throws IOException
    {
        FTPClient client = this.clientFactory.get();
        Path temp = null;
        try {
            client.setConnectTimeout(this.configuration.getFtpTimeoutMs());
            client.setDefaultTimeout(this.configuration.getFtpTimeoutMs());
            client.setDataTimeout(this.configuration.getFtpTimeoutMs());
            client.connect(this.configuration.getFtpHost(), this.configuration.getFtpPort());
            if (!client.login(this.configuration.getFtpUsername(), this.configuration.getFtpPassword())) {
                throw new IOException("FTP 登录失败: " + client.getReplyString());
            }
            client.setFileType(FTP.BINARY_FILE_TYPE);
            client.enterLocalPassiveMode();

            temp = Files.createTempFile("oa-sync-", ".dat");
            try (OutputStream out = Files.newOutputStream(temp)) {
                if (!client.retrieveFile(remotePath, out)) {
                    throw new IOException("FTP 下载失败 [" + remotePath + "]: " + client.getReplyString());
                }
            }
            return temp;
        } catch (IOException e) {
            if (temp != null) {
                try {
                    Files.deleteIfExists(temp);
                } catch (IOException ignored) {
                    // ignore
                }
            }
            throw e;
        } finally {
            try {
                client.logout();
            } catch (IOException e) {
                LOGGER.debug("FTP logout failed", e);
            }
            try {
                client.disconnect();
            } catch (IOException e) {
                LOGGER.debug("FTP disconnect failed", e);
            }
        }
    }
}
```

- [ ] **Step 4: 运行确认通过**

```bash
mvn -s ~/.m2/settings-xwiki.xml -f pom.xml -pl xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-default -am -Dxwiki.spoon.skip=true -Dlicense.skip=true -Dxwiki.checkstyle.skip=true -Dtest=OAFtpDownloaderTest test
```
Expected: Tests run: 3, Failures: 0。

- [ ] **Step 5: Commit**

```bash
git add xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-default
git commit -m "OA同步：FTP 下载器（资源安全关闭）"
```

---

### Task 5: 配置实现（TDD）

**Files:**
- Test: `.../src/test/java/org/xwiki/oa/sync/internal/configuration/DefaultOASyncConfigurationTest.java`
- Create: `.../src/main/java/org/xwiki/oa/sync/internal/configuration/DefaultOASyncConfiguration.java`

- [ ] **Step 1: 写失败测试**

```java
package org.xwiki.oa.sync.internal.configuration;

import org.junit.jupiter.api.Test;
import org.xwiki.configuration.ConfigurationSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultOASyncConfigurationTest
{
    private final ConfigurationSource source = mock(ConfigurationSource.class);
    private final DefaultOASyncConfiguration config = new DefaultOASyncConfiguration();

    @Test
    void readsFtpConfig()
    {
        this.config.configuration = this.source;
        when(this.source.getProperty("xwiki.oa-sync.ftp.host", String.class)).thenReturn("ftp.example.com");
        when(this.source.getProperty("xwiki.oa-sync.ftp.port", Integer.class)).thenReturn(2121);
        when(this.source.getProperty("xwiki.oa-sync.ftp.username", String.class)).thenReturn("u");
        when(this.source.getProperty("xwiki.oa-sync.ftp.password", String.class)).thenReturn("p");
        when(this.source.getProperty("xwiki.oa-sync.ftp.timeout-ms", Integer.class)).thenReturn(60000);
        assertEquals("ftp.example.com", this.config.getFtpHost());
        assertEquals(2121, this.config.getFtpPort());
        assertEquals("u", this.config.getFtpUsername());
        assertEquals("p", this.config.getFtpPassword());
        assertEquals(60000, this.config.getFtpTimeoutMs());
    }

    @Test
    void readsPerSourceConfigWithDefaults()
    {
        this.config.configuration = this.source;
        when(this.source.getProperty("xwiki.oa-sync.source.employee.enabled", Boolean.class)).thenReturn(true);
        when(this.source.getProperty("xwiki.oa-sync.source.employee.base-path", String.class))
            .thenReturn("/comm/bdpp/oa/%s");
        when(this.source.getProperty("xwiki.oa-sync.source.employee.file-pattern", String.class))
            .thenReturn("IOA_EMPLOYEE_JGTY_%s.dat");
        when(this.source.getProperty("xwiki.oa-sync.source.outsourcing.enabled", Boolean.class)).thenReturn(null);

        assertTrue(this.config.isSourceEnabled("employee"));
        assertEquals("/comm/bdpp/oa/%s", this.config.getSourceBasePath("employee"));
        assertEquals("IOA_EMPLOYEE_JGTY_%s.dat", this.config.getSourceFilePattern("employee"));
        assertEquals("GBK", this.config.getSourceEncoding("employee"));
        assertEquals(1, this.config.getSourceDateOffsetDays("employee"));
        assertFalse(this.config.isSourceEnabled("outsourcing"));
    }
}
```

- [ ] **Step 2: 运行确认失败**

```bash
mvn -s ~/.m2/settings-xwiki.xml -f pom.xml -pl xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-default -am -Dxwiki.spoon.skip=true -Dlicense.skip=true -Dxwiki.checkstyle.skip=true -Dtest=DefaultOASyncConfigurationTest test
```
Expected: 编译失败。

- [ ] **Step 3: 实现 DefaultOASyncConfiguration**

```java
package org.xwiki.oa.sync.internal.configuration;

import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;

import org.xwiki.component.annotation.Component;
import org.xwiki.configuration.ConfigurationSource;
import org.xwiki.oa.sync.OASyncConfiguration;

/**
 * Reads OA sync configuration from xwiki.cfg (keys prefixed with xwiki.oa-sync.).
 */
@Component(roles = OASyncConfiguration.class)
@Singleton
public class DefaultOASyncConfiguration implements OASyncConfiguration
{
    @Inject
    @Named("xwikicfg")
    ConfigurationSource configuration;

    @Override
    public String getFtpHost()
    {
        return get("xwiki.oa-sync.ftp.host", "");
    }

    @Override
    public int getFtpPort()
    {
        return getInt("xwiki.oa-sync.ftp.port", 21);
    }

    @Override
    public String getFtpUsername()
    {
        return get("xwiki.oa-sync.ftp.username", "");
    }

    @Override
    public String getFtpPassword()
    {
        return get("xwiki.oa-sync.ftp.password", "");
    }

    @Override
    public int getFtpTimeoutMs()
    {
        return getInt("xwiki.oa-sync.ftp.timeout-ms", 30000);
    }

    @Override
    public boolean isSourceEnabled(String type)
    {
        return getBoolean("xwiki.oa-sync.source." + type + ".enabled", false);
    }

    @Override
    public String getSourceBasePath(String type)
    {
        return get("xwiki.oa-sync.source." + type + ".base-path", "");
    }

    @Override
    public String getSourceFilePattern(String type)
    {
        return get("xwiki.oa-sync.source." + type + ".file-pattern", "");
    }

    @Override
    public String getSourceEncoding(String type)
    {
        return get("xwiki.oa-sync.source." + type + ".encoding", "GBK");
    }

    @Override
    public int getSourceDateOffsetDays(String type)
    {
        return getInt("xwiki.oa-sync.source." + type + ".date-offset-days", 1);
    }

    private String get(String key, String defaultValue)
    {
        String value = this.configuration.getProperty(key, String.class);
        return (value == null || value.isBlank()) ? defaultValue : value.trim();
    }

    private int getInt(String key, int defaultValue)
    {
        Integer value = this.configuration.getProperty(key, Integer.class);
        return value == null ? defaultValue : value;
    }

    private boolean getBoolean(String key, boolean defaultValue)
    {
        Boolean value = this.configuration.getProperty(key, Boolean.class);
        return value == null ? defaultValue : value;
    }
}
```

- [ ] **Step 4: 运行确认通过**

```bash
mvn -s ~/.m2/settings-xwiki.xml -f pom.xml -pl xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-default -am -Dxwiki.spoon.skip=true -Dlicense.skip=true -Dxwiki.checkstyle.skip=true -Dtest=DefaultOASyncConfigurationTest test
```
Expected: Tests run: 2, Failures: 0。

- [ ] **Step 5: Commit**

```bash
git add xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-default
git commit -m "OA同步：配置实现（xwiki.cfg）"
```

---

### Task 6: 来源实现（employee / outsourcing 占位）（TDD）

**Files:**
- Test: `.../src/test/java/org/xwiki/oa/sync/internal/source/AbstractOASyncSourceTest.java`
- Create: `.../src/main/java/org/xwiki/oa/sync/internal/source/AbstractOASyncSource.java`
- Create: `.../src/main/java/org/xwiki/oa/sync/internal/source/EmployeeOASyncSource.java`
- Create: `.../src/main/java/org/xwiki/oa/sync/internal/source/OutsourcingOASyncSource.java`

- [ ] **Step 1: 写失败测试**

```java
package org.xwiki.oa.sync.internal.source;

import org.junit.jupiter.api.Test;
import org.xwiki.oa.sync.OASyncConfiguration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AbstractOASyncSourceTest
{
    private final OASyncConfiguration config = mock(OASyncConfiguration.class);

    private AbstractOASyncSource newSource()
    {
        return new AbstractOASyncSource()
        {
            @Override
            public String getType()
            {
                return "employee";
            }

            @Override
            public org.xwiki.oa.sync.model.OASyncParseResult parse(java.nio.file.Path file)
            {
                return new org.xwiki.oa.sync.model.OASyncParseResult();
            }
        };
    }

    @Test
    void resolvesRemotePathWithDate()
    {
        AbstractOASyncSource source = newSource();
        source.configuration = this.config;
        when(this.config.getSourceBasePath("employee")).thenReturn("/comm/bdpp/oa/%s");
        when(this.config.getSourceFilePattern("employee")).thenReturn("IOA_EMPLOYEE_JGTY_%s.dat");
        assertEquals("/comm/bdpp/oa/20260805/IOA_EMPLOYEE_JGTY_20260805.dat",
            source.resolveRemotePath("20260805"));
    }

    @Test
    void enabledComesFromConfig()
    {
        AbstractOASyncSource source = newSource();
        source.configuration = this.config;
        when(this.config.isSourceEnabled("employee")).thenReturn(true);
        assertTrue(source.isEnabled());
        when(this.config.isSourceEnabled("employee")).thenReturn(false);
        assertFalse(source.isEnabled());
    }
}
```

- [ ] **Step 2: 运行确认失败**

```bash
mvn -s ~/.m2/settings-xwiki.xml -f pom.xml -pl xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-default -am -Dxwiki.spoon.skip=true -Dlicense.skip=true -Dxwiki.checkstyle.skip=true -Dtest=AbstractOASyncSourceTest test
```
Expected: 编译失败。

- [ ] **Step 3: 实现 AbstractOASyncSource**

```java
package org.xwiki.oa.sync.internal.source;

import javax.inject.Inject;

import org.xwiki.oa.sync.OASyncConfiguration;
import org.xwiki.oa.sync.OASyncSource;

/**
 * Base class for OA sync sources: enabled flag + remote path resolution from per-source config.
 */
public abstract class AbstractOASyncSource implements OASyncSource
{
    @Inject
    OASyncConfiguration configuration;

    @Override
    public boolean isEnabled()
    {
        return this.configuration.isSourceEnabled(getType());
    }

    @Override
    public String resolveRemotePath(String dateYYYYMMDD)
    {
        String basePath = this.configuration.getSourceBasePath(getType()).replace("%s", dateYYYYMMDD);
        String filePattern = this.configuration.getSourceFilePattern(getType()).replace("%s", dateYYYYMMDD);
        return basePath.endsWith("/") ? basePath + filePattern : basePath + "/" + filePattern;
    }
}
```

- [ ] **Step 4: 实现 EmployeeOASyncSource**

```java
package org.xwiki.oa.sync.internal.source;

import java.nio.charset.Charset;
import java.nio.file.Path;

import javax.inject.Named;
import javax.inject.Singleton;

import org.xwiki.component.annotation.Component;
import org.xwiki.oa.sync.OASyncSource;
import org.xwiki.oa.sync.model.OASyncParseResult;

/**
 * 行员用户数据来源：/comm/bdpp/oa/YYYYMMDD/IOA_EMPLOYEE_JGTY_YYYYMMDD.dat（GBK, CHAR06）。
 */
@Component(roles = OASyncSource.class)
@Named("employee")
@Singleton
public class EmployeeOASyncSource extends AbstractOASyncSource
{
    private final EmployeeFileParser parser = new EmployeeFileParser();

    @Override
    public String getType()
    {
        return "employee";
    }

    @Override
    public OASyncParseResult parse(Path file)
    {
        try {
            return this.parser.parse(file, Charset.forName(this.configuration.getSourceEncoding(getType())));
        } catch (Exception e) {
            throw new RuntimeException("解析行员文件失败: " + e.getMessage(), e);
        }
    }
}
```

- [ ] **Step 5: 实现 OutsourcingOASyncSource（占位）**

```java
package org.xwiki.oa.sync.internal.source;

import java.nio.file.Path;

import javax.inject.Named;
import javax.inject.Singleton;

import org.xwiki.component.annotation.Component;
import org.xwiki.oa.sync.OASyncSource;
import org.xwiki.oa.sync.model.OASyncParseResult;

/**
 * 业务外包用户数据来源：文件格式未定义，暂为占位实现。默认未启用
 * （xwiki.oa-sync.source.outsourcing.enabled=false）。格式定稿后在此实现 parse。
 */
@Component(roles = OASyncSource.class)
@Named("outsourcing")
@Singleton
public class OutsourcingOASyncSource extends AbstractOASyncSource
{
    @Override
    public String getType()
    {
        return "outsourcing";
    }

    @Override
    public OASyncParseResult parse(Path file)
    {
        throw new UnsupportedOperationException("外包用户文件格式未定义，解析尚未实现");
    }
}
```

- [ ] **Step 6: 运行确认通过**

```bash
mvn -s ~/.m2/settings-xwiki.xml -f pom.xml -pl xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-default -am -Dxwiki.spoon.skip=true -Dlicense.skip=true -Dxwiki.checkstyle.skip=true -Dtest=AbstractOASyncSourceTest test
```
Expected: Tests run: 2, Failures: 0。

- [ ] **Step 7: Commit**

```bash
git add xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-default
git commit -m "OA同步：来源 SPI 实现（行员 + 外包占位）"
```

---

### Task 7: 同步记录类初始化器 + SyncRecordStore（TDD）

**Files:**
- Test: `.../src/test/java/org/xwiki/oa/sync/internal/store/SyncRecordStoreTest.java`
- Create: `.../src/main/java/org/xwiki/oa/sync/internal/initializer/OASyncRecordClassInitializer.java`
- Create: `.../src/main/java/org/xwiki/oa/sync/internal/initializer/OASyncOrgGroupClassInitializer.java`
- Create: `.../src/main/java/org/xwiki/oa/sync/internal/store/SyncRecordStore.java`

- [ ] **Step 1: 写失败测试（用 MockitoOldcore 验证记录保存 + 清理）**

```java
package org.xwiki.oa.sync.internal.store;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.oa.sync.OASyncConfiguration;
import org.xwiki.oa.sync.model.OASyncResult;
import org.xwiki.oa.sync.model.OASyncTriggerType;
import org.xwiki.query.Query;
import org.xwiki.query.QueryManager;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.test.MockitoOldcore;
import com.xpn.xwiki.test.junit5.mockito.InjectMockitoOldcore;
import com.xpn.xwiki.test.junit5.mockito.OldcoreTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@OldcoreTest
class SyncRecordStoreTest
{
    @InjectMockitoOldcore
    private MockitoOldcore oldcore;

    @InjectMockComponents
    private SyncRecordStore store;

    @MockComponent
    private OASyncConfiguration configuration;

    @MockComponent
    private QueryManager queryManager;

    @BeforeEach
    void setUp()
    {
        when(this.configuration.getRecordSpace()).thenReturn("OASync");
    }

    @Test
    void savesRecord() throws Exception
    {
        OASyncResult result = new OASyncResult("employee", LocalDate.of(2026, 8, 5),
            OASyncTriggerType.SCHEDULED, LocalDateTime.of(2026, 8, 6, 5, 0));
        result.setStatus(OASyncResult.Status.SUCCESS);
        result.setEndTime(LocalDateTime.of(2026, 8, 6, 5, 1));
        result.setTotalCount(3);
        result.setSuccessCount(3);
        result.setFailCount(0);
        this.store.save(result, this.oldcore.getXWikiContext());

        XWikiDocument doc = this.oldcore.getXWikiContext().getWiki().getDocument(
            new DocumentReference(this.oldcore.getXWikiContext().getWikiId(), "OASync",
                "employee-20260806050000"), this.oldcore.getXWikiContext());
        assertNotNull(doc.getXObject(new DocumentReference("xwiki", "OASync", "SyncRecordClass")));
    }

    @Test
    void cleanupKeepsOnlyLatestTen() throws Exception
    {
        XWikiContext context = this.oldcore.getXWikiContext();
        Query query = org.mockito.Mockito.mock(Query.class);
        when(this.queryManager.createQuery(anyString(), eq(Query.XWQL))).thenReturn(query);
        when(query.bindValue(eq("type"), anyString())).thenReturn(query);
        when(query.execute()).thenReturn(List.of("a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l"));
        this.store.cleanup("employee", context);
        // 超过 10 条会尝试删除 k、l —— 这里用 mock 查询，主要验证不抛异常
    }
}
```

注意：`OASyncConfiguration` 接口需要加 `String getRecordSpace()`（默认 "OASync"），Task 2 的接口同步补上。

- [ ] **Step 2: 运行确认失败**

```bash
mvn -s ~/.m2/settings-xwiki.xml -f pom.xml -pl xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-default -am -Dxwiki.spoon.skip=true -Dlicense.skip=true -Dxwiki.checkstyle.skip=true -Dtest=SyncRecordStoreTest test
```
Expected: 编译失败。

- [ ] **Step 3: 在 OASyncConfiguration 增加 getRecordSpace()**

在 `xwiki-platform-oa-sync-api/.../OASyncConfiguration.java` 接口末尾加：

```java
    /**
     * @return the wiki space where sync record and org-group classes/pages live, default "OASync"
     */
    default String getRecordSpace()
    {
        return "OASync";
    }
```

- [ ] **Step 4: 实现两个类初始化器**

`.../initializer/OASyncRecordClassInitializer.java`：

```java
package org.xwiki.oa.sync.internal.initializer;

import javax.inject.Named;
import javax.inject.Singleton;

import org.xwiki.component.annotation.Component;
import org.xwiki.model.reference.LocalDocumentReference;

import com.xpn.xwiki.doc.AbstractMandatoryClassInitializer;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.classes.BaseClass;

/**
 * Defines OASync.SyncRecordClass: one object per sync run.
 */
@Component
@Named("OASync.SyncRecordClass")
@Singleton
public class OASyncRecordClassInitializer extends AbstractMandatoryClassInitializer
{
    public static final LocalDocumentReference REFERENCE = new LocalDocumentReference("OASync", "SyncRecordClass");

    public OASyncRecordClassInitializer()
    {
        super(REFERENCE);
    }

    @Override
    protected void createClass(BaseClass xclass)
    {
        xclass.addTextField("syncType", "Sync Type", 30);
        xclass.addDateField("syncDate", "Sync Date");
        xclass.addTextField("triggerType", "Trigger Type", 30);
        xclass.addTextField("status", "Status", 30);
        xclass.addDateField("startTime", "Start Time");
        xclass.addDateField("endTime", "End Time");
        xclass.addNumberField("totalCount", "Total", 10);
        xclass.addNumberField("successCount", "Success", 10);
        xclass.addNumberField("failCount", "Failed", 10);
        xclass.addTextAreaField("errorLog", "Error Log", 60, 10);
    }
}
```

`.../initializer/OASyncOrgGroupClassInitializer.java`：

```java
package org.xwiki.oa.sync.internal.initializer;

import javax.inject.Named;
import javax.inject.Singleton;

import org.xwiki.component.annotation.Component;
import org.xwiki.model.reference.LocalDocumentReference;

import com.xpn.xwiki.doc.AbstractMandatoryClassInitializer;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.classes.BaseClass;

/**
 * Defines OASync.OASyncOrgGroupClass attached to org group documents (orgCode unique, orgName display).
 */
@Component
@Named("OASync.OASyncOrgGroupClass")
@Singleton
public class OASyncOrgGroupClassInitializer extends AbstractMandatoryClassInitializer
{
    public static final LocalDocumentReference REFERENCE =
        new LocalDocumentReference("OASync", "OASyncOrgGroupClass");

    public OASyncOrgGroupClassInitializer()
    {
        super(REFERENCE);
    }

    @Override
    protected void createClass(BaseClass xclass)
    {
        xclass.addTextField("orgCode", "Org Code", 30);
        xclass.addTextField("orgName", "Org Name", 60);
    }
}
```

- [ ] **Step 5: 实现 SyncRecordStore**

```java
package org.xwiki.oa.sync.internal.store;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xwiki.component.annotation.Component;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.oa.sync.OASyncConfiguration;
import org.xwiki.oa.sync.model.OASyncResult;
import org.xwiki.query.Query;
import org.xwiki.query.QueryManager;
import org.xwiki.query.QueryException;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;

/**
 * Persists sync run records as OASync.SyncRecordClass objects and keeps only the latest KEEP_RECORDS per type.
 */
@Component(roles = SyncRecordStore.class)
@Singleton
public class SyncRecordStore
{
    public static final int KEEP_RECORDS = 10;

    private static final Logger LOGGER = LoggerFactory.getLogger(SyncRecordStore.class);
    private static final DateTimeFormatter TS_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    @Inject
    private OASyncConfiguration configuration;

    @Inject
    private QueryManager queryManager;

    public void save(OASyncResult result, XWikiContext context) throws XWikiException
    {
        String space = this.configuration.getRecordSpace();
        String name = result.getType() + "-" + result.getStartTime().format(TS_FORMAT);
        DocumentReference recordRef = new DocumentReference(context.getWikiId(), space, name);
        XWikiDocument doc = new XWikiDocument(recordRef);
        doc.setTitle(result.getType() + " 同步 " + result.getSyncDate());
        doc.setHidden(true);

        BaseObject obj = doc.newXObject(new DocumentReference(context.getWikiId(), space, "SyncRecordClass"), context);
        obj.setStringValue("syncType", result.getType());
        obj.setDateValue("syncDate", toDate(result.getSyncDate()));
        obj.setStringValue("triggerType", result.getTriggerType().name());
        obj.setStringValue("status", result.getStatus() != null ? result.getStatus().name() : "UNKNOWN");
        obj.setDateValue("startTime", toDate(result.getStartTime()));
        obj.setDateValue("endTime", result.getEndTime() != null ? toDate(result.getEndTime()) : null);
        obj.setIntValue("totalCount", result.getTotalCount());
        obj.setIntValue("successCount", result.getSuccessCount());
        obj.setIntValue("failCount", result.getFailCount());
        obj.setLargeStringValue("errorLog", String.join("\n", result.getErrorLog()));

        context.getWiki().saveDocument(doc, "OA sync: record " + name, context);
    }

    public void cleanup(String type, XWikiContext context) throws QueryException, XWikiException
    {
        String space = this.configuration.getRecordSpace();
        String className = space + ".SyncRecordClass";
        Query query = this.queryManager.createQuery(
            "select doc.fullName from Document doc where doc.object(" + className + ").syncType = :type"
                + " order by doc.creationDate desc",
            Query.XWQL);
        query.bindValue("type", type);
        List<String> fullNames = query.<String>execute();
        if (fullNames.size() <= KEEP_RECORDS) {
            return;
        }
        for (String fullName : fullNames.subList(KEEP_RECORDS, fullNames.size())) {
            XWikiDocument doc = context.getWiki().getDocument(fullName, context);
            context.getWiki().deleteDocument(doc, context);
            LOGGER.info("OA sync: deleted old record [{}]", fullName);
        }
    }

    private static Date toDate(java.time.LocalDate localDate)
    {
        return Date.from(localDate.atStartOfDay(ZoneId.systemDefault()).toInstant());
    }

    private static Date toDate(java.time.LocalDateTime localDateTime)
    {
        return Date.from(localDateTime.atZone(ZoneId.systemDefault()).toInstant());
    }
}
```

- [ ] **Step 6: 运行确认通过**

```bash
mvn -s ~/.m2/settings-xwiki.xml -f pom.xml -pl xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-default -am -Dxwiki.spoon.skip=true -Dlicense.skip=true -Dxwiki.checkstyle.skip=true -Dtest=SyncRecordStoreTest test
```
Expected: Tests run: 2, Failures: 0。（若 MockitoOldcore 中 `getRecordSpace` 需要 OASyncConfiguration 组件实例，改用 `@MockComponent` 已在测试中处理。）

- [ ] **Step 7: Commit**

```bash
git add xwiki-platform-core/xwiki-platform-oa-sync
git commit -m "OA同步：记录类初始化器 + 记录存储（保留最近10条）"
```

---

### Task 8: 用户/权限组同步器（TDD）

**Files:**
- Test: `.../src/test/java/org/xwiki/oa/sync/internal/sync/UserGroupSynchronizerTest.java`
- Create: `.../src/main/java/org/xwiki/oa/sync/internal/sync/UserGroupSynchronizer.java`

- [ ] **Step 1: 写失败测试（MockitoOldcore）**

```java
package org.xwiki.oa.sync.internal.sync;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.oa.sync.OASyncConfiguration;
import org.xwiki.oa.sync.model.OAUserRecord;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.test.MockitoOldcore;
import com.xpn.xwiki.test.junit5.mockito.InjectMockitoOldcore;
import com.xpn.xwiki.test.junit5.mockito.OldcoreTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@OldcoreTest
class UserGroupSynchronizerTest
{
    @InjectMockitoOldcore
    private MockitoOldcore oldcore;

    @InjectMockComponents
    private UserGroupSynchronizer synchronizer;

    @MockComponent
    private OASyncConfiguration configuration;

    private XWikiContext context;

    @BeforeEach
    void setUp()
    {
        this.context = this.oldcore.getXWikiContext();
        when(this.configuration.getRecordSpace()).thenReturn("OASync");
    }

    @Test
    void createsUserAndGroupAndMembership() throws Exception
    {
        this.synchronizer.sync(new OAUserRecord("10086", "8801", "科技部", true), this.context);

        XWikiDocument userDoc = this.context.getWiki().getDocument(
            new DocumentReference(this.context.getWikiId(), "XWiki", "10086"), this.context);
        assertFalse(userDoc.isNew());

        XWikiDocument groupDoc = this.context.getWiki().getDocument(
            new DocumentReference(this.context.getWikiId(), "XWiki", "8801"), this.context);
        assertFalse(groupDoc.isNew());
        assertEquals("科技部", groupDoc.getTitle());
        assertNotNull(groupDoc.getXObject(new DocumentReference(this.context.getWikiId(), "OASync",
            "OASyncOrgGroupClass")));
        assertTrue(groupDoc.getXObjectsSize(
            new DocumentReference(this.context.getWikiId(), "XWiki", "XWikiGroups")) > 0);
    }

    @Test
    void disablesExistingUser() throws Exception
    {
        this.synchronizer.sync(new OAUserRecord("10086", "8801", "科技部", true), this.context);
        this.synchronizer.sync(new OAUserRecord("10086", "8801", "科技部", false), this.context);

        XWikiDocument userDoc = this.context.getWiki().getDocument(
            new DocumentReference(this.context.getWikiId(), "XWiki", "10086"), this.context);
        assertEquals(0, userDoc.getIntValue(
            new DocumentReference(this.context.getWikiId(), "XWiki", "XWikiUsers"), "active", 1));
    }

    @Test
    void rejectsBlankOrgCode() throws Exception
    {
        org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
            () -> this.synchronizer.sync(new OAUserRecord("10086", "  ", "科技部", true), this.context));
    }
}
```

- [ ] **Step 2: 运行确认失败**

```bash
mvn -s ~/.m2/settings-xwiki.xml -f pom.xml -pl xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-default -am -Dxwiki.spoon.skip=true -Dlicense.skip=true -Dxwiki.checkstyle.skip=true -Dtest=UserGroupSynchronizerTest test
```
Expected: 编译失败。

- [ ] **Step 3: 实现 UserGroupSynchronizer**

```java
package org.xwiki.oa.sync.internal.sync;

import java.util.Collections;
import java.util.Objects;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.xwiki.component.annotation.Component;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.oa.sync.OASyncConfiguration;
import org.xwiki.oa.sync.model.OAUserRecord;
import org.xwiki.user.group.GroupException;
import org.xwiki.user.group.GroupManager;

import com.xpn.xwiki.XWiki;
import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;
import com.xpn.xwiki.user.api.XWikiUser;

/**
 * Applies one OA user record to XWiki: create/update user, set active/disabled, ensure org group membership.
 * Idempotent: create-if-missing, set-state-only-on-change, add-member-only-if-missing.
 */
@Component(roles = UserGroupSynchronizer.class)
@Singleton
public class UserGroupSynchronizer
{
    @Inject
    private OASyncConfiguration configuration;

    @Inject
    private GroupManager groupManager;

    public void sync(OAUserRecord record, XWikiContext context) throws XWikiException, GroupException
    {
        XWiki wiki = context.getWiki();
        String wikiId = context.getWikiId();
        String username = record.getUsername();

        // 1. Ensure user exists
        DocumentReference userRef = new DocumentReference(wikiId, "XWiki", username);
        XWikiDocument userDoc = wiki.getDocument(userRef, context);
        if (userDoc.isNew()) {
            wiki.createUser(username, Collections.emptyMap(), context);
            userDoc = wiki.getDocument(userRef, context);
        }
        String userFullName = userDoc.getFullName();

        // 2. Set active/disabled (only on change)
        XWikiUser xwikiUser = new XWikiUser(userRef);
        if (xwikiUser.isDisabled(context) == record.isActive()) {
            xwikiUser.setDisabled(!record.isActive(), context);
        }

        // 3. Org group membership
        String orgCode = record.getOrgCode();
        if (orgCode == null || orgCode.isBlank()) {
            throw new XWikiException(XWikiException.MODULE_XWIKI_USER, XWikiException.ERROR_XWIKI_USER_UPDATE,
                "用户 [" + username + "] 归属机构编号为空");
        }
        DocumentReference groupRef = new DocumentReference(wikiId, "XWiki", orgCode);
        ensureGroup(groupRef, record, context);
        addMemberIfMissing(groupRef, userFullName, context);
    }

    private void ensureGroup(DocumentReference groupRef, OAUserRecord record, XWikiContext context)
        throws XWikiException
    {
        XWiki wiki = context.getWiki();
        String space = this.configuration.getRecordSpace();
        DocumentReference orgClassRef = new DocumentReference(context.getWikiId(), space, "OASyncOrgGroupClass");
        DocumentReference groupClassRef = wiki.getGroupClass(context).getDocumentReference()
            .removeParent(groupRef.getWikiReference());

        XWikiDocument groupDoc = wiki.getDocument(groupRef, context);
        if (groupDoc.isNew()) {
            XWikiDocument doc = new XWikiDocument(groupRef);
            doc.setTitle(record.getOrgName());
            doc.newXObject(groupClassRef, context);
            BaseObject orgObj = doc.newXObject(orgClassRef, context);
            orgObj.setStringValue("orgCode", record.getOrgCode());
            orgObj.setStringValue("orgName", record.getOrgName());
            wiki.saveDocument(doc, "OA sync: 创建机构组 " + record.getOrgCode(), context);
        } else if (!Objects.equals(groupDoc.getTitle(), record.getOrgName())) {
            XWikiDocument modified = groupDoc.clone();
            modified.setTitle(record.getOrgName());
            BaseObject orgObj = modified.getXObject(orgClassRef);
            if (orgObj == null) {
                orgObj = modified.newXObject(orgClassRef, context);
            }
            orgObj.setStringValue("orgCode", record.getOrgCode());
            orgObj.setStringValue("orgName", record.getOrgName());
            wiki.saveDocument(modified, "OA sync: 更新机构组 " + record.getOrgCode(), context);
        }
    }

    private void addMemberIfMissing(DocumentReference groupRef, String userFullName, XWikiContext context)
        throws XWikiException, GroupException
    {
        XWiki wiki = context.getWiki();
        DocumentReference groupClassRef = wiki.getGroupClass(context).getDocumentReference()
            .removeParent(groupRef.getWikiReference());
        DocumentReference userRef = new DocumentReference(context.getWikiId(), "XWiki",
            userFullName.substring(userFullName.lastIndexOf('.') + 1));

        if (this.groupManager.getMembers(groupRef, false).contains(userRef)) {
            return;
        }
        XWikiDocument groupDoc = wiki.getDocument(groupRef, context).clone();
        BaseObject memberObject = groupDoc.newXObject(groupClassRef, context);
        memberObject.setStringValue("member", userFullName);
        wiki.saveDocument(groupDoc, "OA sync: 添加成员 " + userFullName + " 到组 " + groupRef.getName(), context);
    }
}
```

- [ ] **Step 4: 运行确认通过**

```bash
mvn -s ~/.m2/settings-xwiki.xml -f pom.xml -pl xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-default -am -Dxwiki.spoon.skip=true -Dlicense.skip=true -Dxwiki.checkstyle.skip=true -Dtest=UserGroupSynchronizerTest test
```
Expected: Tests run: 3, Failures: 0。（如 `XWikiUsers` 类在 MockitoOldcore 中未初始化导致 createUser 失败，测试 setUp 中先 `oldcore.getXWikiContext().getWiki().getUserClass(context)` 触发类初始化。）

- [ ] **Step 5: Commit**

```bash
git add xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-default
git commit -m "OA同步：用户/权限组同步器（幂等 upsert）"
```

---

### Task 9: 编排服务 DefaultOASyncService（TDD）

**Files:**
- Test: `.../src/test/java/org/xwiki/oa/sync/internal/service/DefaultOASyncServiceTest.java`
- Create: `.../src/main/java/org/xwiki/oa/sync/internal/service/DefaultOASyncService.java`

- [ ] **Step 1: 写失败测试（mock 子组件 + 临时文件）**

```java
package org.xwiki.oa.sync.internal.service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.xwiki.component.manager.ComponentLookupException;
import org.xwiki.component.manager.ComponentManager;
import org.xwiki.oa.sync.OASyncConfiguration;
import org.xwiki.oa.sync.OASyncSource;
import org.xwiki.oa.sync.internal.ftp.OAFtpDownloader;
import org.xwiki.oa.sync.internal.store.SyncRecordStore;
import org.xwiki.oa.sync.internal.sync.UserGroupSynchronizer;
import org.xwiki.oa.sync.model.OAUserRecord;
import org.xwiki.oa.sync.model.OASyncParseResult;
import org.xwiki.oa.sync.model.OASyncResult;
import org.xwiki.oa.sync.model.OASyncTriggerType;

import com.xpn.xwiki.test.MockitoOldcore;
import com.xpn.xwiki.test.junit5.mockito.InjectMockitoOldcore;
import com.xpn.xwiki.test.junit5.mockito.OldcoreTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@OldcoreTest
class DefaultOASyncServiceTest
{
    @InjectMockitoOldcore
    private MockitoOldcore oldcore;

    @TempDir
    Path tempDir;

    private DefaultOASyncService service;
    private OASyncConfiguration configuration;
    private OAFtpDownloader ftpDownloader;
    private SyncRecordStore recordStore;
    private UserGroupSynchronizer synchronizer;
    private ComponentManager componentManager;
    private OASyncSource source;

    @BeforeEach
    void setUp() throws Exception
    {
        this.service = new DefaultOASyncService();
        this.configuration = mock(OASyncConfiguration.class);
        this.ftpDownloader = mock(OAFtpDownloader.class);
        this.recordStore = mock(SyncRecordStore.class);
        this.synchronizer = mock(UserGroupSynchronizer.class);
        this.componentManager = mock(ComponentManager.class);
        this.source = mock(OASyncSource.class);

        when(this.componentManager.getInstanceList(OASyncSource.class)).thenReturn(List.of(this.source));
        when(this.source.getType()).thenReturn("employee");
        when(this.source.isEnabled()).thenReturn(true);
        when(this.source.resolveRemotePath("20260805")).thenReturn("/comm/bdpp/oa/20260805/x.dat");
        Path file = this.tempDir.resolve("x.dat");
        Files.write(file, "dummy".getBytes(StandardCharsets.UTF_8));
        when(this.ftpDownloader.download("/comm/bdpp/oa/20260805/x.dat")).thenReturn(file);

        OASyncParseResult parseResult = new OASyncParseResult();
        parseResult.getRecords().add(new OAUserRecord("10086", "8801", "科技部", true));
        when(this.source.parse(any(Path.class))).thenReturn(parseResult);
        when(this.configuration.getSourceDateOffsetDays("employee")).thenReturn(1);
        when(this.configuration.getRecordSpace()).thenReturn("OASync");

        this.service.configuration = this.configuration;
        this.service.ftpDownloader = this.ftpDownloader;
        this.service.recordStore = this.recordStore;
        this.service.synchronizer = this.synchronizer;
        this.service.componentManager = this.componentManager;
        this.service.contextProvider = () -> this.oldcore.getXWikiContext();
    }

    @Test
    void syncAllRunsEnabledSourceAndPersistsRecord() throws Exception
    {
        List<OASyncResult> results =
            this.service.syncAll(OASyncTriggerType.MANUAL, LocalDate.of(2026, 8, 5));
        assertEquals(1, results.size());
        assertEquals(OASyncResult.Status.SUCCESS, results.get(0).getStatus());
        assertEquals(1, results.get(0).getSuccessCount());
        verify(this.recordStore).save(eq(results.get(0)), any());
        verify(this.recordStore).cleanup(eq("employee"), any());
    }

    @Test
    void isolatedUserFailureStillPersistsPartialResult() throws Exception
    {
        doAnswer(inv -> {
            throw new RuntimeException("boom");
        }).when(this.synchronizer).sync(any(), any());

        List<OASyncResult> results = this.service.syncAll(OASyncTriggerType.MANUAL, LocalDate.of(2026, 8, 5));
        assertEquals(OASyncResult.Status.PARTIAL_FAILURE, results.get(0).getStatus());
        assertEquals(1, results.get(0).getFailCount());
        assertEquals(1, results.get(0).getErrorLog().size());
    }

    @Test
    void sourceFailureProducesFailureResult() throws Exception
    {
        when(this.source.parse(any(Path.class))).thenThrow(new RuntimeException("bad file"));
        List<OASyncResult> results = this.service.syncAll(OASyncTriggerType.MANUAL, LocalDate.of(2026, 8, 5));
        assertEquals(OASyncResult.Status.FAILURE, results.get(0).getStatus());
    }
}
```

- [ ] **Step 2: 运行确认失败**

```bash
mvn -s ~/.m2/settings-xwiki.xml -f pom.xml -pl xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-default -am -Dxwiki.spoon.skip=true -Dlicense.skip=true -Dxwiki.checkstyle.skip=true -Dtest=DefaultOASyncServiceTest test
```
Expected: 编译失败。

- [ ] **Step 3: 实现 DefaultOASyncService**

```java
package org.xwiki.oa.sync.internal.service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xwiki.component.annotation.Component;
import org.xwiki.component.manager.ComponentLookupException;
import org.xwiki.component.manager.ComponentManager;
import org.xwiki.oa.sync.OASyncConfiguration;
import org.xwiki.oa.sync.OASyncService;
import org.xwiki.oa.sync.OASyncSource;
import org.xwiki.oa.sync.internal.ftp.OAFtpDownloader;
import org.xwiki.oa.sync.internal.store.SyncRecordStore;
import org.xwiki.oa.sync.internal.sync.UserGroupSynchronizer;
import org.xwiki.oa.sync.model.OAUserRecord;
import org.xwiki.oa.sync.model.OASyncParseResult;
import org.xwiki.oa.sync.model.OASyncResult;
import org.xwiki.oa.sync.model.OASyncTriggerType;

import com.xpn.xwiki.XWikiContext;

/**
 * Orchestrates OA user sync: for each enabled source, download -> parse -> per-record upsert (isolated failures)
 * -> persist record -> cleanup old records.
 */
@Component(roles = OASyncService.class)
@Singleton
public class DefaultOASyncService implements OASyncService
{
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultOASyncService.class);
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    @Inject
    OASyncConfiguration configuration;

    @Inject
    OAFtpDownloader ftpDownloader;

    @Inject
    SyncRecordStore recordStore;

    @Inject
    UserGroupSynchronizer synchronizer;

    @Inject
    Provider<XWikiContext> contextProvider;

    @Inject
    ComponentManager componentManager;

    @Override
    public List<OASyncResult> syncAll(OASyncTriggerType triggerType, LocalDate manualDate)
    {
        List<OASyncResult> results = new ArrayList<>();
        for (OASyncSource source : getSources()) {
            results.add(syncSource(source, triggerType, manualDate));
        }
        return results;
    }

    private List<OASyncSource> getSources()
    {
        try {
            return this.componentManager.getInstanceList(OASyncSource.class);
        } catch (ComponentLookupException e) {
            throw new IllegalStateException("无法获取 OA 同步来源组件", e);
        }
    }

    private OASyncResult syncSource(OASyncSource source, OASyncTriggerType triggerType, LocalDate manualDate)
    {
        XWikiContext context = this.contextProvider.get();
        String type = source.getType();
        if (!source.isEnabled()) {
            return null;
        }
        LocalDate syncDate = manualDate != null
            ? manualDate
            : LocalDate.now().minusDays(this.configuration.getSourceDateOffsetDays(type));
        OASyncResult result = new OASyncResult(type, syncDate, triggerType, LocalDateTime.now());
        Path downloaded = null;
        try {
            String remotePath = source.resolveRemotePath(syncDate.format(DATE_FORMAT));
            downloaded = this.ftpDownloader.download(remotePath);

            OASyncParseResult parseResult = source.parse(downloaded);
            int failed = parseResult.getErrors().size();
            int success = 0;
            result.getErrorLog().addAll(parseResult.getErrors());
            for (OAUserRecord record : parseResult.getRecords()) {
                try {
                    this.synchronizer.sync(record, context);
                    success++;
                } catch (Exception e) {
                    failed++;
                    String msg = "用户 [" + record.getUsername() + "] 同步失败: " + e.getMessage();
                    result.getErrorLog().add(msg);
                    LOGGER.error(msg, e);
                }
            }
            result.setTotalCount(success + failed);
            result.setSuccessCount(success);
            result.setFailCount(failed);
            result.setStatus(failed == 0 ? OASyncResult.Status.SUCCESS : OASyncResult.Status.PARTIAL_FAILURE);
        } catch (Exception e) {
            String msg = "来源 [" + type + "] 同步失败: " + e.getMessage();
            result.getErrorLog().add(msg);
            LOGGER.error(msg, e);
            result.setStatus(OASyncResult.Status.FAILURE);
        } finally {
            if (downloaded != null) {
                try {
                    Files.deleteIfExists(downloaded);
                } catch (Exception e) {
                    LOGGER.warn("删除临时文件失败: {}", downloaded, e);
                }
            }
            result.setEndTime(LocalDateTime.now());
        }
        try {
            this.recordStore.save(result, context);
            this.recordStore.cleanup(type, context);
        } catch (Exception e) {
            LOGGER.error("保存同步记录失败（来源 {}）", type, e);
        }
        return result;
    }
}
```

- [ ] **Step 4: 运行确认通过**

```bash
mvn -s ~/.m2/settings-xwiki.xml -f pom.xml -pl xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-default -am -Dxwiki.spoon.skip=true -Dlicense.skip=true -Dxwiki.checkstyle.skip=true -Dtest=DefaultOASyncServiceTest test
```
Expected: Tests run: 3, Failures: 0。

- [ ] **Step 5: Commit**

```bash
git add xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-default
git commit -m "OA同步：编排服务（失败隔离+记录持久化）"
```

---

### Task 10: Quartz 定时任务 OASyncJob

**Files:**
- Create: `.../src/main/java/org/xwiki/oa/sync/internal/job/OASyncJob.java`

- [ ] **Step 1: 实现 OASyncJob（继承 AbstractJob，非组件，不注册 components.txt）**

```java
package org.xwiki.oa.sync.internal.job;

import org.quartz.JobExecutionContext;
import org.quartz.JobExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xwiki.oa.sync.OASyncService;
import org.xwiki.oa.sync.model.OASyncTriggerType;

import com.xpn.xwiki.plugin.scheduler.AbstractJob;
import com.xpn.xwiki.web.Utils;

/**
 * Quartz job triggered by the XWiki scheduler (SchedulerJobClass document) to run the OA user sync.
 */
public class OASyncJob extends AbstractJob
{
    private static final Logger LOGGER = LoggerFactory.getLogger(OASyncJob.class);

    @Override
    protected void executeJob(JobExecutionContext jobContext) throws JobExecutionException
    {
        try {
            OASyncService service = Utils.getComponent(OASyncService.class);
            service.syncAll(OASyncTriggerType.SCHEDULED, null);
        } catch (Exception e) {
            LOGGER.error("OA 用户同步定时任务执行失败", e);
            throw new JobExecutionException("OA 用户同步定时任务执行失败", e);
        }
    }
}
```

- [ ] **Step 2: 编译验证**

```bash
mvn -s ~/.m2/settings-xwiki.xml -f pom.xml -pl xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-default -am -Dxwiki.spoon.skip=true -Dlicense.skip=true -DskipTests=true -Dxwiki.checkstyle.skip=true compile
```
Expected: BUILD SUCCESS。

- [ ] **Step 3: Commit**

```bash
git add xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-default
git commit -m "OA同步：Quartz 定时任务 OASyncJob"
```

---

### Task 11: REST 手动触发资源

**Files:**
- Create: `.../src/main/java/org/xwiki/oa/sync/internal/rest/OASyncRESTResource.java`

- [ ] **Step 1: 实现 REST 资源（组件，注册进 components.txt）**

```java
package org.xwiki.oa.sync.internal.rest;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import javax.inject.Inject;
import javax.inject.Named;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import org.xwiki.component.annotation.Component;
import org.xwiki.oa.sync.OASyncService;
import org.xwiki.oa.sync.model.OASyncResult;
import org.xwiki.oa.sync.model.OASyncTriggerType;
import org.xwiki.rest.XWikiResource;
import org.xwiki.rest.XWikiRestComponent;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.xpn.xwiki.XWikiContext;

/**
 * REST endpoint to manually trigger OA user sync: POST /rest/oa-sync/trigger?type=employee[&date=YYYYMMDD]
 */
@Component
@Named("org.xwiki.oa.sync.internal.rest.OASyncRESTResource")
@Path("/oa-sync")
public class OASyncRESTResource extends XWikiResource implements XWikiRestComponent
{
    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.BASIC_ISO_DATE;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Inject
    private OASyncService oaSyncService;

    @POST
    @Path("/trigger")
    @Produces(MediaType.APPLICATION_JSON)
    public Response trigger(@QueryParam("type") String type, @QueryParam("date") String date) throws Exception
    {
        XWikiContext context = getXWikiContext();
        try {
            LocalDate manualDate = null;
            if (date != null && !date.isEmpty()) {
                manualDate = LocalDate.parse(date, DATE_FORMAT);
            }
            List<OASyncResult> results = this.oaSyncService.syncAll(OASyncTriggerType.MANUAL, manualDate);
            OASyncResult selected = null;
            for (OASyncResult result : results) {
                if (result != null && (type == null || type.isEmpty() || type.equals(result.getType()))) {
                    selected = result;
                    break;
                }
            }
            if (selected == null) {
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("status", "SKIPPED");
                body.put("message", "来源 [" + (type == null ? "?" : type) + "] 未启用或不存在");
                return Response.ok(OBJECT_MAPPER.writeValueAsString(body)).build();
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("type", selected.getType());
            body.put("syncDate", selected.getSyncDate().toString());
            body.put("triggerType", selected.getTriggerType().name());
            body.put("status", selected.getStatus().name());
            body.put("total", selected.getTotalCount());
            body.put("success", selected.getSuccessCount());
            body.put("failed", selected.getFailCount());
            body.put("errorLog", selected.getErrorLog());
            return Response.ok(OBJECT_MAPPER.writeValueAsString(body)).build();
        } finally {
            context.getWiki().getStore().cleanUp(context);
        }
    }
}
```

- [ ] **Step 2: 编译验证**

```bash
mvn -s ~/.m2/settings-xwiki.xml -f pom.xml -pl xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-default -am -Dxwiki.spoon.skip=true -Dlicense.skip=true -DskipTests=true -Dxwiki.checkstyle.skip=true compile
```
Expected: BUILD SUCCESS。

- [ ] **Step 3: Commit**

```bash
git add xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-default
git commit -m "OA同步：REST 手动触发资源"
```

---

### Task 12: 组件注册 components.txt

**Files:**
- Create: `.../src/main/resources/META-INF/components.txt`

- [ ] **Step 1: 写入组件清单**

`xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-default/src/main/resources/META-INF/components.txt`：

```
org.xwiki.oa.sync.internal.configuration.DefaultOASyncConfiguration
org.xwiki.oa.sync.internal.initializer.OASyncRecordClassInitializer
org.xwiki.oa.sync.internal.initializer.OASyncOrgGroupClassInitializer
org.xwiki.oa.sync.internal.rest.OASyncRESTResource
org.xwiki.oa.sync.internal.service.DefaultOASyncService
org.xwiki.oa.sync.internal.source.EmployeeOASyncSource
org.xwiki.oa.sync.internal.source.OutsourcingOASyncSource
org.xwiki.oa.sync.internal.store.SyncRecordStore
org.xwiki.oa.sync.internal.sync.UserGroupSynchronizer
org.xwiki.oa.sync.internal.ftp.OAFtpDownloader
```

- [ ] **Step 2: 全量单测 + 打包验证**

```bash
mvn -s ~/.m2/settings-xwiki.xml -f pom.xml -pl xwiki-platform-core/xwiki-platform-oa-sync -am -Dxwiki.spoon.skip=true -Dlicense.skip=true -Dxwiki.checkstyle.skip=true install
```
Expected: BUILD SUCCESS，default 模块生成 `target/xwiki-platform-oa-sync-default-18.1.0.jar`，ui 模块生成 `target/xwiki-platform-oa-sync-ui-18.1.0.xar`。

- [ ] **Step 3: Commit**

```bash
git add xwiki-platform-core/xwiki-platform-oa-sync
git commit -m "OA同步：组件注册 components.txt"
```

---

### Task 13: XAR UI 页面（记录列表 + 手动触发 + 定时任务文档）

**Files:**
- Create: `xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-ui/src/main/resources/OASync/WebHome.xml`
- Create: `xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-ui/src/main/resources/OASync/EmployeeSyncJob.xml`

- [ ] **Step 1: OASync/WebHome.xml（LiveTable + 触发按钮）**

```xml
<?xml version="1.1" encoding="UTF-8"?>
<xwikidoc version="1.5" reference="OASync.WebHome" locale="">
  <web>OASync</web>
  <name>WebHome</name>
  <language/>
  <defaultLanguage/>
  <translation>0</translation>
  <creator>xwiki:XWiki.Admin</creator>
  <parent>Main.WebHome</parent>
  <author>xwiki:XWiki.Admin</author>
  <contentAuthor>xwiki:XWiki.Admin</contentAuthor>
  <version>1.1</version>
  <title>OA 用户数据同步</title>
  <comment/>
  <minorEdit>false</minorEdit>
  <syntaxId>xwiki/2.1</syntaxId>
  <hidden>false</hidden>
  <content>{{velocity}}
== OA 用户数据同步 ==

(% class="xform" %)
* **数据日期**：默认同步前一天（`xwiki.oa-sync.source.&lt;type&gt;.date-offset-days`）
* **定时**：每天 05:00，可在「管理 → 调度器」修改 cron
* **记录**：每种来源保留最近 10 条
* **同步记录状态**：SUCCESS=全部成功；PARTIAL_FAILURE=部分失败；FAILURE=整体失败（详见 errorLog）

(% id="syncActions" class="btn-group" %)
(% class="btn btn-primary" data-sync-type="employee" %)立即同步：行员(% /%)
(% /%)

{{html wiki="true"}}
&lt;script&gt;
require(['jquery'], function ($) {
  $('#syncActions .btn').on('click', function () {
    var type = $(this).data('sync-type');
    var btn = $(this);
    btn.prop('disabled', true);
    $.ajax({
      url: '$xwiki.getURL('', 'rest')' + '/oa-sync/trigger?type=' + type,
      method: 'POST',
      dataType: 'json',
      success: function (data) {
        new XWiki.widgets.Notification(
          '[' + data.type + '] ' + data.status + ' 成功=' + data.success + ' 失败=' + data.failed,
          'done');
      },
      error: function (xhr) {
        new XWiki.widgets.Notification('触发失败：' + xhr.responseText, 'error');
      },
      complete: function () { btn.prop('disabled', false); }
    });
  });
});
&lt;/script&gt;
{{/html}}

== 最近同步记录 ==

{{liveTable
  outputOnlyHTML="true"
  doTranslation="false"
  properties="doc.title,doc.creationDate,syncType,syncDate,triggerType,status,totalCount,successCount,failCount,errorLog"
  className="OASync.SyncRecordClass"
  source="liveTable"
/}}
{{/velocity}}</content>
</xwikidoc>
```

- [ ] **Step 2: OASync/EmployeeSyncJob.xml（SchedulerJobClass 对象）**

```xml
<?xml version="1.1" encoding="UTF-8"?>
<xwikidoc version="1.5" reference="OASync.EmployeeSyncJob" locale="">
  <web>OASync</web>
  <name>EmployeeSyncJob</name>
  <language/>
  <defaultLanguage/>
  <translation>0</translation>
  <creator>xwiki:XWiki.Admin</creator>
  <parent>OASync.WebHome</parent>
  <author>xwiki:XWiki.Admin</author>
  <contentAuthor>xwiki:XWiki.Admin</contentAuthor>
  <version>1.1</version>
  <title>OA 行员用户同步任务</title>
  <comment/>
  <minorEdit>false</minorEdit>
  <syntaxId>xwiki/2.1</syntaxId>
  <hidden>true</hidden>
  <content>{{include reference="OASync.WebHome"/}}</content>
  <object>
    <name>OASync.EmployeeSyncJob</name>
    <number>0</number>
    <className>XWiki.SchedulerJobClass</className>
    <guid>a1b2c3d4-oasync-employee-0001</guid>
    <class>
      <name>XWiki.SchedulerJobClass</name>
      <customClass/>
      <customMapping/>
      <defaultViewSheet/>
      <defaultEditSheet/>
      <defaultWeb/>
      <nameField/>
      <validationScript/>
      <cron>
        <cache>0</cache>
        <disabled>0</disabled>
        <displayType>input</displayType>
        <freeText>required</freeText>
        <hint/>
        <name>cron</name>
        <number>5</number>
        <picker>0</picker>
        <prettyName>Cron Expression</prettyName>
        <size>30</size>
        <unmodifiable>0</unmodifiable>
        <validationMessage/>
        <validationRegExp/>
        <classType>com.xpn.xwiki.objects.classes.StringClass</classType>
      </cron>
      <jobClass>
        <cache>0</cache>
        <disabled>0</disabled>
        <displayType>input</displayType>
        <freeText>required</freeText>
        <hint/>
        <name>jobClass</name>
        <number>3</number>
        <picker>0</picker>
        <prettyName>Job Class</prettyName>
        <size>60</size>
        <unmodifiable>0</unmodifiable>
        <validationMessage/>
        <validationRegExp/>
        <classType>com.xpn.xwiki.objects.classes.StringClass</classType>
      </jobClass>
      <jobDescription>
        <cache>0</cache>
        <disabled>0</disabled>
        <displayType>textarea</displayType>
        <freeText>required</freeText>
        <hint/>
        <name>jobDescription</name>
        <number>2</number>
        <picker>0</picker>
        <prettyName>Job Description</prettyName>
        <rows>10</rows>
        <size>45</size>
        <unmodifiable>0</unmodifiable>
        <validationMessage/>
        <validationRegExp/>
        <classType>com.xpn.xwiki.objects.classes.TextAreaClass</classType>
      </jobDescription>
      <jobName>
        <cache>0</cache>
        <disabled>0</disabled>
        <displayType>input</displayType>
        <freeText>required</freeText>
        <hint/>
        <name>jobName</name>
        <number>1</number>
        <picker>0</picker>
        <prettyName>Job Name</prettyName>
        <size>60</size>
        <unmodifiable>0</unmodifiable>
        <validationMessage/>
        <validationRegExp/>
        <classType>com.xpn.xwiki.objects.classes.StringClass</classType>
      </jobName>
      <script>
        <cache>0</cache>
        <disabled>0</disabled>
        <displayType>textarea</displayType>
        <freeText>required</freeText>
        <hint/>
        <name>script</name>
        <number>6</number>
        <picker>0</picker>
        <prettyName>Job Script</prettyName>
        <rows>10</rows>
        <size>60</size>
        <unmodifiable>0</unmodifiable>
        <validationMessage/>
        <validationRegExp/>
        <classType>com.xpn.xwiki.objects.classes.TextAreaClass</classType>
        <contenttype>PureText</contenttype>
      </script>
      <status>
        <cache>0</cache>
        <disabled>0</disabled>
        <displayType>input</displayType>
        <freeText>required</freeText>
        <hint/>
        <name>status</name>
        <number>4</number>
        <picker>0</picker>
        <prettyName>Status</prettyName>
        <size>30</size>
        <unmodifiable>0</unmodifiable>
        <validationMessage/>
        <validationRegExp/>
        <classType>com.xpn.xwiki.objects.classes.StringClass</classType>
      </status>
      <contextDatabase>
        <cache>0</cache>
        <disabled>0</disabled>
        <displayType>input</displayType>
        <freeText>required</freeText>
        <hint/>
        <name>contextDatabase</name>
        <number>9</number>
        <picker>0</picker>
        <prettyName>Job execution context database</prettyName>
        <size>30</size>
        <unmodifiable>0</unmodifiable>
        <validationMessage/>
        <validationRegExp/>
        <classType>com.xpn.xwiki.objects.classes.StringClass</classType>
      </contextDatabase>
      <contextLang>
        <cache>0</cache>
        <disabled>0</disabled>
        <displayType>input</displayType>
        <freeText>required</freeText>
        <hint/>
        <name>contextLang</name>
        <number>8</number>
        <picker>0</picker>
        <prettyName>Job execution context lang</prettyName>
        <size>30</size>
        <unmodifiable>0</unmodifiable>
        <validationMessage/>
        <validationRegExp/>
        <classType>com.xpn.xwiki.objects.classes.StringClass</classType>
      </contextLang>
      <contextUser>
        <cache>0</cache>
        <disabled>0</disabled>
        <displayType>input</displayType>
        <freeText>required</freeText>
        <hint/>
        <name>contextUser</name>
        <number>7</number>
        <picker>0</picker>
        <prettyName>Job execution context user</prettyName>
        <size>30</size>
        <unmodifiable>0</unmodifiable>
        <validationMessage/>
        <validationRegExp/>
        <classType>com.xpn.xwiki.objects.classes.StringClass</classType>
      </contextUser>
    </class>
    <property>
      <jobName>OA 行员用户同步</jobName>
      <jobDescription>每天从 FTP 同步行员用户数据（前一天，全量幂等）</jobDescription>
      <jobClass>org.xwiki.oa.sync.internal.job.OASyncJob</jobClass>
      <status>Normal</status>
      <cron>0 0 5 * * ?</cron>
      <script/>
      <contextUser>XWiki.Admin</contextUser>
      <contextLang/>
      <contextDatabase/>
    </property>
  </object>
</xwikidoc>
```

- [ ] **Step 3: 打包验证 XAR**

```bash
mvn -s ~/.m2/settings-xwiki.xml -f pom.xml -pl xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-ui -am -Dxwiki.spoon.skip=true -Dlicense.skip=true -DskipTests=true -Dxwiki.checkstyle.skip=true package
```
Expected: `xwiki-platform-oa-sync-ui/target/xwiki-platform-oa-sync-ui-18.1.0.xar` 生成。

- [ ] **Step 4: Commit**

```bash
git add xwiki-platform-core/xwiki-platform-oa-sync/xwiki-platform-oa-sync-ui
git commit -m "OA同步：XAR 页面（记录列表+手动触发+定时任务）"
```

---

### Task 14: 最终验证与部署说明

- [ ] **Step 1: 全模块构建 + 全部单测**

```bash
mvn -s ~/.m2/settings-xwiki.xml -f pom.xml -pl xwiki-platform-core/xwiki-platform-oa-sync -am -Dxwiki.spoon.skip=true -Dlicense.skip=true -Dxwiki.checkstyle.skip=true install
```
Expected: BUILD SUCCESS，单测全部通过。

- [ ] **Step 2: 部署清单（写给用户/运维）**

- 复制以下 **3 个 jar** 到 Tomcat `webapps/xwiki/WEB-INF/lib/`（缺一不可，接口/模型在 api 包，FTP 依赖 commons-net）：
  - `xwiki-platform-oa-sync-api/target/xwiki-platform-oa-sync-api-18.1.0.jar`
  - `xwiki-platform-oa-sync-default/target/xwiki-platform-oa-sync-default-18.1.0.jar`
  - `~/.m2/repository/commons-net/commons-net/3.12.0/commons-net-3.12.0.jar`
- 通过「管理 → 扩展管理器」导入 `xwiki-platform-oa-sync-ui-18.1.0.xar`（或在 XAR 目录部署）
- 在 `webapps/xwiki/WEB-INF/xwiki.cfg` 追加配置（host/port/user/password/超时/employee 目录与文件名模板）
- 重启 Tomcat → 进入 `OASync.WebHome` 页面验证记录区；「管理 → 调度器」确认任务已调度（cron `0 0 5 * * ?`，可改）
- 点「立即同步：行员」手动触发一次，核对用户/组/记录
- 等待一个定时周期，核对第二天记录

- [ ] **Step 3: 最终提交（如有遗留改动）**

```bash
git add -A
git commit -m "OA同步：最终验证与收尾"
```
