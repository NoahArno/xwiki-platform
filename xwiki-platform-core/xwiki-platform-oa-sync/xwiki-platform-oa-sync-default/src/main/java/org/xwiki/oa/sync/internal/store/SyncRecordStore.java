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

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.List;

import javax.inject.Inject;
import javax.inject.Singleton;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xwiki.component.annotation.Component;
import org.xwiki.model.reference.DocumentReference;
import org.xwiki.oa.sync.OASyncConfiguration;
import org.xwiki.oa.sync.model.OASyncResult;
import org.xwiki.query.Query;
import org.xwiki.query.QueryException;
import org.xwiki.query.QueryManager;

import com.xpn.xwiki.XWikiContext;
import com.xpn.xwiki.XWikiException;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.BaseObject;

/**
 * Persists sync run records as {@code OASync.SyncRecordClass} objects and keeps only the latest
 * {@value #KEEP_RECORDS} records per sync type.
 *
 * @version $Id$
 */
@Component(roles = SyncRecordStore.class)
@Singleton
public class SyncRecordStore
{
    /**
     * How many records to keep per sync type.
     */
    public static final int KEEP_RECORDS = 10;

    private static final Logger LOGGER = LoggerFactory.getLogger(SyncRecordStore.class);

    private static final DateTimeFormatter TS_FORMAT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    @Inject
    private OASyncConfiguration configuration;

    @Inject
    private QueryManager queryManager;

    /**
     * Save a sync run record as a new hidden document holding one {@code OASync.SyncRecordClass} object.
     *
     * @param result the sync result
     * @param context the XWiki context
     * @throws XWikiException if the document cannot be saved
     */
    public void save(OASyncResult result, XWikiContext context) throws XWikiException
    {
        String space = this.configuration.getRecordSpace();
        String name = result.getType() + "-" + result.getStartTime().format(TS_FORMAT);
        DocumentReference recordRef = new DocumentReference(context.getWikiId(), space, name);
        XWikiDocument doc = new XWikiDocument(recordRef);
        doc.setTitle(result.getType() + " 同步 " + result.getSyncDate());
        doc.setHidden(true);

        BaseObject obj = doc.newXObject(new DocumentReference(context.getWikiId(), space, "SyncRecordClass"), context);
        obj.setStringValue("syncType", result.getType());
        obj.setDateValue("syncDate", toDate(result.getSyncDate()));
        obj.setStringValue("triggerType", result.getTriggerType().name());
        obj.setStringValue("status", result.getStatus() != null ? result.getStatus().name() : "UNKNOWN");
        obj.setDateValue("startTime", toDate(result.getStartTime()));
        if (result.getEndTime() != null) {
            obj.setDateValue("endTime", toDate(result.getEndTime()));
        }
        obj.setIntValue("totalCount", result.getTotalCount());
        obj.setIntValue("successCount", result.getSuccessCount());
        obj.setIntValue("failCount", result.getFailCount());
        obj.setLargeStringValue("errorLog", String.join("\n", result.getErrorLog()));

        context.getWiki().saveDocument(doc, "OA sync: record " + name, context);
    }

    /**
     * Delete records of the given type beyond the latest {@value #KEEP_RECORDS}.
     *
     * @param type the source type
     * @param context the XWiki context
     * @throws QueryException if the query fails
     * @throws XWikiException if a document cannot be deleted
     */
    public void cleanup(String type, XWikiContext context) throws QueryException, XWikiException
    {
        String space = this.configuration.getRecordSpace();
        String className = space + ".SyncRecordClass";
        Query query = this.queryManager.createQuery(
            "select doc.fullName from Document doc where doc.object(" + className + ").syncType = :type"
                + " order by doc.creationDate desc",
            Query.XWQL);
        query.bindValue("type", type);
        List<String> fullNames = query.<String>execute();
        if (fullNames.size() <= KEEP_RECORDS) {
            return;
        }
        for (String fullName : fullNames.subList(KEEP_RECORDS, fullNames.size())) {
            XWikiDocument doc = context.getWiki().getDocument(fullName, context);
            context.getWiki().deleteDocument(doc, context);
            LOGGER.info("OA sync: deleted old record [{}]", fullName);
        }
    }

    private static Date toDate(java.time.LocalDate localDate)
    {
        return Date.from(localDate.atStartOfDay(ZoneId.systemDefault()).toInstant());
    }

    private static Date toDate(java.time.LocalDateTime localDateTime)
    {
        return Date.from(localDateTime.atZone(ZoneId.systemDefault()).toInstant());
    }
}
