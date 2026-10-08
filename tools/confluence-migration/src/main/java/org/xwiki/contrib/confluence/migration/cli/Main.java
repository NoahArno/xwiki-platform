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
package org.xwiki.contrib.confluence.migration.cli;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import org.xwiki.contrib.confluence.migration.db.ConfluenceDb;
import org.xwiki.contrib.confluence.migration.db.ConfluenceHome;
import org.xwiki.contrib.confluence.migration.db.DbConfig;
import org.xwiki.contrib.confluence.migration.export.ExportResult;
import org.xwiki.contrib.confluence.migration.export.MigrationManifest;
import org.xwiki.contrib.confluence.migration.export.SpaceExporter;
import org.xwiki.contrib.confluence.migration.inventory.InventoryCollector;
import org.xwiki.contrib.confluence.migration.inventory.InventoryRenderer;
import org.xwiki.contrib.confluence.migration.inventory.InventoryReport;
import org.xwiki.contrib.confluence.migration.validation.PackageValidator;
import org.xwiki.contrib.confluence.migration.validation.PackageValidator.ValidationResult;

/**
 * 命令行入口。
 *
 * <pre>
 * confluence-migration inventory --confluence-home &lt;path&gt; [选项]
 * confluence-migration export    （待实现）
 * </pre>
 */
public final class Main
{
    private static final long MIB = 1024L * 1024L;

    private Main()
    {
    }

    /**
     * @param args 命令行参数
     */
    public static void main(String[] args)
    {
        int exitCode;
        try {
            exitCode = run(args);
        } catch (Exception e) {
            System.err.println("错误: " + e.getMessage());
            if (Boolean.getBoolean("confluence.migration.debug")) {
                e.printStackTrace(System.err);
            }
            exitCode = 1;
        }
        System.exit(exitCode);
    }

    /**
     * 执行命令，便于测试时不触发 {@link System#exit}。
     *
     * @param args 命令行参数
     * @return 退出码
     * @throws Exception 执行失败时
     */
    static int run(String[] args) throws Exception
    {
        if (args.length == 0) {
            printUsage();
            return 2;
        }

        String command = args[0];
        String[] rest = Arrays.copyOfRange(args, 1, args.length);

        return switch (command) {
            case "inventory" -> inventory(rest);
            case "export" -> export(rest);
            case "validate" -> validate(rest);
            case "help", "--help", "-h" -> {
                printUsage();
                yield 0;
            }
            default -> {
                System.err.println("未知命令: " + command);
                printUsage();
                yield 2;
            }
        };
    }

    /**
     * 校验生成的包是否自洽（引用完整、附件齐全）。
     */
    private static int validate(String[] args) throws IOException
    {
        Arguments arguments = Arguments.parse(args, Set.of("help"));
        Path target = Path.of(arguments.require("package"));

        List<Path> packages = listPackages(target);
        if (packages.isEmpty()) {
            System.err.println("在 " + target.toAbsolutePath() + " 下没有找到任何包（缺少 "
                + SpaceExporter.ENTITIES_FILE + "）");
            return 2;
        }

        boolean allValid = true;
        for (Path packageDirectory : packages) {
            System.out.printf("=== %s ===%n", packageDirectory.toAbsolutePath());
            ValidationResult result = PackageValidator.validate(packageDirectory);
            System.out.print(result.render());
            System.out.println();
            allValid &= result.valid();
        }

        return allValid ? 0 : 1;
    }

    /**
     * 目标既可以是单个包目录，也可以是包含多个包目录的根目录。
     */
    private static List<Path> listPackages(Path target) throws IOException
    {
        if (Files.isRegularFile(target.resolve(SpaceExporter.ENTITIES_FILE))) {
            return List.of(target);
        }
        if (!Files.isDirectory(target)) {
            return List.of();
        }
        try (var entries = Files.list(target)) {
            return entries
                .filter(Files::isDirectory)
                .filter(path -> Files.isRegularFile(path.resolve(SpaceExporter.ENTITIES_FILE)))
                .sorted()
                .toList();
        }
    }

    /**
     * 把一个或多个空间导出成导入器可读的包目录。
     */
    private static int export(String[] args) throws IOException, SQLException
    {
        Arguments arguments = Arguments.parse(args, Set.of("all", "no-attachments", "help"));

        ConfluenceHome home = resolveHome(arguments);

        Path outputRoot = Path.of(arguments.require("output"));
        boolean includeAttachments = !arguments.has("no-attachments");

        List<String> spaceKeys = resolveSpaceKeys(arguments, home);

        try (ConfluenceDb db = ConfluenceDb.open(home.dbConfig())) {
            System.out.println("已连接: " + home.dbConfig().describe());
            System.out.println("附件目录: " + home.attachmentsDir());
            System.out.println("输出目录: " + outputRoot.toAbsolutePath());
            System.out.println();

            SpaceExporter exporter = new SpaceExporter(db, home.attachmentsDir());
            List<ExportResult> results = new ArrayList<>();
            int failures = 0;
            for (String spaceKey : spaceKeys) {
                try {
                    ExportResult result = exporter.export(spaceKey, outputRoot, includeAttachments);
                    results.add(result);
                    printExportResult(result);
                    // 每导完一个空间就重写清单：中途中断时磁盘上始终是一份完整可读的清单
                    MigrationManifest.write(outputRoot, results);
                } catch (Exception e) {
                    // 单个空间失败不应中断整批迁移，记录后继续
                    failures++;
                    System.err.printf("空间 %s 导出失败: %s%n", spaceKey, e.getMessage());
                    if (Boolean.getBoolean("confluence.migration.debug")) {
                        e.printStackTrace(System.err);
                    }
                }
            }

            Path manifest = MigrationManifest.write(outputRoot, results);
            System.out.printf("%n已导出 %d 个空间，清单: %s%n", results.size(), manifest.toAbsolutePath());

            if (failures > 0) {
                System.err.printf("共 %d 个空间导出失败。%n", failures);
                return 1;
            }
        }

        return 0;
    }

    private static List<String> resolveSpaceKeys(Arguments arguments, ConfluenceHome home)
        throws IOException, SQLException
    {
        // 空间来源可以叠加：--all 取全部，--space/--spaces/--space-file 指定子集，
        // 最后统一减去 --exclude-space/--exclude-spaces。用 LinkedHashSet 去重并保持书写顺序。
        Set<String> keys = new LinkedHashSet<>();

        if (arguments.has("all")) {
            try (ConfluenceDb db = ConfluenceDb.open(home.dbConfig())) {
                keys.addAll(db.query("SELECT SPACEKEY FROM SPACES ORDER BY SPACEKEY", rs -> rs.getString(1)));
            }
            System.out.printf("按 --all 选出全部空间: %d 个%n", keys.size());
        }

        keys.addAll(arguments.getAll("space"));
        for (String value : arguments.getAll("spaces")) {
            keys.addAll(splitCommaSeparated(value));
        }
        for (String file : arguments.getAll("space-file")) {
            keys.addAll(readSpaceKeysFromFile(Path.of(file)));
        }

        if (keys.isEmpty()) {
            throw new IllegalArgumentException(
                "没有指定要导出的空间。使用 --space <KEY>（可重复）、--spaces <K1,K2>、"
                    + "--space-file <文件> 或 --all");
        }

        Set<String> excluded = new LinkedHashSet<>();
        excluded.addAll(arguments.getAll("exclude-space"));
        for (String value : arguments.getAll("exclude-spaces")) {
            excluded.addAll(splitCommaSeparated(value));
        }
        if (!excluded.isEmpty()) {
            keys.removeAll(excluded);
            System.out.printf("已排除 %d 个空间，剩余 %d 个%n", excluded.size(), keys.size());
        }

        if (keys.isEmpty()) {
            throw new IllegalArgumentException("排除之后没有剩下任何空间");
        }

        System.out.printf("待导出空间: %d 个%n", keys.size());
        return new ArrayList<>(keys);
    }

    static List<String> splitCommaSeparated(String value)
    {
        List<String> keys = new ArrayList<>();
        for (String part : value.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                keys.add(trimmed);
            }
        }
        return keys;
    }

    /**
     * 从文件读取空间 Key 列表。
     *
     * <p>兼容两种输入：纯文本每行一个 Key，以及体检命令产出的 CSV（取第一列、
     * 跳过表头，因此可以直接用 {@code --csv} 的产物）。空行与 {@code #} 开头的注释行被忽略。</p>
     */
    static List<String> readSpaceKeysFromFile(Path file) throws IOException
    {
        List<String> keys = new ArrayList<>();
        for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String trimmed = line.strip();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                continue;
            }
            String key = trimmed.contains(",") ? trimmed.substring(0, trimmed.indexOf(',')).strip() : trimmed;
            if (key.isEmpty() || "spaceKey".equals(key)) {
                continue;
            }
            keys.add(key);
        }
        return keys;
    }

    private static void printExportResult(ExportResult result)
    {
        System.out.printf("空间 %s:%n", result.spaceKey());
        System.out.printf("  包目录: %s%n", result.packageDirectory().toAbsolutePath());
        System.out.printf("  页面: %d  博客: %d  附件: %d (%.2f MiB)  用户: %d%n",
            result.pageCount(), result.blogPostCount(), result.attachmentCount(),
            result.attachmentBytes() / 1024.0 / 1024.0, result.userCount());
        for (String warning : result.warnings()) {
            System.out.printf("  ! %s%n", warning);
        }
    }

    /**
     * 采集并打印迁移体检数据。
     */
    private static int inventory(String[] args) throws IOException, SQLException
    {
        Arguments arguments = Arguments.parse(args, Set.of("help"));

        long thresholdBytes = arguments.getLong("threshold-mib", 500L) * MIB;
        long manyAttachments = arguments.getLong("many-attachments", 2000L);

        ConfluenceHome home = resolveHome(arguments);

        try (ConfluenceDb db = ConfluenceDb.open(home.dbConfig())) {
            System.out.println("已连接: " + home.dbConfig().describe());
            System.out.println("附件目录: " + home.attachmentsDir());
            System.out.println();

            InventoryReport report = new InventoryCollector(db, home.attachmentsDir()).collect();
            System.out.print(InventoryRenderer.renderText(report, thresholdBytes, manyAttachments));

            String csvPath = arguments.get("csv");
            if (csvPath != null) {
                Path target = Path.of(csvPath);
                Files.writeString(target,
                    InventoryRenderer.renderCsv(report, thresholdBytes, manyAttachments),
                    StandardCharsets.UTF_8);
                System.out.println("\nCSV 已写入: " + target.toAbsolutePath());
            }
        }

        return 0;
    }

    /**
     * 确定 Confluence Home 与数据库配置，允许用命令行参数覆盖。
     */
    private static ConfluenceHome resolveHome(Arguments arguments) throws IOException
    {
        String homePath = arguments.get("confluence-home");
        ConfluenceHome home = homePath == null
            ? new ConfluenceHome(null, null, null)
            : ConfluenceHome.read(Path.of(homePath));

        String jdbcUrl = arguments.get("jdbc-url");
        String username = arguments.get("username");
        String password = arguments.get("password");

        if (jdbcUrl == null && home.dbConfig() == null) {
            throw new IllegalArgumentException(
                "需要通过 --confluence-home 读取配置，或显式提供 --jdbc-url 与 --username");
        }
        if (jdbcUrl != null && username == null) {
            throw new IllegalArgumentException("提供了 --jdbc-url 时必须同时提供 --username");
        }

        DbConfig dbConfig = jdbcUrl != null
            ? new DbConfig(jdbcUrl, username, password == null ? "" : password)
            : new DbConfig(
                home.dbConfig().jdbcUrl(),
                home.dbConfig().username(),
                password == null ? home.dbConfig().password() : password);

        Path attachmentsDir = home.attachmentsDir();
        String overrideAttachments = arguments.get("attachments-dir");
        if (overrideAttachments != null) {
            attachmentsDir = Path.of(overrideAttachments);
        }
        if (attachmentsDir == null) {
            throw new IllegalArgumentException("无法确定附件目录，请提供 --attachments-dir");
        }

        return new ConfluenceHome(home.home(), dbConfig, attachmentsDir);
    }

    private static void printUsage()
    {
        System.out.println("""
            Confluence → XWiki 迁移工具（DB 直读，绕开 Confluence 导出引擎）

            用法:
              confluence-migration inventory [选项]
              confluence-migration export    [选项]
              confluence-migration validate  --package <路径>

            通用选项:
              --confluence-home <路径>   读取 <路径>/confluence.cfg.xml 获取数据库与附件目录配置
              --jdbc-url <url>           直接指定 JDBC 连接串（与 --confluence-home 二选一）
              --username <user>          数据库用户名
              --password <pass>          数据库密码（cfg 中密码被加密时使用）
              --attachments-dir <路径>   覆盖附件目录
              --help                     显示帮助

            inventory 选项:
              --threshold-mib <数值>     单次导出体积阈值，默认 500
              --many-attachments <数值>  附件数超过多少算"碎片多"，默认 2000
              --csv <路径>               额外输出 CSV 报表

            export 选项:
              --space <KEY>              指定空间，可重复书写
              --spaces <K1,K2,...>       逗号分隔的多个空间
              --space-file <路径>        从文件读空间列表（每行一个 Key；
                                         也可直接喂 inventory 产出的 CSV，取第一列）
              --all                      导出全部空间
              --exclude-space <KEY>      排除某空间，可重复书写
              --exclude-spaces <K1,K2>   逗号分隔的排除列表
              --output <路径>            输出根目录，实际包目录为 <输出根>/<SPACEKEY>
              --no-attachments           只导出内容与附件元数据，不复制附件文件

            validate 选项:
              --package <路径>           包目录，或包含多个包目录的根目录

            示例:
              confluence-migration inventory \\
                --confluence-home /data/confluence --csv /tmp/inventory.csv

              confluence-migration export \\
                --confluence-home /data/confluence --space ds --output /mnt/migration

              confluence-migration export \\
                --confluence-home /data/confluence --all \\
                --exclude-spaces ARCHIVE,TEST --output /mnt/migration

              confluence-migration validate --package /mnt/migration
            """);
    }
}
