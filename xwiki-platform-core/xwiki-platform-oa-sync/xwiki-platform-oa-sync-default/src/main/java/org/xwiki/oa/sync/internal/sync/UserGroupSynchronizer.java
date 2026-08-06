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
package org.xwiki.oa.sync.internal.sync;

import java.util.Collections;
import java.util.Objects;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.xwiki.component.annotation.Component;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.model.reference.EntityReference;
import org.xwiki.oa.sync.OASyncConfiguration;
import org.xwiki.oa.sync.model.OAUserRecord;
import org.xwiki.user.group.GroupException;
import org.xwiki.user.group.GroupManager;

import com.xpn.xwiki.XWiki;
import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;
import com.xpn.xwiki.user.api.XWikiUser;

/**
 * Applies one OA user record to XWiki: create/update user, set active/disabled, ensure org group membership.
 * Idempotent: create-if-missing, set-state-only-on-change, add-member-only-if-missing.
 *
 * @version $Id$
 */
@Component(roles = UserGroupSynchronizer.class)
@Singleton
public class UserGroupSynchronizer
{
    @Inject
    private OASyncConfiguration configuration;

    @Inject
    private GroupManager groupManager;

    /**
     * Apply a single record (create/update user, set active state, ensure group membership).
     *
     * @param record the parsed user record
     * @param context the XWiki context
     * @throws XWikiException if user/group operations fail
     * @throws GroupException if membership lookup fails
     */
    public void sync(OAUserRecord record, XWikiContext context) throws XWikiException, GroupException
    {
        XWiki wiki = context.getWiki();
        String wikiId = context.getWikiId();
        String username = record.getUsername();

        // 1. Ensure the user exists
        DocumentReference userRef = new DocumentReference(wikiId, "XWiki", username);
        XWikiDocument userDoc = wiki.getDocument(userRef, context);
        if (userDoc.isNew()) {
            wiki.createUser(username, Collections.emptyMap(), context);
            userDoc = wiki.getDocument(userRef, context);
        }
        String userFullName = userDoc.getFullName();

        // 2. Set active/disabled only when the state differs
        XWikiUser xwikiUser = new XWikiUser(userRef);
        if (xwikiUser.isDisabled(context) == record.isActive()) {
            xwikiUser.setDisabled(!record.isActive(), context);
        }

        // 3. Org group membership
        String orgCode = record.getOrgCode();
        if (orgCode == null || orgCode.isBlank()) {
            throw new XWikiException(XWikiException.MODULE_XWIKI_USER, XWikiException.ERROR_XWIKI_UNKNOWN,
                "用户 [" + username + "] 归属机构编号为空");
        }
        DocumentReference groupRef = new DocumentReference(wikiId, "XWiki", orgCode);
        this.ensureGroup(groupRef, record, context);
        this.addMemberIfMissing(groupRef, userRef, userFullName, context);
    }

    private void ensureGroup(DocumentReference groupRef, OAUserRecord record, XWikiContext context)
        throws XWikiException
    {
        XWiki wiki = context.getWiki();
        String space = this.configuration.getRecordSpace();
        DocumentReference orgClassRef = new DocumentReference(context.getWikiId(), space, "OASyncOrgGroupClass");
        EntityReference groupClassRef =
            wiki.getGroupClass(context).getDocumentReference().removeParent(groupRef.getWikiReference());

        XWikiDocument groupDoc = wiki.getDocument(groupRef, context);
        if (groupDoc.isNew()) {
            XWikiDocument doc = new XWikiDocument(groupRef);
            doc.setTitle(record.getOrgName());
            doc.newXObject(groupClassRef, context);
            BaseObject orgObj = doc.newXObject(orgClassRef, context);
            orgObj.setStringValue("orgCode", record.getOrgCode());
            orgObj.setStringValue("orgName", record.getOrgName());
            wiki.saveDocument(doc, "OA sync: 创建机构组 " + record.getOrgCode(), context);
        } else if (!Objects.equals(groupDoc.getTitle(), record.getOrgName())) {
            XWikiDocument modified = groupDoc.clone();
            modified.setTitle(record.getOrgName());
            BaseObject orgObj = modified.getXObject(orgClassRef);
            if (orgObj == null) {
                orgObj = modified.newXObject(orgClassRef, context);
            }
            orgObj.setStringValue("orgCode", record.getOrgCode());
            orgObj.setStringValue("orgName", record.getOrgName());
            wiki.saveDocument(modified, "OA sync: 更新机构组 " + record.getOrgCode(), context);
        }
    }

    private void addMemberIfMissing(DocumentReference groupRef, DocumentReference userRef, String userFullName,
        XWikiContext context) throws XWikiException, GroupException
    {
        if (this.groupManager.getMembers(groupRef, false).contains(userRef)) {
            return;
        }
        XWiki wiki = context.getWiki();
        EntityReference groupClassRef =
            wiki.getGroupClass(context).getDocumentReference().removeParent(groupRef.getWikiReference());
        XWikiDocument groupDoc = wiki.getDocument(groupRef, context).clone();
        BaseObject memberObject = groupDoc.newXObject(groupClassRef, context);
        memberObject.setStringValue("member", userFullName);
        wiki.saveDocument(groupDoc, "OA sync: 添加成员 " + userFullName + " 到组 " + groupRef.getName(), context);
    }
}
