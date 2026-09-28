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

/**
 * 单个 Confluence 空间的体检结果。
 *
 * @param spaceId 空间 ID
 * @param spaceKey 空间 Key
 * @param spaceName 空间名称
 * @param spaceStatus 空间状态（{@code CURRENT} / {@code ARCHIVED}）
 * @param pageCount 页面数
 * @param blogPostCount 博客数
 * @param attachmentCount 附件数
 * @param draftCount 草稿内容数（导出时应排除）
 * @param trashedCount 回收站内容数（导出时应排除）
 * @param attachmentBytes 附件总字节数（来自文件系统，非数据库）
 * @param largestAttachmentBytes 单个最大附件的字节数
 */
public record SpaceInventory(
    long spaceId,
    String spaceKey,
    String spaceName,
    String spaceStatus,
    long pageCount,
    long blogPostCount,
    long attachmentCount,
    long draftCount,
    long trashedCount,
    long attachmentBytes,
    long largestAttachmentBytes)
{
    private static final double BYTES_PER_MIB = 1024.0 * 1024.0;

    /**
     * 空间的导出处理方式。
     */
    public enum ExportClass
    {
        /** 没有可导出的内容。 */
        EMPTY("空"),
        /** 预计体积在阈值内，可一次导完。 */
        SINGLE_PASS("直接导"),
        /** 超过阈值，需要拆分。 */
        NEEDS_SHARDING("要分片"),
        /** 附件体积不大但附件数极多，逐个文件处理可能成为瓶颈。 */
        MANY_SMALL_ATTACHMENTS("附件碎片多");

        private final String label;

        ExportClass(String label)
        {
            this.label = label;
        }

        /**
         * @return 中文标签，用于报表
         */
        public String label()
        {
            return label;
        }
    }

    /**
     * @return 需要迁移的页面与博客总数（不含草稿/回收站）
     */
    public long migratableContentCount()
    {
        return pageCount + blogPostCount;
    }

    /**
     * @return 附件总体积（MiB）
     */
    public double attachmentMiB()
    {
        return attachmentBytes / BYTES_PER_MIB;
    }

    /**
     * 判断该空间应当如何处理。
     *
     * @param thresholdBytes 单次导出的体积阈值（字节）
     * @param manyAttachmentsThreshold 附件数达到多少算"碎片多"
     * @return 处理方式
     */
    public ExportClass classify(long thresholdBytes, long manyAttachmentsThreshold)
    {
        if (migratableContentCount() == 0 && attachmentCount == 0) {
            return ExportClass.EMPTY;
        }
        if (attachmentBytes > thresholdBytes) {
            return ExportClass.NEEDS_SHARDING;
        }
        if (attachmentCount >= manyAttachmentsThreshold) {
            return ExportClass.MANY_SMALL_ATTACHMENTS;
        }
        return ExportClass.SINGLE_PASS;
    }
}
