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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.oa.sync.OASyncConfiguration;
import org.xwiki.localization.ContextualLocalizationManager;
import org.xwiki.oa.sync.model.OAUserRecord;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link UserGroupSynchronizer}.
 *
 * @version $Id$
 */
@OldcoreTest
@ReferenceComponentList
class UserGroupSynchronizerTest
{
    @InjectMockitoOldcore
    private MockitoOldcore oldcore;

    @InjectMockComponents
    private UserGroupSynchronizer synchronizer;

    @MockComponent
    private OASyncConfiguration configuration;

    @MockComponent
    private ContextualLocalizationManager localizationManager;

    private XWikiContext context;

    @BeforeEach
    void setUp()
    {
        this.context = this.oldcore.getXWikiContext();
        Utils.setComponentManager(this.oldcore.getMocker());
        when(this.configuration.getRecordSpace()).thenReturn("OASync");
    }

    @Test
    void createsUserAndGroupAndMembership() throws Exception
    {
        this.synchronizer.sync(new OAUserRecord("10086", "8801", "科技部", true), this.context);

        XWikiDocument userDoc = this.context.getWiki().getDocument(
            new DocumentReference(this.context.getWikiId(), "XWiki", "10086"), this.context);
        assertFalse(userDoc.isNew());

        XWikiDocument groupDoc = this.context.getWiki().getDocument(
            new DocumentReference(this.context.getWikiId(), "XWiki", "8801"), this.context);
        assertFalse(groupDoc.isNew());
        assertEquals("科技部", groupDoc.getTitle());
        assertNotNull(groupDoc.getXObject(new DocumentReference(this.context.getWikiId(), "OASync",
            "OASyncOrgGroupClass")));
        assertFalse(groupDoc.getXObjects(new DocumentReference(this.context.getWikiId(), "XWiki", "XWikiGroups"))
            .isEmpty());
    }

    @Test
    void disablesExistingUser() throws Exception
    {
        this.synchronizer.sync(new OAUserRecord("10086", "8801", "科技部", true), this.context);
        this.synchronizer.sync(new OAUserRecord("10086", "8801", "科技部", false), this.context);

        XWikiDocument userDoc = this.context.getWiki().getDocument(
            new DocumentReference(this.context.getWikiId(), "XWiki", "10086"), this.context);
        assertEquals(0, userDoc.getIntValue(
            new DocumentReference(this.context.getWikiId(), "XWiki", "XWikiUsers"), "active", 1));
    }

    @Test
    void rejectsBlankOrgCode()
    {
        assertThrows(Exception.class,
            () -> this.synchronizer.sync(new OAUserRecord("10086", "  ", "科技部", true), this.context));
    }
}
