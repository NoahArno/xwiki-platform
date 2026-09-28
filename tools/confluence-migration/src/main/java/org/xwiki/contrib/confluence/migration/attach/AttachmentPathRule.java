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
package org.xwiki.contrib.confluence.migration.attach;

import java.util.ArrayList;
import java.util.List;

/**
 * 复刻 Confluence 附件在文件系统上的存放路径规则。
 *
 * <p>Confluence 使用文件系统存放附件时（{@code confluence.cfg.xml} 中的
 * {@code attachments.dir}），路径并不是存放在数据库里的，而是由实体 ID {@code 计算}出来的——
 * 这正是 {@code ATTACHMENTDATA} 表为空的原因。算错一个字符，附件就会静默丢失。</p>
 *
 * <p>规则来自 {@code confluence-7.3.3.jar} 中的
 * {@code com.atlassian.confluence.pages.persistence.dao.filesystem.HierarchicalContentFileSystemHelper}，
 * 其构造参数为 {@code new IdMultiPartHashGenerator(3, 250, 2)}。</p>
 *
 * <p>路径形如：</p>
 * <pre>
 * ver003/&lt;spaceId 哈希目录&gt;/&lt;spaceId&gt;/&lt;容器ID 哈希目录&gt;/&lt;容器ID&gt;/&lt;附件ID&gt;/&lt;版本&gt;
 * ver003/nonspaced/&lt;容器ID 哈希目录&gt;/&lt;容器ID&gt;/&lt;附件ID&gt;/&lt;版本&gt;
 * </pre>
 *
 * <p>其中"容器"是附件所属内容（页面、博客、草稿）的 ID，取自 {@code CONTENT.PAGEID} 列——
 * 不是 {@code PARENTID}（那是页面层级父节点），也不是 {@code PARENTCCID}（本版本为空）。</p>
 */
public final class AttachmentPathRule
{
    /** 每个哈希段的位宽（十进制字符数）。 */
    private static final int SPLIT = 3;

    /** 每个哈希段取模的基数。 */
    private static final int MODULO = 250;

    /** 哈希段数量。 */
    private static final int PARTS = 2;

    /** 附件存储格式版本目录名。 */
    public static final String STORAGE_VERSION_DIR = "ver003";

    /** 无空间归属的附件（例如挂在没有空间的草稿页上）所用的目录名。 */
    public static final String NON_SPACED_DIR = "nonspaced";

    private AttachmentPathRule()
    {
        // utility class
    }

    /**
     * 按 Confluence 的 {@code IdMultiPartHashGenerator} 把实体 ID 切成若干哈希段。
     *
     * <p>该实现刻意与反编译结果逐行对齐，包括边界行为：当剩余长度不足一段时，
     * 取走剩余的前缀并**在列表末尾补 0**（是追加到末尾，不是插入到开头——
     * 这一点对短 ID 的结果有实质影响）。</p>
     *
     * @param id 实体 ID，不能为负数
     * @return 长度为 {@value #PARTS} 的哈希段列表
     * @throws IllegalArgumentException 当 {@code id} 为负数时
     */
    public static List<Integer> hashParts(long id)
    {
        if (id < 0) {
            throw new IllegalArgumentException("The value to split must be larger than 0");
        }

        String value = Long.toString(id);
        int position = value.length();
        List<Integer> parts = new ArrayList<>(PARTS);

        for (int i = 0; i < PARTS; i++) {
            position -= SPLIT;

            String subPart;
            if (position > 0) {
                subPart = value.substring(position, position + SPLIT);
            } else {
                subPart = value.substring(0, position + SPLIT);
            }
            parts.add(Integer.parseInt(subPart) % MODULO);

            if (position <= 0) {
                break;
            }
        }

        while (parts.size() < PARTS) {
            parts.add(0);
        }

        return parts;
    }

    /**
     * 计算附件相对于 {@code attachments.dir} 的存放路径。
     *
     * @param spaceId 附件所属内容的 {@code SPACEID}，无空间归属时传 {@code null}
     * @param containerId 附件所属内容的 ID（{@code CONTENT.PAGEID}）
     * @param attachmentId 附件的 {@code CONTENTID}
     * @param version 附件版本号
     * @return 使用 {@code /} 分隔的相对路径
     */
    public static String relativePath(Long spaceId, long containerId, long attachmentId, int version)
    {
        StringBuilder path = new StringBuilder(STORAGE_VERSION_DIR).append('/');

        if (spaceId == null) {
            path.append(NON_SPACED_DIR).append('/');
        } else {
            appendHashDirs(path, spaceId);
            path.append(spaceId).append('/');
        }

        appendHashDirs(path, containerId);
        path.append(containerId).append('/');

        path.append(attachmentId).append('/').append(version);

        return path.toString();
    }

    private static void appendHashDirs(StringBuilder path, long id)
    {
        for (int part : hashParts(id)) {
            path.append(part).append('/');
        }
    }
}
