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
package org.xwiki.oa.sync.internal.store;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.oa.sync.OASyncConfiguration;
import org.xwiki.oa.sync.model.OASyncResult;
import org.xwiki.oa.sync.model.OASyncTriggerType;
import org.xwiki.query.Query;
import org.xwiki.query.QueryManager;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.web.Utils;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.test.MockitoOldcore;
import com.xpn.xwiki.test.junit5.mockito.InjectMockitoOldcore;
import com.xpn.xwiki.test.junit5.mockito.OldcoreTest;
import com.xpn.xwiki.test.reference.ReferenceComponentList;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link SyncRecordStore}.
 *
 * @version $Id$
 */
@OldcoreTest
@ReferenceComponentList
class SyncRecordStoreTest
{
    @InjectMockitoOldcore
    private MockitoOldcore oldcore;

    @InjectMockComponents
    private SyncRecordStore store;

    @MockComponent
    private OASyncConfiguration configuration;

    @MockComponent
    private QueryManager queryManager;

    private XWikiContext context;

    @BeforeEach
    void setUp()
    {
        this.context = this.oldcore.getXWikiContext();
        Utils.setComponentManager(this.oldcore.getMocker());
        when(this.configuration.getRecordSpace()).thenReturn("OASync");
    }

    @Test
    void savesRecord() throws Exception
    {
        OASyncResult result = new OASyncResult("employee", LocalDate.of(2026, 8, 5),
            OASyncTriggerType.SCHEDULED, LocalDateTime.of(2026, 8, 6, 5, 0, 1));
        result.setStatus(OASyncResult.Status.SUCCESS);
        result.setEndTime(LocalDateTime.of(2026, 8, 6, 5, 1));
        result.setTotalCount(3);
        result.setSuccessCount(3);
        result.setFailCount(0);

        this.store.save(result, this.context);

        XWikiDocument doc = this.context.getWiki().getDocument(
            new DocumentReference(this.context.getWikiId(), "OASync", "employee-20260806050001"), this.context);
        assertNotNull(doc.getXObject(new DocumentReference(this.context.getWikiId(), "OASync", "SyncRecordClass")));
    }

    @Test
    void cleanupKeepsOnlyLatestTen() throws Exception
    {
        // Create 12 record docs in the mock store
        List<String> fullNames = new ArrayList<>();
        for (int i = 1; i <= 12; i++) {
            String name = String.format("employee-202608060500%02d", i);
            DocumentReference ref = new DocumentReference(this.context.getWikiId(), "OASync", name);
            XWikiDocument doc = new XWikiDocument(ref);
            doc.newXObject(new DocumentReference(this.context.getWikiId(), "OASync", "SyncRecordClass"), this.context)
                .setStringValue("syncType", "employee");
            this.context.getWiki().saveDocument(doc, "test", this.context);
            fullNames.add("OASync." + name);
        }

        // Mock the query: newest first
        Query query = mock(Query.class);
        when(this.queryManager.createQuery(anyString(), eq(Query.XWQL))).thenReturn(query);
        when(query.bindValue(eq("type"), anyString())).thenReturn(query);
        List<String> desc = new ArrayList<>(fullNames);
        java.util.Collections.reverse(desc);
        when(query.<String>execute()).thenReturn(desc);

        this.store.cleanup("employee", this.context);

        // Oldest two (-01, -02) deleted; -03..-12 remain
        for (int i = 1; i <= 2; i++) {
            String name = String.format("employee-202608060500%02d", i);
            XWikiDocument doc = this.context.getWiki().getDocument(
                new DocumentReference(this.context.getWikiId(), "OASync", name), this.context);
            assertTrue(doc.isNew(), name + " should be deleted");
        }
        for (int i = 3; i <= 12; i++) {
            String name = String.format("employee-202608060500%02d", i);
            XWikiDocument doc = this.context.getWiki().getDocument(
                new DocumentReference(this.context.getWikiId(), "OASync", name), this.context);
            assertFalse(doc.isNew(), name + " should remain");
        }
    }
}
