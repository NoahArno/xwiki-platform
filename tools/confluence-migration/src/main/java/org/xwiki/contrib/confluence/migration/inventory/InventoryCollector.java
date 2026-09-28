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
package org.xwiki.contrib.confluence.migration.inventory;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.xwiki.contrib.confluence.migration.attach.AttachmentPathRule;
import org.xwiki.contrib.confluence.migration.db.ConfluenceDb;
import org.xwiki.contrib.confluence.migration.policy.MigrationPolicy;

/**
 * 采集 Confluence 的迁移体检数据。
 *
 * <p>产出的三个关键输入：</p>
 * <ol>
 *   <li>{@code CONTENT_STATUS} 的真实取值分布 —— 决定回收站/草稿的排除规则是否正确；</li>
 *   <li>每个空间的页面数、附件数、附件体积 —— 决定分片阈值与装箱策略；</li>
 *   <li>空间分类 —— 用于判断"为全部空间做 DB 直读生成器"的投入产出比是否成立。</li>
 * </ol>
 *
 * <p><strong>口径一致性</strong>：这里统计的页面与附件，与 {@code SpaceExporter} 实际导出的内容
 * 使用同一套排除规则（{@link MigrationPolicy}）。草稿页上的附件不计入附件体积，
 * 因为导出时它们会被跳过——体检虚报体量会让分片决策建立在错误数字上。</p>
 */
public final class InventoryCollector
{
    /** Confluence 中内容类型为页面的取值。 */
    private static final String TYPE_PAGE = "PAGE";

    /** Confluence 中内容类型为博客的取值。 */
    private static final String TYPE_BLOG_POST = "BLOGPOST";

    /** Confluence 中内容类型为附件的取值。 */
    private static final String TYPE_ATTACHMENT = "ATTACHMENT";

    private final ConfluenceDb db;

    private final Path attachmentsDir;

    /**
     * @param db 数据库访问对象
     * @param attachmentsDir 附件在文件系统上的目录
     */
    public InventoryCollector(ConfluenceDb db, Path attachmentsDir)
    {
        this.db = db;
        this.attachmentsDir = attachmentsDir;
    }

    /**
     * @return 体检报告
     * @throws SQLException 数据库查询失败时
     */
    public InventoryReport collect() throws SQLException
    {
        List<String> warnings = new ArrayList<>();

        Map<String, Long> statusDistribution = countBy("CONTENT_STATUS");
        Map<String, Long> typeDistribution = countBy("CONTENTTYPE");

        long attachmentStorageRows = countAttachmentStorageRows(warnings);

        Map<Long, Set<Long>> migratableContentBySpace = collectMigratableContentIds();
        Map<Long, SpaceCounts> countsBySpace = collectCountsBySpace();
        Map<Long, AttachmentStats> attachmentStatsBySpace =
            collectAttachmentStats(migratableContentBySpace, warnings);

        List<SpaceInventory> spaces = new ArrayList<>();
        for (SpaceRow space : db.query(
            "SELECT SPACEID, SPACEKEY, SPACENAME, SPACESTATUS FROM SPACES ORDER BY SPACEKEY",
            rs -> new SpaceRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4)))) {

            SpaceCounts counts = countsBySpace.getOrDefault(space.spaceId(), new SpaceCounts());
            AttachmentStats attachmentStats =
                attachmentStatsBySpace.getOrDefault(space.spaceId(), new AttachmentStats());

            spaces.add(new SpaceInventory(
                space.spaceId(),
                space.spaceKey(),
                space.spaceName(),
                space.spaceStatus(),
                counts.pages,
                counts.blogPosts,
                counts.attachments,
                counts.drafts,
                counts.trashed,
                attachmentStats.totalBytes,
                attachmentStats.largestBytes));
        }

        return new InventoryReport(
            attachmentStorageRows, statusDistribution, typeDistribution, spaces, warnings);
    }

    private Map<String, Long> countBy(String column) throws SQLException
    {
        String sql = "SELECT " + column + ", COUNT(*) FROM CONTENT GROUP BY " + column + " ORDER BY " + column;
        Map<String, Long> distribution = new TreeMap<>();
        for (Map.Entry<String, Long> entry : db.query(sql, rs -> Map.entry(
            rs.getString(1) == null ? "(null)" : rs.getString(1), rs.getLong(2)))) {
            distribution.put(entry.getKey(), entry.getValue());
        }
        return distribution;
    }

    /**
     * 统计 {@code ATTACHMENTDATA} 的行数。
     *
     * <p>该表非空意味着附件可能存放在数据库里（而不是文件系统），此时本工具基于文件系统的
     * 路径推导会不完整，必须明确告警而不是静默漏掉附件。</p>
     */
    private long countAttachmentStorageRows(List<String> warnings) throws SQLException
    {
        Long rows = db.querySingle("SELECT COUNT(*) FROM ATTACHMENTDATA", rs -> rs.getLong(1));
        long count = rows == null ? 0 : rows;
        if (count > 0) {
            warnings.add(String.format(
                "ATTACHMENTDATA 表有 %d 行非空数据，说明该实例可能把附件存放在数据库中。"
                    + "本工具基于文件系统推导附件路径，体积统计与附件导出可能不完整。",
                count));
        }
        return count;
    }

    /**
     * 收集每个空间里"会被真正导出"的内容 ID（页面与博客）。
     *
     * <p>这些 ID 用于判断附件是否还有归属——挂在草稿页上的附件不会被导出，
     * 因此也不该计入体积。</p>
     */
    private Map<Long, Set<Long>> collectMigratableContentIds() throws SQLException
    {
        String sql = "SELECT SPACEID, CONTENTID FROM CONTENT "
            + "WHERE SPACEID IS NOT NULL AND CONTENTTYPE IN ('PAGE', 'BLOGPOST') "
            + "AND (CONTENT_STATUS IS NULL OR CONTENT_STATUS NOT IN ("
            + MigrationPolicy.excludedStatusSqlLiterals() + "))";

        Map<Long, Set<Long>> bySpace = new LinkedHashMap<>();
        for (ContentIdRow row : db.query(sql, rs -> new ContentIdRow(rs.getLong(1), rs.getLong(2)))) {
            bySpace.computeIfAbsent(row.spaceId(), key -> new LinkedHashSet<>()).add(row.contentId());
        }
        return bySpace;
    }

    private Map<Long, SpaceCounts> collectCountsBySpace() throws SQLException
    {
        Map<Long, SpaceCounts> counts = new LinkedHashMap<>();
        String sql = "SELECT SPACEID, CONTENTTYPE, CONTENT_STATUS, COUNT(*) FROM CONTENT "
            + "WHERE SPACEID IS NOT NULL GROUP BY SPACEID, CONTENTTYPE, CONTENT_STATUS";

        for (CountRow row : db.query(sql, rs -> new CountRow(
            rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4)))) {

            counts.computeIfAbsent(row.spaceId(), key -> new SpaceCounts()).add(row);
        }
        return counts;
    }

    private Map<Long, AttachmentStats> collectAttachmentStats(
        Map<Long, Set<Long>> migratableContentBySpace, List<String> warnings) throws SQLException
    {
        Map<Long, AttachmentStats> stats = new LinkedHashMap<>();
        int missing = 0;
        int orphaned = 0;
        List<String> missingExamples = new ArrayList<>();

        String sql = "SELECT CONTENTID, SPACEID, PAGEID, VERSION, CONTENT_STATUS FROM CONTENT "
            + "WHERE CONTENTTYPE = ?";
        for (AttachmentRow row : db.query(sql, rs -> new AttachmentRow(
            rs.getLong(1),
            rs.getObject(2) == null ? null : rs.getLong(2),
            rs.getLong(3),
            rs.getInt(4),
            rs.getString(5)), TYPE_ATTACHMENT)) {

            if (MigrationPolicy.isExcludedStatus(row.contentStatus())) {
                continue;
            }
            // 容器不在可迁移内容里（例如挂在草稿页上）的附件不会被导出，不计入体积
            Set<Long> migratableContent = migratableContentBySpace.get(row.spaceId());
            if (migratableContent == null || !migratableContent.contains(row.containerId())) {
                orphaned++;
                continue;
            }

            AttachmentStats spaceStats = stats.computeIfAbsent(row.spaceId(), key -> new AttachmentStats());

            Path relative = Path.of(AttachmentPathRule.relativePath(
                row.spaceId(), row.containerId(), row.attachmentId(), row.version()));

            // 一个附件可能有多个版本的独立文件，因此统计其所在目录下的全部文件
            Path attachmentFolder = attachmentsDir.resolve(relative).getParent();
            VersionSize size = measureVersions(attachmentFolder);
            if (size.fileCount == 0) {
                missing++;
                if (missingExamples.size() < 5) {
                    missingExamples.add(attachmentFolder.toString());
                }
                continue;
            }

            spaceStats.totalBytes += size.totalBytes;
            spaceStats.largestBytes = Math.max(spaceStats.largestBytes, size.largestBytes);
        }

        if (orphaned > 0) {
            warnings.add(String.format(
                "有 %d 个附件的所属页面不在迁移范围内（多为草稿页），已按既定策略跳过，不计入体积。",
                orphaned));
        }
        if (missing > 0) {
            warnings.add(String.format(
                "有 %d 个附件在文件系统上找不到对应文件（示例: %s）。"
                    + "这些附件不会被导出，请确认附件目录配置是否正确。",
                missing, String.join(", ", missingExamples)));
        }

        return stats;
    }

    private static VersionSize measureVersions(Path attachmentFolder)
    {
        VersionSize size = new VersionSize();
        if (!Files.isDirectory(attachmentFolder)) {
            return size;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(attachmentFolder)) {
            for (Path file : stream) {
                if (Files.isRegularFile(file)) {
                    long bytes = Files.size(file);
                    size.totalBytes += bytes;
                    size.largestBytes = Math.max(size.largestBytes, bytes);
                    size.fileCount++;
                }
            }
        } catch (IOException e) {
            // 读不到就当作不存在，由调用方计入"缺失附件"并告警
            return new VersionSize();
        }
        return size;
    }

    private record SpaceRow(long spaceId, String spaceKey, String spaceName, String spaceStatus)
    {
    }

    private record CountRow(long spaceId, String contentType, String contentStatus, long count)
    {
    }

    private record ContentIdRow(long spaceId, long contentId)
    {
    }

    private record AttachmentRow(long attachmentId, Long spaceId, long containerId, int version,
        String contentStatus)
    {
    }

    /** 一个空间下按类型/状态的计数累加器。 */
    private static final class SpaceCounts
    {
        private long pages;

        private long blogPosts;

        private long attachments;

        private long drafts;

        private long trashed;

        private void add(CountRow row)
        {
            // 被排除的状态既不计入可迁移内容，也不计入附件数，否则体检体量会大于导出实际产出
            if (MigrationPolicy.isExcludedStatus(row.contentStatus())) {
                if (MigrationPolicy.STATUS_DRAFT.equalsIgnoreCase(row.contentStatus())) {
                    drafts += row.count();
                } else {
                    trashed += row.count();
                }
                return;
            }

            if (TYPE_PAGE.equals(row.contentType())) {
                pages += row.count();
            } else if (TYPE_BLOG_POST.equals(row.contentType())) {
                blogPosts += row.count();
            } else if (TYPE_ATTACHMENT.equals(row.contentType())) {
                attachments += row.count();
            }
        }
    }

    private static final class AttachmentStats
    {
        private long totalBytes;

        private long largestBytes;
    }

    private static final class VersionSize
    {
        private long totalBytes;

        private long largestBytes;

        private int fileCount;
    }

    /**
     * @param spaces 空间列表
     * @return 按附件体积从大到小排序的副本，便于人工审阅"哪些是大空间"
     */
    public static List<SpaceInventory> byAttachmentSizeDescending(List<SpaceInventory> spaces)
    {
        List<SpaceInventory> sorted = new ArrayList<>(spaces);
        sorted.sort(Comparator.comparingLong(SpaceInventory::attachmentBytes).reversed());
        return sorted;
    }
}
