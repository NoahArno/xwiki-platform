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
package org.xwiki.contrib.confluence.migration.export;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.xwiki.contrib.confluence.migration.db.ConfluenceDb;
import org.xwiki.contrib.confluence.migration.policy.MigrationPolicy;

/**
 * 从 Confluence 数据库读取导出所需的数据。
 *
 * <p>只读，且全部 SQL 集中在这里，便于按 Confluence 版本差异做调整。</p>
 *
 * <p><strong>一致性</strong>：本类不做任何事务控制。调用方应当对数据库快照
 * （例如 {@code mysqldump --single-transaction} 恢复出来的临时实例）运行导出，
 * 否则导出期间源数据变化会导致包内部引用自相矛盾——而且这种错误是静默的。</p>
 */
public final class ConfluenceLoader
{
    /** 单条 {@code IN (...)} 语句最多绑定多少个 ID，避免超出驱动的占位符上限。 */
    private static final int CHUNK_SIZE = 500;

    private final ConfluenceDb db;

    /**
     * @param db 数据库访问对象
     */
    public ConfluenceLoader(ConfluenceDb db)
    {
        this.db = db;
    }

    // ---------------------------------------------------------------- 行模型

    /** 空间行。 */
    public record SpaceRow(long id, String name, String key, String lowerKey, Long descriptionId, Long homePageId,
        String creator, LocalDateTime creationDate, String lastModifier, LocalDateTime lastModDate,
        String spaceType, String spaceStatus)
    {
    }

    /** 内容行（页面、博客、附件、评论、空间描述共用）。 */
    public record ContentRow(long id, String contentType, String title, String lowerTitle, int hibernateVersion,
        int version, String creator, LocalDateTime creationDate, String lastModifier, LocalDateTime lastModDate,
        String versionComment, String contentStatus, Long containerId, Long spaceId, Integer position, Long parentId)
    {
    }

    /** 正文行。 */
    public record BodyRow(long id, long contentId, String body, int bodyTypeId)
    {
    }

    /** 内容属性行。 */
    public record PropertyRow(long id, long contentId, String name, String stringValue, Long longValue,
        LocalDateTime dateValue)
    {
    }

    /** 标签行。 */
    public record LabelRow(long id, String name, String owner, String namespace, LocalDateTime creationDate,
        LocalDateTime lastModDate)
    {
    }

    /** 标签与内容的关联行。 */
    public record LabellingRow(long id, long labelId, long contentId, String owner, LocalDateTime creationDate,
        LocalDateTime lastModDate, Long labelableId, String labelableType)
    {
    }

    /** 页面祖先关系行。 */
    public record AncestorRow(long descendantId, int position, long ancestorId)
    {
    }

    // ---------------------------------------------------------------- 查询

    /**
     * 按 Key 读取空间。
     *
     * @param spaceKey 空间 Key
     * @return 空间行；不存在时返回 {@code null}
     * @throws SQLException 查询失败时
     */
    public SpaceRow loadSpace(String spaceKey) throws SQLException
    {
        String sql = "SELECT SPACEID, SPACENAME, SPACEKEY, LOWERSPACEKEY, SPACEDESCID, HOMEPAGE, "
            + "CREATOR, CREATIONDATE, LASTMODIFIER, LASTMODDATE, SPACETYPE, SPACESTATUS "
            + "FROM SPACES WHERE SPACEKEY = ?";

        return db.querySingle(sql, rs -> new SpaceRow(
            rs.getLong("SPACEID"),
            rs.getString("SPACENAME"),
            rs.getString("SPACEKEY"),
            rs.getString("LOWERSPACEKEY"),
            nullableLong(rs, "SPACEDESCID"),
            nullableLong(rs, "HOMEPAGE"),
            rs.getString("CREATOR"),
            toLocalDateTime(rs.getTimestamp("CREATIONDATE")),
            rs.getString("LASTMODIFIER"),
            toLocalDateTime(rs.getTimestamp("LASTMODDATE")),
            rs.getString("SPACETYPE"),
            rs.getString("SPACESTATUS")), spaceKey);
    }

    /**
     * 读取一个空间内 {@code CONTENT_STATUS} 的取值分布。
     *
     * <p>用于在导出前发现预期之外的状态取值。排除规则是一份"已知坏值"清单，必然有盲区；
     * 盲区的后果是静默的（要么漏排回收站内容，要么漏掉本该迁移的内容），
     * 因此必须把实际出现的取值摆到台面上核对，而不是假定清单是完备的。</p>
     *
     * @param spaceId 空间 ID
     * @return 状态取值到数量的映射，按取值排序
     * @throws SQLException 查询失败时
     */
    public Map<String, Long> loadStatusDistribution(long spaceId) throws SQLException
    {
        String sql = "SELECT CONTENT_STATUS, COUNT(*) FROM CONTENT WHERE SPACEID = ? "
            + "GROUP BY CONTENT_STATUS ORDER BY CONTENT_STATUS";

        Map<String, Long> distribution = new LinkedHashMap<>();
        for (Map.Entry<String, Long> entry : db.query(sql, rs -> Map.entry(
            rs.getString(1) == null ? "(null)" : rs.getString(1), rs.getLong(2)), spaceId)) {
            distribution.put(entry.getKey(), entry.getValue());
        }
        return distribution;
    }

    /**
     * 读取一个空间下需要导出的内容行（已排除草稿与回收站）。
     *
     * <p>评论的 {@code SPACEID} 可能为空，因此评论需要按容器单独读取，见
     * {@link #loadCommentsFor(Collection)}。</p>
     *
     * @param spaceId 空间 ID
     * @return 内容行，按 ID 升序
     * @throws SQLException 查询失败时
     */
    public List<ContentRow> loadContents(long spaceId) throws SQLException
    {
        String sql = "SELECT c.CONTENTID, c.CONTENTTYPE, c.TITLE, c.LOWERTITLE, c.HIBERNATEVERSION, c.VERSION, "
            + "c.CREATOR, c.CREATIONDATE, c.LASTMODIFIER, c.LASTMODDATE, c.VERSIONCOMMENT, c.CONTENT_STATUS, "
            + "c.PAGEID, c.SPACEID, c.CHILD_POSITION, c.PARENTID "
            + "FROM CONTENT c WHERE c.SPACEID = ? AND c.CONTENTTYPE IN ('PAGE', 'BLOGPOST', 'ATTACHMENT', "
            + "'SPACEDESCRIPTION') AND (c.CONTENT_STATUS IS NULL OR c.CONTENT_STATUS NOT IN ("
            + MigrationPolicy.excludedStatusSqlLiterals() + ")) ORDER BY c.CONTENTID";

        return db.query(sql, ConfluenceLoader::mapContent, spaceId);
    }

    /**
     * 按容器批量读取评论（评论可能没有 {@code SPACEID}）。
     *
     * @param containerIds 容器（页面/博客/附件）的内容 ID
     * @return 评论行
     * @throws SQLException 查询失败时
     */
    public List<ContentRow> loadCommentsFor(Collection<Long> containerIds) throws SQLException
    {
        if (containerIds.isEmpty()) {
            return List.of();
        }
        String tail = " AND c.CONTENTTYPE = 'COMMENT' ORDER BY c.CONTENTID";
        return queryByChunk(
            "SELECT c.CONTENTID, c.CONTENTTYPE, c.TITLE, c.LOWERTITLE, c.HIBERNATEVERSION, c.VERSION, "
                + "c.CREATOR, c.CREATIONDATE, c.LASTMODIFIER, c.LASTMODDATE, c.VERSIONCOMMENT, c.CONTENT_STATUS, "
                + "c.PAGEID, c.SPACEID, c.CHILD_POSITION, c.PARENTID FROM CONTENT c WHERE c.PAGEID IN (",
            ")", tail, containerIds, ConfluenceLoader::mapContent);
    }

    /**
     * 批量读取正文。
     *
     * @param contentIds 内容 ID
     * @return 内容 ID 到正文列表的映射
     * @throws SQLException 查询失败时
     */
    public Map<Long, List<BodyRow>> loadBodies(Collection<Long> contentIds) throws SQLException
    {
        List<BodyRow> rows = queryByChunk(
            "SELECT BODYCONTENTID, CONTENTID, BODY, BODYTYPEID FROM BODYCONTENT WHERE CONTENTID IN (",
            ")", " ORDER BY BODYCONTENTID", contentIds,
            rs -> new BodyRow(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getInt(4)));
        return groupBy(rows, BodyRow::contentId);
    }

    /**
     * 批量读取内容属性。
     *
     * @param contentIds 内容 ID
     * @return 内容 ID 到属性列表的映射
     * @throws SQLException 查询失败时
     */
    public Map<Long, List<PropertyRow>> loadProperties(Collection<Long> contentIds) throws SQLException
    {
        List<PropertyRow> rows = queryByChunk(
            "SELECT PROPERTYID, CONTENTID, PROPERTYNAME, STRINGVAL, LONGVAL, DATEVAL "
                + "FROM CONTENTPROPERTIES WHERE CONTENTID IN (",
            ")", " ORDER BY PROPERTYID", contentIds,
            rs -> new PropertyRow(rs.getLong(1), rs.getLong(2), rs.getString(3), rs.getString(4),
                nullableLong(rs, 5), toLocalDateTime(rs.getTimestamp(6))));
        return groupBy(rows, PropertyRow::contentId);
    }

    /**
     * 批量读取页面祖先关系。
     *
     * @param descendantIds 后代内容 ID
     * @return 后代 ID 到祖先列表的映射，按位置升序
     * @throws SQLException 查询失败时
     */
    public Map<Long, List<AncestorRow>> loadAncestors(Collection<Long> descendantIds) throws SQLException
    {
        List<AncestorRow> rows = queryByChunk(
            "SELECT DESCENDENTID, ANCESTORPOSITION, ANCESTORID FROM CONFANCESTORS WHERE DESCENDENTID IN (",
            ")", " ORDER BY DESCENDENTID, ANCESTORPOSITION", descendantIds,
            rs -> new AncestorRow(rs.getLong(1), rs.getInt(2), rs.getLong(3)));
        return groupBy(rows, AncestorRow::descendantId);
    }

    /**
     * 读取空间内内容用到的标签与关联。
     *
     * @param contentIds 内容 ID
     * @return 标签关联信息
     * @throws SQLException 查询失败时
     */
    public LabellingData loadLabellings(Collection<Long> contentIds) throws SQLException
    {
        List<LabellingRow> labellings = queryByChunk(
            "SELECT ID, LABELID, CONTENTID, OWNER, CREATIONDATE, LASTMODDATE, LABELABLEID, LABELABLETYPE "
                + "FROM CONTENT_LABEL WHERE CONTENTID IN (",
            ")", " ORDER BY ID", contentIds,
            rs -> new LabellingRow(rs.getLong(1), rs.getLong(2), rs.getLong(3), rs.getString(4),
                toLocalDateTime(rs.getTimestamp(5)), toLocalDateTime(rs.getTimestamp(6)),
                nullableLong(rs, 7), rs.getString(8)));

        List<Long> labelIds = labellings.stream().map(LabellingRow::labelId).distinct().toList();
        List<LabelRow> labels = labelIds.isEmpty() ? List.of() : queryByChunk(
            "SELECT LABELID, NAME, OWNER, NAMESPACE, CREATIONDATE, LASTMODDATE FROM LABEL WHERE LABELID IN (",
            ")", " ORDER BY LABELID", labelIds,
            rs -> new LabelRow(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                toLocalDateTime(rs.getTimestamp(5)), toLocalDateTime(rs.getTimestamp(6))));

        return new LabellingData(labels, labellings);
    }

    /**
     * 读取用户 key 到用户名的映射。
     *
     * @param userKeys 用户 key 集合
     * @return key 到用户名的映射（只包含能解析到的）
     * @throws SQLException 查询失败时
     */
    public Map<String, String> loadUserNames(Collection<String> userKeys) throws SQLException
    {
        if (userKeys.isEmpty()) {
            return Map.of();
        }
        List<Map.Entry<String, String>> rows = queryByChunk(
            "SELECT user_key, username FROM user_mapping WHERE user_key IN (",
            ")", "", userKeys,
            rs -> Map.entry(rs.getString(1), rs.getString(2)));

        Map<String, String> names = new LinkedHashMap<>();
        for (Map.Entry<String, String> row : rows) {
            names.put(row.getKey(), row.getValue());
        }
        return names;
    }

    /** 标签数据。 */
    public record LabellingData(List<LabelRow> labels, List<LabellingRow> labellings)
    {
    }

    // ---------------------------------------------------------------- 内部工具

    private static ContentRow mapContent(ResultSet rs) throws SQLException
    {
        return new ContentRow(
            rs.getLong("CONTENTID"),
            rs.getString("CONTENTTYPE"),
            rs.getString("TITLE"),
            rs.getString("LOWERTITLE"),
            rs.getInt("HIBERNATEVERSION"),
            rs.getInt("VERSION"),
            rs.getString("CREATOR"),
            toLocalDateTime(rs.getTimestamp("CREATIONDATE")),
            rs.getString("LASTMODIFIER"),
            toLocalDateTime(rs.getTimestamp("LASTMODDATE")),
            rs.getString("VERSIONCOMMENT"),
            rs.getString("CONTENT_STATUS"),
            nullableLong(rs, "PAGEID"),
            nullableLong(rs, "SPACEID"),
            nullableInt(rs, "CHILD_POSITION"),
            nullableLong(rs, "PARENTID"));
    }

    /**
     * 以固定大小的块执行 {@code ... IN (...)} 查询，避免占位符数量超出驱动上限，
     * 也避免一次性把大结果集读进内存。
     */
    private <T> List<T> queryByChunk(String prefix, String suffix, String tail, Collection<?> ids,
        ConfluenceDb.RowMapper<T> mapper) throws SQLException
    {
        List<T> results = new ArrayList<>();
        List<?> all = new ArrayList<>(ids);
        for (int start = 0; start < all.size(); start += CHUNK_SIZE) {
            List<?> chunk = all.subList(start, Math.min(all.size(), start + CHUNK_SIZE));
            String placeholders = String.join(",", java.util.Collections.nCopies(chunk.size(), "?"));
            results.addAll(db.query(prefix + placeholders + suffix + tail, mapper, chunk.toArray()));
        }
        return results;
    }

    private static <T> Map<Long, List<T>> groupBy(List<T> rows, java.util.function.ToLongFunction<T> keyFunction)
    {
        Map<Long, List<T>> grouped = new HashMap<>();
        for (T row : rows) {
            grouped.computeIfAbsent(keyFunction.applyAsLong(row), key -> new ArrayList<>()).add(row);
        }
        return grouped;
    }

    /**
     * 把若干可能为 {@code null} 的用户 key 收集起来，供批量解析用户名使用。
     *
     * @param rows 内容行
     * @return 非空用户 key 集合
     */
    public static Set<String> collectUserKeys(Collection<ContentRow> rows)
    {
        Set<String> keys = new LinkedHashSet<>();
        for (ContentRow row : rows) {
            if (row.creator() != null) {
                keys.add(row.creator());
            }
            if (row.lastModifier() != null) {
                keys.add(row.lastModifier());
            }
        }
        return keys;
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException
    {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Long nullableLong(ResultSet rs, int column) throws SQLException
    {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException
    {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Integer nullableInt(ResultSet rs, int column) throws SQLException
    {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static LocalDateTime toLocalDateTime(Timestamp timestamp)
    {
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }
}
