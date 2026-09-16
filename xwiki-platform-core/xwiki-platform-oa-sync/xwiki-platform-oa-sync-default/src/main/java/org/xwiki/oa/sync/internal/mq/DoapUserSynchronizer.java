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

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xwiki.component.annotation.Component;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.EntityReference;
import org.xwiki.oa.sync.mq.DoapMqConstants;
import org.xwiki.oa.sync.mq.model.DoapUserMessage;

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
 * 「建号」与「入组」抽成 {@link #ensureUser(String, XWikiContext)} 与
 * {@link #addMembersToOutsourcingGroup(Collection, XWikiContext)}，
 * 供 MQ 增量与 Excel 全量导入共用，保证两条链路结果状态一致。
 *
 * @version $Id$
 */
@Component(roles = DoapUserSynchronizer.class)
@Singleton
public class DoapUserSynchronizer
{
    private static final Logger LOGGER = LoggerFactory.getLogger(DoapUserSynchronizer.class);

    /**
     * 批量入组时单次保存的成员数上限。
     */
    private static final int GROUP_CHUNK_SIZE = 200;

    /**
     * 组文档是单点，读-改-写必须互斥。
     * <p>
     * MQ 消费是并发多线程的（{@code xwiki.doap-mq.consume-thread-max} 默认 8），
     * 若两个线程同时读同一版本的组文档再各写整篇，后者会覆盖前者，结果是某个用户
     * "建号成功 + 日志 success + MQ ACK"却不在组里 —— 静默丢权限，无重试无告警。
     * <p>
     * 这是 **JVM 级** 锁：单 Tomcat 部署下足够。若将来多节点各起不同消费组的 consumer
     * 并发写同一个组文档，仍需要数据库级的并发控制（版本冲突重试或行锁），本锁不覆盖。
     */
    private static final Object GROUP_WRITE_LOCK = new Object();

    /**
     * 建号结果。
     */
    public static final class EnsuredUser
    {
        private final String fullName;

        private final boolean created;

        /**
         * @param fullName the user document full name, e.g. {@code XWiki.10086}
         * @param created whether the user was created by this call
         */
        public EnsuredUser(String fullName, boolean created)
        {
            this.fullName = fullName;
            this.created = created;
        }

        /**
         * @return the user document full name, e.g. {@code XWiki.10086}
         */
        public String getFullName()
        {
            return this.fullName;
        }

        /**
         * @return whether the user was created by this call
         */
        public boolean isCreated()
        {
            return this.created;
        }
    }

    /**
     * 处理一条外包用户消息。
     *
     * @param message the parsed MQ message
     * @param context the XWiki context
     * @throws XWikiException if user/group operations fail
     */
    public void sync(DoapUserMessage message, XWikiContext context) throws XWikiException
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

        // 1. 用户不存在则新建
        EnsuredUser user = ensureUser(username, context);

        // 2. 加入统一权限组「开发中心科技外包组」
        addMembersToOutsourcingGroup(Collections.singletonList(user.getFullName()), context);

        // 3. 禁用判断：isDeleted=true 或 status=dismission → 禁用；否则保持原状态（不重新启用）
        boolean isDeleted = Boolean.TRUE.equals(message.getBoolean(DoapMqConstants.FIELD_IS_DELETED));
        String status = message.getString(DoapMqConstants.FIELD_STATUS);
        boolean dismission = status != null && status.trim().equals(DoapMqConstants.STATUS_DISMISSION);
        if (isDeleted || dismission) {
            DocumentReference userRef = new DocumentReference(context.getWikiId(), "XWiki", username);
            XWikiUser xwikiUser = new XWikiUser(userRef);
            if (!xwikiUser.isDisabled(context)) {
                xwikiUser.setDisabled(true, context);
                LOGGER.info("DOAP MQ 禁用用户: [{}] isDeleted=[{}] status=[{}]", username, isDeleted, status);
            }
        } else {
            LOGGER.info("DOAP MQ 用户状态无需变更: [{}] isDeleted=[{}] status=[{}]", username, isDeleted, status);
        }
    }

    /**
     * 幂等建号：用户不存在则按工号新建。
     *
     * @param username the staff id, also the XWiki user name
     * @param context the XWiki context
     * @return the ensured user (full name + whether it was created now)
     * @throws XWikiException if the user cannot be created
     */
    public EnsuredUser ensureUser(String username, XWikiContext context) throws XWikiException
    {
        XWiki wiki = context.getWiki();
        DocumentReference userRef = new DocumentReference(context.getWikiId(), "XWiki", username);
        XWikiDocument userDoc = wiki.getDocument(userRef, context);
        boolean created = false;
        if (userDoc.isNew()) {
            // createUser 对已存在用户返回 -3（不抛异常），因此以返回码为准判断是否真的新建：
            // 仅靠 isNew() 是 check-then-act，并发下两个线程会各自报 created=true
            created = wiki.createUser(username, Collections.emptyMap(), context) == 1;
            userDoc = wiki.getDocument(userRef, context);
        }
        if (created) {
            LOGGER.info("OA 用户同步新建用户: [{}]", username);
        }
        return new EnsuredUser(userDoc.getFullName(), created);
    }

    /**
     * 把若干成员批量加入统一外包权限组，返回真正新增的成员数。
     * <p>
     * 组写入用 {@link #GROUP_WRITE_LOCK} 串行化（JVM 级）；多节点部署下本锁不覆盖跨 JVM 并发。
     * 按已有成员求差集后分块保存，每个待加成员都创建**新的** XWikiGroups 对象，绝不覆盖已有成员。
     *
     * @param memberFullNames the member full names, e.g. {@code XWiki.10086}
     * @param context the XWiki context
     * @return how many members were actually added
     * @throws XWikiException if the group cannot be created or saved
     */
    public int addMembersToOutsourcingGroup(Collection<String> memberFullNames, XWikiContext context)
        throws XWikiException
    {
        if (memberFullNames == null || memberFullNames.isEmpty()) {
            return 0;
        }

        synchronized (GROUP_WRITE_LOCK) {
            XWiki wiki = context.getWiki();
            DocumentReference groupRef =
                new DocumentReference(context.getWikiId(), "XWiki", DoapMqConstants.OUTSOURCING_GROUP_NAME);
            EntityReference groupClassRef =
                wiki.getGroupClass(context).getDocumentReference().removeParent(groupRef.getWikiReference());
            ensureGroup(groupRef, context);

            List<String> toAdd =
                computeMissingMembers(wiki.getDocument(groupRef, context), groupClassRef, memberFullNames);
            if (toAdd.isEmpty()) {
                return 0;
            }

            for (int start = 0; start < toAdd.size(); start += GROUP_CHUNK_SIZE) {
                List<String> chunk = toAdd.subList(start, Math.min(start + GROUP_CHUNK_SIZE, toAdd.size()));
                XWikiDocument modified = wiki.getDocument(groupRef, context).clone();
                for (String memberFullName : chunk) {
                    BaseObject memberObject = modified.newXObject(groupClassRef, context);
                    memberObject.setStringValue("member", memberFullName);
                }
                wiki.saveDocument(modified,
                    "OA sync: 批量添加 " + chunk.size() + " 名成员到组 " + groupRef.getName(), context);
            }
            LOGGER.info("OA 用户同步入组: 组=[{}] 新增成员=[{}]", DoapMqConstants.OUTSOURCING_GROUP_NAME,
                toAdd.size());
            return toAdd.size();
        }
    }

    private List<String> computeMissingMembers(XWikiDocument groupDoc, EntityReference groupClassRef,
        Collection<String> memberFullNames)
    {
        Set<String> existing = new HashSet<>();
        List<BaseObject> objects = groupDoc.getXObjects(groupClassRef);
        if (objects != null) {
            for (BaseObject object : objects) {
                if (object != null) {
                    String member = object.getStringValue("member");
                    if (member != null && !member.isBlank()) {
                        existing.add(member.trim());
                    }
                }
            }
        }

        List<String> toAdd = new ArrayList<>();
        for (String memberFullName : memberFullNames) {
            if (memberFullName != null && !memberFullName.isBlank() && existing.add(memberFullName.trim())) {
                toAdd.add(memberFullName.trim());
            }
        }
        return toAdd;
    }

    private void ensureGroup(DocumentReference groupRef, XWikiContext context) throws XWikiException
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
}
