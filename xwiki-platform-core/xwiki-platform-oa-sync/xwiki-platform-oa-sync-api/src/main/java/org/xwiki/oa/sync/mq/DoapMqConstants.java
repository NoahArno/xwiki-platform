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
package org.xwiki.oa.sync.mq;

/**
 * Constants for the DOAP (知脉) user data MQ topic and tags.
 * <p>
 * The upstream DOAP MQ cluster publishes user change events on topic
 * {@value #TOPIC_DOAP_USER} with one of the three tags below.
 *
 * @version $Id$
 */
public final class DoapMqConstants
{
    /**
     * DOAP 用户数据 topic。
     */
    public static final String TOPIC_DOAP_USER = "TOPIC_DOAP_USER";

    /**
     * 用户新增 tag。
     */
    public static final String TAG_USER_CREATE = "TAG_USER_CREATE";

    /**
     * 用户更新 tag。
     */
    public static final String TAG_USER_UPDATE = "TAG_USER_UPDATE";

    /**
     * 用户删除 tag。
     */
    public static final String TAG_USER_DELETE = "TAG_USER_DELETE";

    /**
     * 消息字段：用户类型。
     */
    public static final String FIELD_TYPE = "type";

    /**
     * 消息字段：工号（XWiki 用户名）。
     */
    public static final String FIELD_STAFF_ID = "staffId";

    /**
     * 消息字段：是否删除。
     */
    public static final String FIELD_IS_DELETED = "isDeleted";

    /**
     * 消息字段：在职状态。
     */
    public static final String FIELD_STATUS = "status";

    /**
     * 只关注的外包用户类型值。
     */
    public static final String USER_TYPE_OUTSOURCING = "outsourcing";

    /**
     * 离职状态值，命中即禁用用户。
     */
    public static final String STATUS_DISMISSION = "dismission";

    /**
     * 外包用户统一权限组名称。
     */
    public static final String OUTSOURCING_GROUP_NAME = "开发中心科技外包组";

    private DoapMqConstants()
    {
        // Utility class.
    }
}
