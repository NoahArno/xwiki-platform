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
package org.xwiki.audit.log.internal;

import java.util.ArrayList;
import java.util.List;

import javax.inject.Named;
import javax.inject.Provider;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.xwiki.audit.log.LoginAuditService;
import org.xwiki.audit.log.LoginChannel;
import org.xwiki.audit.log.LoginEventType;
import org.xwiki.configuration.ConfigurationSource;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.query.Query;
import org.xwiki.query.QueryManager;
import org.xwiki.test.junit5.mockito.InjectMockComponents;
import org.xwiki.test.junit5.mockito.MockComponent;

import com.xpn.xwiki.XWiki;
import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.test.MockitoOldcore;
import com.xpn.xwiki.test.junit5.mockito.InjectMockitoOldcore;
import com.xpn.xwiki.test.junit5.mockito.OldcoreTest;
import com.xpn.xwiki.test.reference.ReferenceComponentList;
import com.xpn.xwiki.web.Utils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DefaultLoginAuditService}.
 *
 * @version $Id$
 */
@OldcoreTest
@ReferenceComponentList
class DefaultLoginAuditServiceTest
{
    @InjectMockitoOldcore
    private MockitoOldcore oldcore;

    @InjectMockComponents
    private DefaultLoginAuditService service;

    @MockComponent
    private Provider<XWikiContext> contextProvider;

    @MockComponent
    @Named("xwikicfg")
    private ConfigurationSource configuration;

    @MockComponent
    private QueryManager queryManager;

    private XWikiContext context;

    private XWiki wiki;

    @BeforeEach
    void setUp()
    {
        this.context = this.oldcore.getXWikiContext();
        this.wiki = this.oldcore.getSpyXWiki();
        Utils.setComponentManager(this.oldcore.getMocker());
        when(this.contextProvider.get()).thenReturn(this.context);
        when(this.configuration.getProperty(eq("xwiki.authentication.audit-log.enabled"), eq(Boolean.class)))
            .thenReturn(true);
    }

    private XWikiDocument captureSavedDocument() throws Exception
    {
        ArgumentCaptor<XWikiDocument> captor = ArgumentCaptor.forClass(XWikiDocument.class);
        verify(this.wiki).saveDocument(captor.capture(), anyString(), any(XWikiContext.class));
        return captor.getValue();
    }

    @Test
    void recordsEventWhenEnabled() throws Exception
    {
        this.service.record(LoginEventType.LOGIN_SUCCESS, LoginChannel.OA, "wiki:XWiki.admin", "127.0.0.1", "OK");

        XWikiDocument doc = captureSavedDocument();
        assertNotNull(doc.getXObject(new DocumentReference(this.context.getWikiId(), "AuditLog", "LogRecordClass")));
        com.xpn.xwiki.objects.BaseObject obj =
            doc.getXObject(new DocumentReference(this.context.getWikiId(), "AuditLog", "LogRecordClass"));
        assertEquals("LOGIN_SUCCESS", obj.getStringValue("eventType"));
        assertEquals("OA", obj.getStringValue("channel"));
        assertEquals("wiki:XWiki.admin", obj.getStringValue("userName"));
        assertEquals("127.0.0.1", obj.getStringValue("ip"));
        assertEquals("OK", obj.getLargeStringValue("detail"));
        assertTrue(doc.isHidden());
    }

    @Test
    void skipsWriteWhenDisabled() throws Exception
    {
        when(this.configuration.getProperty(eq("xwiki.authentication.audit-log.enabled"), eq(Boolean.class)))
            .thenReturn(false);

        this.service.record(LoginEventType.LOGIN_SUCCESS, LoginChannel.FORM, "wiki:XWiki.admin", "127.0.0.1", "x");

        verify(this.wiki, never()).saveDocument(any(XWikiDocument.class), anyString(), any(XWikiContext.class));
    }

    @Test
    void prunesOldestWhenOverOneHundred() throws Exception
    {
        // Create 101 existing records, oldest first: AuditLog.LogRecord001 .. LogRecord101.
        List<String> fullNames = new ArrayList<>();
        for (int i = 1; i <= 101; i++) {
            String name = String.format("LogRecord%03d", i);
            DocumentReference ref = new DocumentReference(this.context.getWikiId(), "AuditLog", name);
            XWikiDocument doc = new XWikiDocument(ref);
            doc.newXObject(new DocumentReference(this.context.getWikiId(), "AuditLog", "LogRecordClass"),
                this.context).setStringValue("eventType", "LOGIN_SUCCESS");
            this.wiki.saveDocument(doc, "test", this.context);
            fullNames.add("AuditLog." + name);
        }

        // Mock the XWQL query to return newest first (as the service orders by creationDate desc).
        List<String> newestFirst = new ArrayList<>(fullNames);
        java.util.Collections.reverse(newestFirst);
        Query query = mock(Query.class);
        when(this.queryManager.createQuery(anyString(), eq(Query.XWQL))).thenReturn(query);
        when(query.<String>execute()).thenReturn(newestFirst);

        this.service.record(LoginEventType.LOGIN_SUCCESS, LoginChannel.OA, "wiki:XWiki.admin", "127.0.0.1", "x");

        // The oldest (LogRecord001) is deleted, the rest remain. Use store state (isNew) instead of
        // verify(deleteDocument): XWikiDocument does not override equals(), so argument matching on the
        // freshly-loaded instance would not work.
        XWikiDocument deleted = this.wiki.getDocument(
            new DocumentReference(this.context.getWikiId(), "AuditLog", "LogRecord001"), this.context);
        assertTrue(deleted.isNew(), "LogRecord001 should be deleted");
        XWikiDocument kept = this.wiki.getDocument(
            new DocumentReference(this.context.getWikiId(), "AuditLog", "LogRecord002"), this.context);
        assertNotNull(kept.getXObject(
            new DocumentReference(this.context.getWikiId(), "AuditLog", "LogRecordClass")));
    }

    @Test
    void doesNotThrowWhenSaveFails() throws Exception
    {
        doThrow(new XWikiException(XWikiException.MODULE_XWIKI, XWikiException.ERROR_XWIKI_UNKNOWN, "boom"))
            .when(this.wiki).saveDocument(any(XWikiDocument.class), anyString(), any(XWikiContext.class));

        assertDoesNotThrow(() -> this.service.record(LoginEventType.LOGIN_FAILURE, LoginChannel.FORM,
            "unknown", "127.0.0.1", "bad password"));
    }
}
