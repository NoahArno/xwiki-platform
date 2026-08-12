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
package org.xwiki.oa.sync.internal.mq;

import java.util.Collections;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xwiki.component.annotation.Component;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.EntityReference;
import org.xwiki.oa.sync.mq.DoapMqConstants;
import org.xwiki.oa.sync.mq.model.DoapUserMessage;
import org.xwiki.user.group.GroupException;
import org.xwiki.user.group.GroupManager;

import com.xpn.xwiki.XWiki;
import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;
import com.xpn.xwiki.user.api.XWikiUser;

/**
 * 将 DOAP MQ 消息中的外包用户同步到 XWiki：
 * <ul>
 *   <li>只处理 {@code type=outsourcing} 的消息，其余直接丢弃；</li>
 *   <li>工号 {@code staffId} 作为 XWiki 用户名，用户不存在则新建；</li>
 *   <li>所有外包用户加入统一权限组「开发中心科技外包组」；</li>
 *   <li>{@code isDeleted=true} 或 {@code status=dismission} 时禁用用户，否则保持原状态（不重新启用）。</li>
 * </ul>
 *
 * @version $Id$
 */
@Component(roles = DoapUserSynchronizer.class)
@Singleton
public class DoapUserSynchronizer
{
    private static final Logger LOGGER = LoggerFactory.getLogger(DoapUserSynchronizer.class);

    @Inject
    private GroupManager groupManager;

    /**
     * 处理一条外包用户消息。
     *
     * @param message the parsed MQ message
     * @param context the XWiki context
     * @throws XWikiException if user/group operations fail
     * @throws GroupException if membership lookup fails
     */
    public void sync(DoapUserMessage message, XWikiContext context) throws XWikiException, GroupException
    {
        String type = message.getString(DoapMqConstants.FIELD_TYPE);
        if (!DoapMqConstants.USER_TYPE_OUTSOURCING.equals(type)) {
            LOGGER.info("DOAP MQ 非外包用户消息，丢弃: msgId=[{}] type=[{}]", message.getMsgId(), type);
            return;
        }

        String username = message.getString(DoapMqConstants.FIELD_STAFF_ID);
        if (username == null || username.isBlank()) {
            LOGGER.warn("DOAP MQ 外包用户消息缺少工号 staffId，丢弃: msgId=[{}]", message.getMsgId());
            return;
        }
        username = username.trim();

        XWiki wiki = context.getWiki();
        String wikiId = context.getWikiId();
        DocumentReference userRef = new DocumentReference(wikiId, "XWiki", username);

        // 1. 用户不存在则新建
        XWikiDocument userDoc = wiki.getDocument(userRef, context);
        if (userDoc.isNew()) {
            wiki.createUser(username, Collections.emptyMap(), context);
            userDoc = wiki.getDocument(userRef, context);
            LOGGER.info("DOAP MQ 新建用户: [{}]", username);
        }

        // 2. 加入统一权限组「开发中心科技外包组」
        DocumentReference groupRef =
            new DocumentReference(wikiId, "XWiki", DoapMqConstants.OUTSOURCING_GROUP_NAME);
        EntityReference groupClassRef =
            wiki.getGroupClass(context).getDocumentReference().removeParent(groupRef.getWikiReference());
        this.ensureGroup(groupRef, groupClassRef, context);
        this.addMemberIfMissing(groupRef, groupClassRef, userRef, userDoc.getFullName(), context);

        // 3. 禁用判断：isDeleted=true 或 status=dismission → 禁用；否则保持原状态（不重新启用）
        boolean isDeleted = Boolean.TRUE.equals(message.getBoolean(DoapMqConstants.FIELD_IS_DELETED));
        String status = message.getString(DoapMqConstants.FIELD_STATUS);
        boolean dismission = status != null && status.trim().equals(DoapMqConstants.STATUS_DISMISSION);
        if (isDeleted || dismission) {
            XWikiUser xwikiUser = new XWikiUser(userRef);
            if (!xwikiUser.isDisabled(context)) {
                xwikiUser.setDisabled(true, context);
                LOGGER.info("DOAP MQ 禁用用户: [{}] isDeleted=[{}] status=[{}]", username, isDeleted, status);
            }
        } else {
            LOGGER.info("DOAP MQ 用户状态无需变更: [{}] isDeleted=[{}] status=[{}]", username, isDeleted, status);
        }
    }

    private void ensureGroup(DocumentReference groupRef, EntityReference groupClassRef, XWikiContext context)
        throws XWikiException
    {
        XWiki wiki = context.getWiki();
        XWikiDocument groupDoc = wiki.getDocument(groupRef, context);
        if (groupDoc.isNew()) {
            XWikiDocument doc = new XWikiDocument(groupRef);
            doc.setTitle(DoapMqConstants.OUTSOURCING_GROUP_NAME);
            wiki.saveDocument(doc, "DOAP MQ sync: 创建权限组 " + DoapMqConstants.OUTSOURCING_GROUP_NAME, context);
            LOGGER.info("DOAP MQ 创建权限组: [{}]", DoapMqConstants.OUTSOURCING_GROUP_NAME);
        }
    }

    private void addMemberIfMissing(DocumentReference groupRef, EntityReference groupClassRef,
        DocumentReference userRef, String userFullName, XWikiContext context) throws XWikiException, GroupException
    {
        if (this.groupManager.getMembers(groupRef, false).contains(userRef)) {
            return;
        }
        XWiki wiki = context.getWiki();
        XWikiDocument groupDoc = wiki.getDocument(groupRef, context).clone();
        BaseObject memberObject = groupDoc.getXObject(groupClassRef);
        if (memberObject == null) {
            memberObject = groupDoc.newXObject(groupClassRef, context);
        }
        memberObject.setStringValue("member", userFullName);
        wiki.saveDocument(groupDoc, "DOAP MQ sync: 添加成员 " + userFullName + " 到组 " + groupRef.getName(), context);
    }
}
