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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.xwiki.contrib.confluence.migration.inventory.SpaceInventory.ExportClass;

/**
 * 把体检结果渲染成人类可读的报表或 CSV。
 *
 * <p>纯函数式：只负责格式化，不做 IO，便于测试。</p>
 */
public final class InventoryRenderer
{
    private InventoryRenderer()
    {
        // utility class
    }

    /**
     * 渲染为便于人工审阅的报表。
     *
     * @param report 体检结果
     * @param thresholdBytes 分片体积阈值（字节）
     * @param manyAttachmentsThreshold 附件数超过多少算"碎片多"
     * @return 报表文本
     */
    public static String renderText(InventoryReport report, long thresholdBytes, long manyAttachmentsThreshold)
    {
        StringBuilder out = new StringBuilder();

        out.append("=== 内容状态分布 (CONTENT_STATUS) ===\n");
        out.append("（回收站/草稿排除规则的依据：这里出现什么值，生成器就要排除什么值）\n");
        report.contentStatusDistribution()
            .forEach((status, count) -> out.append(String.format("  %-12s %d%n", status, count)));

        out.append("\n=== 内容类型分布 (CONTENTTYPE) ===\n");
        report.contentTypeDistribution()
            .forEach((type, count) -> out.append(String.format("  %-18s %d%n", type, count)));

        out.append("\n=== 附件存储方式 ===\n");
        out.append(String.format("  ATTACHMENTDATA 行数: %d%n", report.attachmentStorageRows()));
        out.append(report.attachmentStorageRows() == 0
            ? "  => 附件存放在文件系统，路径可由规则推导\n"
            : "  => 警告：附件可能存放在数据库中，本工具的文件系统推导可能不完整\n");

        out.append(String.format("%n=== 空间概览（共 %d 个）===\n", report.spaces().size()));
        out.append(String.format("  %-14s %-22s %-9s %6s %6s %7s %12s  %s%n",
            "空间Key", "名称", "状态", "页面", "博客", "附件", "附件体积", "分类"));

        for (SpaceInventory space : InventoryCollector.byAttachmentSizeDescending(report.spaces())) {
            out.append(String.format("  %-14s %-22s %-9s %6d %6d %7d %9.2f MiB  %s%n",
                truncate(space.spaceKey(), 14),
                truncate(space.spaceName(), 22),
                truncate(space.spaceStatus(), 9),
                space.pageCount(),
                space.blogPostCount(),
                space.attachmentCount(),
                space.attachmentMiB(),
                space.classify(thresholdBytes, manyAttachmentsThreshold).label()));
        }

        out.append("\n=== 分类汇总 ===\n");
        for (Map.Entry<ExportClass, Long> entry : countByClass(report, thresholdBytes, manyAttachmentsThreshold)
            .entrySet()) {
            out.append(String.format("  %-10s %d 个空间%n", entry.getKey().label(), entry.getValue()));
        }

        out.append("\n=== 汇总 ===\n");
        out.append(String.format("  可迁移页面(页面+博客): %d%n", report.totalMigratableContent()));
        out.append(String.format("  附件数量: %d，总体积: %.2f MiB%n",
            report.totalAttachments(), report.totalAttachmentBytes() / 1024.0 / 1024.0));
        out.append(String.format("  草稿(按策略不迁移): %d%n", report.totalDrafts()));
        out.append(String.format("  回收站(按策略不迁移): %d%n", report.totalTrashed()));

        if (!report.warnings().isEmpty()) {
            out.append("\n=== 告警 ===\n");
            for (String warning : report.warnings()) {
                out.append("  ! ").append(warning).append('\n');
            }
        }

        return out.toString();
    }

    /**
     * 渲染为 CSV，便于在电子表格里排序与筛选。
     *
     * @param report 体检结果
     * @param thresholdBytes 分片体积阈值（字节）
     * @param manyAttachmentsThreshold 附件数超过多少算"碎片多"
     * @return CSV 文本（含表头）
     */
    public static String renderCsv(InventoryReport report, long thresholdBytes, long manyAttachmentsThreshold)
    {
        StringBuilder out = new StringBuilder();
        out.append("spaceKey,spaceName,spaceStatus,spaceId,pages,blogPosts,attachments,")
            .append("draftCount,trashedCount,attachmentBytes,attachmentMiB,largestAttachmentBytes,exportClass\n");

        for (SpaceInventory space : InventoryCollector.byAttachmentSizeDescending(report.spaces())) {
            out.append(csv(space.spaceKey())).append(',')
                .append(csv(space.spaceName())).append(',')
                .append(csv(space.spaceStatus())).append(',')
                .append(space.spaceId()).append(',')
                .append(space.pageCount()).append(',')
                .append(space.blogPostCount()).append(',')
                .append(space.attachmentCount()).append(',')
                .append(space.draftCount()).append(',')
                .append(space.trashedCount()).append(',')
                .append(space.attachmentBytes()).append(',')
                .append(String.format(Locale.ROOT, "%.2f", space.attachmentMiB())).append(',')
                .append(space.largestAttachmentBytes()).append(',')
                .append(space.classify(thresholdBytes, manyAttachmentsThreshold).label())
                .append('\n');
        }

        return out.toString();
    }

    private static Map<ExportClass, Long> countByClass(
        InventoryReport report, long thresholdBytes, long manyAttachmentsThreshold)
    {
        Map<ExportClass, Long> counts = new LinkedHashMap<>();
        for (ExportClass exportClass : ExportClass.values()) {
            counts.put(exportClass, 0L);
        }
        for (SpaceInventory space : report.spaces()) {
            counts.merge(space.classify(thresholdBytes, manyAttachmentsThreshold), 1L, Long::sum);
        }
        return counts;
    }

    private static String truncate(String value, int maxLength)
    {
        if (value == null) {
            return "";
        }
        if (value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, Math.max(0, maxLength - 1)) + "…";
    }

    private static String csv(String value)
    {
        if (value == null) {
            return "";
        }
        if (value.indexOf(',') < 0 && value.indexOf('"') < 0 && value.indexOf('\n') < 0) {
            return value;
        }
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    /**
     * 便于调用方直接取用分类计数。
     *
     * @param report 体检结果
     * @param thresholdBytes 分片体积阈值（字节）
     * @param manyAttachmentsThreshold 附件数阈值
     * @return 分类计数
     */
    public static Map<ExportClass, Long> summarize(
        InventoryReport report, long thresholdBytes, long manyAttachmentsThreshold)
    {
        return countByClass(report, thresholdBytes, manyAttachmentsThreshold);
    }
}
