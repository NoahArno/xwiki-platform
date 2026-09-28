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
package org.xwiki.contrib.confluence.migration.policy;

import java.util.List;

/**
 * 迁移范围的统一判定规则。
 *
 * <p>这条规则同时被<i>体检</i>（用于估算迁移体量与分片）和<i>导出</i>（用于决定写出哪些实体）
 * 使用。若两处各写一份，体检报出的体量就会与导出实际产出的内容不一致，
 * 进而导致分片阈值决策建立在错误数字上——所以这里集中定义一次。</p>
 *
 * <h2>为什么排除草稿与回收站</h2>
 * <ul>
 *   <li>导入器本身就会丢弃它们（{@code ConfluenceXMLPackage.mustIgnoreContent}
 *       的注释原文即 "Skip deleted, archived or draft pages"），导出了也是白导；</li>
 *   <li>草稿不是稳定的数据：Confluence 的清理任务会自行删除草稿及其附件，
 *       在本次开发过程中就实测到一整棵草稿子树（含 5 个附件）在数分钟内消失；</li>
 *   <li>草稿页可能没有空间归属（{@code SPACEID} 为 {@code NULL}），
 *       而这正是导入器注释里点名的已知问题。</li>
 * </ul>
 */
public final class MigrationPolicy
{
    /** 内容状态：草稿。 */
    public static final String STATUS_DRAFT = "draft";

    /** 内容状态：回收站。 */
    public static final String STATUS_TRASHED = "trashed";

    /**
     * 被排除的内容状态。
     *
     * <p>取值来自对真实 Confluence 7.3.3 实例的实测：{@code CONTENT.CONTENT_STATUS}
     * 与导出 XML 的 {@code contentStatus} 使用同一套词表（实测到 {@code current} 与
     * {@code draft}），因此无需做值映射。</p>
     */
    public static final List<String> EXCLUDED_STATUSES = List.of(STATUS_DRAFT, STATUS_TRASHED);

    private MigrationPolicy()
    {
        // constants only
    }

    /**
     * 判断某个内容状态是否应当从迁移中排除。
     *
     * @param contentStatus {@code CONTENT_STATUS} 的取值，可为 {@code null}
     * @return 是否排除
     */
    public static boolean isExcludedStatus(String contentStatus)
    {
        if (contentStatus == null) {
            return false;
        }
        return EXCLUDED_STATUSES.stream().anyMatch(status -> status.equalsIgnoreCase(contentStatus));
    }

    /**
     * 生成可直接嵌入 SQL 的排除状态列表，供 {@code CONTENT_STATUS NOT IN (...)} 使用。
     *
     * @return 形如 {@code 'draft', 'trashed'} 的字面量列表
     */
    public static String excludedStatusSqlLiterals()
    {
        return EXCLUDED_STATUSES.stream()
            .map(status -> "'" + status + "'")
            .reduce((left, right) -> left + ", " + right)
            .orElse("''");
    }
}
