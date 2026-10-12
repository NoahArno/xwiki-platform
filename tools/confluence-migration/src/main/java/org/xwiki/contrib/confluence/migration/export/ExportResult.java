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

import java.util.List;

/**
 * 一次空间导出的结果。
 *
 * @param spaceKey 被导出的空间 Key
 * @param packageDirectory 生成的包目录
 * @param pageCount 导出的页面数
 * @param blogPostCount 导出的博客数
 * @param attachmentCount 导出的附件数
 * @param attachmentBytes 复制的附件总字节数
 * @param userCount 写出的用户实体数
 * @param warnings 导出过程中的告警
 */
public record ExportResult(
    String spaceKey,
    java.nio.file.Path packageDirectory,
    long pageCount,
    long blogPostCount,
    long attachmentCount,
    long attachmentBytes,
    long userCount,
    List<String> warnings)
{
}
