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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.xwiki.localization.ContextualLocalizationManager;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.oa.sync.mq.DoapMqConstants;
import org.xwiki.oa.sync.mq.model.DoapUserMessage;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;
import com.xpn.xwiki.test.MockitoOldcore;
import com.xpn.xwiki.test.junit5.mockito.InjectMockitoOldcore;
import com.xpn.xwiki.test.junit5.mockito.OldcoreTest;
import com.xpn.xwiki.test.reference.ReferenceComponentList;
import com.xpn.xwiki.web.Utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link DoapUserSynchronizer}.
 *
 * @version $Id$
 */
@OldcoreTest
@ReferenceComponentList
class DoapUserSynchronizerTest
{
    @InjectMockitoOldcore
    private MockitoOldcore oldcore;

    @InjectMockComponents
    private DoapUserSynchronizer synchronizer;

    @MockComponent
    private ContextualLocalizationManager localizationManager;

    private XWikiContext context;

    @BeforeEach
    void setUp()
    {
        this.context = this.oldcore.getXWikiContext();
        Utils.setComponentManager(this.oldcore.getMocker());
    }

    @Test
    void ignoresNonOutsourcingType() throws Exception
    {
        this.synchronizer.sync(message("employee", "10086", false, "active"), this.context);

        assertTrue(userDoc("10086").isNew());
        assertTrue(groupDoc().isNew());
    }

    @Test
    void createsUserAndAddsToOutsourcingGroup() throws Exception
    {
        this.synchronizer.sync(message("outsourcing", "10086", false, "active"), this.context);

        XWikiDocument userDoc = userDoc("10086");
        assertFalse(userDoc.isNew());
        assertEquals(1, userDoc.getIntValue(
            new DocumentReference(this.context.getWikiId(), "XWiki", "XWikiUsers"), "active", 1));

        XWikiDocument groupDoc = groupDoc();
        assertFalse(groupDoc.isNew());
        assertEquals(DoapMqConstants.OUTSOURCING_GROUP_NAME, groupDoc.getTitle());
        assertFalse(groupDoc.getXObjects(new DocumentReference(this.context.getWikiId(), "XWiki", "XWikiGroups"))
            .isEmpty());
        assertEquals("XWiki.10086", groupDoc.getXObjects(
            new DocumentReference(this.context.getWikiId(), "XWiki", "XWikiGroups")).get(0).getStringValue("member"));
    }

    @Test
    void disablesUserWhenIsDeletedTrue() throws Exception
    {
        this.synchronizer.sync(message("outsourcing", "10086", true, "active"), this.context);

        assertEquals(0, userDoc("10086").getIntValue(
            new DocumentReference(this.context.getWikiId(), "XWiki", "XWikiUsers"), "active", 1));
    }

    @Test
    void disablesUserWhenStatusDismission() throws Exception
    {
        this.synchronizer.sync(message("outsourcing", "10086", false, "dismission"), this.context);

        assertEquals(0, userDoc("10086").getIntValue(
            new DocumentReference(this.context.getWikiId(), "XWiki", "XWikiUsers"), "active", 1));
    }

    @Test
    void keepsUserDisabledWhenNotFlagged() throws Exception
    {
        this.synchronizer.sync(message("outsourcing", "10086", false, "active"), this.context);
        this.synchronizer.sync(message("outsourcing", "10086", true, "active"), this.context);
        // 不满足禁用条件时保持原状态，不能重新启用
        this.synchronizer.sync(message("outsourcing", "10086", false, "active"), this.context);

        assertEquals(0, userDoc("10086").getIntValue(
            new DocumentReference(this.context.getWikiId(), "XWiki", "XWikiUsers"), "active", 1));
    }

    @Test
    void skipsBlankStaffId() throws Exception
    {
        this.synchronizer.sync(message("outsourcing", "  ", false, "active"), this.context);

        assertTrue(userDoc("  ").isNew());
    }

    @Test
    void keepsAllMembersWhenAddingSeveralUsers() throws Exception
    {
        this.synchronizer.sync(message("outsourcing", "10086", false, "active"), this.context);
        this.synchronizer.sync(message("outsourcing", "10087", false, "active"), this.context);
        this.synchronizer.sync(message("outsourcing", "10088", false, "active"), this.context);

        assertEquals(List.of("XWiki.10086", "XWiki.10087", "XWiki.10088"), groupMembers());
    }

    @Test
    void batchAddIsIdempotent() throws Exception
    {
        int first = this.synchronizer.addMembersToOutsourcingGroup(
            List.of("XWiki.10086", "XWiki.10087"), this.context);
        int second = this.synchronizer.addMembersToOutsourcingGroup(
            List.of("XWiki.10086", "XWiki.10087"), this.context);

        assertEquals(2, first);
        assertEquals(0, second);
        assertEquals(List.of("XWiki.10086", "XWiki.10087"), groupMembers());
    }

    @Test
    void batchAddSkipsMembersAlreadyPresent() throws Exception
    {
        this.synchronizer.sync(message("outsourcing", "10086", false, "active"), this.context);

        int added = this.synchronizer.addMembersToOutsourcingGroup(
            List.of("XWiki.10086", "XWiki.10087"), this.context);

        assertEquals(1, added);
        assertEquals(List.of("XWiki.10086", "XWiki.10087"), groupMembers());
    }

    @Test
    void batchAddCreatesTheGroupWhenMissing() throws Exception
    {
        assertTrue(groupDoc().isNew());

        this.synchronizer.addMembersToOutsourcingGroup(List.of("XWiki.10086"), this.context);

        assertEquals(DoapMqConstants.OUTSOURCING_GROUP_NAME, groupDoc().getTitle());
        assertEquals(List.of("XWiki.10086"), groupMembers());
    }

    @Test
    void ensureUserReportsCreatedThenExisted() throws Exception
    {
        DoapUserSynchronizer.EnsuredUser created = this.synchronizer.ensureUser("10086", this.context);
        DoapUserSynchronizer.EnsuredUser existed = this.synchronizer.ensureUser("10086", this.context);

        assertTrue(created.isCreated());
        assertEquals("XWiki.10086", created.getFullName());
        assertFalse(existed.isCreated());
        assertEquals("XWiki.10086", existed.getFullName());
    }

    private List<String> groupMembers() throws Exception
    {
        List<BaseObject> objects = groupDoc().getXObjects(
            new DocumentReference(this.context.getWikiId(), "XWiki", "XWikiGroups"));
        if (objects == null) {
            return List.of();
        }
        List<String> members = new ArrayList<>();
        for (BaseObject object : objects) {
            if (object != null) {
                members.add(object.getStringValue("member"));
            }
        }
        return members;
    }

    private XWikiDocument userDoc(String username) throws Exception
    {
        return this.context.getWiki().getDocument(
            new DocumentReference(this.context.getWikiId(), "XWiki", username), this.context);
    }

    private XWikiDocument groupDoc() throws Exception
    {
        return this.context.getWiki().getDocument(
            new DocumentReference(this.context.getWikiId(), "XWiki", DoapMqConstants.OUTSOURCING_GROUP_NAME),
            this.context);
    }

    private static DoapUserMessage message(String type, String staffId, Boolean isDeleted, String status)
    {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put(DoapMqConstants.FIELD_TYPE, type);
        fields.put(DoapMqConstants.FIELD_STAFF_ID, staffId);
        if (isDeleted != null) {
            fields.put(DoapMqConstants.FIELD_IS_DELETED, isDeleted);
        }
        if (status != null) {
            fields.put(DoapMqConstants.FIELD_STATUS, status);
        }
        return new DoapUserMessage(DoapMqConstants.TOPIC_DOAP_USER, DoapMqConstants.TAG_USER_CREATE, "msg-1", 0,
            "{}", fields);
    }
}
