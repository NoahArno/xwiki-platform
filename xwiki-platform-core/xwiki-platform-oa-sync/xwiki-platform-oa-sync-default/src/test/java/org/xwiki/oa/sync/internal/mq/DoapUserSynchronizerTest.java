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

import java.util.LinkedHashMap;
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
