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
package org.xwiki.oa.sync.internal.initializer;

import javax.inject.Named;
import javax.inject.Singleton;

import org.xwiki.component.annotation.Component;
import org.xwiki.model.reference.LocalDocumentReference;

import com.xpn.xwiki.doc.AbstractMandatoryClassInitializer;
import com.xpn.xwiki.doc.XWikiDocument;
import com.xpn.xwiki.objects.classes.BaseClass;

/**
 * Defines {@code OASync.SyncRecordClass}: one object per sync run.
 *
 * @version $Id$
 */
@Component
@Named("OASync.SyncRecordClass")
@Singleton
public class OASyncRecordClassInitializer extends AbstractMandatoryClassInitializer
{
    /**
     * The reference of the sync record class.
     */
    public static final LocalDocumentReference REFERENCE = new LocalDocumentReference("OASync", "SyncRecordClass");

    /**
     * Default constructor.
     */
    public OASyncRecordClassInitializer()
    {
        super(REFERENCE);
    }

    @Override
    protected void createClass(BaseClass xclass)
    {
        xclass.addTextField("syncType", "Sync Type", 30);
        xclass.addDateField("syncDate", "Sync Date");
        xclass.addTextField("triggerType", "Trigger Type", 30);
        xclass.addTextField("status", "Status", 30);
        xclass.addDateField("startTime", "Start Time");
        xclass.addDateField("endTime", "End Time");
        xclass.addNumberField("totalCount", "Total", 10, "integer");
        xclass.addNumberField("successCount", "Success", 10, "integer");
        xclass.addNumberField("failCount", "Failed", 10, "integer");
        xclass.addTextAreaField("errorLog", "Error Log", 60, 10);
    }

    @Override
    public boolean updateDocument(XWikiDocument document)
    {
        return super.updateDocument(document);
    }
}
