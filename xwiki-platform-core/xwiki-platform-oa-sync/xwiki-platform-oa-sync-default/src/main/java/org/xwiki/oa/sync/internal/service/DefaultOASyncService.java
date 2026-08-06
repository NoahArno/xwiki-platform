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

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Provider;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xwiki.component.annotation.Component;
import org.xwiki.component.manager.ComponentLookupException;
import org.xwiki.component.manager.ComponentManager;
import org.xwiki.oa.sync.OASyncConfiguration;
import org.xwiki.oa.sync.OASyncService;
import org.xwiki.oa.sync.OASyncSource;
import org.xwiki.oa.sync.internal.ftp.OAFtpDownloader;
import org.xwiki.oa.sync.internal.store.SyncRecordStore;
import org.xwiki.oa.sync.internal.sync.UserGroupSynchronizer;
import org.xwiki.oa.sync.model.OAUserRecord;
import org.xwiki.oa.sync.model.OASyncParseResult;
import org.xwiki.oa.sync.model.OASyncResult;
import org.xwiki.oa.sync.model.OASyncTriggerType;

import com.xpn.xwiki.XWikiContext;

/**
 * Orchestrates OA user sync: for each enabled source, download -&gt; parse -&gt; per-record upsert (isolated failures)
 * -&gt; persist record -&gt; cleanup old records.
 *
 * @version $Id$
 */
@Component(roles = OASyncService.class)
@Singleton
public class DefaultOASyncService implements OASyncService
{
    private static final Logger LOGGER = LoggerFactory.getLogger(DefaultOASyncService.class);

    private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd");

    @Inject
    OASyncConfiguration configuration;

    @Inject
    OAFtpDownloader ftpDownloader;

    @Inject
    SyncRecordStore recordStore;

    @Inject
    UserGroupSynchronizer synchronizer;

    @Inject
    Provider<XWikiContext> contextProvider;

    @Inject
    ComponentManager componentManager;

    @Override
    public List<OASyncResult> syncAll(OASyncTriggerType triggerType, LocalDate manualDate)
    {
        List<OASyncResult> results = new ArrayList<>();
        for (OASyncSource source : getSources()) {
            results.add(this.syncSource(source, triggerType, manualDate));
        }
        return results;
    }

    private List<OASyncSource> getSources()
    {
        try {
            return this.componentManager.getInstanceList(OASyncSource.class);
        } catch (ComponentLookupException e) {
            throw new IllegalStateException("无法获取 OA 同步来源组件", e);
        }
    }

    private OASyncResult syncSource(OASyncSource source, OASyncTriggerType triggerType, LocalDate manualDate)
    {
        XWikiContext context = this.contextProvider.get();
        String type = source.getType();
        if (!source.isEnabled()) {
            return null;
        }
        LocalDate syncDate = manualDate != null
            ? manualDate
            : LocalDate.now().minusDays(this.configuration.getSourceDateOffsetDays(type));
        OASyncResult result = new OASyncResult(type, syncDate, triggerType, LocalDateTime.now());
        Path downloaded = null;
        try {
            String remotePath = source.resolveRemotePath(syncDate.format(DATE_FORMAT));
            downloaded = this.ftpDownloader.download(remotePath);

            OASyncParseResult parseResult = source.parse(downloaded);
            int failed = parseResult.getErrors().size();
            int success = 0;
            result.getErrorLog().addAll(parseResult.getErrors());
            for (OAUserRecord record : parseResult.getRecords()) {
                try {
                    this.synchronizer.sync(record, context);
                    success++;
                } catch (Exception e) {
                    failed++;
                    String msg = "用户 [" + record.getUsername() + "] 同步失败: " + e.getMessage();
                    result.getErrorLog().add(msg);
                    LOGGER.error(msg, e);
                }
            }
            result.setTotalCount(success + failed);
            result.setSuccessCount(success);
            result.setFailCount(failed);
            result.setStatus(failed == 0 ? OASyncResult.Status.SUCCESS : OASyncResult.Status.PARTIAL_FAILURE);
        } catch (Exception e) {
            String msg = "来源 [" + type + "] 同步失败: " + e.getMessage();
            result.getErrorLog().add(msg);
            LOGGER.error(msg, e);
            result.setStatus(OASyncResult.Status.FAILURE);
        } finally {
            if (downloaded != null) {
                try {
                    Files.deleteIfExists(downloaded);
                } catch (Exception e) {
                    LOGGER.warn("删除临时文件失败: {}", downloaded, e);
                }
            }
            result.setEndTime(LocalDateTime.now());
        }
        try {
            this.recordStore.save(result, context);
            this.recordStore.cleanup(type, context);
        } catch (Exception e) {
            LOGGER.error("保存同步记录失败（来源 {}）", type, e);
        }
        return result;
    }
}
