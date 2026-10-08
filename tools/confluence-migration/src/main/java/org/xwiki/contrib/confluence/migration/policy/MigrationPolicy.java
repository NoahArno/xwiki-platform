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

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

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
 *
 * <h2>为什么排除列表里同时有 trashed 和 deleted</h2>
 * <p>开发所用的实例里只有 {@code current} 与 {@code draft} 两种取值，<strong>从未观测到回收站内容</strong>，
 * 因此"回收站到底叫什么"无法从该实例得到答案。而 XWiki 导入器的源码检查的是字面量
 * {@code "deleted"}（{@code mustIgnoreContent} 与附件过滤两处），说明 Confluence 产出的词表里
 * 存在 {@code deleted}。为稳妥起见两个都排除——多排除一个不存在的值不会有副作用，
 * 少排除一个则会放回收站内容进包。</p>
 *
 * <p>更关键的是不能只靠一份"已知坏值"清单：清单一定会有盲区，而盲区的后果是静默的。
 * 因此 {@link #isKnownStatus(String)} 用于在导出时把<strong>任何未预期的取值显式告警</strong>，
 * 而不是让它悄悄通过。</p>
 */
public final class MigrationPolicy
{
    /** 内容状态：草稿。 */
    public static final String STATUS_DRAFT = "draft";

    /** 内容状态：回收站（导入器源码里使用的字面量）。 */
    public static final String STATUS_DELETED = "deleted";

    /** 内容状态：回收站的另一种可能取值。 */
    public static final String STATUS_TRASHED = "trashed";

    /** 内容状态：已归档。 */
    public static final String STATUS_ARCHIVED = "archived";

    /** 内容状态：正常内容。这是唯一应当被迁移的取值。 */
    public static final String STATUS_CURRENT = "current";

    /**
     * 被排除的内容状态。
     *
     * <p>注意：这是一份"已知坏值"清单，必然存在盲区。因此绝不能只依赖它——
     * 导出时会用 {@link #isKnownStatus(String)} 检查是否出现了清单之外的取值并告警。</p>
     */
    public static final List<String> EXCLUDED_STATUSES =
        List.of(STATUS_DRAFT, STATUS_TRASHED, STATUS_DELETED, STATUS_ARCHIVED);

    /**
     * 已知的全部取值（应当迁移的 + 应当排除的）。
     *
     * <p>出现此集合之外的取值时，说明源库的词表与预期不符，必须人工确认而不是静默处理。</p>
     */
    public static final Set<String> KNOWN_STATUSES = knownStatuses();

    private MigrationPolicy()
    {
        // constants only
    }

    private static Set<String> knownStatuses()
    {
        Set<String> known = new LinkedHashSet<>();
        known.add(STATUS_CURRENT);
        known.add(null);
        known.addAll(EXCLUDED_STATUSES);
        return known;
    }

    /**
     * 判断某个内容状态是否应当从迁移中排除。
     *
     * @param contentStatus {@code CONTENT_STATUS} 的取值，可为 {@code null}
     * @return 是否排除；{@code null} 视为可迁移
     */
    public static boolean isExcludedStatus(String contentStatus)
    {
        if (contentStatus == null) {
            return false;
        }
        return EXCLUDED_STATUSES.stream().anyMatch(status -> status.equalsIgnoreCase(contentStatus));
    }

    /**
     * 判断某个内容状态是否在已知词表内。
     *
     * <p>返回 {@code false} 意味着源库出现了预期之外的取值：既不能假定它该迁移，
     * 也不能假定它该排除。调用方应当把这个事实<strong>告警出去</strong>，交由人工确认。</p>
     *
     * @param contentStatus {@code CONTENT_STATUS} 的取值，可为 {@code null}
     * @return 是否已知
     */
    public static boolean isKnownStatus(String contentStatus)
    {
        if (contentStatus == null) {
            return true;
        }
        return KNOWN_STATUSES.stream()
            .filter(status -> status != null)
            .anyMatch(status -> status.equalsIgnoreCase(contentStatus));
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
