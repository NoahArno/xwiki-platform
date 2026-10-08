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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link MigrationPolicy} 的测试。
 *
 * <p>重点是"未知取值必须能被识别出来"。排除规则是一份黑名单，而黑名单必然有盲区；
 * 开发所用实例里从未观测到回收站内容，因此 {@code deleted} 是否为真实取值只能靠
 * 导入器源码反推。把这种不确定性做成"可检测"而不是"靠猜"，正是这些用例要钉住的行为。</p>
 */
class MigrationPolicyTest
{
    @Test
    @DisplayName("正常内容不被排除")
    void currentIsMigratable()
    {
        assertFalse(MigrationPolicy.isExcludedStatus(MigrationPolicy.STATUS_CURRENT));
    }

    @Test
    @DisplayName("状态为空视为可迁移")
    void nullStatusIsMigratable()
    {
        assertFalse(MigrationPolicy.isExcludedStatus(null));
    }

    @Test
    @DisplayName("草稿、两种回收站写法与归档都被排除")
    void excludesAllKnownBadStatuses()
    {
        assertTrue(MigrationPolicy.isExcludedStatus(MigrationPolicy.STATUS_DRAFT));
        assertTrue(MigrationPolicy.isExcludedStatus(MigrationPolicy.STATUS_TRASHED));
        assertTrue(MigrationPolicy.isExcludedStatus(MigrationPolicy.STATUS_DELETED));
        assertTrue(MigrationPolicy.isExcludedStatus(MigrationPolicy.STATUS_ARCHIVED));
    }

    @Test
    @DisplayName("大小写不敏感")
    void statusMatchingIsCaseInsensitive()
    {
        assertTrue(MigrationPolicy.isExcludedStatus("DRAFT"));
        assertTrue(MigrationPolicy.isExcludedStatus("Deleted"));
        assertFalse(MigrationPolicy.isExcludedStatus("CURRENT"));
    }

    @Test
    @DisplayName("已知取值能被识别")
    void recognizesKnownStatuses()
    {
        assertTrue(MigrationPolicy.isKnownStatus(MigrationPolicy.STATUS_CURRENT));
        assertTrue(MigrationPolicy.isKnownStatus(MigrationPolicy.STATUS_DRAFT));
        assertTrue(MigrationPolicy.isKnownStatus(MigrationPolicy.STATUS_TRASHED));
        assertTrue(MigrationPolicy.isKnownStatus(MigrationPolicy.STATUS_DELETED));
        assertTrue(MigrationPolicy.isKnownStatus(MigrationPolicy.STATUS_ARCHIVED));
        assertTrue(MigrationPolicy.isKnownStatus(null));
    }

    @Test
    @DisplayName("未预期的取值被识别为未知，以便导出时告警")
    void flagsUnknownStatuses()
    {
        assertFalse(MigrationPolicy.isKnownStatus("published"));
        assertFalse(MigrationPolicy.isKnownStatus("CURRENT_2"));
        assertFalse(MigrationPolicy.isKnownStatus("(null)"));
    }

    @Test
    @DisplayName("排除列表可安全嵌入 SQL")
    void rendersSqlLiterals()
    {
        String literals = MigrationPolicy.excludedStatusSqlLiterals();

        assertTrue(literals.contains("'draft'"), literals);
        assertTrue(literals.contains("'trashed'"), literals);
        assertTrue(literals.contains("'deleted'"), literals);
        assertTrue(literals.contains("'archived'"), literals);
    }
}
