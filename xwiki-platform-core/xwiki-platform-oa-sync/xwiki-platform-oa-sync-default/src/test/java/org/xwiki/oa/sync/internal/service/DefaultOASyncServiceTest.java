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
package org.xwiki.oa.sync.internal.service;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.xwiki.component.manager.ComponentManager;
import org.xwiki.oa.sync.OASyncConfiguration;
import org.xwiki.oa.sync.OASyncSource;
import org.xwiki.oa.sync.internal.ftp.OAFtpDownloader;
import org.xwiki.oa.sync.internal.store.SyncRecordStore;
import org.xwiki.oa.sync.internal.sync.UserGroupSynchronizer;
import org.xwiki.oa.sync.model.OAUserRecord;
import org.xwiki.oa.sync.model.OASyncParseResult;
import org.xwiki.oa.sync.model.OASyncResult;
import org.xwiki.oa.sync.model.OASyncTriggerType;

import com.xpn.xwiki.XWikiContext;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link DefaultOASyncService}.
 *
 * @version $Id$
 */
class DefaultOASyncServiceTest
{
    @TempDir
    Path tempDir;

    private DefaultOASyncService service;
    private OASyncConfiguration configuration;
    private OAFtpDownloader ftpDownloader;
    private SyncRecordStore recordStore;
    private UserGroupSynchronizer synchronizer;
    private ComponentManager componentManager;
    private OASyncSource source;

    @BeforeEach
    void setUp() throws Exception
    {
        this.service = new DefaultOASyncService();
        this.configuration = mock(OASyncConfiguration.class);
        this.ftpDownloader = mock(OAFtpDownloader.class);
        this.recordStore = mock(SyncRecordStore.class);
        this.synchronizer = mock(UserGroupSynchronizer.class);
        this.componentManager = mock(ComponentManager.class);
        this.source = mock(OASyncSource.class);
        XWikiContext context = mock(XWikiContext.class);

        when(this.componentManager.getInstanceList(OASyncSource.class)).thenReturn(List.of(this.source));
        when(this.source.getType()).thenReturn("employee");
        when(this.source.isEnabled()).thenReturn(true);
        when(this.source.resolveRemotePath("20260805")).thenReturn("/comm/bdpp/oa/20260805/x.dat");
        Path file = this.tempDir.resolve("x.dat");
        Files.write(file, "dummy".getBytes(StandardCharsets.UTF_8));
        when(this.ftpDownloader.download("/comm/bdpp/oa/20260805/x.dat")).thenReturn(file);

        OASyncParseResult parseResult = new OASyncParseResult();
        parseResult.getRecords().add(new OAUserRecord("10086", "8801", "科技部", true));
        when(this.source.parse(any(Path.class))).thenReturn(parseResult);
        when(this.configuration.getSourceDateOffsetDays("employee")).thenReturn(1);

        this.service.configuration = this.configuration;
        this.service.ftpDownloader = this.ftpDownloader;
        this.service.recordStore = this.recordStore;
        this.service.synchronizer = this.synchronizer;
        this.service.componentManager = this.componentManager;
        this.service.contextProvider = () -> context;
    }

    @Test
    void syncAllRunsEnabledSourceAndPersistsRecord() throws Exception
    {
        List<OASyncResult> results = this.service.syncAll(OASyncTriggerType.MANUAL, LocalDate.of(2026, 8, 5));
        assertEquals(1, results.size());
        assertEquals(OASyncResult.Status.SUCCESS, results.get(0).getStatus());
        assertEquals(1, results.get(0).getSuccessCount());
        verify(this.recordStore).save(eq(results.get(0)), any());
        verify(this.recordStore).cleanup(eq("employee"), any());
    }

    @Test
    void isolatedUserFailureStillPersistsPartialResult() throws Exception
    {
        doAnswer(inv -> {
            throw new RuntimeException("boom");
        }).when(this.synchronizer).sync(any(), any());

        List<OASyncResult> results = this.service.syncAll(OASyncTriggerType.MANUAL, LocalDate.of(2026, 8, 5));
        assertEquals(OASyncResult.Status.PARTIAL_FAILURE, results.get(0).getStatus());
        assertEquals(1, results.get(0).getFailCount());
        assertEquals(1, results.get(0).getErrorLog().size());
    }

    @Test
    void sourceFailureProducesFailureResult() throws Exception
    {
        when(this.source.parse(any(Path.class))).thenThrow(new RuntimeException("bad file"));
        List<OASyncResult> results = this.service.syncAll(OASyncTriggerType.MANUAL, LocalDate.of(2026, 8, 5));
        assertEquals(OASyncResult.Status.FAILURE, results.get(0).getStatus());
    }

    @Test
    void disabledSourceIsSkipped() throws Exception
    {
        when(this.source.isEnabled()).thenReturn(false);
        List<OASyncResult> results = this.service.syncAll(OASyncTriggerType.SCHEDULED, null);
        assertEquals(1, results.size());
        assertEquals(null, results.get(0));
    }
}
