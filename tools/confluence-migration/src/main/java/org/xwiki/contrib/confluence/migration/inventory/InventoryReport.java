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

import java.util.List;
import java.util.Map;

/**
 * 一次迁移体检的完整结果。
 *
 * @param attachmentStorageRows {@code ATTACHMENTDATA} 的行数；非 0 说明附件可能存在数据库里
 * @param contentStatusDistribution {@code CONTENT_STATUS} 的取值分布
 * @param contentTypeDistribution {@code CONTENTTYPE} 的取值分布
 * @param spaces 各空间的统计
 * @param warnings 采集过程中的告警（数据缺失、配置异常等）
 */
public record InventoryReport(
    long attachmentStorageRows,
    Map<String, Long> contentStatusDistribution,
    Map<String, Long> contentTypeDistribution,
    List<SpaceInventory> spaces,
    List<String> warnings)
{
    /**
     * @return 所有空间的可迁移页面（页面 + 博客）总数
     */
    public long totalMigratableContent()
    {
        return spaces.stream().mapToLong(SpaceInventory::migratableContentCount).sum();
    }

    /**
     * @return 所有空间的附件总数
     */
    public long totalAttachments()
    {
        return spaces.stream().mapToLong(SpaceInventory::attachmentCount).sum();
    }

    /**
     * @return 所有空间的附件总体积（字节）
     */
    public long totalAttachmentBytes()
    {
        return spaces.stream().mapToLong(SpaceInventory::attachmentBytes).sum();
    }

    /**
     * @return 所有空间中草稿内容的总数，这些内容按既定策略不迁移
     */
    public long totalDrafts()
    {
        return spaces.stream().mapToLong(SpaceInventory::draftCount).sum();
    }

    /**
     * @return 所有空间中回收站内容的总数，这些内容按既定策略不迁移
     */
    public long totalTrashed()
    {
        return spaces.stream().mapToLong(SpaceInventory::trashedCount).sum();
    }
}
